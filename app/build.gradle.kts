import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("io.sentry.android.gradle")
}

/** Reads a key from the repo root's .env file (to avoid hardcoding credentials). */
fun readEnv(key: String, default: String = ""): String {
    val f = rootProject.file(".env")
    if (!f.exists()) return default
    return f.readLines()
        .firstOrNull { it.trim().startsWith("$key=") }
        ?.substringAfter("=")?.trim()?.trim('"')?.trim('\'') ?: default
}

/**
 * Fixed XOR mask for embedded native constants (see "String obfuscation of the embedded
 * constants" in docs/superpowers/specs/2026-09-15-split-credential-activation-design.md).
 * Hardcoded here AND in native_credentials.cpp's identical `kObfuscationMask` -- not a secret:
 * whoever disassembles the compiled .so finds this same constant there too, since the native
 * module needs it to de-obfuscate at runtime. Its only job is defeating a naive `strings`/grep
 * scan of the compiled binary, which it does regardless of whether this file (public, in git)
 * also shows it.
 */
val nativeObfuscationMask = byteArrayOf(0x5A, 0x3C, 0x91.toByte(), 0x0F, 0x7E, 0x22, 0xC8.toByte(), 0x64)

/**
 * The 32 raw bytes of the release certificate's SHA-256, read from the keystore with keytool.
 * Single source of truth for the cert hash (the runtime reads the same value from the APK's
 * signing block). Empty when the keystore isn't configured (plain local debug).
 */
fun releaseCertSha256Bytes(): ByteArray {
    val ksPath = readEnv("RELEASE_KEYSTORE_PATH")
    if (ksPath.isBlank() || !file(ksPath).exists()) return ByteArray(0)
    // Fully qualified `java.io.ByteArrayOutputStream` doesn't resolve here: AGP registers a
    // `java` extension accessor on Project that shadows the `java` package in this script.
    val out = ByteArrayOutputStream()
    project.exec {
        commandLine(
            "keytool", "-list", "-v",
            "-keystore", ksPath,
            "-storepass", readEnv("RELEASE_KEYSTORE_PASSWORD"),
            "-alias", readEnv("RELEASE_KEY_ALIAS"),
        )
        standardOutput = out
        isIgnoreExitValue = true
    }
    val line = out.toString().lineSequence().firstOrNull { it.trim().startsWith("SHA256:") } ?: return ByteArray(0)
    val hex = line.substringAfter("SHA256:").trim().split(":")
    if (hex.size != 32) return ByteArray(0)
    return ByteArray(32) { hex[it].toInt(16).toByte() }
}

/**
 * Same rule as `CredentialSplit.split` (app/src/main/java/com/arkiv/player/data/credentials/CredentialSplit.kt)
 * -- duplicated here because this Gradle script has no `buildSrc` to share code with the app
 * module. If the split rule ever changes, update both.
 */
fun nativeHalf(value: String): String {
    val sb = StringBuilder()
    for (i in value.indices) if (i % 2 != 0) sb.append(value[i])
    return sb.toString()
}

/**
 * Per-variant native-secrets header dirs. Each variant's `generated_secrets.h` lives under the
 * build dir, never in `src/main/cpp` -- a single shared file there had no ordering guarantee
 * between `generateNativeSecretsHeaderDebug`/`Release` in an aggregate build (`./gradlew build`,
 * `check`, CI building both variants), so one variant's `libcredentials.so` could compile against
 * the OTHER variant's header and come out with garbage credentials. See
 * docs/superpowers/specs/2026-09-19-anti-mod-hardening-design.md.
 */
val debugSecretsHeaderDir = layout.buildDirectory.dir("generated/cpp/debug")
val releaseSecretsHeaderDir = layout.buildDirectory.dir("generated/cpp/release")

/**
 * HARDENED RELEASE obfuscation (kinomorf O-MVLL plugin) wiring.
 *
 * The release native `.so` is ALWAYS routed through kinomorf's O-MVLL plugin (MBA / RASP / VM,
 * including the VM-virtualized Magis DES `des_block_kernel`). It is wired into `buildTypes.release`
 * below, not into a script you have to remember to run: a bare `./gradlew :app:assembleRelease`
 * (or Android Studio's release build) obfuscates automatically and can't be forgotten. DEBUG never
 * gets the plugin, so dev/debug iteration stays plain and fast (no LLVM obfuscation cost). A release
 * build with the plugin missing FAILS LOUDLY (see `verifyReleaseObfuscation` below) -- it never
 * silently ships un-obfuscated native crypto.
 *
 * `-PallowPlainRelease` is the one explicit, LOUD opt-out (an intentional un-obfuscated release,
 * e.g. on a machine without the plugin). Nothing silent ever turns the hardening off.
 */
