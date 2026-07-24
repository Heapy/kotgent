package io.kotgent.tmux

/**
 * The tmux global flags that isolate kotgent's server from the operator's configuration.
 *
 * **`-L` isolates the SOCKET, not the CONFIG.** tmux parses `/etc/tmux.conf` and `~/.tmux.conf`
 * whenever a command starts a server, whatever the socket is labelled — measured on a throwaway
 * socket, where an operator's `mouse on` / `focus-events on` leaked straight into a server their
 * config has nothing to do with. The consequence that matters is `destroy-unattached on`: kotgent
 * holds exactly ONE upstream `tmux attach` per session and closes it on the last subscriber — that
 * last-detach IS the "Detach", and the agent is supposed to live on inside tmux. With that option
 * inherited, tmux destroys the session the moment the upstream goes away.
 *
 * `-f /dev/null` suppresses the user config completely (measured: `mouse` falls back to the built-in
 * `off` despite `mouse on` in `~/.tmux.conf`), which alone restores the Detach invariant — tmux's own
 * default for `destroy-unattached` is already `off`. Same shape as the `LANG` rule: **force, never
 * inherit.** A generated `~/.kotgent/tmux.conf` was rejected — a file that can go stale and invites
 * hand-editing, where a Kotlin list stays testable.
 *
 * **The flag only applies to the invocation that STARTS a server.** On later calls it is inert but
 * harmless, so it rides on every argv rather than on a "first call" special case. Known limitation:
 * if something *other* than kotgent already started a server on this socket, that server has loaded
 * the user's config and no later `-f` can undo it — [TMUX_SERVER_OPTIONS] still re-converge the
 * option *values* on the next `newSession`, but bindings/hooks/plugins loaded at that server's start
 * remain. Not guarded against: kotgent is the only thing that should touch its socket.
 */
val TMUX_CONFIG_ISOLATION: List<String> = listOf("-f", "/dev/null")

/**
 * One forced tmux option: `set-option <scope> <name> <value>`.
 *
 * [scope] is a tmux option scope flag — `-g` (global, the session/window option namespace kotgent
 * owns wholesale on its socket) or `-s` (server). It documents which namespace an option belongs to
 * and is the flag the read-back in the integration test uses (`show-options <scope>v <name>`); it is
 * **not** a correctness gate. tmux resolves an option's scope from its NAME
 * (`options_scope_from_name`) and ignores a mismatched flag — measured on 3.7b, `set-option -g
 * escape-time 55` exits 0 and really sets the *server* option.
 */
data class TmuxOption(
    /** Option scope flag: `-g` (global) or `-s` (server). */
    val scope: String,
    /** tmux option name, e.g. `history-limit`. */
    val name: String,
    /** Option value as tmux spells it, e.g. `off` / `10000`. */
    val value: String,
)

