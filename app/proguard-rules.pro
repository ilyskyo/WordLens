# WordLens keeps all user data in plain JSON under filesDir, so nothing here is data-related.
# The rules below only cover reflective ML/model entry points.

# kotlinx.serialization generated serializers are looked up reflectively.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.ilyskyo.wordlens.** {
    *** Companion;
}
-keepclasseswithmembers class com.ilyskyo.wordlens.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ilyskyo.wordlens.**$$serializer { *; }

# MediaPipe Tasks loads its graph/assets through native reflection.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ML Kit entry points.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.internal.mlkit_vision_subject_segmentation.** { *; }
-dontwarn com.google.android.gms.internal.mlkit_vision_subject_segmentation.**
