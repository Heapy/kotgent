package io.kotgent.store

import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.Projection
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

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
 * A session read-model cache change notification (Task 14 events-WS). The store emits one whenever a
 * session's cache row changes — after an [EventStore.append] (a new event advanced state / last_seq), an
 * [EventStore.upsertSession] (the daemon wrote a derived state, or created the row), or any of the
 * targeted mutators ([EventStore.updateSessionState], [EventStore.setArchived], [EventStore.setModel],
 * [EventStore.markRead]). The transport `/events` WebSocket fans these out to browsers so the session list
 * and the "needs attention" queue stay live without polling.
 *
 * Carries the minimum a live UI needs to update a row in place — which [sessionId] changed, its new
 * [state] (from which the UI derives "needs attention"), the [lastSeq] high-water mark, and the
 * [unread] count (events past the session's read cursor). Fuller detail is fetched via
 * `GET /sessions/{id}`. Deliberately small: this is a change *signal*, not a snapshot.
 */
data class SessionUpdate(
    val sessionId: SessionId,
    val state: SessionState,
    val lastSeq: Seq,
    val unread: Long,
    /**
     * Whether the session is archived ("done"). Carried on the live signal — not only the snapshot DTO —
     * so the live and the periodic-resync `/events` messages agree: otherwise a live update for an
     * archived session would report `archived=false` and un-hide it in another client until the next
     * resync. Defaults to `false` for the common case (only a live, non-archived session gets appends).
     */
    val archived: Boolean = false,
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
     * Insert or update a session's [SessionMeta] row (identity + launch/tmux/repo context and the
     * initial cache fields). The daemon owns this metadata; [append] only advances the
     * reducer-derived cache fields of an existing row. On update, `createdAt` is preserved.
     *
     * "Update" is full-row with one exception: `read_cursor` is **max-merged**
     * (`MAX(existing, incoming)`), never overwritten, and the emitted [sessionUpdates] signal is built
     * from the stored row read back after the write rather than from the passed-in [meta] — otherwise a
     * caller holding a stale cursor would regress the unread badge, or leave the row right and the
     * broadcast wrong. **Defence in depth, not a live race** — no caller can produce a stale cursor today;
     * `Sessions.sq`'s `upsert` records which callers, and why that could change.
     */
    suspend fun upsertSession(meta: SessionMeta)

    /**
     * Write a daemon-derived control state (a stop/interrupt/resume effect, or a reconciler
     * reclassification) onto an existing session row — updating ONLY [state], [stateSource],
     * [paneId] and `updated_at`, atomically under the writer lock. It deliberately does NOT rewrite
     * `last_seq` or `provider_session_id`: those are advanced by [append] (hook-driven), so a
     * full-row [upsertSession] of a stale [SessionMeta] would clobber a concurrent append. Emits a
     * [sessionUpdates] signal like the other mutators. A no-op if the row does not exist.
     */
    suspend fun updateSessionState(
        sessionId: SessionId,
        state: SessionState,
        stateSource: EventSource,
        paneId: PaneId?,
        updatedAt: Long,
    )

    /**
     * Set the orthogonal `archived` ("done") flag on an existing session row (and its `updated_at`),
     * leaving `state` / `last_seq` / `provider_session_id` untouched. Emits a [sessionUpdates] signal
     * carrying the new `archived` so clients hide/show the row live. A no-op if the row does not exist.
     */
    suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long)

    /**
     * Set the best-effort discovered `model` on an existing session row (and its `updated_at`), leaving
     * `state` / `last_seq` / `provider_session_id` untouched. Written by the model-capture seams (Claude
     * transcript / Codex rollout) whenever a capture — or a RE-capture — lands, so it is repeatable, not
     * once-per-session; a `null` [model] CLEARS the field — the hook ingress' provider-id rebind
     * correction uses it, because a model captured under a provider id that a hook `SessionBound` later
     * displaced may belong to a different session (the re-run capture then writes again). A no-op if the
     * row does not exist. The model reaches clients via the `/events` snapshot/resync frames (which carry
     * it verbatim), so this emits an ordinary signal.
     *
     * A capture whose lookup was keyed by a provider id must use [setModelForProvider] instead, so a
     * rebind racing the capture cannot be overwritten; this unconditional form remains for the two
     * callers with nothing to condition on — the rebind clear itself, and the Claude transcript capture
     * (Claude preallocates its id, so no displacement exists there).
     */
    suspend fun setModel(sessionId: SessionId, model: String?, updatedAt: Long)

    /**
     * Set the discovered `model` like [setModel], but ONLY IF the session row still holds
     * [providerSessionId] — the id the caller's lookup was keyed by — as an ATOMIC check-and-write.
     * Returns whether the row was written; `false` means no such row, or its provider id is no longer
     * [providerSessionId] (never bound, cleared, or displaced by a hook rebind).
     *
     * This is what keeps an in-flight codex model capture from mislabeling a session: the capture reads
     * the row's id, scans the rollout tree (slow), and only then writes — a hook `SessionBound` can
     * displace the id (and the rebind correction clear the model) inside that window, and an
     * unconditional write would race past the clear and restore the displaced id's (possibly a same-cwd
     * neighbour's) model. Conditioned on the id, the raced write touches zero rows and the caller's
     * `false` keeps its retry loop polling under the row's now-authoritative id. Implementations must
     * make the check and the write atomic under the single-writer contract ([SqliteEventStore] carries
     * the check in the statement's `WHERE`); emits a [sessionUpdates] signal only when the write applied.
     */
    suspend fun setModelForProvider(
        sessionId: SessionId,
        providerSessionId: ProviderSessionId,
        model: String,
        updatedAt: Long,
    ): Boolean

    /**
     * Advance the session's read cursor to [seq] — the "I have seen through seq N" mark the Web UI
     * posts for the session it is currently displaying — leaving `state` / `last_seq` /
     * `provider_session_id` untouched, exactly like [setArchived] and [setModel].
     *
     * The write is **monotonic and clamped**, and every implementation owes both: it never regresses the
     * cursor (a stale, out-of-order or retried mark-read cannot make a cleared badge count again) and never
     * advances it past `lastSeq` (a bogus [seq] cannot silence the badge forever). It takes **no
     * `updatedAt`** on purpose — this write must not touch `updated_at`, because viewing a session is not
     * activity and `kotgent list` sorts by that column.
     *
     * Emits a [sessionUpdates] signal **unconditionally** — even when the MAX/MIN made the write a no-op:
     * the emit is the resync path for a client whose earlier POST was lost. A no-op if the row does not
     * exist.
     */
    suspend fun markRead(sessionId: SessionId, seq: Seq)

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

    /**
     * A hot, shared stream of [SessionUpdate]s — one per [append], per [upsertSession] and per targeted
     * mutator ([updateSessionState], [setArchived], [setModel], and [markRead], which emits even when its
     * write was a no-op) — across ALL sessions (the store is where both event appends and daemon
     * control-op writes funnel, so it is the one place that observes every cache change, including
     * hook-driven appends that never pass through the daemon). Hot and non-replaying: a late subscriber
     * sees no history, so the transport `/events` WS pairs this with a [listSessions] snapshot to
     * establish a baseline and then streams subsequent changes. Buffered, so a burst of appends is not
     * lost if a subscriber briefly lags.
     *
     * [decision] The per-session restart-safe cursor lives on [subscribe] (seq is per-session, Task 7); a
     * global cursor over this cross-session signal is not meaningful, so it is intentionally cursor-less.
     */
    val sessionUpdates: SharedFlow<SessionUpdate>

    /**
     * The ordered, lossless-while-subscribed form of [sessionUpdates] for consumers whose correctness
     * depends on seeing every intermediate state.
     *
     * Unlike the UI-oriented signal above, a production implementation may backpressure writers briefly
     * rather than discard an update when this consumer lags. It is still hot and non-replaying: with no
     * subscriber, startup history is represented by [listSessions] instead of being retained in memory.
     *
     * The default preserves compatibility for simple/fake stores. Any implementation whose
     * [sessionUpdates] can drop values must override this with a reliable signal.
     */
    val reliableSessionUpdates: SharedFlow<SessionUpdate>
        get() = sessionUpdates
}
