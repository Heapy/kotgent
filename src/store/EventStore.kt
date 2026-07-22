package io.kotgent.store

import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.Projection
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import kotlinx.coroutines.flow.Flow

/**
 * One stored row of the append-only `events` log: the canonical [event] plus the envelope the store
 * stamps around it — the per-session [seq] it was assigned, the epoch-millis [ts] it was appended,
 * and the [source] that produced it. [sessionId] is carried too so a merged/global feed can hold
 * rows from several sessions; per-session ordering is by [seq].
 */
data class StoredEvent(
    val sessionId: SessionId,
    val seq: Seq,
    val ts: Long,
    val source: EventSource,
    val event: AgentEvent,
)

/**
 * Raised when a subscriber's cursor cannot be honored against the current log — it points beyond
 * the end of the session's contiguous sequence ([requested] > [lastSeq] + 1) or into a gap. This is
 * a hard error on purpose: the events cursor is restart-safe, so a cursor the store cannot serve
 * contiguously signals lost/desynced state (e.g. a client that believes it read further than the
 * log actually goes), which must surface rather than silently skip or replay.
 */
class StaleCursorException(
    val sessionId: SessionId,
    val requested: Seq,
    val lastSeq: Seq,
) : IllegalStateException(
    "stale cursor for session '${sessionId.value}': from=${requested.value} is beyond lastSeq+1=${lastSeq.value + 1}",
)

/**
 * The event-sourcing storage seam (Task 7). Downstream layers (daemon, transport) depend only on
 * this interface, never on the concrete backend, so the storage choice (SQLDelight today —
 * [SqliteEventStore] — or a JSONL fallback later) stays swappable. The contract is storage-agnostic:
 * an append-only per-session event log with a monotonic sequence, a session read-model cache kept
 * transactionally consistent with the log, and a restart-safe cursored subscription.
 *
 * Concurrency contract: a single logical writer. Appends are serialized so per-session seqs stay
 * strictly monotonic and contiguous; reads and subscriptions observe only committed state.
 */
interface EventStore {

    /**
     * Insert or fully update a session's [SessionMeta] row (identity + launch/tmux/repo context and
     * the initial cache fields). The daemon owns this metadata; [append] only advances the
     * reducer-derived cache fields of an existing row. On update, `createdAt` is preserved.
     */
    suspend fun upsertSession(meta: SessionMeta)

    /** The session's current metadata row, or `null` if no such session has been upserted. */
    suspend fun getSession(sessionId: SessionId): SessionMeta?

    /** All known sessions, oldest first (by `createdAt`). */
    suspend fun listSessions(): List<SessionMeta>

    /**
     * Append [event] (attributed to [source]) to [sessionId]'s log at the next monotonic per-session
     * seq, and — in the SAME committed transaction — advance the session's read-model cache to the
     * projection obtained by reducing the prior projection with [event]. Returns the assigned [Seq].
     * Serialized against all other appends (single writer).
     */
    suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq

    /** Read [sessionId]'s stored events with `seq >= fromSeq`, in ascending seq order. */
    suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent>

    /**
     * The session's current projection — the reducer read-model. Reconstructed by replaying the log
     * on a cold cache (restart-safe) and cached in memory thereafter, so this equals
     * `io.kotgent.core.replay` over the same events.
     */
    suspend fun projectionOf(sessionId: SessionId): Projection

    /**
     * Subscribe to [sessionId]'s event stream from cursor [fromSeq]: first the already-stored events
     * with `seq >= fromSeq`, then a live stream of subsequent appends, contiguously and in order.
     * A cursor beyond `lastSeq + 1` (or into a gap) fails the flow with [StaleCursorException].
     */
    fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent>
}
