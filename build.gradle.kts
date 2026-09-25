plugins {
    id("com.android.application") version "8.10.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
    // R8 mapping upload to the self-hosted GlitchTip; see app/build.gradle.kts `sentry {}`.
    id("io.sentry.android.gradle") version "6.22.0" apply false
}
