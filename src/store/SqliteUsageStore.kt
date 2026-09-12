package io.kotgent.store

import app.cash.sqldelight.db.SqlDriver
import io.kotgent.core.USAGE_HEARTBEAT_MILLIS
import io.kotgent.core.USAGE_RETENTION_MILLIS
import io.kotgent.core.UsageObservation
import io.kotgent.core.UsageReset
import io.kotgent.core.UsageSource
import io.kotgent.core.UsageWindowState
import io.kotgent.core.detectReset
import io.kotgent.core.isNotificationEligible
import io.kotgent.db.KotgentDatabase
import io.kotgent.db.Usage_windows
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SqliteUsageStore(
    driver: SqlDriver,
    private val now: () -> Long = ::usageEpochMillis,
) : UsageStore {
    private val db = KotgentDatabase(driver)
    private val queries get() = db.usageQueries
    private val operationsMutex = Mutex()
    private val dbMutex = Mutex()

    override val updates: SharedFlow<UsageWindowState>
        field = MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val resets: SharedFlow<UsageReset>
        field = MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        for (sql in CREATE_TABLES_IF_NOT_EXISTS) driver.execute(null, sql, 0)
        if (!driver.hasColumn("usage_windows", "received_at")) {
            db.transaction {
                driver.execute(null, "ALTER TABLE usage_windows ADD COLUMN received_at INTEGER NOT NULL DEFAULT 0", 0)
                driver.execute(null, "UPDATE usage_windows SET received_at = observed_at", 0)
            }
        }
    }

    override suspend fun observe(observation: UsageObservation): Unit = operationsMutex.withLock {
        val publication = dbMutex.withLock {
            db.transactionWithResult { observeLocked(observation, now()) }
        }
        if (publication != null) {
            // Live frames are hints; snapshots and pending inbox work remain durable in SQLite.
            val _ = updates.tryEmit(publication.state)
            publication.reset?.let { val _ = resets.tryEmit(it) }
        }
    }

    override suspend fun list(): List<UsageWindowState> = dbMutex.withLock {
        queries.selectAllWindows().executeAsList().map { readWindow(it).state }
    }

    override suspend fun prune(): Unit = dbMutex.withLock {
        val cutoff = now() - USAGE_RETENTION_MILLIS
        db.transaction {
            val _ = queries.pruneSamples(cutoff)
            val _ = queries.pruneResets(cutoff)
            val _ = queries.pruneSources(cutoff)
        }
    }

    override suspend fun pendingResetNotifications(since: Long): List<UsageReset> = dbMutex.withLock {
        queries.selectPendingResets(since) {
            id, provider, windowKey, expectedAt, observedAt, usedBefore, usedBeforeSeenAt,
            early, resetsAtMoved, windowSeconds ->
            UsageReset(
                provider, windowKey, expectedAt, observedAt, usedBefore, usedBeforeSeenAt,
                early != 0L, resetsAtMoved != 0L, windowSeconds, id,
            )
        }.executeAsList().filter { it.isNotificationEligible }
    }

    override suspend fun markResetNotificationProjected(id: Long): Unit = dbMutex.withLock {
        val _ = queries.markResetProjected(id)
    }

    private fun observeLocked(input: UsageObservation, timestamp: Long): Publication? {
        val stored = queries.selectWindow(input.provider, input.windowKey).executeAsOneOrNull()?.let(::readWindow)
        val current = stored?.state?.current
        val generation = stored?.generation ?: 0L
        // A wall-clock correction must not leave a persisted future watermark blocking this meter.
        // Source revisions still reject replay within each producer; new Claude sources need a baseline.
        val persistedCaptureAt = stored?.admittedCaptureAt ?: 0L
        val admittedCaptureAt = if (persistedCaptureAt > timestamp &&
            persistedCaptureAt - timestamp > MAX_CAPTURE_LEAD_MILLIS
        ) 0L else persistedCaptureAt
        val source = input.source
        val baseline = source?.let {
            queries.selectSource(input.provider, input.windowKey, it.id, ::readSource).executeAsOneOrNull()
        }
        val previousSource = baseline?.observation?.source
        val advancingSource = source != null && (previousSource == null || source.revision > previousSource.revision)
        val distinctSourceValues = baseline == null || !sameValues(baseline.observation, input)
        // A known cached render can outlive a wall-clock correction. It may refresh receipt, never evidence.
        val knownCachedRender = input.provider == "claude" && source != null && source == previousSource &&
            !distinctSourceValues && current != null && sameValues(current, input)
        if (source != null && source.capturedAt > timestamp &&
            source.capturedAt - timestamp > MAX_CAPTURE_LEAD_MILLIS && !knownCachedRender
        ) return null

        if (source != null) {
            if (previousSource != null && source.revision <= previousSource.revision) {
                if (source.revision < previousSource.revision || source.capturedAt != previousSource.capturedAt ||
                    distinctSourceValues || input.provider != "claude" || current == null || !sameValues(current, input)
                ) return null
            } else {
                if (source.capturedAt < admittedCaptureAt) return null
            }
        }

        // A revision can advance because another Claude window changed, while this meter stayed cached.
        if (input.provider == "claude" && baseline != null && !distinctSourceValues &&
            current != null && !sameValues(current, input)
        ) {
            if (advancingSource) writeSource(input, baseline.generation)
            return null
        }

        if (current != null && sameValues(current, input)) {
            if (advancingSource) {
                writeSource(input, if (distinctSourceValues) generation else baseline.generation)
            }
            // Delivery timestamps from unchanged Claude caches cannot overtake distinct quota evidence.
            val advancesEvidence = advancingSource &&
                (input.provider != "claude" || (baseline != null && distinctSourceValues))
            val nextCaptureAt = if (advancesEvidence) maxOf(admittedCaptureAt, source.capturedAt) else admittedCaptureAt
            if (timestamp >= stored.state.receivedAt &&
                timestamp - stored.state.receivedAt < USAGE_HEARTBEAT_MILLIS
            ) {
                if (nextCaptureAt != persistedCaptureAt) {
                    val _ = queries.updateAdmittedCapture(nextCaptureAt, input.provider, input.windowKey)
                }
                return null
            }
            val state = stored.state.copy(
                current = current.copy(observedAt = nextObservedAt(current, timestamp)),
                receivedAt = timestamp,
            )
            writeWindow(state, generation, nextCaptureAt)
            return Publication(state)
        }

        val admitted = input.copy(observedAt = nextObservedAt(current, timestamp))
        val sameSessionDrop = advancingSource && baseline != null && distinctSourceValues &&
            baseline.generation == generation && baseline.observation.usedPercent > admitted.usedPercent
        val detected = if (input.provider != "claude" || sameSessionDrop) {
            detectReset(current, admitted, stored?.state?.receivedAt ?: timestamp, timestamp)
        } else null
        val nextGeneration = generation + if (detected == null) 0 else 1
        if (advancingSource) {
            writeSource(admitted, if (distinctSourceValues) nextGeneration else baseline.generation)
        }
        val state = UsageWindowState(admitted, changedAt = timestamp, receivedAt = timestamp)
        writeWindow(state, nextGeneration, maxOf(admittedCaptureAt, source?.capturedAt ?: admittedCaptureAt))
        val _ = queries.insertSample(
            admitted.provider, admitted.windowKey, admitted.usedPercent, admitted.resetsAt,
            admitted.windowSeconds, timestamp, source?.id, source?.revision, source?.capturedAt,
        )
        val reset = detected?.let {
            val _ = queries.insertReset(
                it.provider, it.windowKey, it.expectedAt, it.observedAt, it.usedBefore, it.usedBeforeSeenAt,
                if (it.early) 1L else 0L, if (it.resetsAtMoved) 1L else 0L, it.windowSeconds,
            )
            // last_insert_rowid is connection-local, so resolve it before leaving this transaction.
            it.copy(id = queries.lastResetId().executeAsOne())
        }
        return Publication(state, reset)
    }

    private fun writeSource(observation: UsageObservation, generation: Long) {
        val source = requireNotNull(observation.source)
        val _ = queries.upsertSource(
            observation.provider, observation.windowKey, source.id, source.revision, source.capturedAt,
            observation.usedPercent, observation.resetsAt, observation.windowSeconds, generation,
        )
    }

    private fun writeWindow(state: UsageWindowState, generation: Long, admittedCaptureAt: Long) {
        val current = state.current
        val _ = queries.upsertWindow(
            provider = current.provider,
            window_key = current.windowKey,
            used_percent = current.usedPercent,
            resets_at = current.resetsAt,
            window_seconds = current.windowSeconds,
            observed_at = current.observedAt,
            changed_at = state.changedAt,
            received_at = state.receivedAt,
            source_id = current.source?.id,
            source_revision = current.source?.revision,
            source_captured_at = current.source?.capturedAt,
            reset_generation = generation,
            admitted_capture_at = admittedCaptureAt,
        )
    }

    private class Publication(val state: UsageWindowState, val reset: UsageReset? = null)

    private class StoredWindow(val state: UsageWindowState, val generation: Long, val admittedCaptureAt: Long)

    private class SourceBaseline(val observation: UsageObservation, val generation: Long)

    companion object {
        private const val MAX_CAPTURE_LEAD_MILLIS = 60_000L
        /** Runtime migration DDL; keep synchronized with Usage.sq. */
        val CREATE_TABLES_IF_NOT_EXISTS: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS usage_windows (
              provider TEXT NOT NULL,
              window_key TEXT NOT NULL,
              used_percent REAL NOT NULL,
              resets_at INTEGER,
              window_seconds INTEGER,
              observed_at INTEGER NOT NULL,
              changed_at INTEGER NOT NULL,
              received_at INTEGER NOT NULL,
              source_id TEXT,
              source_revision INTEGER,
              source_captured_at INTEGER,
              reset_generation INTEGER NOT NULL DEFAULT 0,
              admitted_capture_at INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (provider, window_key)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS usage_sources (
              provider TEXT NOT NULL,
              window_key TEXT NOT NULL,
              source_id TEXT NOT NULL,
              source_revision INTEGER NOT NULL,
              source_captured_at INTEGER NOT NULL,
              used_percent REAL NOT NULL,
              resets_at INTEGER,
              window_seconds INTEGER,
              baseline_reset_generation INTEGER NOT NULL,
              PRIMARY KEY (provider, window_key, source_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS usage_samples (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              provider TEXT NOT NULL,
              window_key TEXT NOT NULL,
              used_percent REAL NOT NULL,
              resets_at INTEGER,
              window_seconds INTEGER,
              observed_at INTEGER NOT NULL,
              source_id TEXT,
              source_revision INTEGER,
              source_captured_at INTEGER
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS usage_samples_by_window ON usage_samples(provider, window_key, observed_at)",
            """
            CREATE TABLE IF NOT EXISTS usage_resets (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              provider TEXT NOT NULL,
              window_key TEXT NOT NULL,
              expected_at INTEGER,
              observed_at INTEGER NOT NULL,
              used_before REAL NOT NULL,
              used_before_seen_at INTEGER NOT NULL,
              early INTEGER NOT NULL,
              resets_at_moved INTEGER NOT NULL,
              window_seconds INTEGER,
              inbox_projected INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        private fun sameValues(a: UsageObservation, b: UsageObservation): Boolean =
            a.usedPercent == b.usedPercent && a.resetsAt == b.resetsAt && a.windowSeconds == b.windowSeconds

        private fun nextObservedAt(previous: UsageObservation?, timestamp: Long): Long {
            check(previous?.observedAt != Long.MAX_VALUE) { "Usage observation revision exhausted" }
            return maxOf((previous?.observedAt ?: -1L) + 1L, timestamp)
        }

        private fun readSource(
            provider: String,
            windowKey: String,
            sourceId: String,
            sourceRevision: Long,
            sourceCapturedAt: Long,
            usedPercent: Double,
            resetsAt: Long?,
            windowSeconds: Long?,
            generation: Long,
        ): SourceBaseline = SourceBaseline(
            UsageObservation(
                provider, windowKey, usedPercent, resetsAt, windowSeconds,
                source = UsageSource(sourceId, sourceRevision, sourceCapturedAt),
            ),
            generation,
        )

        private fun readWindow(row: Usage_windows): StoredWindow = StoredWindow(
            UsageWindowState(
                current = UsageObservation(
                    provider = row.provider,
                    windowKey = row.window_key,
                    usedPercent = row.used_percent,
                    resetsAt = row.resets_at,
                    windowSeconds = row.window_seconds,
                    observedAt = row.observed_at,
                    source = readSourceIdentity(row.source_id, row.source_revision, row.source_captured_at),
                ),
                changedAt = row.changed_at,
                receivedAt = row.received_at,
            ),
            row.reset_generation,
            row.admitted_capture_at,
        )

        private fun readSourceIdentity(id: String?, revision: Long?, capturedAt: Long?): UsageSource? =
            id?.let { UsageSource(it, requireNotNull(revision), requireNotNull(capturedAt)) }
    }
}

@OptIn(ExperimentalTime::class)
private fun usageEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