// Enabled by a bare `-PallowPlainRelease` (Gradle gives such a flag the empty string) or any value
// other than "false"; `-PallowPlainRelease=false` (or absent) leaves the hardening on.
val allowPlainRelease = project.hasProperty("allowPlainRelease") &&
    project.findProperty("allowPlainRelease") != "false"

/**
 * The kinomorf O-MVLL plugin binary. Resolved without per-build env: process env `PLUGIN_PATH`
 * first (kinomorf's scripts export it), else kino-light's `.env` (`PLUGIN_PATH=...`), else the
 * repo-relative default assuming kinomorf sits next to kino-light. Set it once in `.env` and a bare
 * `assembleRelease` Just Works.
 */
fun resolvedOmvllPluginPath(): String =
    (System.getenv("PLUGIN_PATH")?.takeUnless { it.isBlank() })
        ?: readEnv("PLUGIN_PATH").ifBlank {
            rootProject.file("../kinomorf/src/build/libOMVLL.dylib").absolutePath
        }

/** The plugin-matching NDK (its clang/LLVM version must equal the plugin's -- NDK r29 = LLVM 21). */
fun resolvedOmvllNdkVersion(): String =
    (System.getenv("NDK_VERSION")?.takeUnless { it.isBlank() })
        ?: readEnv("NDK_VERSION").ifBlank { "29.0.14206865" }

/**
 * True when the release build should obfuscate AND can (the plugin binary is present). Drives both
 * the `-fpass-plugin` flag and the plugin-matching NDK below. Evaluated at configuration, so it only
 * reads a path + a file existence check -- never the plugin runtime env, so it can't break a plain
 * `assembleDebug` (the loud env/plugin checks live in the release-only `verifyReleaseObfuscation`).
 */
val omvllReleaseObfuscation = !allowPlainRelease && file(resolvedOmvllPluginPath()).exists()

// DEV-ONLY fast iteration: `-PkinoFastDev` turns OFF R8 minification and the release lint (the two
// tasks that dominate a release build's ~9 min). The .so is unaffected -- it is already O-MVLL
// obfuscated and Gradle caches it, so it is NOT rebuilt for a Kotlin-only change either way. The APK
// is still signed with the release cert (so it activates) but is un-minified and larger. NEVER use
// this for a shipped OTA build; leave it off and the build is the normal production one.
val kinoFastDev = project.hasProperty("kinoFastDev")

