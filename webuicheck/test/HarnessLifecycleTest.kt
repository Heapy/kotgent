package io.kotgent.webuicheck

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class HarnessLifecycleTest {
    @Test
    fun failedCleanupDoesNotReplaceTheStartupFailure() = runBlocking {
        val seedFailure = IllegalStateException("fixture setup failed")
        val cleanupFailure = IllegalArgumentException("fixture cleanup failed")
        var cleanups = 0
        val harness = Harness(Scenario(
            name = "failed-startup",
            seed = { throw seedFailure },
            cleanup = { cleanups++; throw cleanupFailure },
        ), webUiDir = null)

        val actual = assertFailsWith<IllegalStateException> { harness.start() }
        assertSame(seedFailure, actual)
        assertEquals(listOf(cleanupFailure), actual.suppressedExceptions)
        assertEquals(1, cleanups)
    }
}
