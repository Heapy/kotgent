package io.kotgent.transport

import io.kotgent.tmux.ProcessRunner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import platform.posix.F_OK
import platform.posix.access

class WebUiCheckTest {

    @Test
    fun harnessSelfCheckPasses() {
        val binary = webuicheckBinary()
        val result = ProcessRunner.run(listOf(binary, "--self-check"))
        val report = "\n--- webuicheck stdout ---\n${result.stdout}--- webuicheck stderr ---\n${result.stderr}"

        assertEquals(0, result.exitCode, "webuicheck reported a failing self-check$report")
        assertTrue(
            "SUMMARY total=$EXPECTED_CHECKS failed=0" in result.stdout,
            "webuicheck did not run all $EXPECTED_CHECKS self-checks$report",
        )
        assertEquals(
            listOf("SUMMARY total=$EXPECTED_CHECKS failed=0"),
            result.stdout.lines().filter { it.isNotBlank() },
            "only the SUMMARY may reach stdout; per-check reporting belongs on stderr$report",
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun webuicheckBinary(): String {
        // Keep in sync with HarnessFixture; native and JVM modules cannot share this constant.
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
        const val EXPECTED_CHECKS = 2
    }
}
