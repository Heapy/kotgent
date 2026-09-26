#!/usr/bin/env bash
# Test the production unit with a bounded helper and an isolated tmux socket, never the user's daemon.
set -euo pipefail
writer=$(realpath "${1:?usage: test-systemd.sh <systemdcheck binary>}")
systemctl --user show-environment >/dev/null
stage=$(mktemp -d "${TMPDIR:-/tmp}/kotgent-systemd-check.XXXXXX")
socket=${stage##*/}
unit="$socket.service"
helper="$stage/helper %h \$HOME ' quote"
cleanup() {
    systemctl --user stop "$unit" >/dev/null 2>&1 || true
    systemctl --user disable "$unit" >/dev/null 2>&1 || true
    systemctl --user reset-failed "$unit" >/dev/null 2>&1 || true
    tmux -f /dev/null -L "$socket" kill-server >/dev/null 2>&1 || true
    rm -rf "$stage"
    systemctl --user daemon-reload >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 130' INT TERM
cat > "$helper" <<SH
#!/bin/sh
set -eu
tmux -f /dev/null -L '$socket' has-session -t preserved 2>/dev/null ||
  tmux -f /dev/null -L '$socket' new-session -d -s preserved 'exec sleep 300'
trap 'exit 0' TERM INT
count=0
while [ "\$count" -lt 240 ]; do
  sleep 1 &
  wait "\$!" || true
  count=\$((count + 1))
done
SH
chmod 700 "$helper"
"$writer" "$helper" "$stage/$unit"
systemd-analyze verify --man=no "$stage/$unit"
systemctl --user link "$stage/$unit"
systemctl --user daemon-reload
systemctl --user start "$unit"
wait_started() {
    local previous=${1:-0} current
    for ((attempt=0; attempt<100; attempt++)); do
        current=$(systemctl --user show -p MainPID --value "$unit")
        if [[ "$current" =~ ^[0-9]+$ ]] && ((current > 1)) && [[ "$current" != "$previous" ]] &&
            tmux -f /dev/null -L "$socket" has-session -t preserved 2>/dev/null; then
            printf '%s\n' "$current"
            return 0
        fi
        sleep 0.2
    done
    journalctl --user -u "$unit" -n 30 --no-pager >&2
    return 1
}
first=$(wait_started)
pane=$(tmux -f /dev/null -L "$socket" display-message -p -t preserved '#{pane_pid}')
systemctl --user restart "$unit"
second=$(wait_started "$first")
[[ "$(tmux -f /dev/null -L "$socket" display-message -p -t preserved '#{pane_pid}')" == "$pane" ]]
systemctl --user kill --kill-who=main --signal=KILL "$unit"
third=$(wait_started "$second")
[[ "$third" != "$second" ]]
systemctl --user stop "$unit"
[[ "$(tmux -f /dev/null -L "$socket" display-message -p -t preserved '#{pane_pid}')" == "$pane" ]]
kill -0 "$pane"
printf 'systemd install shape, restart, crash recovery, and session survival passed\n'
