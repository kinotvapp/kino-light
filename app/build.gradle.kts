plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.kino.demo"
    compileSdk = 36

    defaultConfig {
        // Identical to kino-app's applicationId, so Android sees this APK as the same app and
        // can update kino-app in-place (same signing cert below + same package = seamless upgrade).
        applicationId = "com.arkiv.player.light"
        minSdk = 24
        targetSdk = 35
// Version hardcoded -- the build doesn't read .env. Bump here when shipping a new release.
// 790 = universal of base 79 (split-scheme). [OtaVersion.baseOf(790)=79], the server's
// 0.9.54 ([baseOf(839)=83]) looks newer (83 > 79), so the dialog opens. Also above
// kino-app v0.9.49 (78) so an in-place install on a device that already has it works.
        versionCode = 790
        versionName = "1.0.0"
    }

    // Release signing comes from gradle.properties (gitignored -- never pushed):
    //   kinoReleaseKeystorePath, kinoReleaseKeystorePassword, kinoReleaseKeyAlias, kinoReleaseKeyPassword
    // Same release key as kino-app. If gradle.properties is missing those keys, the release
    // build comes out unsigned -- on purpose: better than silently falling back to the debug key.
    val keystorePath = (project.findProperty("kinoReleaseKeystorePath") as String?)
        ?.takeIf { it.isNotBlank() }
    val hasSigningConfig = keystorePath != null && file(keystorePath).exists()
    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = (project.findProperty("kinoReleaseKeystorePassword") as String?).orEmpty()
                keyAlias = (project.findProperty("kinoReleaseKeyAlias") as String?).orEmpty()
                keyPassword = (project.findProperty("kinoReleaseKeyPassword") as String?).orEmpty()
            }
        }
    }

    buildTypes {
        release {
            // No proguard-rules.pro on this fork; re-add when kino-light grows past demo.
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasSigningConfig) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Reproducible builds (F-Droid verifies our signed APK byte for byte): no Google-encrypted
    // dependency blob in the APK/bundle, since it differs between builds.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // Robolectric runs the TV focus tests on the JVM with the app's real resources.
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    implementation("io.coil-kt:coil-compose:2.7.0")

    // OTA self-update: HTTP for the manifest + APK, WorkManager for the periodic check.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // android.jar's org.json is a stub that throws on the JVM; the real one parses in unit tests.
    testImplementation("org.json:json:20240303")
}