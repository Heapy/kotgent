package io.kotgent.cli

import io.kotgent.transport.ServerBindException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DaemonStartupFailureTest {
    @Test
    fun anOrdinaryStartupFailureIsReportedAfterCleanupAndReturnsTheCliFailureResult() = runBlocking {
        withTimeout(10.seconds) {
            val effects = mutableListOf<String>()
            val runtime = daemonStartupOrNull(
                onError = { effects += it },
                onBindFailure = { error("storage failure must not diagnose a port") },
            ) {
                withStartupCompensation(compensate = { effects += "cleanup" }) {
                    error("notification database locked")
                }
            }
            assertNull(runtime)
            assertEquals(listOf("cleanup", "kotgent daemon: notification database locked"), effects)
        }
    }

    @Test
    fun bindFailureRetainsPortDiagnosisAndCancellationIsNotReportedAsStartupFailure() = runBlocking {
        withTimeout(10.seconds) {
            val errors = mutableListOf<String>()
            var diagnosed = false
            assertNull(daemonStartupOrNull(
                onError = { errors += it },
                onBindFailure = { diagnosed = true },
            ) { throw ServerBindException("occupied", null) })
            assertTrue(diagnosed)
            assertEquals(listOf("kotgent daemon: failed to bind the kotgent server: occupied"), errors)
            errors.clear()
            val _ = assertFailsWith<CancellationException> {
                daemonStartupOrNull(onError = { errors += it }) { throw CancellationException("stopping") }
            }
            assertTrue(errors.isEmpty())
        }
    }
}
