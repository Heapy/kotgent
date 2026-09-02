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
import kotlinx.atomicfu.atomic
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

private fun increasingEpochClock(): () -> Long {
    val stamp = atomic(1_700_000_000_000L)
    return {
        stamp += 1_000L
        stamp.value
    }
}

// Create proper factory
private val mutex = Mutex()

class FakePreferencesStore : PreferencesStore {
    override val preferences: StateFlow<UiPreferences>
        field = MutableStateFlow(
            UiPreferences(
                basePath = "",
                groupingLevel = 1,
                revision = 0,
            ),
        )

    override suspend fun savePreferences(
        basePath: String,
        groupingLevel: Int,
    ): UiPreferences =
        mutex
            .withLock {
                UiPreferences(
                    basePath = basePath,
                    groupingLevel = groupingLevel,
                    revision = preferences.value.revision + 1,
                ).also {
                    preferences.value = it
                }
            }
}

/** Thread-safe in-memory store shared by native tests and the live browser harness. */
class FakeEventStore(
    private val now: () -> Long = increasingEpochClock(),
    sessionMetadata: Map<SessionId, SessionMeta> = emptyMap(),
) : EventStore {
    private val sessionMetadata = LinkedHashMap(sessionMetadata)

    private var revCounter = 0L
    private val logs = HashMap<SessionId, MutableList<StoredEvent>>()
    private val projections = HashMap<SessionId, Projection>()
    private val subs = HashMap<SessionId, MutableList<SendChannel<StoredEvent>>>()
    override val sessionUpdates: SharedFlow<SessionUpdate>
        field = MutableSharedFlow<SessionUpdate>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    // Push tests require every transition; the public UI flow above may deliberately drop old updates.
    override val reliableSessionUpdates: SharedFlow<SessionUpdate>
        field = MutableSharedFlow<SessionUpdate>()

    private suspend fun emitFromMeta(sessionId: SessionId) {
        val m = sessionMetadata[sessionId] ?: return
        emitUpdate(
            SessionUpdate(
                sessionId = sessionId,
                state = m.state,
                lastSeq = m.lastSeq,
                unread = unread(m.lastSeq.value, m.readCursor.value),
                updatedAt = m.updatedAt,
                archived = m.archived,
                model = m.model,
                name = m.name,
                rev = m.rev,
                taskRef = m.taskRef,
                projectId = m.projectId,
            ),
        )
    }

    override suspend fun upsertSession(meta: SessionMeta): Unit = mutex.withLock {
        val prior = sessionMetadata[meta.id]
        // Whole-row writers must not regress read progress or erase the name and links owned by
        // targeted setters.
        val merged = if (prior != null) {
            meta.copy(
                name = prior.name,
                createdAt = prior.createdAt,
                readCursor = Seq(maxOf(prior.readCursor.value, meta.readCursor.value)),
                taskRef = meta.taskRef ?: prior.taskRef,
                projectId = meta.projectId ?: prior.projectId,
            )
        } else {
            meta
        }
        sessionMetadata[meta.id] = merged.copy(rev = ++revCounter)
        emitFromMeta(meta.id)
    }

    override suspend fun updateSessionState(
        sessionId: SessionId,
        state: SessionState,
        stateSource: EventSource,
        paneId: PaneId?,
        updatedAt: Long,
    ): Unit = mutex.withLock {
        val m = sessionMetadata[sessionId] ?: return@withLock
        sessionMetadata[sessionId] = m.copy(
            state = state, stateSource = stateSource, paneId = paneId, updatedAt = updatedAt,
            rev = ++revCounter,
        )
        emitFromMeta(sessionId)
    }

    override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long): Unit = mutex.withLock {
        val m = sessionMetadata[sessionId] ?: return@withLock
        sessionMetadata[sessionId] = m.copy(archived = archived, updatedAt = updatedAt, rev = ++revCounter)
        emitFromMeta(sessionId)
    }

    override suspend fun setModel(sessionId: SessionId, model: String?): Unit = mutex.withLock {
        val m = sessionMetadata[sessionId] ?: return@withLock
        sessionMetadata[sessionId] = m.copy(model = model, rev = ++revCounter)
        emitFromMeta(sessionId)
    }

    override suspend fun setName(sessionId: SessionId, name: String): Unit = mutex.withLock {
        val m = sessionMetadata[sessionId] ?: return@withLock
        sessionMetadata[sessionId] = m.copy(name = name, rev = ++revCounter)
        emitFromMeta(sessionId)
    }

    override suspend fun setModelForProvider(
        sessionId: SessionId,
        providerSessionId: ProviderSessionId,
        model: String,
    ): Boolean = mutex.withLock {
        val m = sessionMetadata[sessionId] ?: return@withLock false
        if (m.providerSessionId != providerSessionId) return@withLock false
        sessionMetadata[sessionId] = m.copy(model = model, rev = ++revCounter)
        emitFromMeta(sessionId)
        true
    }

    override suspend fun markRead(sessionId: SessionId, seq: Seq): Unit = mutex.withLock {
        val m = sessionMetadata[sessionId] ?: return@withLock
        sessionMetadata[sessionId] = m.copy(
            readCursor = Seq(maxOf(m.readCursor.value, minOf(seq.value, m.lastSeq.value))),
            rev = ++revCounter,
        )
        emitFromMeta(sessionId)
    }


    override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?): Unit =
        mutex.withLock {
            val m = sessionMetadata[sessionId] ?: return@withLock
            sessionMetadata[sessionId] = m.copy(taskRef = taskRef, rev = ++revCounter)
            emitFromMeta(sessionId)
        }

    override suspend fun clearTaskRefIf(
        sessionId: SessionId,
        expectedRef: TaskRef,
    ): Boolean = mutex.withLock {
        val m = sessionMetadata[sessionId] ?: return@withLock false
        if (m.taskRef != expectedRef) return@withLock false
        sessionMetadata[sessionId] = m.copy(taskRef = null, rev = ++revCounter)
        emitFromMeta(sessionId)
        true
    }

    override suspend fun setProjectId(sessionId: SessionId, projectId: ProjectId?): Unit =
        mutex.withLock {
            val m = sessionMetadata[sessionId] ?: return@withLock
            sessionMetadata[sessionId] = m.copy(projectId = projectId, rev = ++revCounter)
            emitFromMeta(sessionId)
        }

    override suspend fun sessionsHoldingTask(taskRef: TaskRef): List<SessionMeta> = mutex.withLock {
        sessionMetadata.values.filter { it.taskRef == taskRef }.sortedWith(ROW_ORDER)
    }

    override suspend fun getSession(sessionId: SessionId): SessionMeta? = mutex.withLock { sessionMetadata[sessionId] }

    override suspend fun listSessions(): List<SessionMeta> =
        mutex.withLock { sessionMetadata.values.sortedWith(ROW_ORDER) }

    override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq = mutex.withLock {
        val log = logs.getOrPut(sessionId) { mutableListOf() }
        val prior = projections.getOrPut(sessionId) { replay(log.map { it.event }) }
        val next = reduce(prior, event)
        projections[sessionId] = next
        val ts = now()
        val stored = StoredEvent(sessionId, next.lastSeq, ts, source, event)
        log.add(stored)
        sessionMetadata[sessionId]?.let { m ->
            // Late provider events cannot revive a row already classified dead by reconciliation.
            val cacheState = if (m.state.isDead) m.state else reduce(prior.copy(state = m.state), event).state
            sessionMetadata[sessionId] = m.copy(
                state = cacheState,
                stateSource = source,
                lastSeq = next.lastSeq,
                providerSessionId = next.providerSessionId ?: m.providerSessionId,
                updatedAt = ts,
                rev = ++revCounter,
            )
        }
        val cached = sessionMetadata[sessionId]
        emitUpdate(
            SessionUpdate(
                sessionId = sessionId,
                state = cached?.state ?: next.state,
                lastSeq = next.lastSeq,
                unread = unread(next.lastSeq.value, cached?.readCursor?.value ?: 0L),
                updatedAt = ts,
                archived = cached?.archived ?: false,
                model = cached?.model,
                name = cached?.name,
                rev = cached?.rev ?: 0,
                taskRef = cached?.taskRef,
                projectId = cached?.projectId,
            ),
        )
        subs[sessionId]?.forEach { it.trySend(stored) }
        next.lastSeq
    }

    private suspend fun emitUpdate(update: SessionUpdate) {
        sessionUpdates.tryEmit(update)
        reliableSessionUpdates.emit(update)
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
