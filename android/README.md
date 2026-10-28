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
                                   │  framed by
                                   ▼
   assets/host.html  ── iframe (?host=parent, same virtual HTTPS origin)
                                   │  wordcraft:ready/open/save postMessage protocol
                                   ▼
        web-bridge.js (injected host page)  ── window.AndroidBridge ── BlobBridge (Java)
                                   │
        MainActivity (KeyboardWebView + androidx.webkit WebViewAssetLoader)
```

* **WebViewAssetLoader** serves the static site over the virtual HTTPS origin
  `https://appassets.androidplatform.net/assets/`, so ES modules, `fetch()` and `blob:` URLs
  behave exactly like a hosted page (a plain `file://` origin breaks module CORS in WebView).
* **Host-mode framing** — `assets/host.html` is the app's start page. It frames
  `web/index.html?host=parent`, which makes WordCraft run its documented host bridge
  (`apps/wordcraft-web/src/host.rs` upstream). The injected `web-bridge.js` is that host:
  * **Open from other apps** — a document launched with an `ACTION_VIEW` intent is staged
    natively (`BlobBridge`). After the child posts `wordcraft:ready`, the host pulls the bytes in
    256 KiB chunks and posts a `wordcraft:open` message, so the document opens directly — no home
    screen and no second picker. A late-arriving intent reloads the host and repeats the flow.
  * **Save / Export** — every save the app would otherwise download arrives as a
    `wordcraft:save` message whose `ArrayBuffer` is streamed to Java. Writes go to
    `Downloads/WordCraft` via MediaStore (API 29+), a public-directory write on API 26–28
    (runtime permission requested automatically), or app-specific storage as a last resort; the
    toast names the final location. A repeated Save overwrites the session's same-named file
    instead of producing `(1)`, `(2)` copies.
  * **Print** — the app opens a generated PDF in a hidden blob: iframe inside the child. A
    same-origin `MutationObserver` in the host catches it, fetches the blob through the child
    realm, and hands it to the system PDF viewer via a FileProvider `ACTION_VIEW` intent; the
    viewer offers Android's print dialog (WebView itself has no `window.print()` support).
  * **Error visibility** — page errors and unhandled promise rejections are forwarded to a
    `logError` JavascriptInterface method that writes logcat and shows a rate-limited toast.
* **Soft keyboard with hardware keyboards** — `KeyboardWebView` overrides
  `onCreateInputConnection`: Android normally suppresses the IME while a Bluetooth/physical
  keyboard is paired, so it requests the soft keyboard there (`SHOW_IMPLICIT`, escalating to
  `SHOW_FORCED` when the system gates the implicit request), and releases it again on focus loss
  and destroy. Both keyboards then work simultaneously, like a standard Android app. The activity
  uses `windowSoftInputMode="adjustResize"` so the editor resizes above the keyboard.
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
| `app/src/main/assets/host.html` | Top frame that hosts `web/index.html?host=parent`. |
| `app/src/main/assets/web-bridge.js` | Injected host-page implementation of the WordCraft host protocol. |
| `app/src/main/assets/web/` | Trunk output staged here by CI (git-ignored; `.gitkeep` only). |
| `app/src/main/java/ai/storyteller/wordcraft/` | `MainActivity`, `KeyboardWebView` and the `BlobBridge` JS interface. |
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
