#!/bin/bash
#
# Builds the working tree and installs it as the kotgent the machine runs, without
# touching Homebrew. The brew keg stays installed and stays the revert path.
#
# Layout mirrors the formula: the binary and `resources/webui` sit together under
# libexec, because the daemon locates the Web UI by walking up from the executable.
#
#   ~/.local/kotgent/libexec/kotgent
#   ~/.local/kotgent/libexec/resources/webui
#   ~/.local/bin/kotgent -> ../kotgent/libexec/kotgent
#
# `~/.local/bin` precedes `/opt/homebrew/bin` on PATH, so the symlink wins the
# `kotgent` command. `install` rewrites the service definition to the staged binary and
# restarts the daemon.
#
# Revert to the released build:
#   rm -f ~/.local/bin/kotgent && rm -rf ~/.local/kotgent && /opt/homebrew/bin/kotgent install

set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir"

prefix="${KOTGENT_LOCAL_PREFIX:-$HOME/.local/kotgent}"
bin_dir="${KOTGENT_LOCAL_BIN:-$HOME/.local/bin}"
libexec="$prefix/libexec"

# Rename a complete copy into place so no request reads a half-copied tree.
swap_dir() {
    [[ -e "$1" ]] && mv "$1" "$1.old"
    mv "$1.new" "$1"
    rm -rf "$1.old"
}

usage() {
    printf 'usage: ./install-local.sh [--no-daemon | --webui-only]\n' >&2
}

skip_install=0
webui_only=0
for arg in "$@"; do
    case "$arg" in
        --no-daemon) skip_install=1 ;;
        --webui-only) webui_only=1 ;;
        *)
            printf 'install-local.sh: unknown option %s\n' "$arg" >&2
            usage
            exit 2
            ;;
    esac
done
if [[ $skip_install -eq 1 && $webui_only -eq 1 ]]; then
    printf 'install-local.sh: --no-daemon and --webui-only are exclusive\n' >&2
    usage
    exit 2
fi

if [[ $webui_only -eq 1 ]]; then
    webui="$libexec/resources/webui"
    [[ -x "$libexec/kotgent" && -d "$libexec/resources" ]] || {
        printf 'install-local.sh: nothing installed at %s; run ./install-local.sh first\n' "$libexec" >&2
        exit 1
    }
    printf '==> replacing %s\n' "$webui"
    rm -rf "$webui.new" "$webui.old"
    cp -R resources/webui "$webui.new"
    swap_dir "$webui"
    # shellcheck disable=SC2009 # pgrep would read the path as a regex; it must match literally.
    if ps -A -ww -o args= | grep -Fqx -- "$libexec/kotgent daemon"; then
        printf '==> reload the browser to pick it up; the daemon keeps running\n'
    else
        printf '==> warning: no running daemon uses %s; the service will not see this until you run ./install-local.sh\n' \
            "$libexec/kotgent" >&2
    fi
    exit 0
fi

printf '==> building release binary\n'
case "$(uname -s)/$(uname -m)" in
    Darwin/arm64) target=macosArm64; module=kotgent-macos ;;
    Linux/x86_64) target=linuxX64; module=kotgent-linux ;;
    Linux/aarch64|Linux/arm64)
        printf 'Kotlin/Native cannot build on Linux ARM64; install the Linux ARM64 release archive or cross-compile on x64.\n' >&2
        exit 1 ;;
    *) printf 'unsupported build host\n' >&2; exit 1 ;;
esac
export KOTGENT_TARGET_PLATFORM="$target"
./kotlin build -v release -p "$target" -m "$module"

# releaseKexePath reports the last build, so it must follow it and no other
# ./kotlin invocation may run in between.
./kotlin 'do' releaseKexePath >/dev/null
kexe=$(cat build/kexe-path)
[[ -x "$kexe" ]] || { printf 'install-local.sh: no binary at %s\n' "$kexe" >&2; exit 1; }

printf '==> staging %s\n' "$libexec"
# Stage beside the target and swap by rename: the running daemon holds the old
# inode, so overwriting the live binary in place would fail with ETXTBSY.
rm -rf "$libexec.new" "$libexec.old"
mkdir -p "$libexec.new/resources"
cp "$kexe" "$libexec.new/kotgent"
chmod +x "$libexec.new/kotgent"
# Copy, never symlink: webUiRevision digests this tree, and a copy that drifts
# under a running daemon would serve assets the revision no longer matches.
cp -R resources/webui "$libexec.new/resources/webui"

mkdir -p "$prefix" "$bin_dir"
swap_dir "$libexec"

ln -sfn "$libexec/kotgent" "$bin_dir/kotgent"
printf '==> %s -> %s\n' "$bin_dir/kotgent" "$libexec/kotgent"

if [[ $skip_install -eq 1 ]]; then
    printf '==> daemon service left alone (--no-daemon); the daemon still runs the old binary\n'
    exit 0
fi

# Run the staged path directly so the service records it, whatever argv[0] resolution does.
printf '==> reinstalling the daemon service (this restarts the daemon)\n'
"$libexec/kotgent" install
