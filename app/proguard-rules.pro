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

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# sherpa-onnx: JNI-referenced classes must not be stripped or renamed
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep Function1 and implementations used by sherpa-onnx JNI callbacks
-keep class kotlin.jvm.functions.** { *; }
-keep class * implements kotlin.jvm.functions.Function1 {
    public java.lang.Integer invoke(**);
    public java.lang.Object invoke(java.lang.Object);
}
-keep class com.example.booklibrary.tts.PiperTtsPlayer** { *; }

# Readium: keep JSON serialization and reflection-based APIs
-keep class org.readium.** { *; }
-keepclassmembers class org.readium.** { *; }
-dontwarn org.readium.**

# Readium TTS media navigator (uses reflection for content tokenization)
-keep class org.readium.navigator.media.** { *; }
-keepclassmembers class org.readium.navigator.media.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { **[] $VALUES; public *; }
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder { *** rewind(); }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Apache Commons Compress
-dontwarn org.apache.commons.compress.**

# Kotlin serialization
-keepattributes *Annotation*
-keepclassmembers class kotlinx.** { volatile <fields>; }