# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/cristian/.android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep line number info so crash stacktraces (Sentry/GlitchTip) can point at the right line once
# remapped through the uploaded R8 mapping.txt. Without this, only class/method names deobfuscate
# -- line numbers are gone from the trace and can't be recovered afterward.
-keepattributes SourceFile,LineNumberTable

# media3 keeps `@Nullable LogSessionId logSessionId` fields (android.media.metrics, API 31) in
# Transformer's asset loaders, renderers and export operations; below API 35 they simply hold null.
# R8 8.10 HORIZONTALLY MERGED some of those classes with unrelated ones (DefaultAssetLoaderFactory
# into appcompat's TooltipPopup, ExoPlayerAssetLoader$Factory into Tink's ProtoKeySerialization,
# TransformerInternal$SequenceAssetLoaderListener into Compose's EdgeEffectWrapper, ...). Merging
# widens the field to Object and adds a `check-cast LogSessionId` at every read. On Android < 12 the
# runtime resolves the cast's class before testing for null, so the read throws
# `NoClassDefFoundError: Failed resolution of: Landroid/media/metrics/LogSessionId;` and every remux
# (DLNA `remux_failed`, the Chromecast remux too) failed synchronously inside Transformer.start().
# Pinning those fields keeps their classes out of merging, so the field keeps its declared type and
# no cast is emitted; names can still be obfuscated. Verify after a release build:
#   dexdump -d classes*.dex | grep 'check-cast .*Landroid/media/metrics/LogSessionId;'
# must print only casts inside API-31-guarded code (PlayerId / MediaMetricsListener), none in a
# method that maps back to androidx.media3.transformer.
-keepclassmembers,allowobfuscation class androidx.media3.** {
    android.media.metrics.LogSessionId *;
}

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefile MyApplication
