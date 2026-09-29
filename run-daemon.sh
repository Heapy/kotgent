#!/bin/bash

set -u

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir" || exit 1

if [[ ! -t 0 ]]; then
    printf 'run-daemon.sh requires an interactive terminal\n' >&2
    exit 2
fi

daemon_args=("$@")
daemon_pid=
webui_pid=
webui_log="$script_dir/build/webui-watch.log"
exit_requested=0
next_kexe_path=

request_exit() {
    exit_requested=1
}

child_is_running() {
    local pid
    for pid in $(jobs -pr); do
        [[ "$pid" == "$1" ]] && return 0
    done
    return 1
}

stop_daemon() {
    local pid=$daemon_pid
    [[ -n "$pid" ]] || return

    if kill -0 "$pid" 2>/dev/null; then
        kill -TERM "$pid" 2>/dev/null || true
    fi

    # A signal can interrupt wait, so keep waiting until the old daemon is
    # actually gone. Starting earlier could race it for the listening port.
    while kill -0 "$pid" 2>/dev/null; do
        wait "$pid" 2>/dev/null || true
    done
    wait "$pid" 2>/dev/null || true
    daemon_pid=
}

stop_webui_watcher() {
    local pid=$webui_pid
    [[ -n "$pid" ]] || return

    if child_is_running "$pid"; then
        kill -TERM "$pid" 2>/dev/null || true
    fi

    while child_is_running "$pid"; do
        wait "$pid" 2>/dev/null || true
    done
    wait "$pid" 2>/dev/null || true
    webui_pid=
}

cleanup() {
    local status=$?
    trap - EXIT
    # Repeated signals must not interrupt child cleanup.
    trap '' INT QUIT TERM HUP
    stop_webui_watcher
    stop_daemon
    exit "$status"
}

trap request_exit INT QUIT TERM HUP
trap cleanup EXIT

install_webui_dependencies() {
    local lock_hash
    lock_hash=$(node -e '
        const fs = require("fs"), crypto = require("crypto");
        console.log(crypto.createHash("sha256")
            .update(fs.readFileSync("webui/package-lock.json")).digest("hex"));
    ') || return
    local stamp=webui/node_modules/.run-daemon-package-lock.sha256
    if [[ -d webui/node_modules && -f "$stamp" && "$(<"$stamp")" == "$lock_hash" ]]; then
        return 0
    fi

    printf 'installing Web UI dependencies...\n'
    npm ci --prefix webui || return
    if ((exit_requested)); then
        return 130
    fi
    printf '%s\n' "$lock_hash" > "$stamp"
}

start_webui_watcher() {
    # Monitor mode isolates Vite from terminal signals handled by the supervisor.
    set -m
    (
        cd webui || exit 1
        exec node node_modules/vite/bin/vite.js build --watch
    ) </dev/null >>"$webui_log" 2>&1 &
    webui_pid=$!
    set +m

    printf 'Web UI watcher started (pid %s)\n' "$webui_pid"
}

ensure_webui_watcher() {
    if ((exit_requested)) || child_is_running "$webui_pid"; then
        return
    fi

    wait "$webui_pid"
    local status=$?
    webui_pid=
    printf 'Web UI watcher exited with status %s; restarting\n' "$status" >&2
    start_webui_watcher
}

build_binary() {
    next_kexe_path=

    case "$(uname -s)/$(uname -m)" in
        Darwin/arm64) export KOTGENT_TARGET_PLATFORM=macosArm64; local module=kotgent-macos ;;
        Linux/x86_64) export KOTGENT_TARGET_PLATFORM=linuxX64; local module=kotgent-linux ;;
        *) printf 'unsupported build host; use a release archive on Linux ARM64\n' >&2; return 1 ;;
    esac
    ./kotlin build -p "$KOTGENT_TARGET_PLATFORM" -m "$module" || return
    ./kotlin "do" kexePath || return

    if [[ ! -s build/kexe-path ]]; then
        printf 'kexePath did not write build/kexe-path\n' >&2
        return 1
    fi

    next_kexe_path=$(<build/kexe-path)
    if [[ ! -x "$next_kexe_path" ]]; then
        printf 'built executable is missing or not executable: %s\n' "$next_kexe_path" >&2
        return 1
    fi
}

start_daemon() {
    # Monitor mode gives the background daemon its own process group. The
    # supervisor keeps ownership of the terminal and handles its shortcuts.
    set -m
    # Bash 3.2 (the macOS system Bash) treats an empty "${array[@]}" as an
    # unbound variable under `set -u`, so the zero-argument case is explicit.
    if ((${#daemon_args[@]})); then
        "$next_kexe_path" daemon "${daemon_args[@]}" </dev/null &
    else
        "$next_kexe_path" daemon </dev/null &
    fi
    daemon_pid=$!
    set +m

    printf 'daemon started (pid %s)\n' "$daemon_pid"
}

daemon_is_running() {
    [[ -n "$daemon_pid" ]] && child_is_running "$daemon_pid"
}

wait_for_action() {
    local key

    while daemon_is_running; do
        ensure_webui_watcher
        key=
        if IFS= read -r -s -n 1 -t 1 key; then
            case "$key" in
                r | R)
                    ensure_webui_watcher
                    return 0
                    ;;
                q | Q)
                    exit_requested=1
                    return 1
                    ;;
            esac
        fi

        if ((exit_requested)); then
            return 1
        fi
    done

    return 2
}

install_webui_dependencies || exit 1
if ((exit_requested)); then
    exit 130
fi

printf 'building initial Web UI...\n'
npm run build --prefix webui || exit 1
if ((exit_requested)); then
    exit 130
fi

mkdir -p build || exit 1
: >"$webui_log" || exit 1
printf 'Web UI watcher log: %s\n' "$webui_log"
start_webui_watcher

printf 'building initial daemon...\n'
if ! build_binary; then
    if ((exit_requested)); then
        exit 130
    fi
    printf 'initial build failed; daemon was not started\n' >&2
    exit 1
fi
if ((exit_requested)); then
    exit 130
fi

ensure_webui_watcher
start_daemon
printf 'r: rebuild and restart on success. q or Ctrl-C: stop.\n'

while :; do
    wait_for_action
    action=$?

    if ((action == 1)); then
        exit 0
    fi

    if ((action == 2)); then
        wait "$daemon_pid"
        daemon_status=$?
        daemon_pid=
        printf 'daemon exited with status %s; supervisor stopping\n' "$daemon_status" >&2
        exit "$daemon_status"
    fi

    printf '\nrebuilding while daemon %s keeps running...\n' "$daemon_pid"
    if ! build_binary; then
        if ((exit_requested)); then
            exit 130
        fi
        ensure_webui_watcher
        if ! daemon_is_running; then
            wait "$daemon_pid"
            daemon_status=$?
            daemon_pid=
            printf 'build failed and the old daemon exited with status %s\n' "$daemon_status" >&2
            exit "$daemon_status"
        fi
        printf 'build failed; old daemon %s is still running. Press r to retry.\n' "$daemon_pid" >&2
        continue
    fi

    if ((exit_requested)); then
        exit 130
    fi

    ensure_webui_watcher
    old_pid=$daemon_pid
    printf 'build succeeded; stopping daemon %s...\n' "$old_pid"
    stop_daemon

    if ((exit_requested)); then
        exit 0
    fi

    start_daemon
done
