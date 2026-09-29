#!/usr/bin/env bash
set -euo pipefail
archive=${1:?usage: smoke-package.sh <release archive>}
archive_dir=$(cd "$(dirname "$archive")" && pwd)
archive_name=$(basename "$archive")
(
    cd "$archive_dir"
    if command -v sha256sum >/dev/null; then
        sha256sum -c "$archive_name.sha256"
    else
        shasum -a 256 -c "$archive_name.sha256"
    fi
)
stage=$(mktemp -d "${TMPDIR:-/tmp}/kotgent-package.XXXXXX")
trap 'rm -rf "$stage"' EXIT
tar -xzf "$archive_dir/$archive_name" -C "$stage"
name=${archive_name%.tar.gz}
version=${name#kotgent-}
case "$version" in
    *-macos-arm64) version=${version%-macos-arm64} ;;
    *-linux-x64) version=${version%-linux-x64} ;;
    *-linux-arm64) version=${version%-linux-arm64} ;;
    *) printf 'unknown archive target: %s\n' "$archive_name" >&2; exit 1 ;;
esac
binary="$stage/$name/kotgent"
test -x "$binary"
test -s "$stage/$name/resources/webui/index.html"
test -s "$stage/$name/resources/webui/sw.js"
assets="$stage/$name/resources/webui/assets"
[[ -d "$assets" && -n $(find "$assets" -type f -print -quit) ]] || {
    printf 'missing or empty Web UI assets directory: %s\n' "$assets" >&2
    exit 1
}
cd "$stage"
actual_version=$("$binary" --version)
[[ "$actual_version" == "kotgent $version" ]] || {
    printf 'archive/binary version mismatch: %s\n' "$actual_version" >&2
    exit 1
}
printf '%s\n' "$actual_version"
"$binary" --help >/dev/null
if [[ $(uname -s) == Linux ]]; then
    dependencies=$(ldd "$binary")
    printf '%s\n' "$dependencies"
    if [[ "$dependencies" == *'not found'* ]]; then exit 1; fi
fi
