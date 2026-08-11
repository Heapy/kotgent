package io.kotgent.store

import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.ProjectId
import io.kotgent.core.Projection
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.core.reduce
import io.kotgent.core.replay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A host-free, thread-safe in-memory [EventStore] (and [PreferencesStore]) honoring the Task-7 contract:
 * append-only per-session log with a monotonic seq, a session cache advanced transactionally with each
 * append, a cursored [subscribe] whose stale cursor is a hard [StaleCursorException], and the Task-14
 * [sessionUpdates] signal. Guarded by one coroutine [Mutex]; every observable is a [Channel] /
 * [SharedFlow], so it is safe to touch from a server's engine threads and a test thread at once.
 *
 * ## Why it is a module, not a `private class`
 * Two consumers now stand a real `KotgentServer` on it — the root module's transport tests and the
 * `webuicheck` harness the browser tier drives — and a `NoopEventStore` cannot serve either, because
 * every scenario worth loading in a browser has sessions in it. Copying the class into the harness
 * would fork the one behaviour both tiers are supposed to be asserting against.
 */
class FakeEventStore(private val now: () -> Long = { 1L }) : EventStore, PreferencesStore {
    private val mutex = Mutex()
    private val metas = LinkedHashMap<SessionId, SessionMeta>()

    /** Mirrors the real store's revision counter: every meta write stamps `++revCounter` (under [mutex]). */
    private var revCounter = 0L
    private val logs = HashMap<SessionId, MutableList<StoredEvent>>()
    private val projections = HashMap<SessionId, Projection>()
    private val subs = HashMap<SessionId, MutableList<SendChannel<StoredEvent>>>()
    private val updates = MutableSharedFlow<SessionUpdate>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val sessionUpdates: SharedFlow<SessionUpdate> get() = updates

    /**
     * Its OWN unbuffered companion, not the interface default (which just re-exposes [sessionUpdates]).
     * The push notifier's correctness rests on seeing every intermediate transition, and a `DROP_OLDEST`
     * flow can silently swallow one; keeping the two distinct is what makes a notifier test over this
     * fake mean anything. Do not collapse it back onto the default.
     */
    private val reliableUpdates = MutableSharedFlow<SessionUpdate>()
    override val reliableSessionUpdates: SharedFlow<SessionUpdate> get() = reliableUpdates
    private val preferenceState = MutableStateFlow(UiPreferences("", 1, 0))
    override val preferences: StateFlow<UiPreferences> get() = preferenceState

    /**
     * The fake's [SqliteEventStore] `emitFromRow`: rebuild the signal from the STORED meta rather than
     * from each mutator's arguments, so all the targeted writers stay in step with the real store by
     * construction instead of by comment — including `archived`, which a client assigns unconditionally
     * and which therefore un-hides a "done" row if any emitter drops it, and `taskRef`, which is what
     * moves the sidebar's task badge without a reload. [append] is the one exception, mirroring the real
     * store (see its own note below).
     */
    private suspend fun emitFromMeta(sessionId: SessionId) {
        val m = metas[sessionId] ?: return
        emitUpdate(
            SessionUpdate(
                sessionId, m.state, m.lastSeq, unread(m.lastSeq.value, m.readCursor.value), m.archived,
                model = m.model, rev = m.rev, taskRef = m.taskRef, projectId = m.projectId,
            ),
        )
    }

    override suspend fun upsertSession(meta: SessionMeta): Unit = mutex.withLock {
        val prior = metas[meta.id]
        // Honors the contract: full-row EXCEPT createdAt (preserved) and readCursor (max-merged, so a
        // caller holding a stale cursor cannot regress the badge — Sessions.sq's `upsert`).
        val merged = if (prior != null) {
            meta.copy(
                createdAt = prior.createdAt,
                readCursor = Seq(maxOf(prior.readCursor.value, meta.readCursor.value)),
            )
        } else {
            meta
        }
        metas[meta.id] = merged.copy(rev = ++revCounter) // the store stamps rev, never the caller
        emitFromMeta(meta.id)
    }

    override suspend fun updateSessionState(
        sessionId: SessionId,
        state: SessionState,
        stateSource: EventSource,
        paneId: PaneId?,
        updatedAt: Long,
    ): Unit = mutex.withLock {
        val m = metas[sessionId] ?: return@withLock
        // Honors the contract: update only state/state_source/pane_id/updated_at, NEVER last_seq or
        // provider_session_id (so a concurrent append is not clobbered).
        metas[sessionId] = m.copy(
            state = state, stateSource = stateSource, paneId = paneId, updatedAt = updatedAt,
            rev = ++revCounter,
        )
        emitFromMeta(sessionId)
    }

