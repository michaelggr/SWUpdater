# Add project specific ProGuard rules here.

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jspecify.annotations.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.swupdater.model.** { *; }

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**
