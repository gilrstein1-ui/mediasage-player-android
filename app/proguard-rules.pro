# Keep the JavaScript bridge interface intact (release builds only).
-keepclassmembers class com.tulik.player.MainActivity$NowPlayingBridge {
    @android.webkit.JavascriptInterface <methods>;
}