android {
    lint {
        // Skip the (slow) release lint on a fast dev build; production/OTA builds still run it.
        checkReleaseBuilds = !kinoFastDev
    }
    namespace = "com.arkiv.player"
    compileSdk = 36
    // r28+: the linker defaults to 16 KB-aligned LOAD segments, which newer Android devices
    // require. r26 didn't, and left our own native module (libcredentials.so) unaligned.
    // When the hardened-release obfuscation is active (the plugin is present), the whole module
    // moves to the plugin-matching NDK: a clang `-fpass-plugin` must be loaded by the exact LLVM
    // version the plugin was built against (NDK r29 = LLVM 21) or clang refuses it. ndkVersion is
    // module-wide, so debug builds on such a machine also use r29 -- still plain + fast (debug never
    // gets `-fpass-plugin`), and r29 is the NDK the debug smoke already validated. CMakeLists.txt
    // forces the 16 KB alignment explicitly (-Wl,-z,max-page-size=16384) regardless of the NDK's own
    // default, so the override never reintroduces the alignment problem the newer NDK fixed.
    ndkVersion = if (omvllReleaseObfuscation) resolvedOmvllNdkVersion() else "28.2.13676358"

    defaultConfig {
        applicationId = "com.arkiv.player.light" // own id: full Arkiv and Arkiv Light coexist on the same device
        // Android 7.0 (Fire OS 6 Fire Sticks / older TV boxes stuck there). The modern JDK APIs the
        // app uses (java.util.Base64, java.time, java.nio.file) are back-ported by core library
        // desugaring (see compileOptions + the desugar_jdk_libs_nio dependency), so API 24/25 gets
        // them without app-level guards.
        minSdk = 24
        targetSdk = 35
        // Instrumented tests (app/src/androidTest): the native 3DES key/crypto path can only be
        // exercised on a device/emulator, where libcredentials.so actually loads. See
        // MagisNativeCryptoInstrumentedTest.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // `ADULT_CODE` used to live here, the 18+ lock's code baked in from .env at build time. Not
        // anymore: the person picks the code in Settings and it starts at a public default (see
        // `CandadoDeAdultos`). An APK distributed with a code only the person who built it knew left
        // the section locked for everyone else.
        // Cast receiver to launch on the TV. Empty falls back to Google's Default Media Receiver,
        // which cannot play the MPEG-TS Magis serves -- see `receiver/index.html`. It lives in the
        // .env because it is registered per developer account in the Cast Developer Console, so a
        // checkout without one still builds and still casts what the default receiver can handle.
        buildConfigField("String", "CAST_RECEIVER_ID", "\"${readEnv("CAST_RECEIVER_ID")}\"")
        // Default activation code baked into the build: when non-blank, the activation screen (phone
        // and TV) starts already activated-ready -- it pre-fills the code and hides the entry, so the
        // person only presses "Activar" instead of typing the archive.org item suffix. Lives in .env
        // (not git) like the other per-deploy values; blank = the classic "type the code" screen, so
        // a checkout without it still builds and still works. Changing the code means a new build (a
        // new APK version), the deliberate trade for a one-tap activation.
        buildConfigField("String", "ACTIVATION_CODE", "\"${readEnv("ACTIVATION_CODE")}\"")
        // Error reporting (Sentry SDK -> self-hosted GlitchTip, see ArkivApp.kt). Read from .env
        // like the other per-deploy values, never hardcoded: the DSN is a public client key (safe
        // inside the shipped APK) but the source tree stays clean either way. Blank = Sentry.init
        // is skipped entirely (see ArkivApp.installSentry) -- a checkout without it still builds
        // and just doesn't report.
        buildConfigField("String", "SENTRY_DSN", "\"${readEnv("SENTRY_DSN")}\"")
        // Native (NDK) crash capture opt-in -- default OFF, see ArkivApp.installSentry's comment
        // on `options.isEnableNdk` for why (this app's native module has its own anti-tamper
        // checks; a second native signal handler needs on-device verification first). Set
        // `SENTRY_ENABLE_NDK=true` in `.env` to opt in once that's been verified.
        buildConfigField("boolean", "SENTRY_ENABLE_NDK", readEnv("SENTRY_ENABLE_NDK", "false"))
        // Frozen on purpose: CI always overrides both via .env for a real release build (see
        // .github/workflows/release.yml). These are only what a plain local `assembleDebug` gets.
        versionCode = readEnv("VERSION_CODE").toIntOrNull() ?: 48
        versionName = readEnv("VERSION_NAME").ifBlank { "0.9.17" }
        // Task 8 (Step 3): `ARKIV_API_KEY` used to live here, the last build-time credential still
        // left in the APK -- a compiled-in constant, the same for every device, that anyone who
        // opened the APK could extract. Gone entirely: the app now authenticates with the PER-DEVICE
        // credential already issued at sign-up (person session + device), revocable one at a time.
        // See `docs/INVENTARIO_DE_LLAVES.md`.
        // ABI filters are set per build type (see buildTypes below), not here. Every build type --
        // debug, hardened (obfuscated) release, and plain release -- ships BOTH real-device ABIs:
        // phone arm64-v8a + Fire Stick armeabi-v7a. (The obfuscated release covered arm64 only until
        // the O-MVLL Virtualize pass was fixed for 32-bit armeabi-v7a; see the release block.)
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    // Release signing from .env (same mechanism as the credentials: the file is gitignored, so the
    // keystore and its password never enter the repo). If it isn't configured, the block isn't
    // created and `assembleRelease` comes out unsigned -- on purpose: better than silently falling
    // back to signing with the debug key.
    val keystorePath = readEnv("RELEASE_KEYSTORE_PATH")
    val hasSigningConfig = keystorePath.isNotBlank() && file(keystorePath).exists()
    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = readEnv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = readEnv("RELEASE_KEY_ALIAS")
                keyPassword = readEnv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // R8 is ON. It was off for years because libVLC reached classes and fields through JNI
            // that the shrinker could not see referenced and would strip; libVLC is deleted, so the
            // reason went with it. Measured 2026-09-12, same build type both ways: the release APK
            // goes 20,151,333 -> 7,404,256 bytes with R8, and it builds with no extra keep rules.
            // NOT exercised on a device yet: installing a release build means uninstalling the debug
            // one, which wipes app data, so that check waits for a device that can afford it.
            isMinifyEnabled = !kinoFastDev
            if (hasSigningConfig) signingConfig = signingConfigs.getByName("release")
            // The hardened (obfuscated) release ships BOTH real-device ABIs -- phone arm64-v8a AND
            // Fire Stick armeabi-v7a. The O-MVLL Virtualize pass used to SIGSEGV compiling the marked
            // kernels for 32-bit armeabi-v7a: first a compile crash (the embedded interpreter IR was
            // aarch64-only -> datalayout/triple mismatch), then a runtime SSA-domination bug in the
            // decode-splice byte batching. Both are fixed in kinomorf (src/passes/virtualize/
            // VmEmit.cpp) and gated by scripts/armv7-32bit-check.sh -- the qemu-system-arm cortex-a15
            // gate that proves the virtualized armv7 kernel is byte-identical to the native one. So
            // the obfuscated release now covers the Fire Stick 32-bit too, not arm64 only.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            externalNativeBuild {
                cmake {
                    // Binds the credential's native half to the release certificate; unset in debug
                    // so ADB installs keep working. See docs/superpowers/specs/2026-09-19-anti-mod-hardening-design.md.
                    cppFlags += "-DKINO_BIND_SIGNATURE=1"
                    // This variant's own generated_secrets.h (see debugSecretsHeaderDir/
                    // releaseSecretsHeaderDir above) -- never src/main/cpp, so debug and release
                    // can't clobber each other's header in an aggregate build.
                    cppFlags += "-I" + layout.buildDirectory.get().asFile.resolve("generated/cpp/release").absolutePath
                    // HARDENED RELEASE: always route the native credential/Magis sources through
                    // kinomorf's O-MVLL plugin (MBA / RASP / VM -- including the VM-virtualized Magis
                    // DES `des_block_kernel`). THIS is what makes the SHIPPED release .so obfuscated,
                    // not merely stripped + cert-bound. Wired into the build type itself, so a bare
                    // `assembleRelease` obfuscates automatically -- it can't be forgotten and is never
                    // in debug. Both cppFlags and cFlags, since CMake compiles these sources as C++.
                    // The loud "plugin present + its OMVLL_CONFIG/OMVLL_PYTHONPATH runtime env are
                    // exported" enforcement is `verifyReleaseObfuscation` (a release-only task, so it
                    // can't break a plain `assembleDebug`). If the plugin is absent here, the flag is
                    // simply not added and that task fails the release loudly rather than silently
                    // shipping un-obfuscated crypto. `-PallowPlainRelease` is the one loud opt-out.
                    if (omvllReleaseObfuscation) {
                        val omvllPluginPath = resolvedOmvllPluginPath()
                        cppFlags += "-fpass-plugin=$omvllPluginPath"
                        cFlags += "-fpass-plugin=$omvllPluginPath"
                    }
                }
            }
        }
        debug {
            isMinifyEnabled = false
            // Debug is plain + fast (no O-MVLL plugin) and keeps both real-device ABIs.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            externalNativeBuild {
                cmake {
                    cppFlags += "-I" + layout.buildDirectory.get().asFile.resolve("generated/cpp/debug").absolutePath
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Back-port java.util.Base64 / java.time / java.nio.file to minSdk 24 (Android 7). The
        // _nio dependency variant is added below.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // quickjs-kt 1.0.0-alpha13's AAR ships libquickjs.so linked for 4 KB pages, which Android 15+
        // flags as not 16 KB compatible. src/main/jniLibs has the same sources rebuilt with 16 KB
        // alignment (see the README there); pin that the app's copy is the one that ships.
        jniLibs {
            pickFirsts += "**/libquickjs.so"
        }
    }
}

