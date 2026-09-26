#!/usr/bin/env bash
# Run only on disposable Ubuntu 22.04 CI/build hosts; ARM64 adds Ubuntu's multiarch repositories.
set -euo pipefail
# Package names, repositories and GCC paths are deliberately tied to the release baseline.
# shellcheck source=/dev/null
source /etc/os-release
[[ "$ID" == ubuntu && "$VERSION_ID" == 22.04 ]] || {
    printf 'use a disposable Ubuntu 22.04 x64 build host\n' >&2
    exit 1
}
target=${1:-linuxX64}
case "$target" in linuxX64|linuxArm64) ;; *) exit 2 ;; esac
elevate=()
if ((EUID != 0)); then elevate=(sudo); fi
export DEBIAN_FRONTEND=noninteractive
if [[ "$target" == linuxArm64 ]]; then
    [[ $(uname -m) == x86_64 ]] || { printf 'cross builds require an x64 compiler host\n' >&2; exit 1; }
    "${elevate[@]}" dpkg --add-architecture arm64
    # The x64 archive does not carry ARM64 packages. Keep the two repository architectures separate.
    "${elevate[@]}" sed -i -E 's/^deb (\[[^]]*\] )?/deb [arch=amd64] /' /etc/apt/sources.list
    "${elevate[@]}" tee /etc/apt/sources.list.d/kotgent-arm64.list >/dev/null <<'SOURCES'
deb [arch=arm64] http://ports.ubuntu.com/ubuntu-ports jammy main universe
deb [arch=arm64] http://ports.ubuntu.com/ubuntu-ports jammy-updates main universe
deb [arch=arm64] http://ports.ubuntu.com/ubuntu-ports jammy-security main universe
SOURCES
    # Ktor bundles curl/OpenSSL; curl development packages conflict across architectures.
    packages=(libcurl4:arm64 libsqlite3-dev:arm64 libstdc++-11-dev:arm64 zlib1g-dev:arm64)
else
    packages=(libcurl4 libsqlite3-dev libstdc++-11-dev zlib1g-dev)
fi
"${elevate[@]}" apt-get update -qq
"${elevate[@]}" apt-get install -y --no-install-recommends "${packages[@]}" tmux curl perl openssl ca-certificates python3 systemd dbus-user-session
