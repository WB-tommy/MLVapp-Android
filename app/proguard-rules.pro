# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- JNI Core Protection ---

# Keep all classes that declare native methods, and keep the method names.
# This is crucial for JNI linkage.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep data classes that are created or accessed by the JNI layer.
# Proguard would otherwise remove or obfuscate them, causing crashes in JNI_OnLoad
# when the native code can't find the expected classes or fields.
-keep class fm.magiclantern.forum.data.ClipMetaData { *; }
-keep class fm.magiclantern.forum.data.Clip { *; }
-keep class fm.magiclantern.forum.data.ClipPreviewData { *; }