#!/usr/bin/env bash
# Builds .github/readme/showcase.webp, the README's showcase, from the website's marketing shots in
# website/static/shots/: Apple TV Home above the iPad player on the left, iPhone Home and a show's page on the right.
# Needs magick (ImageMagick 7) with WebP support.
set -euo pipefail

cd "$(dirname "$0")/.."
command -v magick >/dev/null || { echo "readme_showcase.sh: magick not found" >&2; exit 1; }

shots=website/static/shots
out=.github/readme/showcase.webp
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

col=1000 # width of the TV and iPad column
gap=40
phone_h=$((col * 9 / 16 + gap + col * 3 / 4))

# Rounds the corners of $1 resized to $2, radius $3, written to $4 as PNG with alpha.
rounded() {
  magick "$1" -resize "$2" -alpha set \
    \( +clone -alpha transparent -fill white -draw "roundrectangle 0,0 %[fx:w-1],%[fx:h-1] $3,$3" \) \
    -compose DstIn -composite "$4"
}

rounded "$shots/tv-home-1600.webp" "${col}x" 22 "$tmp/tv.png"
rounded "$shots/ipad-player-1600.webp" "${col}x" 22 "$tmp/ipad.png"
rounded "$shots/iphone-home-640.webp" "x${phone_h}" 58 "$tmp/home.png"
rounded "$shots/iphone-detail-640.webp" "x${phone_h}" 58 "$tmp/detail.png"

magick -background none \
  \( "$tmp/tv.png" -size "1x$gap" xc:none "$tmp/ipad.png" -append \) \
  -size "${gap}x1" xc:none "$tmp/home.png" -size "${gap}x1" xc:none "$tmp/detail.png" \
  +append -resize 1800x -quality 82 -define webp:method=6 -define webp:alpha-quality=100 "$out"

echo "wrote $out"
