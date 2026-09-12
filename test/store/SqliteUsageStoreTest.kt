package io.kotgent.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.USAGE_HEARTBEAT_MILLIS
import io.kotgent.core.USAGE_RETENTION_MILLIS
import io.kotgent.core.UsageObservation
import io.kotgent.core.UsageReset
import io.kotgent.core.UsageSource
import io.kotgent.core.UsageWindowState
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.closedir
import platform.posix.getenv
import platform.posix.mkdtemp
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class SqliteUsageStoreTest {
    private val epoch = 1_800_000_000_000L
    private val week = 604_800L
    private val resetAt = epoch + week * 1_000

    private inner class Fixture(val driver: SqlDriver = inMemoryDriver(preUsageSchema)) {
        var clock = epoch
        val store = SqliteUsageStore(driver) { clock }

        fun observation(
            percent: Double,
            source: String? = "a",
            revision: Long = 1,
            capturedAt: Long = clock,
            provider: String = "claude",
            key: String = "seven_day",
            resetsAt: Long? = resetAt,
            duration: Long? = week,
        ) = UsageObservation(
            provider = provider,
            windowKey = key,
            usedPercent = percent,
            resetsAt = resetsAt,
            windowSeconds = duration,
            source = source?.let { UsageSource(it, revision, capturedAt) },
        )

        suspend fun current() = store.list().single()
        fun count(table: String) = scalar(driver, "SELECT COUNT(*) FROM $table")
    }

    private fun test(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        withTimeout(20.seconds) {
            val fixture = Fixture()
            try {
                block(fixture)
            } finally {
                fixture.driver.close()
            }
        }
    }

    private suspend fun CoroutineScope.recordUpdatesThrough(
        fixture: Fixture,
        finalPercent: Double,
        action: suspend () -> Unit,
    ): List<UsageWindowState> {
        val events = Channel<UsageWindowState>(16)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.store.updates.collect { events.send(it) }
        }
        try {
            action()
            return buildList {
                do {
                    val item = events.receive()
                    add(item)
                } while (item.current.usedPercent != finalPercent)
            }
        } finally {
            collector.cancel()
            collector.join()
            events.close()
        }
    }

    @Test
    fun repeatingTheSameSourceRevisionInsideTheHeartbeatWritesNothingAndEmitsNothing() = test { f ->
        val initial = f.observation(40.0)
        f.store.observe(initial)
        val before = f.current()
        val writesBefore = scalar(f.driver, "SELECT total_changes()")

        val updates = recordUpdatesThrough(f, 41.0) {
            f.clock += USAGE_HEARTBEAT_MILLIS - 1
            f.store.observe(initial)
            assertEquals(before, f.current())
            assertEquals(writesBefore, scalar(f.driver, "SELECT total_changes()"))
            assertEquals(1L, f.count("usage_samples"))
            f.store.observe(f.observation(41.0, revision = 2))
        }

        assertEquals(listOf(41.0), updates.map { it.current.usedPercent })
    }

    @Test
    fun aMatchingCachedHeartbeatRefreshesOnlyReceiptTimeWithoutASampleOrReset() = test { f ->
        val initial = f.observation(40.0)
        f.store.observe(initial)
        val before = f.current()

        val update = async(start = CoroutineStart.UNDISPATCHED) { f.store.updates.first() }
        f.clock += USAGE_HEARTBEAT_MILLIS
        f.store.observe(initial)

        val after = update.await()
        assertEquals(before.current.copy(observedAt = f.clock), after.current)
        assertEquals(before.changedAt, after.changedAt)
        assertEquals(before.previous, after.previous)
        assertEquals(after, f.current())
        assertEquals(1L, f.count("usage_samples"))
        assertEquals(0L, f.count("usage_resets"))
        assertEquals(1L, scalar(f.driver, "SELECT source_revision FROM usage_sources"))
        assertEquals(epoch, scalar(f.driver, "SELECT source_captured_at FROM usage_sources"))
    }

    @Test
    fun aNewCodexRecordWithUnchangedQuotaPersistsItsWatermarkWithoutAQuotaWrite() = test { f ->
        f.store.observe(f.observation(40.0, provider = "codex", key = "primary"))
        val before = f.current()

        val updates = recordUpdatesThrough(f, 42.0) {
            f.clock += 1
            f.store.observe(f.observation(40.0, revision = 3, provider = "codex", key = "primary"))
            assertEquals(before, f.current())
            assertEquals(3L, scalar(f.driver, "SELECT source_revision FROM usage_sources"))
            f.clock += 1
            f.store.observe(f.observation(90.0, revision = 2, provider = "codex", key = "primary"))
            assertEquals(before, f.current(), "the invisible watermark still rejects an older record")
            assertEquals(1L, f.count("usage_samples"))
            f.store.observe(f.observation(42.0, revision = 4, provider = "codex", key = "primary"))
        }

        assertEquals(listOf(42.0), updates.map { it.current.usedPercent })
    }

    @Test
    fun valueChangesImmediatelyPreserveTheEntirePreviousObservation() = test { f ->
        f.store.observe(f.observation(40.0, duration = 18_000))
        val before = f.current()
        f.clock += 1
        val changed = f.observation(41.5, source = "b", resetsAt = resetAt + 1_000, duration = week)
        val update = async(start = CoroutineStart.UNDISPATCHED) { f.store.updates.first() }

        f.store.observe(changed)

        val after = update.await()
        assertEquals(changed.copy(observedAt = f.clock), after.current)
        assertEquals(before.current, after.previous)
        assertEquals(f.clock, after.changedAt)
        assertEquals(after, f.current())
        assertEquals(2L, f.count("usage_samples"))
        assertEquals(0L, f.count("usage_resets"))
    }

    @Test
    fun aDurationOnlyChangeIsHistoryAndDoesNotMasqueradeAsAHeartbeat() = test { f ->
        f.store.observe(f.observation(40.0, provider = "codex", key = "primary", duration = 18_000))
        val before = f.current()
        f.clock += 1
        val update = async(start = CoroutineStart.UNDISPATCHED) { f.store.updates.first() }

        f.store.observe(f.observation(40.0, revision = 2, provider = "codex", key = "primary", duration = week))

        val after = update.await()
        assertEquals(week, after.current.windowSeconds)
        assertEquals(before.current, after.previous)
        assertEquals(f.clock, after.changedAt)
        assertEquals(2L, f.count("usage_samples"))
        assertEquals(0L, f.count("usage_resets"))
    }

    @Test
    fun aSameSourceDecreaseJournalsAndReliablyEmitsTheCommittedReset() = test { f ->
        f.store.observe(f.observation(70.0))
        val before = f.current().current
        val reset = async(start = CoroutineStart.UNDISPATCHED) { f.store.resets.first() }
        f.clock += 1

        f.store.observe(f.observation(5.0, revision = 2))

        val emitted = reset.await()
        assertTrue(emitted.id > 0)
        assertEquals(70.0, emitted.usedBefore)
        assertEquals(before.observedAt, emitted.usedBeforeSeenAt)
        assertEquals(resetAt, emitted.expectedAt)
        assertEquals(f.clock, emitted.observedAt)
        assertEquals(week, emitted.windowSeconds)
        assertTrue(emitted.early)
        assertFalse(emitted.resetsAtMoved)
        assertEquals(listOf(emitted), f.store.pendingResetNotifications(0))
        assertEquals(emitted.id, scalar(f.driver, "SELECT id FROM usage_resets"))
        assertEquals(1L, scalar(f.driver, "SELECT reset_generation FROM usage_windows"))
        assertEquals(1L, scalar(f.driver, "SELECT baseline_reset_generation FROM usage_sources"))
    }

    @Test
    fun aSubscriberCanReadItsSnapshotWhileAResetIsBeingPublished() = test { f ->
        f.store.observe(f.observation(70.0))
        val subscribed = CompletableDeferred<Unit>()
        val readSnapshot = CompletableDeferred<Unit>()
        val seen = async(start = CoroutineStart.UNDISPATCHED) {
            f.store.resets.onSubscription {
                subscribed.complete(Unit)
                readSnapshot.await()
                assertEquals(5.0, f.current().current.usedPercent)
            }.first()
        }
        subscribed.await()
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            repeat(65) { index ->
                f.clock += 1
                f.store.observe(f.observation(69.0 - index, revision = index + 2L))
            }
        }
        readSnapshot.complete(Unit)

        val reset = seen.await()
        writer.join()
        val pending = f.store.pendingResetNotifications(0)
        assertEquals(65, pending.size, "committed work survives a subscriber leaving after the first signal")
        assertEquals(reset, pending.first())
    }

    @Test
    fun changedValuesStayOrderedAcrossEqualTicksAndABackwardClockStep() = test { f ->
        f.store.observe(f.observation(20.0, source = null, provider = "codex", key = "primary"))
        val first = f.current()
        f.store.observe(f.observation(21.0, source = null, provider = "codex", key = "primary"))
        val second = f.current()
        f.clock -= 10_000
        f.store.observe(f.observation(22.0, source = null, provider = "codex", key = "primary"))
        val third = f.current()

        assertEquals(epoch, first.current.observedAt)
        assertEquals(epoch + 1, second.current.observedAt)
        assertEquals(epoch + 2, third.current.observedAt)
        assertEquals(second.current, third.previous)
        assertEquals(third.current.observedAt, third.changedAt)
    }

    @Test
    fun cachedHeartbeatsCannotUndoAnotherSourcesHigherOrLowerProjection() = test { f ->
        val low = f.observation(40.0)
        f.store.observe(low)
        f.clock += 1
        val high = f.observation(80.0, source = "b")
        f.store.observe(high)
        val highProjection = f.current()

        val updates = recordUpdatesThrough(f, 81.0) {
            f.clock += USAGE_HEARTBEAT_MILLIS
            f.store.observe(low)
            assertEquals(highProjection, f.current(), "cached low quota must not revert the latest account value")
            f.store.observe(f.observation(81.0, source = "b", revision = 2))
        }
        assertEquals(listOf(81.0), updates.map { it.current.usedPercent })

        f.clock += 1
        f.store.observe(f.observation(10.0, source = "b", revision = 3))
        val resetProjection = f.current()
        f.clock += USAGE_HEARTBEAT_MILLIS
        f.store.observe(low)
        f.store.observe(high)
        assertEquals(resetProjection, f.current(), "cached pre-reset values cannot restore the old high quota")
        assertEquals(1L, f.count("usage_resets"))
    }

    @Test
    fun aLowerFirstReadingOrMissingClaudeProvenanceCannotProveAReset() = test { f ->
        f.store.observe(f.observation(80.0))
        f.clock += 1
        f.store.observe(f.observation(40.0, source = "new-source"))
        assertEquals(40.0, f.current().current.usedPercent)
        f.clock += 1
        f.store.observe(f.observation(20.0, source = null))

        assertEquals(0L, f.count("usage_resets"))
        assertTrue(f.store.pendingResetNotifications(0).isEmpty())
    }

    @Test
    fun aSameSourceDecreaseMustAlsoDecreaseTheAccountProjection() = test { f ->
        f.store.observe(f.observation(80.0))
        f.clock += 1
        f.store.observe(f.observation(20.0, source = "b"))
        f.clock += 1
        f.store.observe(f.observation(70.0, revision = 2))

        assertEquals(70.0, f.current().current.usedPercent)
        assertEquals(0L, f.count("usage_resets"), "source A decreased but the displayed account percentage rose")
    }

    @Test
    fun sourceOrderingRejectsOlderRevisionsChangedDuplicatesAndOlderCaptures() = test { f ->
        f.store.observe(f.observation(50.0, revision = 5))
        val before = f.current()
        val updates = recordUpdatesThrough(f, 51.0) {
            f.clock += 1
            f.store.observe(f.observation(90.0, revision = 4))
            f.store.observe(f.observation(10.0, revision = 5))
            f.store.observe(f.observation(5.0, revision = 6, capturedAt = epoch - 1))
            assertEquals(before, f.current())
            assertEquals(5L, scalar(f.driver, "SELECT source_revision FROM usage_sources"))
            assertEquals(1L, f.count("usage_samples"))
            assertEquals(0L, f.count("usage_resets"))
            f.store.observe(f.observation(51.0, revision = 6))
        }
        assertEquals(listOf(51.0), updates.map { it.current.usedPercent })
    }

    @Test
    fun anOlderCaptureFromAnotherSourceCannotReorderTheAccount() = test { f ->
        f.store.observe(f.observation(50.0))
        f.clock += 100
        f.store.observe(f.observation(60.0, source = "b"))
        val before = f.current()
        f.clock += 1

        f.store.observe(f.observation(10.0, source = "late", capturedAt = epoch + 50))

        assertEquals(before, f.current())
        assertEquals(2L, f.count("usage_samples"))
        assertEquals(0L, f.count("usage_resets"))
    }

    @Test
    fun anIgnoredCachedWindowCannotAdvanceTheAccountOrderingBarrier() = test { f ->
        f.store.observe(f.observation(40.0, source = "b", capturedAt = epoch + 90))
        f.clock += 1
        f.store.observe(f.observation(80.0, capturedAt = epoch + 100))
        f.clock += 1
        f.store.observe(f.observation(40.0, source = "b", revision = 2, capturedAt = epoch + 110))
        assertEquals(80.0, f.current().current.usedPercent)
        f.clock += 1

        f.store.observe(f.observation(81.0, revision = 2, capturedAt = epoch + 105))

        assertEquals(81.0, f.current().current.usedPercent, "B's unchanged cached value cannot discard A's valid update")
        f.clock += 1
        f.store.observe(f.observation(5.0, revision = 3, capturedAt = epoch + 106))
        assertEquals(81.0, f.store.pendingResetNotifications(0).single().usedBefore)
    }

    @Test
    fun aMatchingCachedWindowCannotDiscardAnotherSourcesDistinctSample() = test { f ->
        f.store.observe(f.observation(80.0, source = "b", capturedAt = epoch + 90))
        f.clock += 1
        f.store.observe(f.observation(80.0, capturedAt = epoch + 100))
        f.clock += 1
        f.store.observe(f.observation(80.0, source = "b", revision = 2, capturedAt = epoch + 110))
        f.clock += 1

        f.store.observe(f.observation(81.0, revision = 2, capturedAt = epoch + 105))

        assertEquals(81.0, f.current().current.usedPercent)
    }

    @Test
    fun anIgnoredCaptureCannotBecomeAnOrderingBarrierWhenTheValueLaterMatches() = test { f ->
        f.store.observe(f.observation(40.0, source = "b", capturedAt = epoch + 90))
        f.clock += 1
        f.store.observe(f.observation(80.0, capturedAt = epoch + 100))
        f.clock += 1
        f.store.observe(f.observation(40.0, source = "b", revision = 2, capturedAt = epoch + 200))
        f.clock += 1
        f.store.observe(f.observation(40.0, revision = 2, capturedAt = epoch + 110))
        f.clock += 1

        f.store.observe(f.observation(41.0, revision = 3, capturedAt = epoch + 120))

        assertEquals(41.0, f.current().current.usedPercent)
        assertEquals(1L, f.count("usage_resets"))
    }

    @Test
    fun aLaggingSourceCannotTurnItsCatchUpIntoASecondReset() = test { f ->
        f.store.observe(f.observation(70.0))
        f.clock += 1
        val cachedB = f.observation(80.0, source = "b")
        f.store.observe(cachedB)
        f.clock += 1
        f.store.observe(f.observation(10.0, revision = 2))
        f.clock += USAGE_HEARTBEAT_MILLIS
        f.store.observe(cachedB)
        assertEquals(0L, scalar(f.driver, "SELECT baseline_reset_generation FROM usage_sources WHERE source_id = 'b'"))
        f.clock += 1

        f.store.observe(f.observation(5.0, source = "b", revision = 2))

        assertEquals(5.0, f.current().current.usedPercent)
        assertEquals(1L, f.count("usage_resets"), "the second decrease is B learning A's already-journalled reset")
        assertEquals(1L, scalar(f.driver, "SELECT baseline_reset_generation FROM usage_sources WHERE source_id = 'b'"))
        f.clock += 1
        f.store.observe(f.observation(4.0, source = "b", revision = 3))
        assertEquals(2L, f.count("usage_resets"), "a later distinct B decrease has a baseline in the current generation")
    }

    @Test
    fun pendingProjectionSelectsOnlyEarlyWeeklyResetsAndAcknowledgesIdempotently() = test { f ->
        f.store.observe(f.observation(70.0))
        f.clock += 1
        f.store.observe(f.observation(10.0, revision = 2))
        val claudeReset = f.store.pendingResetNotifications(0).single()
        f.clock += 1
        f.store.observe(f.observation(50.0, key = "five_hour", duration = 18_000))
        f.clock += 1
        f.store.observe(f.observation(1.0, revision = 2, key = "five_hour", duration = 18_000))
        f.clock += 1
        f.store.observe(f.observation(50.0, provider = "codex", key = "primary"))
        f.clock += 1
        f.store.observe(f.observation(1.0, revision = 2, provider = "codex", key = "primary", resetsAt = resetAt + 10_000))
        f.clock += 1
        f.store.observe(f.observation(50.0, provider = "codex", key = "secondary", duration = 18_000))
        f.clock += 1
        f.store.observe(f.observation(1.0, revision = 2, provider = "codex", key = "secondary", duration = 18_000, resetsAt = resetAt + 10_000))
        f.clock = resetAt
        f.store.observe(f.observation(0.0, revision = 3))

        assertEquals(5L, f.count("usage_resets"))
        val eligible = f.store.pendingResetNotifications(0)
        assertEquals(setOf("claude:seven_day", "codex:primary"), eligible.map { "${it.provider}:${it.windowKey}" }.toSet())
        assertTrue(eligible.all { it.id > 0 })
        assertEquals(2, eligible.map { it.id }.toSet().size)
        assertEquals(1, f.store.pendingResetNotifications(claudeReset.observedAt + 1).size)
        f.store.markResetNotificationProjected(claudeReset.id)
        f.store.markResetNotificationProjected(claudeReset.id)
        f.store.markResetNotificationProjected(Long.MAX_VALUE)
        assertEquals(listOf("codex"), f.store.pendingResetNotifications(0).map { it.provider })
        assertEquals(5L, f.count("usage_resets"), "projection acknowledgement does not erase the reset journal")
    }

    @Test
    fun aFailedResetTransactionRollsBackEveryTableAndPublishesNothing() = test { f ->
        f.store.observe(f.observation(70.0))
        val before = f.current()
        f.driver.execute(null, "CREATE TRIGGER fail_usage_reset BEFORE INSERT ON usage_resets BEGIN SELECT RAISE(ABORT, 'injected reset failure'); END", 0)
        f.clock += 1
        val drop = f.observation(5.0, revision = 2)
        val reset = async(start = CoroutineStart.UNDISPATCHED) { f.store.resets.first() }

        val updates = recordUpdatesThrough(f, 6.0) {
            val _ = assertFailsWith<Exception> { f.store.observe(drop) }
            assertEquals(before, f.current())
            assertEquals(1L, f.count("usage_samples"))
            assertEquals(0L, f.count("usage_resets"))
            assertEquals(1L, scalar(f.driver, "SELECT source_revision FROM usage_sources"))
            assertEquals(0L, scalar(f.driver, "SELECT reset_generation FROM usage_windows"))
            assertEquals(0L, scalar(f.driver, "SELECT baseline_reset_generation FROM usage_sources"))
            f.driver.execute(null, "DROP TRIGGER fail_usage_reset", 0)
            f.store.observe(drop)
            f.clock += 1
            f.store.observe(f.observation(6.0, revision = 3))
        }

        assertEquals(listOf(5.0, 6.0), updates.map { it.current.usedPercent })
        assertEquals(before.current, updates.first().previous)
        assertEquals(3L, f.count("usage_samples"))
        assertEquals(1L, f.count("usage_resets"))
        assertEquals(listOf(reset.await()), f.store.pendingResetNotifications(0))
    }

    @Test
    fun retentionPrunesOldHistoryAndSourcesWhileKeepingTheCurrentProjection() = test { f ->
        f.store.observe(f.observation(70.0))
        f.clock += 1
        f.store.observe(f.observation(5.0, revision = 2))
        val oldProjection = f.current()
        f.clock = epoch + USAGE_RETENTION_MILLIS + 2
        f.store.observe(f.observation(20.0, provider = "codex", key = "primary", resetsAt = f.clock + week * 1_000))

        f.store.prune()
        f.store.prune()

        assertEquals(oldProjection, f.store.list().single { it.current.provider == "claude" })
        assertEquals(2L, f.count("usage_windows"))
        assertEquals(1L, f.count("usage_samples"))
        assertEquals(0L, f.count("usage_resets"))
        assertEquals(1L, f.count("usage_sources"))
        assertEquals(1L, scalar(f.driver, "SELECT COUNT(*) FROM usage_sources WHERE provider = 'codex'"))
        assertTrue(f.store.pendingResetNotifications(0).isEmpty())
    }

    @Test
    fun fileBackedReopenPreservesSourceOrderingResetGenerationsAndPendingIds() = runBlocking {
        withTimeout(20.seconds) {
            withTempDbDir { directory ->
                var expected: UsageWindowState? = null
                var pending: UsageReset? = null
                var clock = epoch
                fun open() = NativeSqliteDriver(
                    schema = preUsageSchema,
                    name = "usage-reopen.db",
                    onConfiguration = { it.copy(extendedConfig = it.extendedConfig.copy(basePath = directory)) },
                )

                val original = open()
                try {
                    original.execute(null, "INSERT INTO legacy_marker (value) VALUES ('preserved')", 0)
                    val f = Fixture(original)
                    f.store.observe(f.observation(70.0))
                    f.clock += 1
                    f.store.observe(f.observation(80.0, source = "b"))
                    f.clock += 1
                    f.store.observe(f.observation(10.0, revision = 2))
                    expected = f.current()
                    val committedReset = f.store.pendingResetNotifications(0).single()
                    pending = committedReset
                    assertTrue(committedReset.id > 0, "a file-backed reset id must come from the writing connection")
                    assertEquals(committedReset.id, scalar(original, "SELECT id FROM usage_resets"))
                    assertEquals(0L, scalar(original, "SELECT last_insert_rowid()"), "the reader-pool connection did not perform the insert")
                    clock = f.clock
                } finally {
                    original.close()
                }

                val reopenedDriver = open()
                try {
                    val store = SqliteUsageStore(reopenedDriver) { clock }
                    assertEquals(listOf(expected), store.list())
                    assertEquals(listOf(pending), store.pendingResetNotifications(0), "reset projection survives without any live signal subscriber")
                    assertEquals(1L, scalar(reopenedDriver, "SELECT COUNT(*) FROM legacy_marker WHERE value = 'preserved'"))
                    assertEquals(0L, scalar(reopenedDriver, "SELECT baseline_reset_generation FROM usage_sources WHERE source_id = 'b'"))
                    clock += 1
                    store.observe(UsageObservation("claude", "seven_day", 90.0, resetAt, week, source = UsageSource("a", 1, clock)))
                    assertEquals(listOf(expected), store.list(), "the persisted source watermark rejects the reordered revision after restart")
                    clock += 1
                    store.observe(UsageObservation("claude", "seven_day", 5.0, resetAt, week, source = UsageSource("b", 2, clock)))
                    assertEquals(1L, scalar(reopenedDriver, "SELECT COUNT(*) FROM usage_resets"), "a lagging baseline remains lagging across a real close/reopen")
                    assertEquals(5.0, store.list().single().current.usedPercent)
                    store.markResetNotificationProjected(assertNotNull(pending).id)
                } finally {
                    reopenedDriver.close()
                }

                val thirdDriver = open()
                try {
                    val store = SqliteUsageStore(thirdDriver) { clock }
                    assertTrue(store.pendingResetNotifications(0).isEmpty(), "acknowledgement also survives restart")
                    assertEquals(5.0, store.list().single().current.usedPercent)
                    assertEquals(1L, scalar(thirdDriver, "SELECT COUNT(*) FROM usage_resets"))
                } finally {
                    thirdDriver.close()
                }
            }
        }
    }

    private fun scalar(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(assertNotNull(cursor.getLong(0)))
        },
        parameters = 0,
    ).value

    private val preUsageSchema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE legacy_marker (value TEXT NOT NULL)", 0)
            return QueryResult.Unit
        }
        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }

    private inline fun withTempDbDir(block: (String) -> Unit) {
        val directory = memScoped {
            val temporaryRoot = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
            val encoded = "$temporaryRoot/kotgent-usagestore-test-XXXXXX".encodeToByteArray()
            val chars = allocArray<ByteVar>(encoded.size + 1)
            encoded.forEachIndexed { index, byte -> chars[index] = byte }
            chars[encoded.size] = 0
            mkdtemp(chars)?.toKString() ?: error("could not create the usage-store test directory")
        }
        try {
            block(directory)
        } finally {
            val handle = opendir(directory)
            if (handle != null) {
                val names = buildList {
                    while (true) {
                        val entry = readdir(handle) ?: break
                        val name = entry.pointed.d_name.toKString()
                        if (name != "." && name != "..") add(name)
                    }
                }
                closedir(handle)
                for (name in names) unlink("$directory/$name")
            }
            rmdir(directory)
        }
    }
}