    override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long): Unit = mutex.withLock {
        val m = metas[sessionId] ?: return@withLock
        metas[sessionId] = m.copy(archived = archived, updatedAt = updatedAt, rev = ++revCounter)
        emitFromMeta(sessionId)
    }

    override suspend fun setModel(sessionId: SessionId, model: String?, updatedAt: Long): Unit = mutex.withLock {
        val m = metas[sessionId] ?: return@withLock
        metas[sessionId] = m.copy(model = model, updatedAt = updatedAt, rev = ++revCounter)
        emitFromMeta(sessionId)
    }

    override suspend fun setModelForProvider(
        sessionId: SessionId,
        providerSessionId: ProviderSessionId,
        model: String,
        updatedAt: Long,
    ): Boolean = mutex.withLock {
        // Honors the contract: check-and-write atomically under the writer lock — a row whose
        // provider id changed (a hook rebind) is left untouched and the caller told so.
        val m = metas[sessionId] ?: return@withLock false
        if (m.providerSessionId != providerSessionId) return@withLock false
        metas[sessionId] = m.copy(model = model, updatedAt = updatedAt, rev = ++revCounter)
        emitFromMeta(sessionId)
        true
    }

    override suspend fun markRead(sessionId: SessionId, seq: Seq): Unit = mutex.withLock {
        val m = metas[sessionId] ?: return@withLock
        // Mirrors the SQL: monotonic (max) and clamped to lastSeq (min); updated_at is NOT written.
        metas[sessionId] = m.copy(
            readCursor = Seq(maxOf(m.readCursor.value, minOf(seq.value, m.lastSeq.value))),
            rev = ++revCounter,
        )
        emitFromMeta(sessionId)
    }

    // --- the task-link members, whose interface defaults THROW -----------------------------------
    //
    // They are defaulted only so the suite's older hand-written fakes keep compiling, and the default is
    // an UnsupportedOperationException rather than a silent no-op precisely so a fake that skipped one
    // cannot make a link that persisted nothing look green. This store is the one a real server runs on
    // in both consumers, so all of them are implemented for real, over the SAME LinkedHashMap, with the
    // same `++revCounter` stamp and the same `emitFromMeta` broadcast as every mutator above — without
    // that, a link/unlink or a `transition(done)` driven from the browser answers 500 instead of moving
    // the badge.

    override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?, updatedAt: Long): Unit =
        mutex.withLock {
            // Unconditional, like the real store: a null CLEARS, and pointing a session at another task
            // simply overwrites — kotgent enforces no exclusivity (see EventStore.setTaskRef).
            val m = metas[sessionId] ?: return@withLock
            metas[sessionId] = m.copy(taskRef = taskRef, updatedAt = updatedAt, rev = ++revCounter)
            emitFromMeta(sessionId)
        }

    /**
     * Overridden rather than left on the interface's two-step default, for the reason that default names:
     * it is a [getSession] followed by a [setTaskRef], which is exact only for a store with ONE caller,
     * and this one is reached from a server's engine threads. Under [mutex] the check and the write are
     * one indivisible step, so a link made to a different task in between survives untouched.
     */
    override suspend fun clearTaskRefIf(
        sessionId: SessionId,
        expectedRef: TaskRef,
        updatedAt: Long,
    ): Boolean = mutex.withLock {
        val m = metas[sessionId] ?: return@withLock false
        if (m.taskRef != expectedRef) return@withLock false
        metas[sessionId] = m.copy(taskRef = null, updatedAt = updatedAt, rev = ++revCounter)
        emitFromMeta(sessionId)
        true
    }

    override suspend fun setProjectId(sessionId: SessionId, projectId: ProjectId?, updatedAt: Long): Unit =
        mutex.withLock {
            val m = metas[sessionId] ?: return@withLock
            metas[sessionId] = m.copy(projectId = projectId, updatedAt = updatedAt, rev = ++revCounter)
            emitFromMeta(sessionId)
        }

    override suspend fun sessionsHoldingTask(taskRef: TaskRef): List<SessionMeta> = mutex.withLock {
        // A LIST, not an optional: linking is many-sessions-to-one-task by design. Oldest first, the
        // stable order `transition(done)` and `delete` iterate to unlink every holder — and the id
        // breaks a tie, because `Sessions.sq` says `ORDER BY created_at, id` and two rows sharing a
        // timestamp is the ordinary case for a fixture that stamps a whole scenario at one instant.
        metas.values.filter { it.taskRef == taskRef }.sortedWith(ROW_ORDER)
    }

    override suspend fun getSession(sessionId: SessionId): SessionMeta? = mutex.withLock { metas[sessionId] }

    /**
     * Every row, in the daemon's own order.
     *
     * `Sessions.sq`'s `list` is `ORDER BY created_at, id`, and this is the list the sidebar renders and
     * `kotgent list` prints, so insertion order was a second contract that happened to agree only
     * because every fixture seeds ascending. It stops agreeing the moment a scenario stamps two rows
     * with one timestamp, and the disagreement shows up as a row in the wrong place in a browser
     * assertion rather than as a failure here.
     */
    override suspend fun listSessions(): List<SessionMeta> =
        mutex.withLock { metas.values.sortedWith(ROW_ORDER) }

    override suspend fun savePreferences(basePath: String, groupingLevel: Int): UiPreferences =
        mutex.withLock {
            UiPreferences(basePath, groupingLevel, preferenceState.value.revision + 1).also {
                preferenceState.value = it
            }
        }

    override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq = mutex.withLock {
        val log = logs.getOrPut(sessionId) { mutableListOf() }
        val prior = projections.getOrPut(sessionId) { replay(log.map { it.event }) }
        val next = reduce(prior, event)
        projections[sessionId] = next
        val ts = now()
        val stored = StoredEvent(sessionId, next.lastSeq, ts, source, event)
        log.add(stored)
        metas[sessionId]?.let { m ->
            // Mirrors the real store's cache-state authority (SqliteEventStore.append): a DEAD cached
            // state is never resurrected by an append — an import's late `SessionBound` must leave the
            // row `resumable` — while an alive one applies the event over the cached (control-aware)
            // state. last_seq and the provider id still come from the pure event-log projection.
            val cacheState = if (m.state.isDead) m.state else reduce(prior.copy(state = m.state), event).state
            metas[sessionId] = m.copy(
                state = cacheState,
                stateSource = source,
                lastSeq = next.lastSeq,
                providerSessionId = next.providerSessionId ?: m.providerSessionId,
                updatedAt = ts,
                rev = ++revCounter,
            )
        }
        // Hand-built rather than [emitFromMeta], for the same two reasons the real store's `append` is
        // exempt: the signal carries the freshly reduced lastSeq (and the control-authoritative cache
        // state), and it must still go out when no meta row exists (the event was stored regardless).
        // read_cursor and archived are untouched by an append but still ride it — an event on a done
        // session must not un-hide its row.
        val cached = metas[sessionId]
        emitUpdate(
            SessionUpdate(
                sessionId, cached?.state ?: next.state, next.lastSeq,
                unread(next.lastSeq.value, cached?.readCursor?.value ?: 0L), cached?.archived ?: false,
                model = cached?.model, rev = cached?.rev ?: 0,
                taskRef = cached?.taskRef, projectId = cached?.projectId,
            ),
        )
        subs[sessionId]?.forEach { it.trySend(stored) }
        next.lastSeq
    }

    /** Mirror production: the UI signal may drop, while the notifier signal preserves committed order. */
    private suspend fun emitUpdate(update: SessionUpdate) {
        updates.tryEmit(update)
        reliableUpdates.emit(update)
    }

    override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = mutex.withLock {
        (logs[sessionId] ?: emptyList()).filter { it.seq.value >= fromSeq.value }
    }

    override suspend fun projectionOf(sessionId: SessionId): Projection = mutex.withLock {
        projections.getOrPut(sessionId) { replay((logs[sessionId] ?: emptyList()).map { it.event }) }
    }

    override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = channelFlow {
        val relay = Channel<StoredEvent>(Channel.UNLIMITED)
        val snapshot = mutex.withLock {
            val last = (projections[sessionId] ?: replay((logs[sessionId] ?: emptyList()).map { it.event })).lastSeq
            if (fromSeq.value > last.value + 1) throw StaleCursorException(sessionId, fromSeq, last)
            val snap = (logs[sessionId] ?: emptyList()).filter { it.seq.value >= fromSeq.value }
            subs.getOrPut(sessionId) { mutableListOf() }.add(relay)
            snap
        }
        try {
            for (e in snapshot) send(e)
            for (e in relay) send(e)
        } finally {
            withContext(NonCancellable) { mutex.withLock { subs[sessionId]?.remove(relay) } }
            relay.close()
        }
    }

    private fun unread(last: Long, readCursor: Long): Long = (last - readCursor).coerceAtLeast(0)

    private companion object {
        /**
         * `ORDER BY created_at, id` — the one row order `Sessions.sq` declares, shared by `list` and
         * `sessionsHoldingTask` there and therefore by both of them here.
         */
        val ROW_ORDER: Comparator<SessionMeta> = compareBy({ it.createdAt }, { it.id.value })
    }
}
