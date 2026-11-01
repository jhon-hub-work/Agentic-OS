# Agentic OS phone: R8 rules. The app has no reflection; Activities/Services/Receivers are kept by the manifest.
# The dashboard page calls these through the WebView bridge (MainActivity's "AOSApp" object): keep the names.
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
