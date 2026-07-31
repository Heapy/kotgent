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
exit_requested=0
next_kexe_path=

request_exit() {
    exit_requested=1
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

cleanup() {
    local status=$?
    trap - EXIT INT QUIT TERM HUP
    stop_daemon
    exit "$status"
}

trap request_exit INT QUIT TERM HUP
trap cleanup EXIT

build_binary() {
    next_kexe_path=

    ./kotlin build || return
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
    [[ -n "$daemon_pid" ]] && [[ "$(jobs -pr)" == "$daemon_pid" ]]
}

wait_for_action() {
    local key

    while daemon_is_running; do
        key=
        if IFS= read -r -s -n 1 -t 1 key; then
            case "$key" in
                r | R)
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

    old_pid=$daemon_pid
    printf 'build succeeded; stopping daemon %s...\n' "$old_pid"
    stop_daemon

    if ((exit_requested)); then
        exit 0
    fi

    start_daemon
done
