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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

data class StoredEvent(
    val sessionId: SessionId,
    val seq: Seq,
    val ts: Long,
    val source: EventSource,
    val event: AgentEvent,
)

data class SessionUpdate(
    val sessionId: SessionId,
    val state: SessionState,
    val lastSeq: Seq,
    val unread: Long,
    // Carried on every update so a client that only ever sees patches can still order rows by recency.
    val updatedAt: Long,
    val archived: Boolean = false,
    val model: String? = null,
    val rev: Long = 0,
    val taskRef: TaskRef? = null,
    val projectId: ProjectId? = null,
)

/** The requested cursor cannot be served as one contiguous stream and must not be silently resynced. */
class StaleCursorException(
    val sessionId: SessionId,
    val requested: Seq,
    val lastSeq: Seq,
) : IllegalStateException(
    "stale cursor for session '${sessionId.value}': from=${requested.value} is beyond lastSeq+1=${lastSeq.value + 1}",
)

/** Append-only event log plus its transactionally consistent session projection; writes are serialized. */
interface EventStore {

    /** Preserves createdAt, max-merges readCursor, and preserves targeted task/project values on null input. */
    suspend fun upsertSession(meta: SessionMeta)

    suspend fun updateSessionState(
        sessionId: SessionId,
        state: SessionState,
        stateSource: EventSource,
        paneId: PaneId?,
        updatedAt: Long,
    )

    suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long)

    /** Derived metadata write: advances rev and emits while preserving the committed activity timestamp. */
    suspend fun setModel(sessionId: SessionId, model: String?)

    /** Atomically writes only while the row still holds the provider id used for the model lookup. */
    suspend fun setModelForProvider(
        sessionId: SessionId,
        providerSessionId: ProviderSessionId,
        model: String,
    ): Boolean

    /**
     * Monotonically advances the cursor, clamps it to lastSeq, and does not count viewing as activity.
     * Emits even for a no-op so a client can use a repeated mark as a resynchronization signal.
     */
    suspend fun markRead(sessionId: SessionId, seq: Seq)

    /** Unconditional by design: several sessions may work the same task; preserves activity ordering. */
    suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?): Unit =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not model session task links: override setTaskRef",
        )

    /**
     * Prevents a clear from erasing a newer link. This default is non-atomic and is suitable only for
     * single-threaded fakes; concurrent stores must override it with one check-and-write.
     */
    suspend fun clearTaskRefIf(sessionId: SessionId, expectedRef: TaskRef): Boolean {
        if (getSession(sessionId)?.taskRef != expectedRef) return false
        setTaskRef(sessionId, null)
        return true
    }

    /** Derived metadata write that preserves activity ordering. */
    suspend fun setProjectId(sessionId: SessionId, projectId: ProjectId?): Unit =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not model session projects: override setProjectId",
        )

    suspend fun sessionsHoldingTask(taskRef: TaskRef): List<SessionMeta> =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not model session task links: override sessionsHoldingTask",
        )

    suspend fun getSession(sessionId: SessionId): SessionMeta?

    suspend fun listSessions(): List<SessionMeta>

    suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq

    suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent>

    suspend fun projectionOf(sessionId: SessionId): Projection

    fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent>

    val sessionUpdates: SharedFlow<SessionUpdate>

    /** Lossless committed-order stream used for notification edge tracking. */
    val reliableSessionUpdates: SharedFlow<SessionUpdate>
        get() = sessionUpdates
}
