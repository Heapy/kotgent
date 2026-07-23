package io.kotgent.daemon

import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionState
import io.kotgent.store.EventStore
import io.kotgent.tmux.TmuxControl
import io.kotgent.tmux.TmuxPane

/**
 * Probes the agent's vendor store for a session's transcript — for Claude, whether `~/.claude/...`
 * holds a transcript for the session launched in [cwd] with [providerSessionId], which makes a dead
 * session *resumable*. Both keys are needed because Claude namespaces transcripts by project directory
 * (`~/.claude/projects/<encoded-cwd>/<provider-session-id>.jsonl`). Injected so the [Reconciler] stays
 * host-free and unit-testable with a fake; the real one is [claudeVendorStoreProbe] (stats the path).
 */
fun interface VendorStoreProbe {
    suspend fun hasTranscript(cwd: String, providerSessionId: ProviderSessionId): Boolean
}

/** One session's reconciliation outcome: how it was reclassified and whether its tmux pane is alive. */
data class ReconciledSession(
    val sessionId: SessionId,
    val previousState: SessionState,
    val newState: SessionState,
    val paneAlive: Boolean,
)

/** The result of a full reconciliation pass: per-session outcomes + the rebuilt live pane→session map. */
data class ReconcileResult(
    val sessions: List<ReconciledSession>,
    val livePanes: Map<PaneId, SessionId>,
)

/**
 * Daemon-start reconciliation (plan Task 13): reconcile the persisted `sessions` against tmux reality
 * and the agent vendor store, and rebuild the [PaneRegistry] from the panes tmux actually reports.
 *
 * For each stored session it probes:
 *  - **tmux liveness** — is there a live (non-dead) pane for `kt-<id>`? (from [TmuxControl.listPanes])
 *  - **vendor store** — does the session's transcript survive? (from [vendorProbe]) → *resumable*
 *  - **clean-stop intent** — was it cleanly stopped? (cached `stopped`, or a `stopped` projection)
 *
 * and classifies (see [classify]): alive → running (keeping a finer live state if the log has one);
 * dead + clean-stop → `stopped`; dead + transcript → `resumable`; dead + neither → `crashed`. Changed
 * classifications (and refreshed `pane_id` correlations) are written to the sessions cache with source
 * [EventSource.liveness]. The registry is replaced with the live pane→session map.
 *
 * It deliberately does NOT re-raise terminal bridges: those are lazy and restored on the first
 * terminal-WS subscribe (Task 9). Host-free: [tmux] / [store] / [vendorProbe] / [registry] are all
 * injected, so the whole pass runs against fakes + an in-memory store with no real host.
 */
class Reconciler(
    private val tmux: TmuxControl,
    private val store: EventStore,
    private val vendorProbe: VendorStoreProbe,
    private val registry: PaneRegistry,
    private val now: () -> Long = ::daemonEpochMillis,
) {
    suspend fun reconcile(): ReconcileResult {
        val sessions = store.listSessions()
        // Live (non-dead) panes indexed by their owning session name (`kt-<id>`). v1 creates exactly one
        // pane per `kt-<id>` session, so this is single-pane in practice; if a session ever reported
        // multiple panes we keep the FIRST deterministically (list-panes order is stable) rather than an
        // arbitrary last-wins, so hook routing stays predictable. [decision] one-pane-per-session is a v1
        // invariant of the create path; a defensive first-wins guards a future multi-pane session.
        val livePaneBySession: Map<String, TmuxPane> =
            tmux.listPanes().filter { !it.dead }
                .groupBy { it.session }
                .mapValues { (_, panes) -> panes.first() }

        val livePanes = LinkedHashMap<PaneId, SessionId>()
        val reconciled = ArrayList<ReconciledSession>(sessions.size)

        for (meta in sessions) {
            val livePane = livePaneBySession[meta.tmuxSession]
            val paneAlive = livePane != null
            val projection = store.projectionOf(meta.id)
            val stopIntent = meta.state == SessionState.stopped ||
                projection.state == SessionState.stopped ||
                projection.stopRequested
            val transcriptExists = meta.providerSessionId?.let { vendorProbe.hasTranscript(meta.cwd, it) } ?: false

            // Classify a LIVE session from the CACHE-authoritative state (meta.state), not the pure
            // event-log projection: the cache carries control effects (interrupt/resume) that are NOT in
            // the log, so replaying the log would lose an unpersisted interrupt and could resurrect a
            // stale `needs_approval`. tmux liveness only moves alive↔dead. (Consistent with the store's
            // cache-state authority — see SqliteEventStore.append.)
            val newState = classify(paneAlive, meta.state, stopIntent, transcriptExists)
            // Rebuild the pane_id correlation from the live pane (tmux is authoritative); keep the stored
            // one when the session is not currently live.
            val newPaneId = livePane?.paneId ?: meta.paneId

            if (newState != meta.state || newPaneId != meta.paneId) {
                store.upsertSession(
                    meta.copy(
                        state = newState,
                        paneId = newPaneId,
                        stateSource = EventSource.liveness,
                        updatedAt = now(),
                    ),
                )
            }
            if (livePane != null) livePanes[livePane.paneId] = meta.id
            reconciled.add(ReconciledSession(meta.id, meta.state, newState, paneAlive))
        }

        registry.replaceAll(livePanes)
        return ReconcileResult(reconciled, livePanes)
    }

    companion object {
        /**
         * Pure classification (host-free, exhaustively testable):
         *  - `paneAlive` → the process is live: keep the current live state ([currentState], the
         *    cache-authoritative session state) if it is itself a live state, else `running` (a stale
         *    dead classification is corrected up to live).
         *  - dead + `stopIntent` → `stopped` (an intended, clean termination — wins over a surviving
         *    transcript; the operator chose to stop it).
         *  - dead + `transcriptExists` → `resumable` (the conversation survives and can be revived —
         *    this is what turns even an abnormal exit into a resume rather than a dead-end `crashed`).
         *  - dead + neither → `crashed` (lost with nothing to resume, e.g. no provider id / no transcript).
         */
        fun classify(
            paneAlive: Boolean,
            currentState: SessionState,
            stopIntent: Boolean,
            transcriptExists: Boolean,
        ): SessionState = when {
            paneAlive -> if (currentState.isAlive) currentState else SessionState.running
            stopIntent -> SessionState.stopped
            transcriptExists -> SessionState.resumable
            else -> SessionState.crashed
        }
    }
}
