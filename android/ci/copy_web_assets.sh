#!/usr/bin/env bash
#
# Stage the Trunk-built WordCraft web site into the Android APK's assets.
#
# Usage: android/ci/copy_web_assets.sh <dist-dir>
#   e.g. android/ci/copy_web_assets.sh upstream/dist/web
#
# The Gradle project only ever reads assets/web, so the WASM build output never needs to be
# committed: CI builds it fresh on every run.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <dist-dir>" >&2
  exit 2
fi

DIST="$1"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEST="$ROOT/android/app/src/main/assets/web"

if [[ ! -f "$DIST/index.html" ]]; then
  echo "error: $DIST/index.html not found; build the web app first (trunk build --release)" >&2
  exit 1
fi

# Trunk is configured with public_url = "./", so every reference must be relative. A root-absolute
# URL would silently break inside file:///android_asset/.
if grep -Eq '(src|href)="/[^/]' "$DIST/index.html"; then
  echo "error: $DIST/index.html contains root-absolute URLs; it cannot be packaged for Android" >&2
  exit 1
fi

rm -rf "$DEST"
mkdir -p "$DEST"
cp -R "$DIST/." "$DEST/"

# Hosting samples are useful on a web server but dead weight inside the APK.
rm -f "$DEST/_headers" "$DEST/.htaccess" "$DEST/HOSTING.md" "$DEST/README.md"

# Keep the tracked placeholder so the empty assets/web directory exists in fresh checkouts.
touch "$DEST/.gitkeep"

count="$(find "$DEST" -type f | wc -l | tr -d ' ')"
echo "staged $count web files into $DEST"
find "$DEST" -maxdepth 1 -type f -printf '  %f (%s bytes)\n' | sort
