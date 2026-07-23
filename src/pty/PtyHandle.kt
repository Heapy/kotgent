package io.kotgent.pty

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Abstraction over a pseudo-terminal with a child process attached to its slave side — the
 * dependency the PTY fan-out ([Broadcaster] / [TerminalBridge], Task 9) is written against
 * instead of the concrete cinterop-backed [io.kotgent.pty.Pty] (Task 2, sysnative).
 *
 * ## Why an interface (KT-78062)
 * Our own custom cinterop (the `pty.def` in `sysnative` that backs [io.kotgent.pty.Pty]) does
 * **not** link into the TEST binary on Kotlin Toolchain 0.11.0 (KT-78062): the reference links,
 * but calling a cinterop function from the test binary throws `IrLinkageError` at runtime (that is
 * why [io.kotgent.pty.Pty]'s own integration tests are `@Ignore`d). The fan-out LOGIC — lazy
 * upstream lifecycle, multi-subscriber fan-out, input routing, "last active" resize, capture-pane
 * seeding — is pure Kotlin and must be unit-tested in the test binary. So everything downstream
 * depends on this pure-Kotlin [PtyHandle] plus a [PtyFactory], and:
 *  - **production** wires the factory to a real [io.kotgent.pty.Pty] via [RealPtyHandle], and
 *  - **unit tests** wire it to a pure-Kotlin `FakePtyHandle` (no cinterop) — these run for real in
 *    the test binary and cover the actual fan-out/lifecycle logic.
 *
 * The single genuine end-to-end path (a live `Pty.open("tmux … attach …")` driving real bytes
 * through the real cinterop) is covered by the `@Ignore`d integration test in TerminalBridgeTest
 * and, executably, by the Task 18 acceptance test.
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
 * child exits immediately, so the upstream must carry at least `TERM` (plus `HOME`/`PATH`). See
 * [terminalAttachEnv] / [terminalBridgeForSession].
 */
typealias PtyFactory = (command: List<String>, env: Map<String, String>) -> PtyHandle
