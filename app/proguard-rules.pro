# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK.
# For more details, see
#   https://developer.android.com/build/shrink-code

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }