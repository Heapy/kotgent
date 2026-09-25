package io.kotgent.adapter.codex

/** Keeps the pane alive across Codex's successful, pre-session update exit. */
internal object CodexStartup {
    fun wrap(command: List<String>): List<String> =
        listOf("/bin/sh", "-c", script, "kotgent-codex") + command

    // A changed executable alone is insufficient: another terminal can upgrade a running conversation.
    // SessionStart marks that boundary before its HTTP hook runs, even when the daemon is unreachable.
    private val script = $$"""
        unset KOTGENT_CODEX_STARTUP_DIR
        before=$("$1" --version 2>/dev/null) || exec "$@"
        case "$before" in
          'codex-cli '[0-9]*) ;;
          *) exec "$@" ;;
        esac
        startup_dir=$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/kotgent-codex-startup.XXXXXX") || exec "$@"
        cleanup() {
          /bin/rm -f "$startup_dir/started"
          /bin/rmdir "$startup_dir"
        }
        trap cleanup EXIT
        interrupted=0
        trap 'interrupted=1' INT
        trap 'exit 129' HUP
        trap 'exit 143' TERM
        KOTGENT_CODEX_STARTUP_DIR=$startup_dir
        export KOTGENT_CODEX_STARTUP_DIR
        "$@"
        status=$?
        if [ "$status" -ne 0 ] || [ "$interrupted" -ne 0 ] || [ -e "$startup_dir/started" ]; then
          exit "$status"
        fi
        after=$("$1" --version 2>/dev/null) || exit "$status"
        case "$after" in
          'codex-cli '[0-9]*) ;;
          *) exit "$status" ;;
        esac
        [ "$before" != "$after" ] || exit "$status"
        cleanup
        trap - EXIT INT HUP TERM
        unset KOTGENT_CODEX_STARTUP_DIR
        printf '\nKotgent: Codex updated; continuing startup.\n'
        exec "$@"
    """.trimIndent()
}
