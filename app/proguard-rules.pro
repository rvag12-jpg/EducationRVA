# JavaScript bridge methods must remain callable by WebView.
-keepclassmembers class es.aulaevidencia.android.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*
