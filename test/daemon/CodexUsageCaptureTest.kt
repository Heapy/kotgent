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
import io.kotgent.host.DecodedFileRead
import kotlinx.cinterop.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.posix.*
import kotlin.test.assertFailsWith
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
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

    private inner class Fixture(parent: CoroutineScope, now: () -> Long = { 1_000L }) {
        val job = Job(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        val events = FakeEventStore()
        private val driver = inMemoryDriver(KotgentDatabase.Schema)
        val usage = SqliteUsageStore(driver, now)
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

    private fun test(block: suspend TestScope.(Fixture) -> Unit) = runTest(timeout = 20.seconds) {
        val fixture = Fixture(this)
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellationAndCaptureDeadlineStopActualDecodingWithoutRefreshingUsage() = runBlocking {
        withTimeout(20.seconds) {
            val root = memScoped { requireNotNull(mkdtemp("/tmp/kotgent-usage.XXXXXX".cstr.ptr)?.toKString()) }
            val directories = listOf("$root/sessions", "$root/sessions/2026", "$root/sessions/2026/10", "$root/sessions/2026/10/22")
            for (directory in directories) assertEquals(0, mkdir(directory, 448u))
            val plain = "${directories.last()}/rollout-2026-10-22T10-00-00-${providerId.value}.jsonl"
            val path = "$plain.zst"
            val f = Fixture(this, now = { 1_789_150_000_000L })
            try {
                f.events.seedSession(meta())
                f.usage.observe(observation(10.0, 1))
                val before = f.usage.list()
                // One GiB of decoded padding in a 32 KiB synthetic RLE frame; no large input allocation.
                writeUsageBytes(path, usageRepeatedFrame(1024 * 1024 * 1024) + usageRawFrame("\n" + usageQuota))
                var directoryReads = 0
                val scan = CodexRolloutScan(root, visitEntries = { directory, visit ->
                    directoryReads++
                    visitDirectoryEntries(directory, visit)
                })
                val descriptors = usageDescriptorCount()
                val reading = async(start = CoroutineStart.UNDISPATCHED) { scan.rateLimitsOf(providerId) }
                repeat(16) { yield() }
                assertFalse(reading.isCompleted, "multi-chunk decoding yields before the whole transcript is read")
                reading.cancelAndJoin()
                assertFailsWith<CancellationException> { reading.await() }
                assertEquals(descriptors, usageDescriptorCount(), "cancellation closes the actual native reader")

                // Warm the canonical location so the deadline exercises decoding, not inventory lookup.
                writeUsageBytes(path, usageRawFrame(usageQuota))
                assertEquals(20.0, scan.rateLimitsOf(providerId).single().usedPercent)
                writeUsageBytes(path, usageRepeatedFrame(1024 * 1024 * 1024) + usageRawFrame("\n" + usageQuota))
                val warmReads = directoryReads
                val capture = f.capture(scan::rateLimitsOf, timeoutMillis = 10)
                capture.onTurnCompleted(sessionId)
                f.joinCapture()
                assertIs<TimeoutCancellationException>(f.errors.single())
                assertEquals(warmReads, directoryReads, "timeout occurs on the cached content read")
                assertEquals(before, f.usage.list(), "a deadline cannot publish partial quota or advance freshness")
                assertEquals(descriptors, usageDescriptorCount())
                assertTrue(f.scope.isActive)

                // A quota-bearing first frame followed by a truncated frame is not publishable either.
                writeUsageBytes(path, usageRawFrame(usageQuota) + usageRawFrame("suffix").dropLast(1).toByteArray())
                val recovery = f.capture(scan::rateLimitsOf)
                recovery.onTurnCompleted(sessionId)
                f.joinCapture()
                assertEquals(before, f.usage.list())
                assertEquals(descriptors, usageDescriptorCount())

                val paddingBytes = 16 * 1024 * 1024
                writeUsageBytes(path, usageRepeatedFrame(paddingBytes) + usageRawFrame("\n" + usageQuota))
                val started = TimeSource.Monotonic.markNow()
                val window = assertIs<DecodedFileRead.Window>(
                    CodexRolloutReader().tail(requireNotNull(RolloutLocation.fromPath(plain)), CodexRolloutScan.TOKEN_COUNT_TAIL_BYTES),
                )
                println("compressed usage fixture: decoded=${paddingBytes + 1 + usageQuota.encodeToByteArray().size}, retained=${window.bytes.size}, elapsed=${started.elapsedNow()}")
                assertEquals(CodexRolloutScan.TOKEN_COUNT_TAIL_BYTES, window.bytes.size)
                assertEquals((paddingBytes + 1 + usageQuota.encodeToByteArray().size - window.bytes.size).toLong(), window.logicalOffset)
                recovery.onTurnCompleted(sessionId)
                f.joinCapture()
                assertEquals(20.0, f.usage.list().single().current.usedPercent)
                assertEquals(descriptors, usageDescriptorCount(), "another capture succeeds after cancellation and corruption")
            } finally {
                f.close()
                unlink(path)
                for (directory in directories.asReversed()) rmdir(directory)
                rmdir(root)
            }
        }
    }

    private val usageQuota = """{"timestamp":"2026-09-11T18:05:58.392Z","type":"event_msg","payload":{"type":"token_count","rate_limits":{"primary":{"used_percent":20,"window_minutes":10080}}}}""" + "\n"

    private fun usageRawFrame(text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        val block = (bytes.size shl 3) or 1
        return "28b52ffda0".hexToByteArray() + ByteArray(4) { (bytes.size ushr (8 * it)).toByte() } +
            ByteArray(3) { (block ushr (8 * it)).toByte() } + bytes
    }

    private fun usageRepeatedFrame(size: Int): ByteArray {
        val blocks = size / (128 * 1024)
        // Non-single-segment frame, 128 KiB window, four-byte content size, then RLE blocks.
        // Its decoded size is deliberately much larger than its decoder window.
        return "28b52ffd8038".hexToByteArray() + ByteArray(4) { (size ushr (8 * it)).toByte() } +
            ByteArray(blocks * 4) { index ->
                val header = (128 * 1024 shl 3) or 2 or (if (index / 4 == blocks - 1) 1 else 0)
                if (index % 4 == 3) 'x'.code.toByte() else (header ushr (8 * (index % 4))).toByte()
            }
    }

    private fun writeUsageBytes(path: String, bytes: ByteArray) {
        val file = requireNotNull(fopen(path, "wb"))
        try {
            bytes.usePinned { assertEquals(bytes.size.toULong(), fwrite(it.addressOf(0), 1u, bytes.size.convert(), file)) }
        } finally { fclose(file) }
    }

    private fun usageDescriptorCount(): Int {
        val directory = requireNotNull(opendir("/dev/fd"))
        var count = 0
        try { while (readdir(directory) != null) count++ } finally { closedir(directory) }
        return count
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
    @OptIn(ExperimentalCoroutinesApi::class)
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
        // Start the scan before advancing its deadline; runner scheduling must not consume the budget.
        runCurrent()
        assertEquals(1, scans)
        advanceTimeBy(49)
        runCurrent()
        assertFalse(cleaned.isCompleted, "the scan remains blocked before its deadline")
        assertTrue(f.errors.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertTrue(cleaned.isCompleted, "the deadline cancels the scan and runs its cleanup")
        f.joinCapture()

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