/**
 * The options kotgent forces on its own tmux server, in application order.
 *
 * | Option | Built-in | Why |
 * |---|---|---|
 * | `destroy-unattached off` | `off` | Pin: Detach must never kill the agent |
 * | `default-terminal tmux-256color` | `tmux-256color` | Pin against a future default change |
 * | `status off` | `on` | The status bar costs a row and renders noise into a pane nobody drives with tmux keys |
 * | `history-limit 10000` | `2000` | 2000 lines is thin for an agent transcript |
 * | `escape-time 10` | `10` | Pin: the legacy 500 ms default makes `ESC` laggy in a TUI |
 *
 * Three of them (`destroy-unattached`, `default-terminal`, `escape-time`) are **no-ops against
 * today's tmux built-in defaults** — measured under `-f /dev/null` on tmux 3.7b. They are kept as
 * deliberate pins: the invariant they protect must not silently depend on a future upstream default.
 * `escape-time` is pinned at tmux's own 10 ms rather than `0`: the lag `0` would buy back is 10 ms,
 * and a zero escape-time is exactly what mis-splits a multi-byte escape sequence that arrives
 * fragmented — which is the normal case for kotgent's remote/phone access over the tunnel, where
 * terminal input reaches the pane in whatever chunks the WebSocket delivered.
 *
 * `default-terminal` names a terminfo entry that must exist on the host: `tmux-256color` is present
 * on current macOS but absent on some older releases. Pinning tmux's own default keeps that a no-op
 * everywhere it matters today; if the pin is ever changed to a value tmux would not have chosen
 * itself, check the entry resolves first — `set-option` succeeds regardless (tmux does not validate
 * terminfo at set time), so a bad value only shows up as a broken TUI inside the pane.
 *
 * **`mouse` is deliberately NOT here** (it stays at the built-in `off`). Turning it on looks like
 * free wheel-scrollback but breaks two things under kotgent's fan-out: copy-mode is *pane* state, so
 * one subscriber's wheel puts the shared pane into copy-mode, and while a pane is in copy-mode every
 * `send-keys` — including [Tmux.sendKeys]'s Ctrl-C — is routed to the copy-mode key table and never
 * reaches the process, silently (tmux still exits 0). The pane's terminal-mode DECSET is also only
 * ever seen by the subscriber that opened the upstream, so the feature would not even work for a
 * client that joins an existing bridge. Client-side scrollback (xterm.js, the CLI's own terminal)
 * covers the ergonomics without any of that.
 *
 * **`focus-events` is deliberately NOT here.** Focus tracking is meaningless under kotgent's fan-out
 * — one upstream client serves N subscribers (browser, IDE, CLI), so "is the terminal focused" has
 * no single answer, and kotgent already has strictly better signals (the bridge's subscriber count,
 * agent state over hooks). Because kotgent never sets it, it is also the decoy the integration
 * isolation test uses to prove `-f /dev/null` actually suppresses a user config; adding it here
 * would make that test unfalsifiable, which is why a unit test asserts its absence.
 */
val TMUX_SERVER_OPTIONS: List<TmuxOption> = listOf(
    TmuxOption("-g", "destroy-unattached", "off"),
    TmuxOption("-g", "default-terminal", "tmux-256color"),
    TmuxOption("-g", "status", "off"),
    TmuxOption("-g", "history-limit", "10000"),
    TmuxOption("-s", "escape-time", "10"),
)

/**
 * Expand [options] into tmux argv: one `set-option <scope> <name> <value>` per option, each
 * terminated by a `;` command separator — so the result **prefixes** a subcommand in the SAME tmux
 * invocation (`tmux -f /dev/null -L <socket> set-option … ';' new-session …`).
 *
 * The chain is not an optimisation, it is the only way: a standalone `set-option` does **not** start
 * a server (measured — `error connecting to …`, exit 1, nothing applied), and `default-terminal` is
 * read when a pane is CREATED, so setting it after `new-session` would already be too late for the
 * agent running in that pane. [ProcessRunner] quotes every argument, so the literal `;` survives
 * `/bin/sh` and reaches tmux as a separator rather than being eaten by the shell.
 *
 * [options] is a required parameter rather than a direct read of [TMUX_SERVER_OPTIONS] so the
 * caller's own configured set drives the expansion — see [Tmux.serverOptions].
 */
fun tmuxOptionCommands(options: List<TmuxOption>): List<String> =
    options.flatMap { listOf("set-option", it.scope, it.name, it.value, ";") }

/**
 * argv for any kotgent tmux invocation: `tmuxPath` + [TMUX_CONFIG_ISOLATION] + `-L socket` + `args`.
 *
 * The single assembly point for control-plane calls, so isolation cannot be forgotten at a new call
 * site. Both `-f /dev/null` and `-L` are tmux **global** flags and must precede the subcommand;
 * appending them to [args] would make tmux read them as subcommand arguments.
 */
fun tmuxCommand(tmuxPath: String, socket: String, args: List<String>): List<String> =
    listOf(tmuxPath) + TMUX_CONFIG_ISOLATION + listOf("-L", socket) + args
