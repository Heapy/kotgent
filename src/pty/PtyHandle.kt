package io.kotgent.pty

import io.kotgent.sys.utf8LocaleOrDefault
import io.kotgent.tmux.TMUX_CONFIG_ISOLATION
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Abstraction over a pseudo-terminal with a child process attached to its slave side — the
 * dependency the PTY fan-out ([Broadcaster] / [TerminalBridge], Task 9) is written against
 * instead of the concrete cinterop-backed [io.kotgent.pty.Pty] (Task 2, sysnative).
 *
 * ## Why an interface (KT-78062)
 * Our own custom cinterop (the `pty.def` in `sysnative` that backs [io.kotgent.pty.Pty]) does
 * **not** link into the TEST binary on Kotlin Toolchain 0.11.x (KT-78062): the reference links,
 * but calling a cinterop function from the test binary throws `IrLinkageError` at runtime (that is
 * why [io.kotgent.pty.Pty]'s own integration checks run from the `ptycheck` main binary). The
 * fan-out LOGIC — lazy
 * upstream lifecycle, multi-subscriber fan-out, input routing, "last active" resize, capture-pane
 * seeding — is pure Kotlin and must be unit-tested in the test binary. So everything downstream
 * depends on this pure-Kotlin [PtyHandle] plus a [PtyFactory], and:
 *  - **production** wires the factory to a real [io.kotgent.pty.Pty] via [RealPtyHandle], and
 *  - **unit tests** wire it to a pure-Kotlin `FakePtyHandle` (no cinterop) — these run for real in
 *    the test binary and cover the actual fan-out/lifecycle logic.
 *
 * The single genuine end-to-end path (a live `Pty.open("tmux … attach …")` driving real bytes
 * through the real cinterop to two subscribers) runs in `ptycheck/src/Main.kt` — a main binary,
 * where the cinterop does link — and the suite's `PtyTest` execs it.
 *
 * The interface deliberately lives in the app module (not `sysnative`) even though the concrete
 * [io.kotgent.pty.Pty] is in `sysnative`: it is pure Kotlin, the app depends on `sysnative`, and
 * keeping the contract + its fake together in the app's test binary is what makes the fan-out
 * testable without cinterop.
 */
interface PtyHandle {
    /**
     * Bytes read off the pty master fd, in arrival order. Closed when the child reaches EOF
     * (its slave side was closed). Consumers only receive — the producing side is owned by the
     * handle's implementation (a dedicated reader thread for the real [io.kotgent.pty.Pty]).
     */
    val output: ReceiveChannel<ByteArray>

    /** Write [bytes] to the master fd (terminal input). A no-op for empty input. */
    fun write(bytes: ByteArray)

    /** Set the terminal window size ([cols] x [rows]) — `ioctl(TIOCSWINSZ)` on the real handle. */
    fun resize(cols: Int, rows: Int)

    /**
     * Terminate the child (if still alive), close the master fd and stop the reader. Idempotent.
     * For the `tmux attach` upstream this ends *this* attach client only — the tmux session (and
     * the agent running in it) survives, which is exactly the Detach semantics the bridge relies on.
     */
    fun close()
}

/**
 * How the fan-out obtains an upstream [PtyHandle] for a given [command] (argv) and child [env].
 * Injected so the lifecycle logic is testable: production passes [realPtyFactory] (opens a real
 * cinterop [Pty]); unit tests pass a fake factory that mints pure-Kotlin fakes and records how it
 * was called.
 *
 * The [env] is load-bearing for the production `tmux attach` upstream: with an empty environment
 * `tmux attach` fails to build a terminal description ("missing or unsuitable terminal") and the
 * child exits immediately, so the upstream must carry at least `TERM` and a UTF-8 `LANG` (plus
 * `HOME`/`PATH`). See [terminalAttachEnv] / [terminalBridgeForSession].
 */
typealias PtyFactory = (command: List<String>, env: Map<String, String>) -> PtyHandle

/**
 * `TERM` for the `tmux attach` upstream. The attach is a shared transport for xterm.js and CLI
 * clients, not the daemon's own terminal, so this must be stable and portable: inheriting a value
 * such as `xterm-ghostty` would also require a custom `TERMINFO` path that a launchd daemon may not
 * have. The system-provided `xterm-256color` entry always resolves.
 */
const val ATTACH_TERM: String = "xterm-256color"

/** `PATH` floor for the attach upstream when the daemon itself has none (launchd's minimal env). */
const val ATTACH_FALLBACK_PATH: String = "/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"

/**
 * argv for the upstream attach client: `tmux -f /dev/null -u -L <socket> attach -t <session>` —
 * **pure**, so the exact flags are unit-testable without a tmux server (KT-78062: the cinterop that
 * would actually run this does not link into the test binary, so the argv rule is asserted here).
 *
 * `-u` tells tmux to emit UTF-8 to this client whatever its locale says. It duplicates what a UTF-8
 * `LANG` in [terminalAttachEnv] already buys, on purpose: the locale route depends on the requested
 * locale existing on the host, the flag does not. Losing both means tmux rewrites every non-ASCII
 * cell as `_` (`tty_check_codeset`) and an agent's box-drawing TUI arrives as underscores.
 *
 * [TMUX_CONFIG_ISOLATION] rides along so every kotgent tmux argv looks the same, control-plane and
 * attach alike. Be precise about what it buys **here**: `-f` only applies to the invocation that
 * STARTS a server, and by the time an attach happens the daemon has normally already created the
 * session (`Tmux.newSession`), so on that path the flag is **inert**. It matters in the case where
 * this attach is what brings the server up, and it keeps a single rule rather than a per-call-site
 * judgement about who started the server. Like `-L`, `-f` is a tmux **global** flag: it and its
 * value must precede the `attach` subcommand or tmux reads them as `attach` arguments.
 */
fun attachUpstreamCommand(tmuxPath: String, socket: String, session: String): List<String> =
    listOf(tmuxPath) + TMUX_CONFIG_ISOLATION + listOf("-u", "-L", socket, "attach", "-t", session)

/**
 * The environment handed to the `tmux attach` upstream child — **pure**, built from the daemon's own
 * [lang] / [home] / [path] (the `getenv` reads live in the no-arg overload in `RealPtyHandle.kt`).
 *
 * `tmux attach` needs at least [ATTACH_TERM] to build a terminal description — with an empty
 * environment it fails ("missing or unsuitable terminal"), the child exits immediately and the
 * browser/CLI terminal shows "[terminal disconnected]".
 *
 * `LANG` is **always set**, falling back to a UTF-8 locale via [utf8LocaleOrDefault] rather than
 * being passed through only when inherited: a tmux client that reads as non-UTF-8 rewrites every
 * non-ASCII cell as `_`, and under launchd there is no inherited `LANG` at all. Identity is never
 * derived from inherited env.
 */
fun terminalAttachEnv(lang: String?, home: String?, path: String?): Map<String, String> {
    val env = LinkedHashMap<String, String>()
    env["TERM"] = ATTACH_TERM
    home?.ifBlank { null }?.let { env["HOME"] = it }
    env["PATH"] = path?.ifBlank { null } ?: ATTACH_FALLBACK_PATH
    env["LANG"] = utf8LocaleOrDefault(lang)
    return env
}

/**
 * The mouse-reporting DECSET a per-subscriber seed carries: normal + button tracking, then the SGR
 * encoding — byte-for-byte the set a `tmux attach` client emits for `mouse on` (measured, tmux 3.7b).
 *
 * ## Why the seed has to carry it at all
 * A subscriber joining an **existing** bridge is seeded from `capture-pane -p -e`, and that output
 * contains **zero** private-mode sequences (measured). The mouse-enable the upstream `tmux attach`
 * emitted went out as live deltas when the upstream opened — to whoever was subscribed *then*. So a
 * second browser tab keeps mouse reporting off, its wheel never reaches tmux, and the pane history
 * (`history-limit 10000`, the only scrollback that viewer has) is unreachable. That is exactly the
 * benefit `TMUX_SERVER_OPTIONS`' `mouse on` is documented to buy, so the seed must deliver it rather
 * than leave the claim aspirational.
 *
 * tmux does re-emit the whole mode set on `tty_invalidate`, so a joiner whose resize *changes* the
 * upstream geometry gets one for free — but macOS raises `SIGWINCH` only on an actual size change, so
 * a second tab at the same geometry gets nothing. Relying on that is relying on a coincidence.
 *
 * Residual, accepted: when the pane's app has asked for **any-motion** tracking (`\u001b[?1003h`) tmux
 * also forwards `1003h`, which this fixed set does not reproduce — a joiner then reports buttons and
 * drags but not free motion until the next full repaint. The wheel, which is what the option is for,
 * works either way. Undoing all of it on exit is `TERMINAL_MODE_RESET`'s job.
 */
const val TERMINAL_MOUSE_ENABLE: String = "\u001b[?1000h\u001b[?1002h\u001b[?1006h"

/**
 * Compose one subscriber's terminal seed: the `capture-pane -p -e` snapshot [capturedPane], preceded
 * by [TERMINAL_MOUSE_ENABLE] when the session's tmux server forces `mouse on` ([mouseForced]).
 *
 * The enable goes **first** so the joining terminal is armed before the repaint lands. An EMPTY
 * capture stays empty: `capturePane` returns `""` for an unknown session or a torn-down server, and
 * `Broadcaster.attach` treats an empty seed as "nothing to send" — a client attaching to a session
 * that is not there should not be handed a stray mode change.
 *
 * ## Known residual — recorded, NOT fixed here
 * Arming a subscriber's terminal is necessary but not sufficient: **only the subscriber that resized
 * last has a fully live wheel.** The events this enable produces are resolved by tmux against the ONE
 * upstream client's window — it maps a mouse report's (x,y) onto that client's geometry and discards
 * anything out of range — and under [Broadcaster]'s "last active" resize policy that geometry belongs
 * to whichever subscriber resized most recently. So a *larger* tab reports coordinates the upstream
 * client's window does not contain, and its wheel is dead over the lower/right part of its viewport
 * while the smaller, last-resizing viewer scrolls fine. Not fixed here because it is not a seed bug: it
 * follows from one shared client serving N geometries, so a fix means changing the resize policy so no
 * single subscriber's size is authoritative (per-subscriber tmux clients would fix it too, and would
 * break the single-upstream invariant outright). See [io.kotgent.tmux.TMUX_SERVER_OPTIONS]' residuals.
 *
 * Pure, so the rule is unit-testable without tmux or cinterop (KT-78062).
 */
fun terminalSeed(capturedPane: String, mouseForced: Boolean): ByteArray = when {
    capturedPane.isEmpty() -> ByteArray(0)
    mouseForced -> (TERMINAL_MOUSE_ENABLE + capturedPane).encodeToByteArray()
    else -> capturedPane.encodeToByteArray()
}
