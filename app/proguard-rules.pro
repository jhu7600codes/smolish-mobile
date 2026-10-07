# methods called from javascript are found by name at runtime, keep them
-keepclassmembers class com.smolish.** {
    @android.webkit.JavascriptInterface <methods>;
}