// Unit tests get quickjs-kt-jvm (desktop natives) instead of quickjs-kt-android. Both publish the
// same classes; with both on the classpath the Android one could win and fail to load its .so.
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath") || name.endsWith("UnitTestCompileClasspath")) {
        exclude(group = "io.github.dokar3", module = "quickjs-kt-android")
    }
}

fun writeSecretsHeader(bindCert: Boolean, outputDir: File) {
    outputDir.mkdirs()
    val certHash = if (bindCert) releaseCertSha256Bytes() else ByteArray(0)

    fun maskedLiteral(bytes: ByteArray, bindThisField: Boolean): String {
        val masked = ByteArray(bytes.size) { i ->
            var b = bytes[i].toInt() xor nativeObfuscationMask[i % nativeObfuscationMask.size].toInt()
            if (bindThisField && certHash.isNotEmpty()) b = b xor certHash[i % certHash.size].toInt()
            b.toByte()
        }
        return masked.joinToString(", ") { "0x%02x".format(it) }
    }
    fun field(name: String, value: String, bindThisField: Boolean): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return "static const unsigned char $name[] = { ${maskedLiteral(bytes, bindThisField)} };\n" +
            "static const int ${name}_LEN = ${bytes.size};\n"
    }
    val content = buildString {
        appendLine("// GENERATED -- do not edit by hand, never committed (see .gitignore).")
        appendLine("// Regenerated before every native build. Arrays are XOR-masked; the GEN_NATIVE_*")
        appendLine("// credential halves are additionally cert-bound in release (bindCert=$bindCert).")
        appendLine("#pragma once")
        append(field("GEN_BLOB_KEY", readEnv("CREDENTIALS_BLOB_KEY"), false)) // never cert-bound: the blob is shared
        append(field("GEN_NATIVE_3DES", nativeHalf(readEnv("IPTV_3DES_KEY")), true))
        append(field("GEN_NATIVE_HOSTS", nativeHalf(readEnv("IPTV_HOSTS")), true))
        append(field("GEN_NATIVE_APP_ID", nativeHalf(readEnv("IPTV_APP_ID")), true))
        append(field("GEN_NATIVE_APK_VERSION", nativeHalf(readEnv("IPTV_APK_VERSION")), true))
        append(field("GEN_NATIVE_TMDB", nativeHalf(readEnv("API_KEY")), true))
        // Optional (see RemoteCredentials' KDoc): empty halves still produce a valid, if
        // useless, native field -- resolve() just returns "" for it, same as any missing key.
        append(field("GEN_NATIVE_FALLBACK_EMAIL", nativeHalf(readEnv("MAGIS_FALLBACK_EMAIL")), true))
        append(field("GEN_NATIVE_FALLBACK_PASSWORD", nativeHalf(readEnv("MAGIS_FALLBACK_PASSWORD")), true))
    }
    outputDir.resolve("generated_secrets.h").writeText(content)
}

