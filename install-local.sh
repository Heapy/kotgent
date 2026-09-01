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
# `kotgent` command. `install` rewrites the launchd plist to the staged binary and
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

skip_install=0
for arg in "$@"; do
    case "$arg" in
        --no-daemon) skip_install=1 ;;
        *)
            printf 'install-local.sh: unknown option %s\n' "$arg" >&2
            printf 'usage: ./install-local.sh [--no-daemon]\n' >&2
            exit 2
            ;;
    esac
done

printf '==> building release binary\n'
./kotlin build -v release -p macosArm64 -m kotgent

# releaseKexePath reports the last build, so it must follow it and no other
# ./kotlin invocation may run in between.
./kotlin do releaseKexePath >/dev/null
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
[[ -e "$libexec" ]] && mv "$libexec" "$libexec.old"
mv "$libexec.new" "$libexec"
rm -rf "$libexec.old"

ln -sfn "$libexec/kotgent" "$bin_dir/kotgent"
printf '==> %s -> %s\n' "$bin_dir/kotgent" "$libexec/kotgent"

if [[ $skip_install -eq 1 ]]; then
    printf '==> launchd agent left alone (--no-daemon); the daemon still runs the old binary\n'
    exit 0
fi

# Run the staged path directly so the plist records it, whatever argv[0] resolution does.
printf '==> reinstalling the launchd agent (this restarts the daemon)\n'
"$libexec/kotgent" install
