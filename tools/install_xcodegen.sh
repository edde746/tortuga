#!/usr/bin/env bash
# Installs the XcodeGen release apple/project.yml is generated with into <dir> (once; checked against the release
# zip's sha256) and prints its binary's path. The CI and release workflows and the Apple test harness use it, so every
# machine generates the Xcode project with the same XcodeGen; project.yml's options.minimumXcodeGenVersion names the
# same version, so an older one elsewhere (brew's, on a Mac) refuses to generate.
#
# Usage: tools/install_xcodegen.sh <dir>
#   "$(tools/install_xcodegen.sh "$RUNNER_TEMP")" generate
# A new version: change both lines below and minimumXcodeGenVersion together.
set -euo pipefail
version=2.46.0
sha256=4d9e34b62172d645eed6457cac13fc222569974098ef4ee9c3368bedf0196806

dir="${1:?usage: $0 <dir>}/xcodegen-$version"
if [[ ! -x "$dir/bin/xcodegen" ]]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL --retry 3 --max-time 300 -o "$tmp/xcodegen.zip" \
    "https://github.com/yonaskolb/XcodeGen/releases/download/$version/xcodegen.zip"
  echo "$sha256  $tmp/xcodegen.zip" | shasum -a 256 -c - > /dev/null
  unzip -q "$tmp/xcodegen.zip" -d "$tmp"
  rm -rf "$dir"
  mkdir -p "$(dirname "$dir")"
  mv "$tmp/xcodegen" "$dir"
fi
echo "$dir/bin/xcodegen"
