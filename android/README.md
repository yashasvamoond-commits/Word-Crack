# WordCraft for Android

This directory packages [storytold/wordcraft](https://github.com/storytold/wordcraft) — the
open-source, clean-room reimplementation of Microsoft Word in pure Rust — as an installable
Android APK.

WordCraft already ships a complete browser build (`apps/wordcraft-web`, a Rust→WebAssembly app
built with [Trunk](https://trunkrs.dev/)). Rather than porting the desktop `egui` app to Android,
this project wraps that **unchanged** WASM build in a small native WebView shell, so the Android
app always runs the exact same editor engine as WordCraft on every other platform.

## Architecture

```
storytold/wordcraft (git ref, pinned in CI)
   └─ trunk build --release  →  dist/web/  (index.html + wasm + js glue)
                                   │  android/ci/copy_web_assets.sh
                                   ▼
              app/src/main/assets/web/  (staged at build time, not committed)
                                   │  loaded by
                                   ▼
        MainActivity (WebView + androidx.webkit WebViewAssetLoader)
                                   │  window.AndroidBridge  (JavascriptInterface)
                                   ▼
                            BlobBridge (Java)
```

* **WebViewAssetLoader** serves the static site over the virtual HTTPS origin
  `https://appassets.androidplatform.net/assets/web/`, so ES modules, `fetch()` and `blob:` URLs
  behave exactly like a hosted page (a plain `file://` origin breaks module CORS in WebView).
* **web-bridge.js** is injected on every page load and adapts the browser mechanisms that
  Android WebView implements differently or not at all:
  * **Save / Export** — recent WebViews expose `window.showSaveFilePicker`, but its promise never
    settles because WebView has no File System Access UI; WordCraft would await it forever and
    never reach its fallback. The bridge shadows the API with `undefined` before any app script
    runs, so WordCraft takes its hidden `<a download>` + `blob:` URL path. Both the click event
    and `HTMLAnchorElement.prototype.click()` are intercepted, and the blob is streamed to Java
    in 256 KiB chunks. Writes go to `Downloads/WordCraft` via MediaStore (API 29+), a public
    directory write on API 26–28 (runtime permission requested automatically), or app-specific
    storage as a last-resort fallback — the toast names the final location.
  * **Print** — the app opens a generated PDF in a hidden iframe and calls `print()` on it.
    WebView has no print support, so the same PDF blob is handed to the system PDF viewer through
    a FileProvider `ACTION_VIEW` intent; the viewer offers Android's print dialog.
  * **Open from other apps** — a document launched with an `ACTION_VIEW` intent is staged
    natively, pulled back into the page as a `File`, and fed to the app's own
    `<input type="file">` picker instead of showing a second picker.
  * **Error visibility** — page errors and unhandled promise rejections are forwarded to a
    `logError` JavascriptInterface method that writes logcat and shows a rate-limited toast.
* **Open / Insert picture / Add fonts** inside the app use the normal WebView file chooser.
  `MainActivity` launches WebView's own `FileChooserParams.createIntent()` (correct accept types,
  multi-select and File System Access modes for the installed WebView version), falling back to
  `ACTION_GET_CONTENT` on tablets or custom ROMs without a DocumentsUI provider, and reports a
  clear toast if no picker app exists at all.
* Preferences the app stores in `localStorage` persist in the app's WebView data directory and
  are included in Android backups.

The APK contains **no native code** and is therefore universal (one artifact for every
architecture). The app declares no network usage beyond WebView's default; all editing happens
on the device.

## Directory layout

| Path | Purpose |
|---|---|
| `app/` | The Android application module (pure Java + resources). |
| `app/src/main/assets/web-bridge.js` | Injected WebView adaptation layer. |
| `app/src/main/assets/web/` | Trunk output staged here by CI (git-ignored; `.gitkeep` only). |
| `app/src/main/java/ai/storyteller/wordcraft/` | `MainActivity` and the `BlobBridge` JS interface. |
| `app/src/main/res/` | Manifest resources, launcher icons (generated), FileProvider/backup rules. |
| `ci/copy_web_assets.sh` | Copies and validates a Trunk `dist/web` into the APK assets. |
| `ci/generate_icons.py` | Regenerates every launcher-icon density from `ci/icons/wordcraft-1024.png`. |
| `ci/icons/wordcraft-1024.png` | Upstream WordCraft 1024 px icon (the source of truth for icons). |
| `gradle/`, `gradlew`, `gradlew.bat` | Pinned Gradle 8.11.1 wrapper. |

## Build

### Continuous integration (the normal path)

`.github/workflows/android-apk.yml` runs on every push to this branch and on demand
(`workflow_dispatch`, with a `wordcraft_ref` input to build any upstream branch, tag or SHA).
It mirrors the upstream release toolchain:

1. Checks out `storytold/wordcraft`, plus the pinned `storytold/craft-fonts` and the
   `storytold/craft-i18n` revision named by `craft-i18n.rev` (embedded fonts and translations).
2. Installs stable Rust with the `wasm32-unknown-unknown` target and Trunk 0.21.14
   (checksum-verified, same pin as WordCraft's own release workflow).
3. `trunk build --release` → stages the site into `app/src/main/assets/web/`.
4. Builds and validates the release APK with Gradle (AGP 8.7.3, JDK 17, compile/target SDK 35,
   min SDK 26).
5. Publishes the APK to the rolling **`android-latest`** GitHub Release. Two assets are kept
   fresh there:
   * `WordCraft-android.apk` — stable name, permanent one-click link:
     <https://github.com/yashasvamoond-commits/Word-Crack/releases/download/android-latest/WordCraft-android.apk>
   * `WordCraft-<version>-android.apk` — the same build with the upstream version in its name.

The same APK is also retained for 30 days as a workflow-run artifact.

### Local build

Requirements: Rust (stable, with `wasm32-unknown-unknown`), Trunk 0.21.x, JDK 17, Android SDK
with platform 35 and build-tools 35.0.0.

```bash
# 1. Build the WASM site (from a wordcraft checkout, with craft-fonts/craft-i18n optional)
cd apps/wordcraft-web && trunk build --release

# 2. Stage it into this project (from this repository's root)
android/ci/copy_web_assets.sh ../wordcraft/dist/web

# 3. Build the APK
cd android
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

## Signing

Release builds are currently signed with the Android debug key so the APK is installable by
clicking it immediately (sideloading). For Play Store distribution, generate a real keystore and
replace the `signingConfig` in `app/build.gradle.kts` with one driven by environment variables or
a secrets file, then raise `versionCode` (CI passes the monotonic GitHub Actions run number).

## Regenerating icons

```bash
pip install pillow
python3 android/ci/generate_icons.py
```

The generated PNGs and `res/values/colors.xml` are committed, so CI itself needs no image tools.
