package io.kotgent.cli

import io.kotgent.daemon.VendorStoreProbe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DaemonModulesTest {
    private fun sessions(): SessionModule = DaemonModules(
        port = 0,
        publicUrl = null,
        vendorProbe = VendorStoreProbe { _, _, _ -> false },
    ).sessions

    @Test
    fun closingUnusedSessionsDoesNotCreateABackgroundScope() = runBlocking {
        withTimeout(10.seconds) {
            val sessions = sessions()
            assertFalse(sessions.background.isInitialized)

            sessions.close()

            assertFalse(sessions.background.isInitialized)
        }
    }

    @Test
    fun closingSessionsWaitsForCancelledBackgroundChildrenToFinishCleanup() = runBlocking {
        withTimeout(10.seconds) {
            val sessions = sessions()
            val scope = sessions.background.value
            val backgroundJob = requireNotNull(scope.coroutineContext[Job])
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val child = scope.launch {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        releaseCleanup.await()
                        cleaned.complete(Unit)
                    }
                }
            }
            try {
                entered.await()
                val closing = async(start = CoroutineStart.UNDISPATCHED) { sessions.close() }
                try {
                    cleaning.await()
                    assertFalse(closing.isCompleted, "storage must remain open while a background child is finishing")
                    assertFalse(child.isCompleted)

                    releaseCleanup.complete(Unit)
                    closing.await()

                    assertTrue(cleaned.isCompleted)
                    assertTrue(child.isCompleted)
                    assertTrue(backgroundJob.isCompleted)
                } finally {
                    releaseCleanup.complete(Unit)
                    withContext(NonCancellable) { closing.cancelAndJoin() }
                }
            } finally {
                releaseCleanup.complete(Unit)
                withContext(NonCancellable) { backgroundJob.cancelAndJoin() }
            }
        }
    }
}
