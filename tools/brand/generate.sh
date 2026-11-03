#!/usr/bin/env bash
# Regenerates every committed icon, banner and Top Shelf image from tools/brand/*.svg:
#   android/app/src/main/res/{mipmap-*,mipmap-anydpi-v26,drawable,drawable-*dpi}/ (launcher icons, TV banner,
#                                                          the media notification's status-bar icon)
#   apple/AppIcon.icon/                                    (iOS, iPadOS and macOS: the Icon Composer icon)
#   apple/Brand.xcassets/                                  (tvOS: the layered icon and Top Shelf)
#   desktop/app/icons/                                     (Windows .ico, Linux hicolor PNGs)
#   desktop/app/packaging/windows/assets/                  (the MSIX package's logos, tiles and splash screen)
#   store/{google-play/metadata/en-US,amazon,microsoft}/images/
#                                                          (the stores' listing graphics: Play's icon, feature graphic
#                                                          and TV banner, Amazon's icons, promotional image and Fire TV
#                                                          art, Microsoft's tile icon and Super hero art)
# Needs python3, rsvg-convert (librsvg) and magick (ImageMagick 7), plus the "Avenir Next" font
# the TV banner wordmark is set in. Outputs are byte-for-byte reproducible with the same tools.
# tools/brand/verify.sh checks the results with actool and aapt2.
set -euo pipefail

cd "$(dirname "$0")/../.."

for tool in python3 rsvg-convert magick fc-match; do
  command -v "$tool" >/dev/null || { echo "generate.sh: $tool not found" >&2; exit 1; }
done

# rsvg-convert substitutes a fallback font silently; the banner must use the one it names.
font=$(fc-match --format '%{family}' 'Avenir Next:bold')
case "$font" in
  *"Avenir Next"*) ;;
  *) echo "generate.sh: font 'Avenir Next' is not installed (fontconfig picked '$font')" >&2; exit 1 ;;
esac

exec python3 tools/brand/brand.py
