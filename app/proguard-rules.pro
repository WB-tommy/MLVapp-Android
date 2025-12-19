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
-keep class fm.magiclantern.forum.data.ClipPreviewData { *; }
-keep class fm.magiclantern.forum.data.ProcessingData { *; }

# Export model classes accessed by JNI (moved to features.export.model after refactor)
-keep class fm.magiclantern.forum.features.export.model.ExportCodec { *; }
-keep class fm.magiclantern.forum.features.export.model.ExportOptions { *; }
-keep class fm.magiclantern.forum.features.export.model.ExportSettings { *; }
-keep class fm.magiclantern.forum.features.export.model.CdngNaming { *; }
-keep class fm.magiclantern.forum.features.export.model.CdngVariant { *; }
-keep class fm.magiclantern.forum.features.export.model.ProResProfile { *; }
-keep class fm.magiclantern.forum.features.export.model.ProResEncoder { *; }
-keep class fm.magiclantern.forum.features.export.model.DebayerQuality { *; }
-keep class fm.magiclantern.forum.features.export.model.SmoothingOption { *; }
-keep class fm.magiclantern.forum.features.export.model.H264Quality { *; }
-keep class fm.magiclantern.forum.features.export.model.H264Container { *; }
-keep class fm.magiclantern.forum.features.export.model.H265BitDepth { *; }
-keep class fm.magiclantern.forum.features.export.model.H265Quality { *; }
-keep class fm.magiclantern.forum.features.export.model.H265Container { *; }
-keep class fm.magiclantern.forum.features.export.model.PngBitDepth { *; }
-keep class fm.magiclantern.forum.features.export.model.DnxhrProfile { *; }
-keep class fm.magiclantern.forum.features.export.model.DnxhdProfile { *; }
-keep class fm.magiclantern.forum.features.export.model.Vp9Quality { *; }
-keep class fm.magiclantern.forum.features.export.model.ResizeSettings { *; }
-keep class fm.magiclantern.forum.features.export.model.ScalingAlgorithm { *; }
-keep class fm.magiclantern.forum.features.export.model.FrameRateOverride { *; }

# JNI callback interfaces - JNI calls these methods by name
-keep interface fm.magiclantern.forum.features.export.model.ProgressListener { *; }
-keep class fm.magiclantern.forum.features.export.ExportFdProvider { *; }

# Domain model classes accessed by JNI (referenced from ExportOptions)
-keep class fm.magiclantern.forum.domain.model.RawCorrectionSettings { *; }
-keep class fm.magiclantern.forum.domain.model.ClipGradingData { *; }
-keep class fm.magiclantern.forum.domain.model.ColorGradingSettings { *; }
-keep class fm.magiclantern.forum.domain.model.DebayerAlgorithm { *; }