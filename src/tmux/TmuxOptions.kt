package io.kotgent.tmux

/**
 * `-L` isolates the socket, not tmux configuration. User config such as `destroy-unattached on` can
 * violate Detach by killing the session with the last client, so every server-starting argv carries
 * `-f /dev/null`. The flag only affects the invocation that starts a server; if another process starts
 * kotgent's socket first, later flags cannot unload its bindings, hooks, or plugins.
 */
val TMUX_CONFIG_ISOLATION: List<String> = listOf("-f", "/dev/null")

data class TmuxOption(
    val scope: String,
    val name: String,
    val value: String,
)

/**
 * `destroy-unattached` preserves Detach; `default-terminal` and `escape-time` pin compatible defaults;
 * zero escape-time would split fragmented remote escape sequences. Mouse exposes tmux's authoritative
 * pane history, but its shared copy-mode is coupled to [Tmux.sendKeys] and [Tmux.leaveCopyMode]. Joining
 * subscribers need synthesized mouse mode because `capture-pane` contains no private-mode enables.
 *
 * Two fan-out residuals require per-subscriber state: one viewer's copy-mode can swallow another's
 * interactive input, and only the last-resized viewer has accurate mouse coordinates. `focus-events`
 * is intentionally absent because one upstream client has no meaningful single focus state.
 */
val TMUX_SERVER_OPTIONS: List<TmuxOption> = listOf(
    TmuxOption("-g", "destroy-unattached", "off"),
    TmuxOption("-g", "default-terminal", "tmux-256color"),
    TmuxOption("-g", "mouse", "on"),
    TmuxOption("-g", "status", "off"),
    TmuxOption("-g", "history-limit", "10000"),
    TmuxOption("-s", "escape-time", "10"),
)

/** Keeps a joining subscriber's synthesized mouse mode aligned with server configuration. */
fun forcesMouseOn(options: List<TmuxOption>): Boolean =
    options.any { it.name == "mouse" && it.value == "on" }

/**
 * A standalone set-option cannot start a server, and `default-terminal` is consumed at pane creation,
 * so options must prefix new-session in one tmux command chain.
 */
fun tmuxOptionCommands(options: List<TmuxOption>): List<String> =
    options.flatMap { listOf("set-option", it.scope, it.name, it.value, ";") }

/**
 * The global hook must share the server-starting invocation, before options and new-session. Reapplying
 * it on later sessions is idempotent.
 */
fun newSessionArgv(
    serverOptions: List<TmuxOption>,
    hookScriptPath: String?,
    id: String,
    cwd: String,
    cmd: String,
    cols: Int,
    rows: Int,
): List<String> {
    val hook = hookScriptPath?.let {
        listOf("set-hook", "-g", "session-closed", TmuxHookConfig.hookCommand(it), ";")
    }.orEmpty()
    return hook + tmuxOptionCommands(serverOptions) + listOf(
        "new-session", "-d",
        "-s", "kt-$id",
        "-c", cwd,
        "-x", cols.toString(),
        "-y", rows.toString(),
        "-e", "KOTGENT_SESSION_ID=$id",
        "-P", "-F", "#{pane_id}",
        cmd,
    )
}

/**
 * Single assembly point for control-plane calls. Both isolation and socket flags are global and must
 * precede the subcommand.
 */
fun tmuxCommand(tmuxPath: String, socket: String, args: List<String>): List<String> =
    listOf(tmuxPath) + TMUX_CONFIG_ISOLATION + listOf("-L", socket) + args
