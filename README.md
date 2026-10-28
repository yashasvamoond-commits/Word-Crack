# Word-Crack — WordCraft for Android

This repository builds an installable **Android APK** of
[storytold/wordcraft](https://github.com/storytold/wordcraft), the open-source, clean-room
reimplementation of Microsoft Word written in pure Rust.

WordCraft's editor compiles to WebAssembly and already runs unchanged in the browser. This
repository wraps that official WASM build in a small native Android WebView shell, producing a
standalone, offline app: the same editor engine, packaged for one-click installation.

## Install in one click

**[⬇️ Download WordCraft-android.apk](https://github.com/yashasvamoond-commits/Word-Crack/releases/download/android-latest/WordCraft-android.apk)**

The link always points at the newest successful build from the
[`android-latest` release](https://github.com/yashasvamoond-commits/Word-Crack/releases/tag/android-latest).
On your Android phone:

1. Open the link and download the APK (minimum **Android 8.0 / API 26**, any architecture).
2. Tap the download in the notification shade or your Downloads folder.
3. Allow your browser or file manager to install from this source when prompted.

The APK is debug-signed for sideloading (no Play Store upload is involved). Every push to this
branch rebuilds it from the latest upstream `main`; use the **Run workflow** button on
[Build Android APK](.github/workflows/android-apk.yml) to build any other upstream branch, tag or
commit.

## What works

- Full WordCraft editor: `.docx`, `.odt`, `.rtf`, `.txt`, `.md`, `.html` and more — all processing
  is local on the device.
- **Tap a document in your file manager → it opens directly in the app** through WordCraft's
  host-bridge protocol (no home screen detour, no second picker).
- **Open / Insert picture / Add fonts** through the Android system document picker (with a
  `GET_CONTENT` fallback for devices without a DocumentsUI provider).
- **Save / Export** writes files to `Downloads/WordCraft`, overwriting the same document on
  repeated saves, with app-specific storage as an automatic fallback and a toast showing the
  final location.
- **Soft keyboard works alongside a paired Bluetooth keyboard** — the on-screen IME is forced
  available when an editable field gains focus, and the layout resizes above it.
- **Print** hands the generated PDF to the system viewer, which offers Android's print dialog.
- Interface translations and embedded fonts from the upstream release are included.

## How it is built

| Piece | Technology |
|---|---|
| Editor engine | `storytold/wordcraft` compiled to `wasm32-unknown-unknown` with Trunk |
| App shell | Same-origin `host.html` framing `?host=parent` in an Android WebView + `androidx.webkit` `WebViewAssetLoader` (pure Java, no Kotlin) |
| File bridge | WordCraft host protocol (`wordcraft:open/save` postMessage) over a `JavascriptInterface` chunked transport in `web-bridge.js` |
| Keyboard | `KeyboardWebView` forces the soft IME even with a hardware keyboard paired |
| Build | GitHub Actions: Rust/Trunk → Gradle AGP 8.7.3, SDK 35, min SDK 26 |
| Distribution | Rolling GitHub Release with a stable asset name (permanent install link) |

See [`android/README.md`](android/README.md) for the full architecture, local build
instructions, signing notes and icon regeneration.
