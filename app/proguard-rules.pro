# PrivateTwo Proguard / R8 rules

# Keep WebRTC classes and JNI methods
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep OkHttp
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class * extends okhttp3.internal.concurrent.TaskQueue { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep models used in cryptographic envelopes
-keepclassmembers class org.privatetwo.app.core.crypto.** { *; }
-keepclassmembers class org.privatetwo.app.core.signaling.** { *; }
-keepclassmembers class org.privatetwo.app.core.database.** { *; }

# Google Tink / Security Crypto / ErrorProne annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Strip Android logging in production release builds to prevent data leaks
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