val generateNativeSecretsHeaderDebug = tasks.register("generateNativeSecretsHeaderDebug") {
    outputs.file(debugSecretsHeaderDir.map { it.file("generated_secrets.h") })
    doLast { writeSecretsHeader(bindCert = false, outputDir = debugSecretsHeaderDir.get().asFile) }
}
val generateNativeSecretsHeaderRelease = tasks.register("generateNativeSecretsHeaderRelease") {
    outputs.file(releaseSecretsHeaderDir.map { it.file("generated_secrets.h") })
    doLast { writeSecretsHeader(bindCert = true, outputDir = releaseSecretsHeaderDir.get().asFile) }
}
// Regenerate the header before the matching variant's CMake tasks. Debug never binds the cert
// (ADB installs keep working); non-debug binds it, matching -DKINO_BIND_SIGNATURE=1.
tasks.matching { it.name.contains("CMake") && it.name.contains("Debug") }.configureEach {
    dependsOn(generateNativeSecretsHeaderDebug)
}
tasks.matching { it.name.contains("CMake") && !it.name.contains("Debug") }.configureEach {
    dependsOn(generateNativeSecretsHeaderRelease)
}

// HARDENED RELEASE enforcement -- runs ONLY before the release native build (same "non-Debug CMake
// task" match as the release secrets header above), so it can never break a plain `assembleDebug`.
// It fails the release LOUDLY unless the O-MVLL plugin is present AND its runtime env is exported,
// turning what would otherwise be a cryptic mid-compile plugin abort (or, worse, a silently
// un-obfuscated release) into a clear, early error. `-PallowPlainRelease` downgrades it to a loud
// warning for an intentional un-obfuscated release.
val verifyReleaseObfuscation = tasks.register("verifyReleaseObfuscation") {
    doLast {
        if (allowPlainRelease) {
            logger.warn(
                "\n############################################################\n" +
                "# HARDENED RELEASE DISABLED via -PallowPlainRelease:\n" +
                "# this release .so ships UN-OBFUSCATED (no MBA/RASP/VM). Do NOT distribute it.\n" +
                "############################################################\n"
            )
            return@doLast
        }
        val pluginPath = resolvedOmvllPluginPath()
        if (!file(pluginPath).exists()) throw GradleException(
            "Hardened release requires the kinomorf O-MVLL obfuscation plugin, but it was not found at:\n" +
            "  $pluginPath\n" +
            "Build it once (in the kinomorf repo):\n" +
            "  scripts/fetch-toolchain.sh && scripts/build-plugin.sh\n" +
            "or set PLUGIN_PATH in kino-light/.env to the built libOMVLL.dylib. A release must be\n" +
            "obfuscated; to build an intentional un-obfuscated release, pass -PallowPlainRelease."
        )
        // The plugin reads OMVLL_CONFIG (the marking, kinomorf/kino/omvll_config.py) and
        // OMVLL_PYTHONPATH (its embedded interpreter's stdlib) from the PROCESS env when clang loads
        // it -- Gradle passes its own environment straight down to the CMake/ninja/clang subprocess.
        // readEnv() can see .env, but only the process env reaches the subprocess, so require them
        // here and fail loud with the exact way to set them.
        val cfg = System.getenv("OMVLL_CONFIG")
        val pyp = System.getenv("OMVLL_PYTHONPATH")
        if (cfg.isNullOrBlank() || pyp.isNullOrBlank()) throw GradleException(
            "Hardened release: the O-MVLL plugin was found at\n  $pluginPath\n" +
            "but its runtime env is not exported to this Gradle process:\n" +
            "  OMVLL_CONFIG     = ${cfg ?: "(unset)"}\n" +
            "  OMVLL_PYTHONPATH = ${pyp ?: "(unset)"}\n" +
            "The plugin reads them via getenv when clang loads it. Set them in kino-light/.env and\n" +
            "source it before building, e.g.:\n" +
            "  set -a; . ./.env; set +a; ./gradlew :app:assembleRelease\n" +
            "or use the one-command wrapper (in kinomorf): scripts/build-apk-obf.sh --release"
        )
        logger.lifecycle("verifyReleaseObfuscation: O-MVLL plugin + runtime env present -- release will be obfuscated.")
    }
}
tasks.matching { it.name.contains("CMake") && !it.name.contains("Debug") }.configureEach {
    dependsOn(verifyReleaseObfuscation)
}

ksp {
    // Room generates Kotlin instead of Java: sidesteps the javac bug on JDK 17.0.13+/21.0.5+
    // ("insert(Iterable) and insert(T) inherited with the same signature") that breaks compilation
    // of the generated code in the unit test variant.
    arg("room.generateKotlin", "true")
}

/**
 * Sentry Gradle plugin config. SDK init itself is manual, in ArkivApp.kt -- `autoInstallation` is
 * off here so DSN/environment/privacy stay explicit and in one place instead of split between
 * this file and a manifest meta-data the plugin would inject.
 *
 * `includeProguardMapping` stays on unconditionally: it's what makes the plugin generate a UUID
 * per release build AND embed it into the APK (`generateSentryProguardUuidRelease` +
 * `injectSentryDebugMetaPropertiesIntoAssetsRelease`, into `assets/sentry-debug-meta.properties`)
 * -- that embedded UUID is what lets GlitchTip match a crash event to a mapping, and costs nothing
 * to always produce, token or not.
 *
 * `autoUploadProguardMapping` is unconditionally OFF, not gated on the token: the plugin's own
 * upload task uses `sentry-cli`'s chunked upload/assemble protocol, and this self-hosted
 * GlitchTip's implementation of that protocol has a confirmed server-side bug for ProGuard
 * mappings specifically -- it calls a native-debug-file-only parser
 * (`apps/difs/tasks.py::difs_extract_metadata_from_file` -> `symbolic.Archive.open()`)
 * unconditionally, so the mapping silently fails to persist while the plugin still reports
 * "UPLOADED" (verified: real upload, checked the row never landed in Postgres, found the exact
 * "unsupported object file format" error in the worker's own logs -- see
 * `.superpowers/sdd/2026-09-22-companion-sync/sentry-integration-report.md`). Leaving the broken
 * auto-upload on would silently produce a false "success" signal on every release build.
 *
 * [uploadProguardMappingLegacy] below replaces it: same mapping.txt, same embedded UUID, but
 * through GlitchTip's OTHER upload endpoint (`files/dsyms/`, a single zip POST) -- verified by
 * hand to actually persist and be usable for deobfuscation. If GlitchTip ever fixes the chunked
 * endpoint, `autoUploadProguardMapping` can flip back on and this task can go.
 */
val sentryAuthToken = readEnv("SENTRY_AUTH_TOKEN")
val sentryOrg = "comparadorinternet"
val sentryProjectSlug = "errores"
val sentryUrl = "https://errores.comparadorinternet.co"
sentry {
    org.set(sentryOrg)
    projectName.set(sentryProjectSlug)
    url.set(sentryUrl)
    if (sentryAuthToken.isNotBlank()) {
        authToken.set(sentryAuthToken)
    }
    includeProguardMapping.set(true)
    // Broken upstream for this server -- see the KDoc above. uploadProguardMappingLegacy replaces it.
    autoUploadProguardMapping.set(false)
    // Manual init (see ArkivApp.kt) -- don't let the plugin also inject its own auto-init.
    autoInstallation {
        enabled.set(false)
    }
    // Native `.so` is stripped and routed through O-MVLL separately; NDK symbolication is out of
    // scope here (see the task notes / kino-light CLAUDE.md). JVM/Kotlin only. (Unrelated to
    // ArkivApp's runtime `SENTRY_ENABLE_NDK` flag -- that's the SDK's NDK *crash capture*, this is
    // the Gradle plugin's NDK *symbol upload*; both default off, for different reasons.)
    uploadNativeSymbols.set(false)
    includeNativeSources.set(false)
    // YAGNI: error reporting + mapping upload only. No perf monitoring. Session replay isn't
    // configured anywhere (no sample rate is ever set, so it stays inert) even though
    // `sentry-android-replay` is pulled in transitively by the `sentry-android` bundle -- and no
    // source-context upload of this credential-handling codebase to a third party, no
    // build-dependency report.
    includeSourceContext.set(false)
    includeDependenciesReport.set(false)
    telemetry.set(false)
}

/**
 * Replaces the Sentry Gradle plugin's broken auto-upload (see the `sentry {}` KDoc above): zips
 * the release `mapping.txt` as `proguard/<uuid>.txt` -- the exact shape GlitchTip's legacy
 * `files/dsyms/` endpoint expects (`apps/difs/api.py::dsyms`, verified by hand to work: HTTP 200,
 * the row shows up in Postgres, GlitchTip's own stacktrace resolver picks it up by
 * `data__symbol_type="proguard"`) -- using the SAME UUID the plugin already embedded into this
 * build's APK (`generateSentryProguardUuidRelease`), so a real crash from this exact build
 * deobfuscates once this upload lands.
 *
 * Gated on [sentryAuthToken] being present: `onlyIf` makes this a clean SKIP (not a failure) on a
 * tokenless build, same contract as the rest of this file's Sentry wiring. `dependsOn` +
 * `finalizedBy` below make it runnable either way: `./gradlew uploadProguardMappingLegacy`
 * directly (builds `assembleRelease` first if needed) or automatically right after a plain
 * `./gradlew :app:assembleRelease`.
 *
 * Fails LOUDLY (throws, non-zero exit) on anything other than HTTP 200 with the uploaded UUID
 * echoed back in the response -- this task exists specifically because the plugin's own upload
 * silently claims success while doing nothing, so silently doing the same thing here would defeat
 * the entire point.
 */
