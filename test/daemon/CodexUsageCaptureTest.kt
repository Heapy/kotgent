package io.kotgent.daemon

import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.UsageObservation
import io.kotgent.core.UsageSource
import io.kotgent.db.KotgentDatabase
import io.kotgent.store.FakeEventStore
import io.kotgent.store.SqliteUsageStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CodexUsageCaptureTest {
    private val sessionId = SessionId("codex-session")
    private val providerId = ProviderSessionId("11111111-2222-4333-8444-555555555555")
    private val nextProviderId = ProviderSessionId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")

    private fun meta(provider: ProviderSessionId? = providerId, agent: String = "codex") = SessionMeta(
        id = sessionId,
        name = "quota capture",
        agent = agent,
        providerSessionId = provider,
        cwd = "/repo",
        tmuxSession = "unused-test-session",
        state = SessionState.ready,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun observation(percent: Double, revision: Long, key: String = "primary") = UsageObservation(
        provider = "codex",
        windowKey = key,
        usedPercent = percent,
        resetsAt = 2_000_000,
        windowSeconds = if (key == "primary") 604_800 else 18_000,
        source = UsageSource(providerId.value, revision, revision),
    )

    private inner class Fixture(parent: CoroutineScope) {
        val job = Job(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        val events = FakeEventStore()
        private val driver = inMemoryDriver(KotgentDatabase.Schema)
        val usage = SqliteUsageStore(driver) { 1_000L }
        val errors = mutableListOf<Throwable>()

        fun capture(
            scan: suspend (ProviderSessionId) -> List<UsageObservation>,
            retry: suspend () -> Unit = {},
            timeoutMillis: Long = 5_000,
        ) = CodexUsageCapture(scope, events, usage, scan, retry, timeoutMillis, onError = { errors += it })

        suspend fun joinCapture() {
            for (child in job.children.toList()) child.join()
        }

        suspend fun close() {
            job.cancelAndJoin()
            driver.close()
        }
    }

    private fun test(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        withTimeout(20.seconds) {
            val fixture = Fixture(this)
            try {
                block(fixture)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun callbackReturnsWhileTheBackgroundScanIsStillBlocked() = test { f ->
        f.events.seedSession(meta())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val captured = observation(20.0, 100)
        var scans = 0
        val capture = f.capture(scan = {
            scans += 1
            entered.complete(Unit)
            release.await()
            listOf(captured)
        })

        capture.onTurnCompleted(sessionId)
        entered.await()
        assertFalse(release.isCompleted, "the caller returned without waiting for quota IO")
        assertTrue(f.usage.list().isEmpty())
        release.complete(Unit)
        f.joinCapture()

        assertEquals(2, scans)
        assertEquals(captured.source, f.usage.list().single().current.source)
        assertTrue(f.errors.isEmpty())
    }

    @Test
    fun aNonemptyFirstScanStillRetriesAndKeepsTheNewRecordProvenance() = test { f ->
        f.events.seedSession(meta())
        val first = observation(20.0, 100)
        val latest = observation(25.0, 200)
        var scans = 0
        var retries = 0
        val capture = f.capture(
            scan = { id ->
                assertEquals(providerId, id)
                scans += 1
                listOf(if (scans == 1) first else latest)
            },
            retry = {
                retries += 1
                assertEquals(first.source, f.usage.list().single().current.source)
            },
        )

        capture.onTurnCompleted(sessionId)
        f.joinCapture()

        assertEquals(2, scans)
        assertEquals(1, retries)
        val current = f.usage.list().single().current
        assertEquals(latest.usedPercent, current.usedPercent)
        assertEquals(latest.source, current.source, "scan provenance is never replaced by capture receipt time")
    }

    @Test
    fun anEmptyTailGetsOneRetryAndBothReturnedWindowsReachTheStore() = test { f ->
        f.events.seedSession(meta())
        val primary = observation(20.0, 100)
        val secondary = observation(30.0, 100, key = "secondary")
        var scans = 0
        val capture = f.capture(scan = {
            scans += 1
            if (scans == 1) emptyList() else listOf(primary, secondary)
        })

        capture.onTurnCompleted(sessionId)
        f.joinCapture()

        assertEquals(2, scans)
        assertEquals(listOf("primary", "secondary"), f.usage.list().map { it.current.windowKey })
        assertEquals(listOf(primary.source, secondary.source), f.usage.list().map { it.current.source })
    }

    @Test
    fun providerIdentityIsReadAgainAfterTheRetryWait() = test { f ->
        f.events.seedSession(meta())
        val scanned = mutableListOf<ProviderSessionId>()
        val capture = f.capture(
            scan = { id -> scanned.add(id); emptyList() },
            retry = { f.events.upsertSession(meta(provider = nextProviderId)) },
        )

        capture.onTurnCompleted(sessionId)
        f.joinCapture()

        assertEquals(listOf(providerId, nextProviderId), scanned)
    }

    @Test
    fun aMissingSessionOrProviderIdCanAppearBeforeTheOnlyRetry() = test { f ->
        var scans = 0
        var retries = 0
        val capture = f.capture(
            scan = { scans += 1; listOf(observation(20.0, 100)) },
            retry = { retries += 1; f.events.upsertSession(meta()) },
        )

        capture.onTurnCompleted(sessionId)
        f.joinCapture()
        assertEquals(1, scans, "the missing session did not prevent its post-wait lookup")
        assertEquals(1, retries)

        f.events.upsertSession(meta(provider = null))
        capture.onTurnCompleted(sessionId)
        f.joinCapture()
        assertEquals(2, scans, "an existing session without a provider id also retries the lookup")
        assertEquals(2, retries)
    }

    @Test
    fun missingMetadataStopsAfterOneRetryAndNoncodexMetadataNeverScans() = test { f ->
        var scans = 0
        var retries = 0
        val capture = f.capture(
            scan = { scans += 1; emptyList() },
            retry = { retries += 1 },
        )
        capture.onTurnCompleted(sessionId)
        f.joinCapture()
        assertEquals(0, scans)
        assertEquals(1, retries)

        f.events.seedSession(meta(agent = "claude"))
        capture.onTurnCompleted(sessionId)
        f.joinCapture()
        assertEquals(0, scans)
        assertEquals(1, retries, "an explicitly different provider needs no retry")
        assertTrue(f.errors.isEmpty())
    }

    @Test
    fun scanFailuresAreReportedAndRetriedWithoutCancellingTheDaemonScope() = test { f ->
        f.events.seedSession(meta())
        var scans = 0
        val failure = IllegalStateException("injected scan failure")
        val capture = f.capture(scan = {
            scans += 1
            if (scans <= 2) throw failure
            listOf(observation(20.0, 100))
        })

        capture.onTurnCompleted(sessionId)
        f.joinCapture()
        assertEquals(2, scans)
        assertEquals(listOf<Throwable>(failure, failure), f.errors)
        assertTrue(f.scope.isActive)

        capture.onTurnCompleted(sessionId)
        f.joinCapture()
        assertEquals(4, scans)
        assertEquals(20.0, f.usage.list().single().current.usedPercent)
    }

    @Test
    fun metadataFailureIsReportedAndDoesNotSkipTheRetry() = test { f ->
        f.events.seedSession(meta())
        var lookups = 0
        f.events.afterGetSession = {
            lookups += 1
            if (lookups == 1) throw IllegalStateException("injected lookup failure")
        }
        var scans = 0
        val capture = f.capture(scan = { scans += 1; listOf(observation(20.0, 100)) })

        capture.onTurnCompleted(sessionId)
        f.joinCapture()

        assertEquals(2, lookups)
        assertEquals(1, scans)
        assertEquals(1, f.errors.size)
        assertEquals(20.0, f.usage.list().single().current.usedPercent)
        assertTrue(f.scope.isActive)
    }

    @Test
    fun timeoutCancelsBlockedWorkAndReportsWithoutCancellingTheDaemonScope() = test { f ->
        f.events.seedSession(meta())
        val cleaned = CompletableDeferred<Unit>()
        var scans = 0
        val capture = f.capture(
            scan = {
                scans += 1
                try {
                    awaitCancellation()
                } finally {
                    cleaned.complete(Unit)
                }
            },
            timeoutMillis = 50,
        )

        capture.onTurnCompleted(sessionId)
        f.joinCapture()

        assertTrue(cleaned.isCompleted)
        assertEquals(1, scans, "timeout ends the whole bounded capture")
        assertIs<TimeoutCancellationException>(f.errors.single())
        assertTrue(f.usage.list().isEmpty())
        assertTrue(f.scope.isActive)
    }

    @Test
    fun cancellingTheDaemonScopeCancelsTheRetryAndDoesNotReportAnError() = test { f ->
        f.events.seedSession(meta())
        val waiting = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        var scans = 0
        val capture = f.capture(
            scan = { scans += 1; emptyList() },
            retry = {
                waiting.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cleaned.complete(Unit)
                }
            },
        )

        capture.onTurnCompleted(sessionId)
        waiting.await()
        f.job.cancelAndJoin()

        assertTrue(cleaned.isCompleted)
        assertEquals(1, scans)
        assertTrue(f.errors.isEmpty())
        assertFalse(f.scope.isActive)
    }
}
