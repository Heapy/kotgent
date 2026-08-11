package io.kotgent.transport

import io.kotgent.tmux.ProcessRunner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import platform.posix.F_OK
import platform.posix.access

/**
 * Drives `webuicheck --self-check`: the cinterop-dependent half of the browser harness — a real
 * [io.kotgent.pty.Pty] under a real [io.kotgent.pty.TerminalBridge], reached through the same factory
 * the harness hands its [KotgentServer], with the whole assembly (the five doubles, `TaskService`, a
 * bound listener) alive around it.
 *
 * ## Why they run out-of-process instead of here
 * The same reason `PtyTest` execs `ptycheck`: Kotlin Toolchain 0.11.x registers the cinterop-klib task
 * for the non-test fragment only (`isTest=false`) while a test link asks for test-fragment cinterop
 * artifacts, so the klib never reaches a TEST binary and partial linkage turns every call into a stub
 * that throws `IrLinkageError` (KT-78062). A main binary links it fine.
 *
 * ## What this test deliberately does NOT cover
 * Everything else about the harness — the handshake, the scenarios, the stdin commands, the in-memory
 * edges — is exercised first, and with real assertions, by the browser tier in `webuitest`. Pulling
 * those under the fixture's own counter would replace named assertions with a number.
 *
 * ## Precondition
 * `./kotlin test` alone never links a main binary, so the `webuicheck` binary comes from
 * `./kotlin build` — the documented order. If it is missing, this test says so instead of skipping:
 * the suite has no skips.
 */
class WebUiCheckTest {

    @Test
    fun harnessSelfCheckPasses() {
        val binary = webuicheckBinary()
        val result = ProcessRunner.run(listOf(binary, "--self-check"))
        val report = "\n--- webuicheck stdout ---\n${result.stdout}--- webuicheck stderr ---\n${result.stderr}"

        assertEquals(0, result.exitCode, "webuicheck reported a failing self-check$report")
        // A binary that ran nothing would also exit 0, so pin the contract: every check ran, none failed.
        assertTrue(
            "SUMMARY total=$EXPECTED_CHECKS failed=0" in result.stdout,
            "webuicheck did not run all $EXPECTED_CHECKS self-checks$report",
        )
        // stdout is the fixture's machine channel — the browser driver parses `PORT=`/`TICKET=`/`READY`
        // off it and nothing else may appear there. The harness enforces that by pointing fd 1 at stderr
        // at startup and writing its protocol to a private duplicate; this asserts the enforcement holds,
        // including for Ktor's own logging (`KtorSimpleLogger` prints on Kotlin/Native).
        assertEquals(
            listOf("SUMMARY total=$EXPECTED_CHECKS failed=0"),
            result.stdout.lines().filter { it.isNotBlank() },
            "only the SUMMARY may reach stdout; per-check reporting belongs on stderr$report",
        )
    }

    /**
     * Locate the `webuicheck` binary. The test binary runs with the project root as its working
     * directory (Kotlin Toolchain behaviour), so the toolchain's task-output path is relative.
     *
     * **The same two candidate paths and the same `./kotlin build` sentence live in
     * `webuitest/test/HarnessFixture.kt` (`HARNESS_BINARIES` / `harnessBinary`).** They cannot share a
     * constant — this is a Kotlin/Native test binary and that is a JVM one, with no module either can
     * import from the other — so the duplication is structural. Change one and change the other; this
     * comment is the only thing linking them.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun webuicheckBinary(): String {
        val candidates = listOf(
            "build/tasks/_webuicheck_linkMacosArm64Debug/webuicheck.kexe",
            "build/tasks/_webuicheck_linkMacosArm64Release/webuicheck.kexe",
        )
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: fail(
                "the webuicheck binary is missing (looked for ${candidates.joinToString()}). " +
                    "It is built by `./kotlin build`, which `./kotlin test` does not do on its own — " +
                    "run `./kotlin build` first.",
            )
    }

    private companion object {
        /**
         * Number of checks `webuicheck --self-check` reports; keep in sync with `selfCheckCases()` in
         * `webuicheck/src/SelfCheck.kt`. Deliberately the ONE constant no other task touches — the
         * self-check's budget is fixed at the cinterop-dependent minimum.
         */
        const val EXPECTED_CHECKS = 2
    }
}
