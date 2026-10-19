#!/bin/sh
# Vendors the tokentweak package into altitude/static/js/lib, the way every library there is vendored: the
# built files copied byte for byte out of the npm tarball, the license beside them. Run as `make vendor-tokentweak`.
#
#   sh scripts/vendor-tokentweak.sh [spec]
#
# The spec is what to vendor:
#   a version             0.2.0 (the default is `latest`): the registry tarball, fetched with `npm pack`
#   a tarball             ../tokentweak/tokentweak-0.2.0.tgz: an `npm pack` output
#   a checkout            ../tokentweak: packed as it stands, so build it first (`npm run build` there)
#
# Nothing is installed: the tarball is unpacked in a temporary directory and three files are copied from it. A file
# already identical to the tarball's is left alone. The version table in lib/README.md is not edited; the script
# says when its rows need the new version.
set -eu

spec=${1:-latest}
root=$(cd "$(dirname "$0")/.." && pwd)
lib="$root/altitude/static/js/lib"
readme="$lib/README.md"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT INT TERM

case "$spec" in
  *.tgz)
    [ -f "$spec" ] || { echo "vendor-tokentweak: no such tarball: $spec" >&2; exit 1; }
    tgz=$spec
    ;;
  *)
    if [ -d "$spec" ]; then
      [ -f "$spec/package.json" ] || { echo "vendor-tokentweak: $spec is not a package checkout (no package.json)" >&2; exit 1; }
      what=$(cd "$spec" && pwd)
      echo "Packing the checkout $what as it stands (its dist is not rebuilt here)"
    else
      what="tokentweak@$spec"
      echo "Fetching $what from the npm registry"
    fi
    # `npm pack` prints the tarball's file name on stdout and its summary on stderr, which is shown only on a failure
    name=$(cd "$tmp" && npm pack --pack-destination "$tmp" "$what" 2>"$tmp/npm.err") || {
      cat "$tmp/npm.err" >&2; echo "vendor-tokentweak: npm pack failed for $what" >&2; exit 1; }
    tgz="$tmp/$name"
    ;;
esac

mkdir "$tmp/unpacked"
tar -xzf "$tgz" -C "$tmp/unpacked"
pkg="$tmp/unpacked/package"
[ "$(node -p "require('$pkg/package.json').name")" = "tokentweak" ] || {
  echo "vendor-tokentweak: $tgz is not a tokentweak tarball" >&2; exit 1; }
version=$(node -p "require('$pkg/package.json').version")

# tarball path → vendored name
vendor() {
  src="$pkg/$1"
  dest="$lib/$2"
  [ -f "$src" ] || { echo "vendor-tokentweak: $1 is missing from the tarball" >&2; exit 1; }
  if [ -f "$dest" ] && cmp -s "$src" "$dest"; then
    echo "  $2: unchanged"
  else
    cp "$src" "$dest"
    echo "  $2: updated"
  fi
}

echo "tokentweak $version → $lib"
vendor dist/tokentweak.js tokentweak.js
vendor dist/boot.js tokentweak-boot.js
vendor LICENSE tokentweak.LICENSE

if grep -q "^| \`tokentweak.js\` .*| $version " "$readme"; then
  echo "README.md already records $version"
else
  echo "Now record $version in the tokentweak rows of $readme"
fi
echo "A running dev server serves the new files after \`mill altitude.resources\` (make watch does it)"
