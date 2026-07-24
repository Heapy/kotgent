package io.kotgent.pty

import io.kotgent.sys.utf8LocaleOrDefault
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
/**
 * argv for the upstream attach client: `tmux -u -L <socket> attach -t <session>` — **pure**, so the
 * exact flags are unit-testable without a tmux server.
 *
 * `-u` tells tmux to emit UTF-8 to this client whatever its locale says. It duplicates what a UTF-8
 * `LANG` in [terminalAttachEnv] already buys, on purpose: the locale route depends on the requested
 * locale existing on the host, the flag does not. Losing both means tmux rewrites every non-ASCII
 * cell as `_` (`tty_check_codeset`) and an agent's box-drawing TUI arrives as underscores.
 */
fun attachUpstreamCommand(tmuxPath: String, socket: String, session: String): List<String> =
    listOf(tmuxPath, "-u", "-L", socket, "attach", "-t", session)

fun terminalAttachEnv(lang: String?, home: String?, path: String?): Map<String, String> {
    val env = LinkedHashMap<String, String>()
    env["TERM"] = ATTACH_TERM
    home?.ifBlank { null }?.let { env["HOME"] = it }
    env["PATH"] = path?.ifBlank { null } ?: ATTACH_FALLBACK_PATH
    env["LANG"] = utf8LocaleOrDefault(lang)
    return env
}
