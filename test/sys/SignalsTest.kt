package io.kotgent.sys

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.raise

/**
 * Unit tests for the daemon's shutdown-signal plumbing ([installShutdownSignals]).
 *
 * These run the REAL mechanism — `signal(2)` and `raise(3)` are stock `platform.posix`, so they link into
 * the test binary (unlike our own cinterop, KT-78062) — which is the point: the bug being guarded is
 * precisely that a delivered SIGINT did *not* do what the process expected of it (Ktor's native
 * `EmbeddedServer.start()` had taken the handler over and only stopped the engine, leaving the process
 * alive forever). A test asserting that the signal both fails to kill this process AND is observable
 * afterwards is exactly the contract the daemon's park loop depends on.
 *
 * Every test restores the default disposition afterwards — a leaked handler would swallow a signal for
 * the rest of the binary, and a leaked *flag* would make the daemon's loop exit instantly.
 */
class SignalsTest {

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
        // If the default disposition were still in effect this line would terminate the test binary; the
        // assertion below therefore also proves the handler is installed.
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
        // Re-installing must work after a restore (the daemon path installs exactly once, but tests and
        // any future re-entry must not depend on that).
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
