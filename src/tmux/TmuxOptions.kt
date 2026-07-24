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
 * The flag only applies to the invocation that STARTS a server; on later calls it is inert but
 * harmless, so it rides on every argv rather than on a "first call" special case.
 */
val TMUX_CONFIG_ISOLATION: List<String> = listOf("-f", "/dev/null")

/**
 * One forced tmux option: `set-option <scope> <name> <value>`.
 *
 * [scope] is a tmux option scope flag — `-g` (global, the session/window option namespace kotgent
 * owns wholesale on its socket) or `-s` (server). It is NOT a free-form string: tmux rejects, for
 * example, `-g escape-time`, because that one is server-scoped.
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
 * | `mouse on` | `off` | Wheel-scrolls the pane's history; an app that requests mouse reporting still receives its own events |
 * | `status off` | `on` | The status bar costs a row and renders noise into a pane nobody drives with tmux keys |
 * | `history-limit 10000` | `2000` | 2000 lines is thin for an agent transcript |
 * | `escape-time 0` | `10` | The built-in delay makes `ESC` laggy in a TUI |
 *
 * Two of them (`destroy-unattached`, `default-terminal`) are **no-ops against today's tmux built-in
 * defaults** — measured under `-f /dev/null` on tmux 3.7b. They are kept as deliberate pins: the
 * invariant they protect must not silently depend on a future upstream default.
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
    TmuxOption("-g", "mouse", "on"),
    TmuxOption("-g", "status", "off"),
    TmuxOption("-g", "history-limit", "10000"),
    TmuxOption("-s", "escape-time", "0"),
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
 * [options] is a parameter (defaulting to [TMUX_SERVER_OPTIONS]) so the caller's own configured set
 * — including a deliberately bogus one in tests — drives the expansion.
 */
fun tmuxOptionCommands(options: List<TmuxOption> = TMUX_SERVER_OPTIONS): List<String> =
    options.flatMap { listOf("set-option", it.scope, it.name, it.value, ";") }

/**
 * argv for any kotgent tmux invocation: `[tmuxPath] + `[TMUX_CONFIG_ISOLATION]` + [-L, socket] + [args]`.
 *
 * The single assembly point for control-plane calls, so isolation cannot be forgotten at a new call
 * site. Both `-f /dev/null` and `-L` are tmux **global** flags and must precede the subcommand;
 * appending them to [args] would make tmux read them as subcommand arguments.
 */
fun tmuxCommand(tmuxPath: String, socket: String, args: List<String>): List<String> =
    listOf(tmuxPath) + TMUX_CONFIG_ISOLATION + listOf("-L", socket) + args
