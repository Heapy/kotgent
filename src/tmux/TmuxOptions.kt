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
 * `-f /dev/null` suppresses the user config completely (measured: `focus-events` falls back to the
 * built-in `off` despite `focus-events on` in `~/.tmux.conf` — the decoy the integration isolation test
 * uses, precisely because kotgent never forces it), which alone restores the Detach invariant — tmux's own
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
 * | `mouse on` | `off` | The wheel scrolls the pane's history, which lives in tmux and nowhere else |
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
 * **`mouse on` is the one row that changes real behaviour for the operator**, and it is here on
 * purpose — tmux's built-in is `off`, so this is a genuine flip, not a pin. What it buys, measured
 * on tmux 3.7b: the wheel scrolls **the pane's own history** in both viewers — the web terminal and
 * `kotgent attach`. That history lives in the tmux pane (`history-limit 10000` above), not in
 * xterm.js and not in the CLI's scrollback: a subscriber joining an existing bridge is seeded from
 * `capture-pane` and sees only the current screen, so without `mouse on` the older transcript is
 * simply unreachable from the UI. A wheel event over a *normal-screen* pane enters `copy-mode` and
 * scrolls (measured `scroll_position` moves); an app that has itself requested SGR mouse reporting
 * still receives its own events untouched (measured: `^[[<64;10;10M` arrives at the app, tmux does
 * not intercept), so an alt-screen TUI keeps behaving exactly as it did.
 *
 * What it costs, also measured: **copy-mode is shared *pane* state, not per-client** — one
 * subscriber's wheel puts *the* pane into copy-mode for everyone, and while `pane_in_mode=1` tmux
 * routes every `send-keys` to the copy-mode key table instead of the process, silently (exit 0). That
 * would swallow `SessionManager.interrupt`'s `0x03` and let the projection record an interrupt that
 * never happened. **This option is therefore coupled to [Tmux.sendKeys], which issues a best-effort
 * `send-keys -X … cancel` before every send** — do not delete that cancel; it is what makes this row
 * safe, and it covers prefix-typed copy-mode too. (Copy-mode also auto-exits on its own once the
 * wheel scrolls back to the bottom, but that only handles the operator who scrolls back down — the
 * cancel is what handles the one who does not.) Two smaller consequences are accepted: `kotgent
 * attach` must undo the mouse-reporting DECSET on exit (`TERMINAL_MODE_RESET`, written in the same
 * `finally` as the tty restore) and the browser terminal needs
 * `macOptionClickForcesSelection` so text selection survives — Option-drag on macOS, Shift-drag
 * elsewhere.
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
