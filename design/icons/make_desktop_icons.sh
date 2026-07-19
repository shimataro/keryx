#!/usr/bin/env bash
#
# Generates the desktop packaging icons (macOS .icns, Windows .ico, Linux .png)
# and the tray icons from the SVG masters in design/icons/svg/. This repo is
# self-contained.
#
# Requires: rsvg-convert (`brew install librsvg`) to rasterize the SVG masters
# - plain ImageMagick's built-in SVG delegate silently drops the arc paths in
# app_icon.svg (the broadcast-wave curves), so it's not a valid substitute.
# Also requires: sips + iconutil (macOS) for .icns, ImageMagick (magick) for
# padding/.ico/tray compositing.
set -euo pipefail

command -v rsvg-convert >/dev/null || {
  echo "rsvg-convert not found. Install it with: brew install librsvg" >&2
  exit 1
}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
KMP_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SVG_DIR="$SCRIPT_DIR/svg"
OUT="$KMP_ROOT/composeApp/icons"
DRAWABLE="$KMP_ROOT/composeApp/src/commonMain/composeResources/drawable"

mkdir -p "$OUT" "$DRAWABLE"

ICON_WORK="$(mktemp -d)"
trap 'rm -rf "$ICON_WORK"' EXIT

SRC="$ICON_WORK/app_icon_1024.png"
rsvg-convert -w 1024 -h 1024 "$SVG_DIR/app_icon.svg" -o "$SRC"

# --- Pad the app icon to macOS's full-bleed safe area (824x824 centered in a
# transparent 1024x1024 canvas) so it matches the size of other Mac apps in
# the Dock. The source artwork bleeds edge-to-edge with no margin baked in. ---
PADDED="$ICON_WORK/padded_1024.png"
if command -v magick >/dev/null; then
  magick "$SRC" -resize 824x824 \( -size 1024x1024 xc:none \) +swap -gravity center -composite "$PADDED"
else
  cp "$SRC" "$PADDED"
fi

# --- Linux ---
cp "$PADDED" "$OUT/keryx.png"

# --- macOS .icns ---
if command -v sips >/dev/null && command -v iconutil >/dev/null; then
  WORK="$(mktemp -d)"
  ICONSET="$WORK/keryx.iconset"
  mkdir -p "$ICONSET"
  sips -z 16 16     "$PADDED" --out "$ICONSET/icon_16x16.png"      >/dev/null
  sips -z 32 32     "$PADDED" --out "$ICONSET/icon_16x16@2x.png"   >/dev/null
  sips -z 32 32     "$PADDED" --out "$ICONSET/icon_32x32.png"      >/dev/null
  sips -z 64 64     "$PADDED" --out "$ICONSET/icon_32x32@2x.png"   >/dev/null
  sips -z 128 128   "$PADDED" --out "$ICONSET/icon_128x128.png"    >/dev/null
  sips -z 256 256   "$PADDED" --out "$ICONSET/icon_128x128@2x.png" >/dev/null
  sips -z 256 256   "$PADDED" --out "$ICONSET/icon_256x256.png"    >/dev/null
  sips -z 512 512   "$PADDED" --out "$ICONSET/icon_256x256@2x.png" >/dev/null
  sips -z 512 512   "$PADDED" --out "$ICONSET/icon_512x512.png"    >/dev/null
  sips -z 1024 1024 "$PADDED" --out "$ICONSET/icon_512x512@2x.png" >/dev/null
  iconutil -c icns "$ICONSET" -o "$OUT/keryx.icns"
  rm -rf "$WORK"
fi

# --- Windows .ico ---
if command -v magick >/dev/null; then
  magick "$PADDED" -define icon:auto-resize=16,32,48,64,128,256 "$OUT/keryx.ico"
fi

# --- Compose resource used for the Dock icon at runtime (main.kt) ---
if command -v magick >/dev/null; then
  magick "$PADDED" -resize 512x512 "$DRAWABLE/app_icon.png"
fi

# --- Tray icons (Compose resources) ---
rsvg-convert -w 64 -h 64 "$SVG_DIR/tray_icon.svg" -o "$DRAWABLE/tray_icon.png"

# macOS tray icon: derived from the shared black silhouette by adding a white
# fill + black outline, so a single asset stays legible on both light and dark
# menu bars without any OS theme detection. (AWT's apple.awt.enableTemplateImages
# was tried first but doesn't reliably reflect the real OS appearance on this
# JDK - see plan history.) Requires ImageMagick (magick).
if command -v magick >/dev/null; then
  TRAY_WORK="$(mktemp -d)"
  TRAY_SRC="$TRAY_WORK/tray_icon_macos_template.png"
  rsvg-convert -w 64 -h 64 "$SVG_DIR/tray_icon_macos_template.svg" -o "$TRAY_SRC"
  magick "$TRAY_SRC" -alpha extract -morphology Dilate Disk:3.5 "$TRAY_WORK/outline_mask.png"
  magick "$TRAY_WORK/outline_mask.png" -background black -alpha shape "$TRAY_WORK/outline.png"
  magick "$TRAY_SRC" -fill white -colorize 100 "$TRAY_WORK/fill.png"
  magick "$TRAY_WORK/outline.png" "$TRAY_WORK/fill.png" -gravity center -composite \
    -type TrueColorAlpha -define png:color-type=6 "$DRAWABLE/tray_icon_macos.png"
  rm -rf "$TRAY_WORK"
fi

echo "Generated desktop icons in $OUT and tray icons in $DRAWABLE"
