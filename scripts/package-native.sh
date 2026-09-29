#!/usr/bin/env bash
# Package a previously built release; the binary and its immutable resource tree travel together.
set -euo pipefail
cd "$(dirname "$0")/.."
target=${1:?usage: package-native.sh <macosArm64|linuxX64|linuxArm64>}
[[ -s resources/webui/index.html ]] || {
    printf 'missing Web UI build; run npm ci and npm run build in webui/\n' >&2
    exit 1
}
version=$(tr -d '\r\n' < version.txt)
[[ "$version" =~ ^[0-9][0-9A-Za-z.+-]*$ ]] || { printf 'invalid version.txt\n' >&2; exit 1; }
case "$target" in
    macosArm64) suffix=macos-arm64 ;;
    linuxX64) suffix=linux-x64 ;;
    linuxArm64) suffix=linux-arm64 ;;
    *) printf 'unsupported target: %s\n' "$target" >&2; exit 1 ;;
esac
KOTGENT_TARGET_PLATFORM="$target" ./kotlin 'do' releaseKexePath
binary=$(cat build/kexe-path)
[[ -x "$binary" ]] || { printf 'missing executable: %s\n' "$binary" >&2; exit 1; }
name="kotgent-$version-$suffix"
stage="build/packages/$name"
rm -rf "$stage"
mkdir -p "$stage/resources"
cp "$binary" "$stage/kotgent"
chmod 755 "$stage/kotgent"
cp -R resources/webui "$stage/resources/webui"
tar -czf "build/packages/$name.tar.gz" -C build/packages "$name"
(
    cd build/packages
    if command -v sha256sum >/dev/null; then
        sha256sum "$name.tar.gz" > "$name.tar.gz.sha256"
    else
        shasum -a 256 "$name.tar.gz" > "$name.tar.gz.sha256"
    fi
)
printf '%s\n' "build/packages/$name.tar.gz"
