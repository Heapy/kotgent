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
 * [state] (from which the UI derives "needs attention"), the [lastSeq] high-water mark, the
 * [unread] count (events past the session's read cursor), plus the orthogonal [archived]/[model]
 * fields and the row's [rev]. Fuller detail is fetched via `GET /sessions/{id}`. Deliberately small:
 * this is a change *signal*, not a snapshot.
 */
data class SessionUpdate(
    val sessionId: SessionId,
    val state: SessionState,
    val lastSeq: Seq,
    val unread: Long,
    /**
     * Whether the session is archived ("done"). Carried on the live signal so an update for an
     * archived session never reports `archived=false` and un-hides the row in a connected client.
     * Defaults to `false` for the common case (only a live, non-archived session gets appends).
     */
    val archived: Boolean = false,
    /**
     * The row's best-effort discovered model, re-read from the committed row — so the live signal is
     * authoritative for it, `null` included (the provider-id rebind correction clears a suspect model,
     * and that clear must propagate). Defaults to `null` only for hand-built test constructions.
     */
    val model: String? = null,
    /**
     * The row's global monotonic revision (see `Sessions.sq`), always re-read from the committed row.
     * `0` means the update has no persisted `sessions` row (an append can outrun the row's creation);
     * the transport does not forward those. Clients apply a frame only if its rev is newer than the
     * row they hold, which makes HTTP responses and WS frames safely mergeable in any arrival order.
     */
    val rev: Long = 0,
    /**
     * The task this session is linked to, re-read from the committed row (`null` included — an unlink is
     * authoritative). It rides the live signal because the sidebar's task badge is rendered from it: a
     * link written by `kotgent task claim` inside a pane must move the badge without a reload.
     */
    val taskRef: TaskRef? = null,
    /** The session's resolved project, re-read from the committed row. */
    val projectId: ProjectId? = null,
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
     * row does not exist. The emitted signal carries the committed row's model verbatim (`null` included),
     * so the write — or the clear — reaches connected clients on this very emission.
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

    /**
     * Point [sessionId] at [taskRef], or clear the link with `null`, leaving `state` / `last_seq` /
     * `provider_session_id` untouched — the [setArchived] / [setModel] shape. Emits a [sessionUpdates]
     * signal carrying the new ref, without which the sidebar's task badge would only move on a reload.
     * A no-op if the row does not exist.
     *
     * **Unconditional, and that is the design.** A task may be linked from any number of sessions —
     * kotgent cannot enforce "one worker per task", because the operator opens a second terminal in the
     * same repository and the daemon never hears about it, and an invariant that only holds against your
     * own API is not an invariant. Pointing a session at a different task simply overwrites the link;
     * there is no error case and nothing to compensate. The one conditional write in the design lives in
     * the TASK store (`Backlog.sq`'s `startIfTodo`) and is a selection convention, not a protected
     * invariant — see [io.kotgent.store.TaskStore.startIfTodo].
     *
     * **`sessions` has exactly one writer, and it is this store.** `rev` comes from an in-memory counter
     * owned by [SqliteEventStore]; a second store writing this table would fork the counter and emit no
     * update at all. That is why the task store never touches `sessions` and why
     * [io.kotgent.daemon.TaskService] calls the two stores sequentially rather than nesting them.
     *
     * ## Three of the four task-link members are defaulted, and that default THROWS
     * They carry a default body only so the suite's hand-written fake stores — all of which predate the
     * task layer and none of which models a session link — keep compiling untouched; making them
     * abstract would mean editing seven shared test files, which the parallel-execution plan forbids.
     * But the default is [UnsupportedOperationException], **not** a silent no-op: these are the first
     * defaulted WRITES on this interface, and a fake that forgot to override a silent one would let
     * `TaskService.link()` pass its test while persisting nothing — a green test for a feature that does
     * not work. Failing loudly turns that into a one-line fix in the fake that owns the test. Any store
     * a task-linking path actually runs against must override all three. ([clearTaskRefIf] is the fourth
     * member and the one exception: its default is a working two-step composition rather than a throw —
     * see the reasoning there.)
     */
    suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?, updatedAt: Long): Unit =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not model session task links: override setTaskRef",
        )

    /**
     * CLEAR [sessionId]'s link like `setTaskRef(sessionId, null, …)`, but ONLY IF the row still holds
     * [expectedRef] — a check-and-write every real implementation owes atomically, [setModelForProvider]'s
     * shape. Returns whether the row was written; `false` means no such row, or it points at a different
     * task (or at none) now.
     *
     * **Every clear in the design is a read followed by a write, and this is what closes that window.**
     * An explicit `unlink`, a board close, a `delete` and a session's "Done" all learn WHICH ref they are
     * clearing (from the row, or from [sessionsHoldingTask]) and only then write. A link made to a
     * DIFFERENT task inside that window is newer than everything the caller read: erasing it would leave
     * that task `in_progress` with no terminal behind it, and make the activity feed claim an `unlinked`
     * from the old ref for a write that actually destroyed the new one. So the check rides in the
     * statement's `WHERE` ([SqliteEventStore]) and the caller appends its `unlinked` row only on `true`.
     *
     * **This is not exclusivity returning.** Linking stays unconditional ([setTaskRef]) and a task may
     * still be held by any number of sessions; what is refused is erasing a link the caller never read,
     * which is a lost-update rule, not an occupancy rule. `false` is normal — the ordinary shape of
     * "somebody re-pointed this session while I was working" — and never an error to report upward.
     *
     * Emits a [sessionUpdates] signal only when the write applied.
     *
     * ## The defaulted body is a NON-ATOMIC fallback, and only a single-threaded fake may keep it
     * Unlike its three neighbours, this one's default is not an [UnsupportedOperationException]: it is
     * [getSession] followed by [setTaskRef], composed from members every store already has. That keeps
     * the suite's hand-written fakes — which predate the task layer and are shared across files this
     * change may not touch — working with the semantics they had, because for a fake with one caller the
     * two steps cannot be interleaved and the answer is exact.
     *
     * It is deliberately NOT the hazard the throwing defaults exist to prevent (a write that silently
     * persists nothing): this one really does check and really does clear. What it lacks is atomicity.
     * **Any store with concurrent writers MUST override it** and make the check and the write one
     * indivisible operation — [SqliteEventStore] carries the check in the statement's `WHERE`, under the
     * single-writer mutex — or the very window this member exists to close is open again inside its own
     * implementation.
     */
    suspend fun clearTaskRefIf(sessionId: SessionId, expectedRef: TaskRef, updatedAt: Long): Boolean {
        if (getSession(sessionId)?.taskRef != expectedRef) return false
        setTaskRef(sessionId, null, updatedAt)
        return true
    }

    /**
     * Set (or clear) the session's resolved project, with the same targeted-write and emission contract
     * as [setTaskRef]. A no-op if the row does not exist. Defaulted — and the default throws — for the
     * reason on [setTaskRef].
     *
     * ## Three writers reach `sessions.project_id`, and only two of them come through here
     *  1. `start` / `import` (`SessionManager.resolveAndRegisterProject`) INSERT the row already carrying
     *     the id, so the column is written by the insert itself — never observable without its project,
     *     and no second targeted write or second `SessionUpdate`.
     *  2. Startup reconciliation (`Reconciler.backfillProjectId`) patches rows that already exist and
     *     still name no project — the repair path, which is also what makes 1's write-both-or-neither
     *     failure mode self-healing.
     *  3. `POST /tasks` (`TaskWriteRoutes`' `bindSessionProject`) binds the project a create had to
     *     resolve from the filesystem onto the CALLING session's row.
     *
     * ## Why the third exists — do not remove it
     * `sessions.project_id` is what a ref-less `GET /tasks` and `POST /tasks/next` resolve a request
     * through, so while 2 was this setter's only production caller the session that BOOTSTRAPPED a project
     * — the one that filed the first card in a repository with no `.kotgent.json` — held a null column
     * until the daemon next restarted, and could not then run `task list` / `task next` / `task show`
     * without an explicit `--project` uuid. That is the whole ref-less agent loop, failing in exactly the
     * session that just created the project; it was the review's highest-severity finding. A create that
     * names its project explicitly deliberately does NOT bind: that is the board relaying someone else's
     * backlog, and it must not re-point the session that relayed it.
     */
    suspend fun setProjectId(sessionId: SessionId, projectId: ProjectId?, updatedAt: Long): Unit =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not model session projects: override setProjectId",
        )

    /**
     * Every session currently linked to [taskRef], oldest first. A list, not an optional: linking is
     * many-sessions-to-one-task by design (see [setTaskRef]). This is what `transition(done)` and
     * `delete` iterate to unlink every holder, and what a task's detail view renders. Defaulted — and
     * the default throws — for the reason on [setTaskRef]: an empty list here would read as "nothing is
     * linked", so `transition(done)` and `delete` would silently unlink nobody and still report success.
     */
    suspend fun sessionsHoldingTask(taskRef: TaskRef): List<SessionMeta> =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not model session task links: override sessionsHoldingTask",
        )

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
     * [decision] The per-session restart-safe cursor lives on [subscribe] (seq is per-session, Task 7).
     * This flow itself stays resumption-cursor-less — a late subscriber re-baselines from [listSessions],
     * never replays — but every update carries the row's [SessionUpdate.rev], a global monotonic per-row
     * revision, so a consumer can apply updates idempotently (newest-rev-wins) however they interleave
     * with snapshot or HTTP reads. The rev orders observations of a row; it does not resume this flow.
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
