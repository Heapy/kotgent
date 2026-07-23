package io.kotgent.core

/**
 * The reducer's read-model (Task 6): the projection of a session's [AgentEvent] log, computed by
 * the pure [reduce] / [replay] functions. It holds exactly what a fold of the log (plus the live
 * [ControlSignal] inputs) can know — nothing that requires IO or a clock — so it is an immutable
 * `data class` and `replay` is restart-safe.
 *
 * The store/daemon caches these fields onto the `sessions` row ([SessionMeta.state],
 * `pendingApprovals` is transient/derived, [SessionMeta.lastSeq], [SessionMeta.providerSessionId]).
 * [SessionMeta.stateSource] is deliberately NOT here: the reducer sees an [AgentEvent], not its
 * `events.source` column, so provenance is stamped by the layer that persists the event.
 *
 * Projection invariant, preserved by every transition: `pendingApprovals > 0` **iff**
 * `state == needs_approval`. Only [AgentEvent.ApprovalRequested] raises the count; every transition
 * into any other lifecycle state resets it to 0.
 */
data class Projection(
    /**
     * Current lifecycle state. The v1 reducer produces only `running / needs_approval / ready`
     * (live) and `stopped / crashed` (dead). `needs_answer` is forward-modeled (waiting logic is
     * approval-only in v1) and `resumable` is a reconciler classification (daemon, Task 13) — the
     * reducer never yields either.
     */
    val state: SessionState,
    /**
     * Outstanding approval requests. Because Claude emits no "permission answered" signal, this is
     * reset to 0 whenever the session (re-)enters `running`; it is `> 0` exactly when
     * `state == needs_approval`.
     */
    val pendingApprovals: Int,
    /**
     * Highest applied event seq (mirrors `sessions.last_seq`). Advances by exactly 1 per applied
     * [AgentEvent]; [ControlSignal]s do NOT advance it (they are not part of the persisted 7-type
     * log). `Seq(0)` means no events applied yet. Unread is derived from this against a read cursor.
     */
    val lastSeq: Seq,
    /** The provider's session id once [AgentEvent.SessionBound] fires; `null` until then. */
    val providerSessionId: ProviderSessionId?,
    /**
     * Clean-termination intent armed by [ControlSignal.Stop]. When set, a subsequent
     * [AgentEvent.Exited] with a non-zero code is classified as an intended `stopped` rather than a
     * `crashed` — the daemon (Task 13, StopMode) uses this to disambiguate an operator stop from a
     * genuine crash, which exit code alone cannot. Consumed (reset) by `Exited` and `Resume`.
     */
    val stopRequested: Boolean,
) {
    init {
        require(pendingApprovals >= 0) { "pendingApprovals must be non-negative, was $pendingApprovals" }
    }

    /** True when the session is blocked waiting on the human (see [SessionState.needsAttention]). */
    val needsAttention: Boolean get() = state.needsAttention

    /**
     * Number of events not yet seen by a client whose read cursor is [readCursor] (held elsewhere,
     * e.g. [SessionMeta.readCursor], since a projection is not per-client). Never negative.
     */
    fun unread(readCursor: Seq): Long = unread(lastSeq.value, readCursor.value)

    /** Whether any event past [readCursor] has been applied. */
    fun hasUnread(readCursor: Seq): Boolean = lastSeq > readCursor

    companion object {
        /**
         * The fold identity / seed for [replay]: a just-created session with no events applied. It
         * is `running` because a session exists in the log only after its agent process was
         * launched (live), matching the `SessionMeta` created-state and the `start -> running`
         * rule; the first lifecycle event overrides it, so an empty seed only surfaces for a
         * session whose log has no state-changing event yet.
         */
        val EMPTY: Projection = Projection(
            state = SessionState.running,
            pendingApprovals = 0,
            lastSeq = Seq(0),
            providerSessionId = null,
            stopRequested = false,
        )
    }
}

/**
 * The number of unread events for a read cursor: `lastSeq - readCursor`, floored at 0. The single
 * definition of "unread"; the store cache signal ([io.kotgent.store.SessionUpdate]) and the transport
 * DTOs use it against a [SessionMeta]'s columns, and [Projection.unread] delegates here.
 */
fun unread(lastSeq: Long, readCursor: Long): Long = (lastSeq - readCursor).coerceAtLeast(0)
