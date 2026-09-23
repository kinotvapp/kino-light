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

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefile MyApplication
