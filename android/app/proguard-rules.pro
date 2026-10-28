# Minification is disabled for the release build, so these are belt-and-braces only.

# Keep the JavascriptInterface-annotated methods; their names are called from web-bridge.js.
-keepclassmembers class ai.storyteller.wordcraft.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class ai.storyteller.wordcraft.** { *; }
