# ProGuard rules for VisionAssist

# Keep all VisionAssist classes
-keep class com.visionassist.** { *; }
-keep interface com.visionassist.** { *; }

# Firebase
-keep class com.firebase.** { *; }
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }

# TensorFlow Lite
-keep class org.tensorflow.** { *; }
-keep interface org.tensorflow.** { *; }

# Android Architecture Components
-keep class androidx.lifecycle.** { *; }
-keep interface androidx.lifecycle.** { *; }
-keep class androidx.room.** { *; }
-keep interface androidx.room.** { *; }

# Kotlin Coroutines
-keep class kotlin.coroutines.** { *; }
-keep interface kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class com.squareup.okhttp3.** { *; }
-keep interface com.squareup.okhttp3.** { *; }

# Timber logging
-keep class timber.log.** { *; }

# Keep data classes
-keep class com.visionassist.navigation.NavigationState { *; }
-keep class com.visionassist.navigation.AuthState { *; }
-keep class com.visionassist.navigation.AppState { *; }
-keep class com.visionassist.navigation.NavigationEvent { *; }

# Remove logging in release builds
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** i(...);
    public static *** v(...);
}

# Optimization
-optimizationpasses 5
-dontpreverify
-verbose

# Keep source file names and line numbers for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Generic signature attributes
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable
