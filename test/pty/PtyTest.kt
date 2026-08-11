package io.kotgent.pty

import io.kotgent.tmux.ProcessRunner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import platform.posix.F_OK
import platform.posix.access

class PtyTest {

    @Test
    fun realPtyChecksPass() {
        val binary = ptycheckBinary()
        val result = ProcessRunner.run(listOf("/usr/bin/env", "$PTY_CLOSE_TRACE_ENV=1", binary))
        val report = "\n--- ptycheck stdout ---\n${result.stdout}--- ptycheck stderr ---\n${result.stderr}"

        assertEquals(0, result.exitCode, "ptycheck reported a failing PTY check$report")
        assertTrue(
            "SUMMARY total=$EXPECTED_CHECKS failed=0" in result.stdout,
            "ptycheck did not run all $EXPECTED_CHECKS checks$report",
        )
    }

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
        const val PTY_CLOSE_TRACE_ENV = "KOTGENT_PTY_CLOSE_TRACE"

        const val EXPECTED_CHECKS = 11
    }
}
