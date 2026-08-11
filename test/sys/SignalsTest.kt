package io.kotgent.sys

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.raise

class SignalsTest {

    // Never leak a process-wide handler or pending flag into later tests.
    @AfterTest
    fun restore() = restoreDefaultShutdownSignals()

    @Test
    fun noSignalMeansNoShutdownRequest() {
        installShutdownSignals()
        assertEquals(0, pendingShutdownSignal(), "nothing has been raised, so the daemon must stay parked")
    }

    @Test
    fun sigintIsCaughtInsteadOfKillingTheProcess() {
        installShutdownSignals()
        raise(SIGINT)
        assertEquals(SIGINT, pendingShutdownSignal(), "SIGINT must surface as a shutdown request")
    }

    @Test
    fun sigtermIsCaughtToo() {
        installShutdownSignals()
        raise(SIGTERM)
        assertEquals(SIGTERM, pendingShutdownSignal(), "launchd stops the daemon with SIGTERM, not SIGINT")
    }

    @Test
    fun installingClearsAStaleFlag() {
        installShutdownSignals()
        raise(SIGINT)
        assertNotEquals(0, pendingShutdownSignal())
        installShutdownSignals()
        assertEquals(0, pendingShutdownSignal(), "a fresh install must not start out already shutting down")
    }

    @Test
    fun restoringClearsBothTheFlagAndTheHandler() {
        installShutdownSignals()
        raise(SIGINT)
        restoreDefaultShutdownSignals()
        assertEquals(0, pendingShutdownSignal(), "the flag is cleared with the handlers")
        installShutdownSignals()
        raise(SIGTERM)
        assertEquals(SIGTERM, pendingShutdownSignal())
    }

    @Test
    fun theNamesAreTheOnesAnOperatorTyped() {
        assertEquals("SIGINT", shutdownSignalName(SIGINT), "what Ctrl+C sends")
        assertEquals("SIGTERM", shutdownSignalName(SIGTERM), "what launchctl bootout / kill sends")
        assertTrue(shutdownSignalName(9).contains("9"), "an unexpected signal still names itself")
    }
}
