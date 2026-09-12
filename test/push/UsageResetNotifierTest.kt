package io.kotgent.push

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.NOTIFICATION_WINDOW_MILLIS
import io.kotgent.core.UsageObservation
import io.kotgent.core.UsageReset
import io.kotgent.core.UsageSource
import io.kotgent.db.KotgentDatabase
import io.kotgent.store.NotificationStore
import io.kotgent.store.SqliteNotificationStore
import io.kotgent.store.SqliteUsageStore
import io.kotgent.store.UsageStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class UsageResetNotifierTest {
    private val epoch = 1_800_000_000_000L
    private val week = 604_800L

    private inner class Fixture(val scope: CoroutineScope) {
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        var clock = epoch
        private var revision = 0L
        val usage = SqliteUsageStore(driver) { clock }
        val inbox = SqliteNotificationStore(driver) { clock }
        var beforePending: suspend () -> Unit = {}
        var beforeInsert: suspend (UsageReset) -> Unit = {}
        var beforeAck: suspend (Long) -> Unit = {}
        var beforeWake: suspend (String) -> Unit = {}
        var retry: suspend (Long) -> Unit = {}
        var pendingReads = 0
        val insertResults = mutableListOf<Boolean>()
        val retryDelays = mutableListOf<Long>()
        val acknowledged = Channel<Long>(Channel.UNLIMITED)
        val sent = Channel<String>(Channel.UNLIMITED)
        val errors = Channel<String>(Channel.UNLIMITED)
        private val send: suspend (String) -> Unit = { topic ->
            sent.send(topic)
            beforeWake(topic)
        }

        private val usagePort = object : UsageStore by usage {
            override suspend fun pendingResetNotifications(since: Long): List<UsageReset> {
                pendingReads += 1
                beforePending()
                return usage.pendingResetNotifications(since)
            }

            override suspend fun markResetNotificationProjected(id: Long) {
                beforeAck(id)
                usage.markResetNotificationProjected(id)
                acknowledged.send(id)
            }
        }

        private val inboxPort = object : NotificationStore by inbox {
            override suspend fun insert(reset: UsageReset): Boolean {
                beforeInsert(reset)
                return inbox.insert(reset).also { insertResults += it }
            }
        }

        suspend fun start(pushEnabled: Boolean = true): UsageResetNotifier.Running = UsageResetNotifier(
            usageStore = usagePort,
            inbox = inboxPort,
            wake = if (pushEnabled) send else null,
            now = { clock },
            awaitRetry = { millis -> retryDelays += millis; retry(millis) },
            onError = { val _ = errors.trySend(it) },
        ).start(scope)

        suspend fun recordReset(
            provider: String = "claude",
            key: String = "seven_day",
            duration: Long = week,
            onTime: Boolean = false,
        ): Long {
            val previousEnd = if (onTime) clock - 1 else clock + week * 1_000
            observe(80.0, provider, key, duration, previousEnd)
            observe(3.0, provider, key, duration, if (provider == "codex") previousEnd + week * 1_000 else previousEnd)
            return count("SELECT COALESCE(MAX(id), 0) FROM usage_resets")
        }

        private suspend fun observe(percent: Double, provider: String, key: String, duration: Long, end: Long) {
            clock += 1
            revision += 1
            usage.observe(UsageObservation(
                provider, key, percent, end, duration,
                source = UsageSource("session:$provider:$key", revision, clock),
            ))
        }

        suspend fun awaitAcknowledged(id: Long) {
            while (acknowledged.receive() != id) Unit
        }

        fun count(sql: String): Long = driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(assertNotNull(cursor.getLong(0)))
            },
            parameters = 0,
        ).value
    }

    private fun test(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        withTimeout(20.seconds) {
            val owner = SupervisorJob(coroutineContext[Job])
            val fixture = Fixture(CoroutineScope(coroutineContext + owner))
            try {
                block(fixture)
            } finally {
                owner.cancelAndJoin()
                fixture.driver.close()
            }
        }
    }

    @Test
    fun startupProjectsPendingReplayBeforeReadinessAndHoldsWakeUntilActivation() = test { fixture ->
        val id = fixture.recordReset()
        assertTrue(fixture.inbox.insert(fixture.usage.pendingResetNotifications(0).single()))
        val inserting = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeInsert = { inserting.complete(Unit); gate.await() }
        val starting = async(start = CoroutineStart.UNDISPATCHED) { fixture.start() }
        inserting.await()
        assertFalse(starting.isCompleted)
        assertEquals(listOf(id), fixture.usage.pendingResetNotifications(0).map { it.id })
        gate.complete(Unit)
        val running = starting.await()
        assertTrue(fixture.usage.pendingResetNotifications(0).isEmpty())
        assertEquals(listOf(false), fixture.insertResults, "an existing inbox row still needs acknowledgement and a wake")
        assertEquals(1, fixture.inbox.recent(0).size)
        assertTrue(fixture.sent.tryReceive().isFailure)
        running.activateDelivery()
        running.activateDelivery()
        assertEquals("usage.reset:$id", fixture.sent.receive())
        running.close()
        assertTrue(fixture.sent.tryReceive().isFailure)
    }

    @Test
    fun pushDisabledStillProjectsOnlyEarlyWeeklyResetsIncludingCodexPrimary() = test { fixture ->
        fixture.recordReset(onTime = true)
        fixture.recordReset(key = "five_hour", duration = 18_000)
        fixture.recordReset(provider = "codex", key = "secondary", duration = 18_000)
        val claudeId = fixture.recordReset()
        val codexId = fixture.recordReset(provider = "codex", key = "primary")
        val running = fixture.start(pushEnabled = false)
        running.activateDelivery()
        assertEquals(5L, fixture.count("SELECT COUNT(*) FROM usage_resets"))
        assertEquals(setOf("usage.reset:$claudeId", "usage.reset:$codexId"), fixture.inbox.recent(0).map { it.id }.toSet())
        assertTrue(fixture.usage.pendingResetNotifications(0).isEmpty())
        assertTrue(fixture.sent.tryReceive().isFailure)
        running.close()
    }

    @Test
    fun startupRetriesPendingReadsWithBoundedDelaysAndFreshQueries() = test { fixture ->
        val id = fixture.recordReset()
        fixture.beforePending = {
            if (fixture.pendingReads < 3) error("database busy")
        }
        val running = fixture.start()
        assertEquals(3, fixture.pendingReads)
        assertEquals(listOf(250L, 1_000L), fixture.retryDelays)
        assertEquals("usage.reset:$id", fixture.inbox.recent(0).single().id)
        assertTrue(fixture.usage.pendingResetNotifications(0).isEmpty())
        assertTrue(fixture.sent.tryReceive().isFailure)
        running.close()
    }

    @Test
    fun startupExhaustionFailsReadinessWithoutAnUncaughtChildOrLostPendingRow() = test { fixture ->
        val id = fixture.recordReset()
        fixture.beforePending = { error("database unavailable") }
        val failure = assertFailsWith<IllegalStateException> { fixture.start() }
        assertEquals("database unavailable", failure.message)
        assertEquals(3, fixture.pendingReads)
        assertEquals(listOf(250L, 1_000L), fixture.retryDelays)
        assertTrue(fixture.scope.coroutineContext[Job]!!.children.none())
        assertEquals(listOf(id), fixture.usage.pendingResetNotifications(0).map { it.id })
        assertTrue(fixture.inbox.recent(0).isEmpty())
        assertTrue(fixture.sent.tryReceive().isFailure)
    }

    @Test
    fun runtimeExhaustionRetainsPendingWorkUntilTheNextResetSignal() = test { fixture ->
        val running = fixture.start(pushEnabled = false)
        var failing = true
        fixture.beforeInsert = { if (failing) error("inbox busy") }
        val first = fixture.recordReset()
        assertTrue(fixture.errors.receive().contains("after 3 attempts"))
        assertEquals(listOf(first), fixture.usage.pendingResetNotifications(0).map { it.id })
        assertTrue(fixture.inbox.recent(0).isEmpty())
        assertEquals(listOf(250L, 1_000L), fixture.retryDelays)
        failing = false
        val second = fixture.recordReset()
        fixture.awaitAcknowledged(second)
        assertEquals(setOf("usage.reset:$first", "usage.reset:$second"), fixture.inbox.recent(0).map { it.id }.toSet())
        assertTrue(fixture.usage.pendingResetNotifications(0).isEmpty())
        assertTrue(running.job.isActive)
        running.close()
    }

    @Test
    fun acknowledgementFailureRetriesExistingInboxRowBeforeWaking() = test { fixture ->
        val id = fixture.recordReset()
        var acknowledgementAttempts = 0
        fixture.beforeAck = {
            acknowledgementAttempts += 1
            if (acknowledgementAttempts == 1) error("acknowledgement busy")
        }
        val retrying = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        fixture.retry = { retrying.complete(Unit); gate.await() }
        val starting = async(start = CoroutineStart.UNDISPATCHED) { fixture.start() }
        retrying.await()
        assertEquals(1, fixture.inbox.recent(0).size)
        assertEquals(listOf(id), fixture.usage.pendingResetNotifications(0).map { it.id })
        assertTrue(fixture.sent.tryReceive().isFailure)
        gate.complete(Unit)
        val running = starting.await()
        assertEquals(2, fixture.pendingReads)
        assertEquals(listOf(true, false), fixture.insertResults)
        assertEquals(listOf(250L), fixture.retryDelays)
        assertTrue(fixture.usage.pendingResetNotifications(0).isEmpty())
        running.activateDelivery()
        assertEquals("usage.reset:$id", fixture.sent.receive())
        running.close()
        assertTrue(fixture.sent.tryReceive().isFailure)
    }

    @Test
    fun resetCollectorAndProjectionKeepUpWhileDatabaseAndNetworkWorkersAreBlocked() = test { fixture ->
        val running = fixture.start()
        running.activateDelivery()
        val inserting = CompletableDeferred<Unit>()
        val insertGate = CompletableDeferred<Unit>()
        var firstInsert = true
        fixture.beforeInsert = {
            if (firstInsert) {
                firstInsert = false
                inserting.complete(Unit)
                insertGate.await()
            }
        }
        val waking = CompletableDeferred<Unit>()
        val wakeGate = CompletableDeferred<Unit>()
        var firstWake = true
        fixture.beforeWake = {
            if (firstWake) {
                firstWake = false
                waking.complete(Unit)
                wakeGate.await()
            }
        }
        fixture.recordReset()
        inserting.await()
        var lastId = 0L
        repeat(80) { lastId = fixture.recordReset() }
        assertEquals(81, fixture.usage.pendingResetNotifications(0).size, "more than the 64-entry reset flow buffer commits while projection is blocked")
        insertGate.complete(Unit)
        waking.await()
        fixture.awaitAcknowledged(lastId)
        assertTrue(fixture.usage.pendingResetNotifications(0).isEmpty())
        repeat(80) { lastId = fixture.recordReset() }
        fixture.awaitAcknowledged(lastId)
        assertEquals(161, fixture.inbox.recent(0).size)
        assertTrue(fixture.usage.pendingResetNotifications(0).isEmpty(), "network delivery does not hold up local acknowledgement")
        val _ = fixture.sent.receive()
        wakeGate.complete(Unit)
        assertEquals("usage.reset:$lastId", fixture.sent.receive(), "one conflated follow-up fetch covers every projected inbox row")
        running.close()
    }

    @Test
    fun deliveryFailureIsReportedAndLaterResetsStillProjectAndWake() = test { fixture ->
        val running = fixture.start()
        running.activateDelivery()
        var failing = true
        fixture.beforeWake = { if (failing) error("network unavailable") }
        val first = fixture.recordReset()
        assertEquals("usage.reset:$first", fixture.sent.receive())
        assertTrue(fixture.errors.receive().contains("cannot send wake"))
        assertTrue(running.job.isActive)
        failing = false
        val second = fixture.recordReset()
        assertEquals("usage.reset:$second", fixture.sent.receive())
        assertEquals(2, fixture.inbox.recent(0).size)
        assertTrue(fixture.usage.pendingResetNotifications(0).isEmpty())
        running.close()
        assertTrue(fixture.errors.tryReceive().isFailure)
    }

    @Test
    fun stalePendingRowsDoNotProduceAnInboxItemOrWake() = test { fixture ->
        fixture.clock = epoch - NOTIFICATION_WINDOW_MILLIS - 10
        val old = fixture.recordReset()
        fixture.clock = epoch
        val running = fixture.start()
        running.activateDelivery()
        assertTrue(fixture.inbox.recent(0).isEmpty())
        assertEquals(listOf(old), fixture.usage.pendingResetNotifications(0).map { it.id })
        val fresh = fixture.recordReset()
        assertEquals("usage.reset:$fresh", fixture.sent.receive())
        assertEquals(listOf("usage.reset:$fresh"), fixture.inbox.recent(0).map { it.id })
        assertEquals(listOf(old), fixture.usage.pendingResetNotifications(0).map { it.id })
        running.close()
        assertTrue(fixture.sent.tryReceive().isFailure)
    }

    @Test
    fun cancellingStartupDuringRetryJoinsTheWatcherAndPreservesPendingWork() = test { fixture ->
        val id = fixture.recordReset()
        fixture.beforePending = { error("database busy") }
        val retrying = CompletableDeferred<Unit>()
        fixture.retry = { retrying.complete(Unit); awaitCancellation() }
        val starting = async(start = CoroutineStart.UNDISPATCHED) { fixture.start() }
        retrying.await()
        starting.cancelAndJoin()
        assertTrue(fixture.scope.coroutineContext[Job]!!.children.none())
        assertEquals(listOf(id), fixture.usage.pendingResetNotifications(0).map { it.id })
        assertTrue(fixture.inbox.recent(0).isEmpty())
        assertTrue(fixture.errors.tryReceive().isFailure)
    }

    @Test
    fun closeCancelsAndJoinsAnActiveDeliveryAndStopsProjection() = test { fixture ->
        val running = fixture.start()
        running.activateDelivery()
        val waking = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        fixture.beforeWake = {
            waking.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        fixture.recordReset()
        waking.await()
        running.close()
        assertTrue(stopped.isCompleted)
        assertTrue(running.job.isCompleted)
        val afterClose = fixture.recordReset()
        assertEquals(listOf(afterClose), fixture.usage.pendingResetNotifications(0).map { it.id })
        assertEquals(1, fixture.inbox.recent(0).size)
        assertTrue(fixture.errors.tryReceive().isFailure)
        running.close()
    }
}
