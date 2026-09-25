#!/usr/bin/env bash
# Renders the TV banner SVG into the PNGs Android TV / Fire TV use (banner + 16:9 TV launcher icon).
# Uses headless Chrome because ImageMagick's built-in SVG renderer drops strokes.
set -euo pipefail
cd "$(dirname "$0")/../.."
chrome="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
res=app/src/main/res
for spec in xhdpi:320x180 xxhdpi:480x270; do
  density=${spec%%:*}; w=${spec#*:}; w=${w%x*}; h=${spec##*x}
  out=$(mktemp -d)/banner.png
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=$(echo "scale=4; $w/160" | bc) \
    --window-size=160,90 --screenshot="$out" "file://$PWD/docs/brand/kino-tv-banner.svg" 2>/dev/null
  magick "$out" -resize "${w}x${h}!" -strip "$res/drawable-$density/ic_banner.png"
  cp "$res/drawable-$density/ic_banner.png" "$res/mipmap-television-$density/ic_launcher.png"
done
