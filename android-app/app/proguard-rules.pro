# ProGuard Rules for Sports Betting App
# Add project specific ProGuard rules here.

# Keep Gson model classes
-keepclassmembers class com.openminis.betting.app.model.** { *; }
-keep class com.openminis.betting.app.model.** { *; }

# Keep API client and network classes
-keepclassmembers class com.openminis.betting.app.network.** { *; }
-keep class com.openminis.betting.app.network.** { *; }

# Keep DataRepository and database classes
-keepclassmembers class com.openminis.betting.app.data.** { *; }
-keep class com.openminis.betting.app.data.** { *; }

# Keep SettingsRepository
-keepclassmembers class com.openminis.betting.app.settings.** { *; }
-keep class com.openminis.betting.app.settings.** { *; }

# Keep Core services
-keepclassmembers class com.openminis.betting.app.core.** { *; }
-keep class com.openminis.betting.app.core.** { *; }

# Keep CrashReporter
-keepclassmembers class com.openminis.betting.app.CrashReporter { *; }
-keep class com.openminis.betting.app.CrashReporter { *; }

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# Okio
-keep class okio.** { *; }
-dontwarn okio.**

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepclassmembers class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# AndroidX
-keep class androidx.** { *; }
-keepclassmembers class androidx.** { *; }

# Material
-keep class com.google.android.material.** { *; }

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# Keep Enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep WebView JavaScript Interface
-keepclassmembers class com.openminis.betting.app.MainActivity$WebAppInterface {
    public *;
}

# Keep Resource IDs
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Optimization
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}