package io.kotgent.webuicheck

import io.kotgent.cli.eprintln
import io.kotgent.pty.Subscriber
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * One `--self-check` check: a name for the report and a suspending body that throws to fail.
 *
 * @param run the assertions. Bounded by [SELF_CHECK_TIMEOUT_MS] when [runSelfCheck] drives it, so a pty
 *   that never answers fails the run instead of hanging the native suite behind it.
 */
class SelfCheckCase(val name: String, val run: suspend () -> Unit)

/**
 * Run every [SelfCheckCase], report on stderr, and print `SUMMARY total=N failed=M` on stdout.
 *
 * The split of streams is the same one the scenario mode uses and for the same reason: stdout is the
 * machine channel (`WebUiCheckTest` matches the SUMMARY line and nothing else), stderr is for humans —
 * which is where the per-check `PASS`/`FAIL` lines and a failure's message belong.
 *
 * @return the process exit code: [EXIT_OK] when nothing failed, [EXIT_SELF_CHECK_FAILED] otherwise.
 */
fun runSelfCheck(cases: List<SelfCheckCase>): Int {
    var failed = 0
    for (case in cases) {
        val error = runBlocking {
            try {
                withTimeout(SELF_CHECK_TIMEOUT_MS) { case.run() }
                null
            } catch (e: Throwable) {
                e
            }
        }
        if (error == null) {
            eprintln("PASS  ${case.name}")
        } else {
            failed++
            eprintln("FAIL  ${case.name} — ${error::class.simpleName}: ${error.message}")
        }
    }
    writeStdoutLine("SUMMARY total=${cases.size} failed=$failed")
    return if (failed == 0) EXIT_OK else EXIT_SELF_CHECK_FAILED
}

/**
 * The checks `--self-check` runs, and the rule that decides what may be one.
 *
 * **Only what a TEST binary physically cannot do belongs here**, i.e. only what needs the `sysnative`
 * cinterop: a real [io.kotgent.pty.Pty] (`openpty` + `posix_spawn`) under a real
 * [io.kotgent.pty.TerminalBridge], reached through the very factory this harness hands
 * [io.kotgent.transport.KotgentServer]. KT-78062 stubs those calls in any test binary, so `ptycheck`'s
 * precedent applies — and it applies ONLY here. Every other fact about the harness (the handshake, the
 * scenarios, the commands, the safe edges) is touched first by the browser tier, which asserts it with
 * a real assertion; dragging those under a hand-rolled counter would trade good failure messages for a
 * number.
 *
 * Two checks is the whole budget, and they are the two halves of the terminal's contract: bytes flow in
 * both directions over a real pty, and the lazy upstream really is torn down and really is respawned.
 */
fun selfCheckCases(): List<SelfCheckCase> = listOf(
    SelfCheckCase("a real pty streams and echoes through the harness's own TerminalBridge") {
        val harness = Harness(selfCheckScenario(), webUiDir = null)
        // Started for real: the check runs with the whole assembly — the five doubles, TaskService and a
        // bound listener — alive around the pty, which is the shape a scenario runs in.
        val context = harness.start()
        try {
            expect(context.port > 0) { "the harness server bound no port (reported ${context.port})" }
            val bridge = harness.terminalBridgeFactory(SELF_CHECK_SESSION_ID, harness.background)
            val subscriber = bridge.subscribe(cols = 80, rows = 24)
            try {
                val banner = receiveUntil(subscriber, SELF_CHECK_BANNER)
                expect(SELF_CHECK_BANNER in banner) {
                    "the upstream's own output never arrived, got: <$banner>"
                }
                subscriber.write("$SELF_CHECK_ECHO\n".encodeToByteArray())
                val echoed = receiveUntil(subscriber, SELF_CHECK_ECHO)
                expect(SELF_CHECK_ECHO in echoed) {
                    "input written to the bridge did not come back from the pty, got: <$echoed>"
                }
            } finally {
                subscriber.close()
            }
            expect(bridge.subscriberCount() == 0) {
                "the last subscriber left, so the bridge should hold none"
            }
        } finally {
            harness.stop()
        }
    },
    SelfCheckCase("the last subscriber closes the real upstream and the next one respawns it") {
        // No server here: this half is about the pty's lifecycle, and a second bind buys nothing.
        val harness = Harness(selfCheckScenario(), webUiDir = null)
        try {
            val bridge = harness.terminalBridgeFactory(SELF_CHECK_SESSION_ID, harness.background)
            val first = bridge.subscribe(cols = 80, rows = 24)
            val opened = receiveUntil(first, SELF_CHECK_BANNER)
            expect(SELF_CHECK_BANNER in opened) { "the first attach never saw the upstream, got: <$opened>" }
            first.close()

            // The banner is printed ONCE per child, so seeing it again proves the previous pty really was
            // torn down and a new one spawned — not that a surviving upstream replayed anything.
            val second = bridge.subscribe(cols = 80, rows = 24)
            val reopened = receiveUntil(second, SELF_CHECK_BANNER)
            expect(SELF_CHECK_BANNER in reopened) {
                "re-attaching did not respawn the upstream, got: <$reopened>"
            }
            second.close()
            bridge.shutdown()
        } finally {
            harness.stop()
        }
    },
)

/**
 * The scenario the self-check runs on: nothing seeded, and a deterministic upstream that announces
 * itself once and then echoes forever. `printf` + `cat` is chosen over anything richer because both
 * halves of the check read exact bytes.
 */
private fun selfCheckScenario(): Scenario = Scenario(
    name = "self-check",
    seed = { _ -> },
    // `exec cat` so the shell REPLACES itself: one process on the pty, which the bridge's close can
    // terminate and reap outright instead of leaving an orphaned child holding the slave.
    terminalUpstream = listOf("/bin/sh", "-c", "printf '$SELF_CHECK_BANNER\\n'; exec cat"),
)

/** Receive from [subscriber] until [needle] shows up in the accumulated output, or time out. */
private suspend fun receiveUntil(
    subscriber: Subscriber,
    needle: String,
    timeoutMs: Long = RECEIVE_TIMEOUT_MS,
): String {
    val received = StringBuilder()
    withTimeout(timeoutMs) {
        while (needle !in received) received.append(subscriber.output.receive().decodeToString())
    }
    return received.toString()
}

private inline fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw SelfCheckFailed(message())
}

private class SelfCheckFailed(message: String) : RuntimeException(message)

/** The logical session id the bridge factory is called with; it ignores the value by design. */
private const val SELF_CHECK_SESSION_ID = "selfcheck"

/** What the upstream prints exactly once, at spawn. */
private const val SELF_CHECK_BANNER = "kotgent-webuicheck-upstream"

/** What the check types into the pty to prove the input direction. */
private const val SELF_CHECK_ECHO = "kotgent-webuicheck-echo"

/** Per-check ceiling. Generous: a loaded CI runner spawning a pty is slow, a broken one never answers. */
private const val SELF_CHECK_TIMEOUT_MS = 30_000L

/** Per-read ceiling, well inside [SELF_CHECK_TIMEOUT_MS] so a stall names the read that stalled. */
private const val RECEIVE_TIMEOUT_MS = 10_000L
