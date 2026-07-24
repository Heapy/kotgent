package io.kotgent.pty

import io.kotgent.tmux.ProcessRunner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import platform.posix.F_OK
import platform.posix.access

/**
 * Drives the real-PTY integration checks for [Pty] — the `cat` round-trip, `resize`, the child's
 * exit code, a failing spawn, a real `tmux attach` running on the spawned pts, a resize reaching that
 * attach *while it runs*, and [TerminalBridge]'s fan-out over the real attach.
 *
 * ## Why they run out-of-process instead of here
 * [Pty] calls the `sysnative` cinterop (`openpty`, `posix_spawn`), and Kotlin Toolchain 0.11.x never
 * links a custom cinterop klib into a TEST binary: the toolchain registers the cinterop-klib task
 * for the non-test fragment only (`isTest=false`), while the test link asks for cinterop artifacts
 * of the test fragment (`isTest=true`), so nothing matches and partial linkage replaces every call
 * with a stub that throws `IrLinkageError` (KT-78062). Verified to still be the case on toolchain
 * 0.11.0, 0.11.1 and 0.12.0-dev, and reproducible in a one-module project — so the assertions live
 * in the `ptycheck` module, whose MAIN binary *does* link the cinterop, and this test execs it.
 *
 * ## Precondition
 * `./kotlin test` alone never links a main binary (not even its own module's), so the `ptycheck`
 * binary comes from `./kotlin build` — the documented order. If it is missing, this test says so
 * instead of silently passing.
 */
class PtyTest {

    @Test
    fun realPtyChecksPass() {
        val binary = ptycheckBinary()
        // Close tracing is silent on success because ProcessRunner captures stderr. If a real-PTY
        // check fails, the assertion report below includes the exact teardown stage and timing.
        val result = ProcessRunner.run(listOf("/usr/bin/env", "$PTY_CLOSE_TRACE_ENV=1", binary))
        val report = "\n--- ptycheck stdout ---\n${result.stdout}--- ptycheck stderr ---\n${result.stderr}"

        assertEquals(0, result.exitCode, "ptycheck reported a failing PTY check$report")
        // The exit code alone would also be 0 for a binary that ran nothing, so pin the contract:
        // every check reported, none failed.
        assertTrue(
            "SUMMARY total=$EXPECTED_CHECKS failed=0" in result.stdout,
            "ptycheck did not run all $EXPECTED_CHECKS checks$report",
        )
    }

    /**
     * Locate the `ptycheck` binary. The test binary runs with the project root as its working
     * directory (Kotlin Toolchain behaviour), so the toolchain's task-output path is relative.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun ptycheckBinary(): String {
        val candidates = listOf(
            "build/tasks/_ptycheck_linkMacosArm64Debug/ptycheck.kexe",
            "build/tasks/_ptycheck_linkMacosArm64Release/ptycheck.kexe",
        )
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: fail(
                "the ptycheck binary is missing (looked for ${candidates.joinToString()}). " +
                    "It is built by `./kotlin build`, which `./kotlin test` does not do on its own — " +
                    "run `./kotlin build` first.",
            )
    }

    private companion object {
        /** Keep in sync with Pty.CLOSE_TRACE_ENV without linking the cinterop-backed class in this test. */
        const val PTY_CLOSE_TRACE_ENV = "KOTGENT_PTY_CLOSE_TRACE"

        /** Number of checks `ptycheck` reports; keep in sync with `ptycheck/src/Main.kt`. */
        const val EXPECTED_CHECKS = 11
    }
}
