#!/usr/bin/env bash
# Uploads debug files of one type to the crash-report server, edde746/bugs, which takes sentry-cli's chunked uploads,
# with the sentry-cli release below (checked against its sha256). It waits until the server has processed each file
# (600 s at most, the wait bugs advertises by default), so a file the server can't read fails the upload, and it fails
# when sentry-cli found no file of that type in the paths. The release workflow runs it: the Android job for the native
# libraries (elf), the Apple jobs for their dSYMs (dsym).
#
# Usage: tools/upload_symbols.sh <elf|dsym> <path>...
#   tools/upload_symbols.sh elf android/app/build/intermediates/merged_native_libs/release
# Env: SENTRY_AUTH_TOKEN (the server's admin token), SENTRY_URL, SENTRY_ORG, SENTRY_PROJECT (the project's slug)
# A new version: change the version and both checksums together.
set -euo pipefail
version=3.8.2
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) asset=sentry-cli-Linux-x86_64 sha256=c958865f9d4000183509ea21b1f04925d5390de6d8d87386b9e69f2b305070c3 ;;
  Darwin-*) asset=sentry-cli-Darwin-universal sha256=2d1b118fa46960ba5e8e0cca9a6e197366905776a0ddc5a89c7d05a3908b77db ;;
  *) echo "upload_symbols.sh: no sentry-cli for $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

type=${1:?usage: $0 <elf|dsym> <path>...}
shift
(($#)) || { echo "usage: $0 <elf|dsym> <path>..." >&2; exit 2; }
for path in "$@"; do
  [[ -e $path ]] || { echo "upload_symbols.sh: $path doesn't exist" >&2; exit 1; }
done

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL --retry 3 --max-time 300 -o "$tmp/sentry-cli" \
  "https://github.com/getsentry/sentry-cli/releases/download/$version/$asset"
echo "$sha256  $tmp/sentry-cli" | shasum -a 256 -c - > /dev/null
chmod +x "$tmp/sentry-cli"

"$tmp/sentry-cli" debug-files upload --type "$type" --wait-for 600 "$@" 2>&1 | tee "$tmp/upload.log"
sed 's/\x1b\[[0-9;]*m//g' "$tmp/upload.log" | grep -Eq 'Found [1-9][0-9]* debug information files?' ||
  { echo "upload_symbols.sh: sentry-cli found no $type debug files in $*" >&2; exit 1; }
