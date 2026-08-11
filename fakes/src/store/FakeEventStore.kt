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

/** Thread-safe in-memory store shared by native tests and the live browser harness. */
class FakeEventStore(private val now: () -> Long = { 1L }) : EventStore, PreferencesStore {
    private val mutex = Mutex()
    private val metas = LinkedHashMap<SessionId, SessionMeta>()

    private var revCounter = 0L
    private val logs = HashMap<SessionId, MutableList<StoredEvent>>()
    private val projections = HashMap<SessionId, Projection>()
    private val subs = HashMap<SessionId, MutableList<SendChannel<StoredEvent>>>()
    private val updates = MutableSharedFlow<SessionUpdate>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val sessionUpdates: SharedFlow<SessionUpdate> get() = updates

    // Push tests require every transition; the public UI flow above may deliberately drop old updates.
    private val reliableUpdates = MutableSharedFlow<SessionUpdate>()
    override val reliableSessionUpdates: SharedFlow<SessionUpdate> get() = reliableUpdates
    private val preferenceState = MutableStateFlow(UiPreferences("", 1, 0))
    override val preferences: StateFlow<UiPreferences> get() = preferenceState

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
        // Whole-row writers must not regress read progress or erase links owned by targeted setters.
        val merged = if (prior != null) {
            meta.copy(
                createdAt = prior.createdAt,
                readCursor = Seq(maxOf(prior.readCursor.value, meta.readCursor.value)),
                taskRef = meta.taskRef ?: prior.taskRef,
                projectId = meta.projectId ?: prior.projectId,
            )
        } else {
            meta
        }
        metas[meta.id] = merged.copy(rev = ++revCounter)
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
        val m = metas[sessionId] ?: return@withLock false
        if (m.providerSessionId != providerSessionId) return@withLock false
        metas[sessionId] = m.copy(model = model, updatedAt = updatedAt, rev = ++revCounter)
        emitFromMeta(sessionId)
        true
    }

    override suspend fun markRead(sessionId: SessionId, seq: Seq): Unit = mutex.withLock {
        val m = metas[sessionId] ?: return@withLock
        metas[sessionId] = m.copy(
            readCursor = Seq(maxOf(m.readCursor.value, minOf(seq.value, m.lastSeq.value))),
            rev = ++revCounter,
        )
        emitFromMeta(sessionId)
    }


    override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?, updatedAt: Long): Unit =
        mutex.withLock {
            val m = metas[sessionId] ?: return@withLock
            metas[sessionId] = m.copy(taskRef = taskRef, updatedAt = updatedAt, rev = ++revCounter)
            emitFromMeta(sessionId)
        }

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
        metas.values.filter { it.taskRef == taskRef }.sortedWith(ROW_ORDER)
    }

    override suspend fun getSession(sessionId: SessionId): SessionMeta? = mutex.withLock { metas[sessionId] }

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
            // Late provider events cannot revive a row already classified dead by reconciliation.
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
        // Snapshot and registration share the lock so no event can fall into the gap between them.
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
            // Cancellation must not strand a dead subscriber in the fan-out list.
            withContext(NonCancellable) { mutex.withLock { subs[sessionId]?.remove(relay) } }
            relay.close()
        }
    }

    private fun unread(last: Long, readCursor: Long): Long = (last - readCursor).coerceAtLeast(0)

    private companion object {
        val ROW_ORDER: Comparator<SessionMeta> = compareBy({ it.createdAt }, { it.id.value })
    }
}
