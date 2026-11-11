# jcifs-ng
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-keep class org.bouncycastle.** { *; }
# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# WebView JS-мост читалки (позиция прокрутки)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}