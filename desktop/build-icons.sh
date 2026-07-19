#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SOURCE="$ROOT_DIR/build/icon.svg"
ICONSET="$ROOT_DIR/build/icon.iconset"

rm -rf "$ICONSET"
mkdir -p "$ICONSET"

for size in 16 32 128 256 512; do
  /opt/homebrew/bin/rsvg-convert -w "$size" -h "$size" "$SOURCE" -o "$ICONSET/icon_${size}x${size}.png"
  retina=$((size * 2))
  /opt/homebrew/bin/rsvg-convert -w "$retina" -h "$retina" "$SOURCE" -o "$ICONSET/icon_${size}x${size}@2x.png"
done

iconutil -c icns "$ICONSET" -o "$ROOT_DIR/build/icon.icns"
/opt/homebrew/bin/rsvg-convert -w 256 -h 256 "$SOURCE" -o "$ROOT_DIR/build/icon-256.png"
sips -s format ico "$ROOT_DIR/build/icon-256.png" --out "$ROOT_DIR/build/icon.ico" >/dev/null
/opt/homebrew/bin/rsvg-convert -w 1024 -h 1024 "$SOURCE" -o "$ROOT_DIR/build/icon.png"
rm -rf "$ICONSET" "$ROOT_DIR/build/icon-256.png"

echo "Generated build/icon.icns, build/icon.ico and build/icon.png"