val uploadProguardMappingLegacy = tasks.register("uploadProguardMappingLegacy") {
    group = "sentry"
    description = "Uploads release's R8 mapping.txt to GlitchTip via the legacy files/dsyms/ zip " +
        "endpoint (the plugin's own chunked auto-upload is broken on this GlitchTip instance)."
    dependsOn("assembleRelease")
    onlyIf {
        if (kinoFastDev) {
            logger.lifecycle("uploadProguardMappingLegacy: kinoFastDev (R8 off, no mapping.txt), skipping.")
            return@onlyIf false
        }
        if (sentryAuthToken.isBlank()) {
            logger.lifecycle("uploadProguardMappingLegacy: SENTRY_AUTH_TOKEN not set, skipping (tokenless build).")
        }
        sentryAuthToken.isNotBlank()
    }
    doLast {
        val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile
        if (!mappingFile.exists()) {
            throw GradleException(
                "uploadProguardMappingLegacy: no mapping.txt at ${mappingFile.absolutePath} -- " +
                "is R8/minification enabled for the release build type?"
            )
        }
        val propsFile = layout.buildDirectory.file(
            "intermediates/assets/release/injectSentryDebugMetaPropertiesIntoAssetsRelease/sentry-debug-meta.properties"
        ).get().asFile
        if (!propsFile.exists()) {
            throw GradleException(
                "uploadProguardMappingLegacy: no sentry-debug-meta.properties at ${propsFile.absolutePath} -- " +
                "is includeProguardMapping on and did generateSentryProguardUuidRelease run?"
            )
        }
        val props = Properties().apply { propsFile.inputStream().use { load(it) } }
        val uuid = props.getProperty("io.sentry.ProguardUuids")?.substringBefore(",")?.trim()
        if (uuid.isNullOrBlank()) {
            throw GradleException(
                "uploadProguardMappingLegacy: sentry-debug-meta.properties has no io.sentry.ProguardUuids " +
                "(${propsFile.absolutePath})"
            )
        }

        val zipFile = layout.buildDirectory.file("tmp/sentry/release-mapping-$uuid.zip").get().asFile
        zipFile.parentFile.mkdirs()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // No bare "proguard/" directory entry: GlitchTip 400s the whole request on one (its
            // regex match against the directory entry's own name fails, verified by hand).
            zos.putNextEntry(ZipEntry("proguard/$uuid.txt"))
            mappingFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }

        val responseFile = layout.buildDirectory.file("tmp/sentry/release-mapping-response.json").get().asFile

        // The token goes in a curl config file (-K), not argv: a process's command line (its
        // `-H "Authorization: Bearer ..."` argument) is visible to any other local user/process via
        // `ps`/`/proc`, but a config file's CONTENTS aren't. Mode 600 (owner-only) and deleted in
        // the `finally` below regardless of outcome, so the secret touches disk only as long as
        // curl needs it.
        val curlConfigFile = layout.buildDirectory.file("tmp/sentry/release-mapping-curl.conf").get().asFile
        curlConfigFile.parentFile.mkdirs()
        // curl config-file syntax: `option = "value"`, one per line. The token itself never
        // contains a `"` (see APIToken.generate_token in GlitchTip -- hex only), so no escaping
        // is needed for this specific value.
        curlConfigFile.writeText("header = \"Authorization: Bearer $sentryAuthToken\"\n")
        runCatching {
            Files.setPosixFilePermissions(curlConfigFile.toPath(), PosixFilePermissions.fromString("rw-------"))
        }

        val httpCode: String
        val body: String
        try {
            val statusOut = ByteArrayOutputStream()
            project.exec {
                commandLine(
                    "curl", "-sS",
                    "-K", curlConfigFile.absolutePath,
                    "-o", responseFile.absolutePath,
                    "-w", "%{http_code}",
                    "-F", "file=@${zipFile.absolutePath};type=application/zip",
                    "$sentryUrl/api/0/projects/$sentryOrg/$sentryProjectSlug/files/dsyms/",
                )
                standardOutput = statusOut
            }
            httpCode = statusOut.toString().trim()
            body = if (responseFile.exists()) responseFile.readText() else ""
        } finally {
            curlConfigFile.delete()
        }

        if (httpCode != "200" || !body.contains(uuid)) {
            throw GradleException(
                "uploadProguardMappingLegacy: GlitchTip rejected the mapping upload " +
                "(HTTP $httpCode, uuid=$uuid).\nResponse: $body"
            )
        }
        logger.lifecycle(
            "uploadProguardMappingLegacy: mapping uploaded to GlitchTip (uuid=$uuid, HTTP $httpCode).\n$body"
        )
    }
}
// afterEvaluate: AGP registers assembleRelease (and the rest of the variant-specific task graph)
// once the full android{} config is resolved, not synchronously while this script evaluates --
// tasks.named(...) here (outside afterEvaluate) would fail with "task 'assembleRelease' not found".
afterEvaluate {
    tasks.named("assembleRelease") {
        finalizedBy(uploadProguardMappingLegacy)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // Core library desugaring: back-ports java.util.Base64, java.time.* and java.nio.file to the
    // minSdk-24 floor (Android 7). The _nio variant is the superset that also covers java.nio.file
    // (Files.move / File.toPath), which the plain artifact does not.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")

    implementation("androidx.core:core-ktx:1.15.0")
    // System splash: avoids the black frame between launch and Compose's first frame.
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // ProcessLifecycleOwner: keep the companion link alive while the app is in the foreground.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Error reporting: pure HTTP client, no Google Play Services dependency (Fire OS / no-GMS
    // devices need that). Manual init in ArkivApp.kt, not the Gradle plugin's auto-install (see
    // `sentry {}` above).
    implementation("io.sentry:sentry-android:8.57.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Forced past the version material3 pulls in transitively (1.0.1, whose native library
    // isn't 16 KB-aligned): a direct declaration wins over a transitive one at the same Gradle
    // conflict-resolution level, with no need to bump the whole Compose BOM for it.
    implementation("androidx.graphics:graphics-path:1.1.0")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Compose for TV (Android TV / Fire TV)
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    // HLS: needed by LiveExoPlayer (Magis' live channel, via LiveHlsProxy) -- was missing and
    // caused a runtime ClassNotFoundException (DefaultMediaSourceFactory looks up
    // HlsMediaSource$Factory by reflection, the compiler can't detect it).
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-datasource:1.11.1")
    // Plugin streams play through OkHttp so every manifest, segment, key and redirect hop is
    // host-gated in an interceptor before the request leaves the device (PluginStreamHttp).
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    // Transmux MPEG-TS -> MP4 for cast. Transformer copies the compressed samples when the format
    // already fits (no re-encode, so no quality loss and little CPU), which is what turns a
    // container the Cast receiver refuses into one it indexes properly. Chosen over ffmpeg-kit,
    // which was retired in January 2025 and would have re-added a large native blob right after
    // libVLC was removed from this branch.
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-muxer:1.11.1")

    // Chromecast
    implementation("androidx.media3:media3-cast:1.11.1")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Room
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // Networking (JSON parsed with bundled org.json)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // kino.html.select for plugins (CSS selectors over fetched HTML).
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Companion LAN (phone<->TV): WebSocket server+client for the pairing/transport channel.
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Plugin sandbox (see docs/superpowers/specs/2026-09-24-plugin-sources-design.md). alpha13 is the
    // last quickjs-kt built with Kotlin 2.0; every later release needs Kotlin >= 2.3. Its engine
    // quirks are pinned by QuickJsSpikeTest.
    implementation("io.github.dokar3:quickjs-kt:1.0.0-alpha13")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests: Android's own (android.jar) is a stub that throws at
    // runtime, so any test that parses JSON would fail without this.
    testImplementation("org.json:json:20240303")
    // Dispatchers.setMain + runTest/StandardTestDispatcher: without this, any real ViewModel
    // (viewModelScope = Dispatchers.Main.immediate) blows up in a pure JVM test ("Module with the
    // Main dispatcher had failed to initialize"). Testing-only: doesn't ship in the APK, same deal
    // as mockwebserver a bit further down.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Fake HTTP server for HttpFetcher tests (cached cookie, challenge/retry) with no real network.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Real SQLite to test the DDL Room doesn't validate (the `updatedAt` triggers): they're plain
    // SQL, so running them is the only honest way to know whether they seal what they must seal.
    testImplementation("org.xerial:sqlite-jdbc:3.45.3.0")
    // The same engine with desktop natives (macOS/Linux), so PluginRuntime runs in JVM unit tests.
    // The Android artifact is excluded from the unit-test classpaths below: its loader calls
    // System.loadLibrary, which can't find an Android .so on the host JVM.
    testImplementation("io.github.dokar3:quickjs-kt-jvm:1.0.0-alpha13")

    // Instrumented tests: the native 3DES key/crypto path (MagisNativeCryptoInstrumentedTest) has
    // to run on a device/emulator where libcredentials.so loads -- it cannot run on the JVM.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
