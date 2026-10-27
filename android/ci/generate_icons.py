#!/usr/bin/env python3
"""Generate the Android launcher icons from WordCraft's upstream 1024 px app icon.

The source icon (android/ci/icons/wordcraft-1024.png, copied from
storytold/wordcraft assets/app-icon) is a full-bleed rounded square. This script produces:

  * legacy `ic_launcher` / `ic_launcher_round` bitmaps for every density (API 25 and below),
  * adaptive-icon foreground bitmaps on transparent 108 dp canvases (API 26+); the source art
    is scaled into the 72 dp safe zone,
  * res/values/colors.xml with the sampled brand blue used as the adaptive background.

The generated PNGs are committed so CI only needs the Android SDK (no Pillow there). Re-run
after replacing the source icon:

    python3 android/ci/generate_icons.py
"""

from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "android" / "ci" / "icons" / "wordcraft-1024.png"
RES = ROOT / "android" / "app" / "src" / "main" / "res"

# Density qualifier -> legacy launcher size in px (48 dp baseline).
LEGACY_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# Density qualifier -> adaptive icon canvas size in px (108 dp viewport).
ADAPTIVE_SIZES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

# Adaptive icons keep their artwork inside the centre 72 dp of the 108 dp canvas.
SAFE_ZONE = 72 / 108


def sample_background(icon: Image.Image) -> str:
    """Sample the brand blue from points safely inside the rounded-square field."""
    width, height = icon.size
    pts = [
        (width // 2, height // 16),
        (width // 16, height // 2),
        (width * 15 // 16, height // 2),
        (width // 2, height * 15 // 16),
        (width // 8, height // 8),
        (width * 7 // 8, height * 7 // 8),
    ]
    rgb = Counter(icon.convert("RGBA").getpixel(p)[:3] for p in pts).most_common(1)[0][0]
    return "#%02X%02X%02X" % rgb


def write_legacy(icon: Image.Image) -> None:
    for qualifier, size in LEGACY_SIZES.items():
        out_dir = RES / qualifier
        out_dir.mkdir(parents=True, exist_ok=True)
        resized = icon.resize((size, size), Image.LANCZOS)
        resized.save(out_dir / "ic_launcher.png")
        resized.save(out_dir / "ic_launcher_round.png")


def write_foreground(icon: Image.Image) -> None:
    for qualifier, canvas in ADAPTIVE_SIZES.items():
        out_dir = RES / qualifier
        out_dir.mkdir(parents=True, exist_ok=True)
        art_size = round(canvas * SAFE_ZONE)
        art = icon.resize((art_size, art_size), Image.LANCZOS)
        layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        offset = ((canvas - art_size) // 2, (canvas - art_size) // 2)
        layer.alpha_composite(art, offset)
        layer.save(out_dir / "ic_launcher_foreground.png")


def write_colors(blue: str) -> None:
    values = RES / "values"
    values.mkdir(parents=True, exist_ok=True)
    (values / "colors.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        f'    <color name="ic_launcher_background">{blue}</color>\n'
        "    <color name=\"window_background\">#E6E6E6</color>\n"
        "</resources>\n",
        encoding="utf-8",
    )


def main() -> int:
    if not SOURCE.exists():
        print(f"missing source icon: {SOURCE}", file=sys.stderr)
        return 1
    icon = Image.open(SOURCE).convert("RGBA")
    if icon.size != (1024, 1024):
        print(f"expected a 1024x1024 source, got {icon.size}", file=sys.stderr)
        return 1
    blue = sample_background(icon)
    write_legacy(icon)
    write_foreground(icon)
    write_colors(blue)
    print(f"wrote launcher icons to {RES} (background {blue})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
