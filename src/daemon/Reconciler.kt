package io.kotgent.daemon

import io.kotgent.cli.eprintln
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.store.EventStore
import io.kotgent.store.TaskStore
import io.kotgent.task.ProjectFs
import io.kotgent.task.resolveProject
import io.kotgent.tmux.TmuxControl
import io.kotgent.tmux.TmuxPane
import kotlinx.coroutines.CancellationException

/**
 * Probes an agent's vendor store for a session's transcript — whether the provider still holds the
 * conversation launched in [cwd] with [providerSessionId], which is what makes a dead session
 * *resumable*. Injected so the [Reconciler] stays host-free and unit-testable with a fake.
 *
 * All three keys are part of the question because providers disagree on what identifies a transcript:
 * Claude namespaces by project directory (`~/.claude/projects/<encoded-cwd>/<id>.jsonl`, so it needs
 * [cwd]), while Codex names its rollout by id alone
 * (`~/.codex/sessions/<date>/rollout-<ts>-<id>.jsonl`) and Junie its session directory by id alone
 * (`~/.junie/sessions/<id>/`), so both ignore [cwd] — and [agent] is what selects between them. The real
 * probes are [claudeVendorStoreProbe], [codexVendorStoreProbe] and [junieVendorStoreProbe], dispatched by
 * [byAgentVendorStoreProbe].
 */
fun interface VendorStoreProbe {
    suspend fun hasTranscript(agent: String, cwd: String, providerSessionId: ProviderSessionId): Boolean
}

/**
 * Dispatch to the per-provider probe registered for a session's [SessionMeta.agent]. An agent kind with
 * no registered probe answers `false` — "no transcript known" — which classifies its dead sessions as
 * `crashed` rather than offering a resume that would fail. That is the honest answer for a session row
 * whose provider this daemon build cannot inspect (e.g. a kind removed in a later version).
 */
fun byAgentVendorStoreProbe(probes: Map<String, VendorStoreProbe>): VendorStoreProbe =
    VendorStoreProbe { agent, cwd, providerSessionId ->
        probes[agent]?.hasTranscript(agent, cwd, providerSessionId) ?: false
    }

/**
 * The PRODUCTION probe dispatch — one real per-provider probe per supported agent kind, rooted at the
 * real vendor homes by default. The daemon bootstrap passes this ONE instance to both the [Reconciler]
 * and [SessionManager.importSession], so an import is validated with exactly the `(agent, cwd, id)`
 * question every later daemon start re-asks. The dirs are injectable so the import wiring test drives
 * this same function over throwaway homes — the guard against "the tests ran real probes while
 * production shipped a stub" (the Task-15 bug recorded in the ClaudeVendorStoreProbe.kt header).
 */
fun productionVendorStoreProbe(
    claudeDir: String = defaultClaudeDir(),
    codexDir: String = defaultCodexDir(),
    junieDir: String = defaultJunieDir(),
): VendorStoreProbe = byAgentVendorStoreProbe(
    mapOf(
        CLAUDE_AGENT_KIND to claudeVendorStoreProbe(claudeDir),
        CODEX_AGENT_KIND to codexVendorStoreProbe(codexDir),
        JUNIE_AGENT_KIND to junieVendorStoreProbe(junieDir),
        SHELL_AGENT_KIND to shellVendorStoreProbe(),
    ),
)

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
 *  - **clean-stop intent** — was it cleanly stopped? (the cache-authoritative `stopped`, which is
 *    incarnation-scoped — see the note in [reconcile])
 *
 * and classifies (see [classify]): alive → running (keeping a finer live state if the log has one);
 * dead + clean-stop → `stopped`; dead + transcript → `resumable`; dead + neither → `crashed`. Changed
 * classifications (and refreshed `pane_id` correlations) are written to the sessions cache with source
 * [EventSource.liveness]. The registry is replaced with the live pane→session map.
 *
 * When a task layer is wired ([taskStore] + [projectFs]) it then runs [reconcileTaskLinks] — the project
 * backfill and the dangling-link clear, and nothing else about tasks; see that method.
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
    /**
     * The task layer, or `null` for a daemon (or a test) without one. Startup reconciliation uses it to
     * BACKFILL `sessions.project_id` and to clear a `sessions.task_ref` naming a task no longer in
     * `backlog_entries` (a reference, not a foreign key — see `Sessions.sq`).
     *
     * **Nothing else about tasks is reconciled.** An `in_progress` entry with no linked session is
     * legitimate — a human dragged the card — so there is nothing to recover, and a pass that "fixed" it
     * could not tell its target from that card.
     *
     * Nullable with a null DEFAULT, appended after [now], so `ReconcilerTest`, `ImportWiringTest`,
     * `SessionImportTest` and `ShutdownSignalsTest` keep compiling untouched.
     */
    private val taskStore: TaskStore? = null,
    /** Filesystem access for the `project_id` backfill, or `null` to skip it. Same rationale as [taskStore]. */
    private val projectFs: ProjectFs? = null,
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
            // Stop intent is INCARNATION-scoped, and only the sessions cache can express that. The cache is
            // the control-authoritative lifecycle: it advances inside every append transaction AND on every
            // control op / earlier reconcile, so a cached `stopped` is the CURRENT stop intent. The event
            // log is not — a `stopped` there was produced by an `Exited` of a possibly PREVIOUS incarnation,
            // and `Resume` is a control signal that is never logged, so after resume-then-die that historical
            // `stopped` would masquerade as a fresh operator stop and mask the true `resumable` / `crashed`.
            // (Same reason `projection.stopRequested` is not consulted: ControlSignal.Stop is not persisted,
            // so a replay can never observe it.)
            val stopIntent = meta.state == SessionState.stopped
            val transcriptExists =
                meta.providerSessionId?.let { vendorProbe.hasTranscript(meta.agent, meta.cwd, it) } ?: false

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

        // AFTER every state write, never interleaved with them: the loop above upserts a FULL row built
        // from the meta it read, and `upsert` COALESCEs `task_ref` / `project_id` (Sessions.sq) — so a
        // clear written first would be resurrected by the stale snapshot that follows it.
        reconcileTaskLinks(sessions)

        registry.replaceAll(livePanes)
        return ReconcileResult(reconciled, livePanes)
    }

    /**
     * The task layer's half of a reconcile, and deliberately only two things:
     *
     *  - **backfill `sessions.project_id`** for a row that has none — a session started before the backlog
     *    existed, one whose `.kotgent.json` was committed after it launched, or one whose registration lost
     *    a store failure (see `SessionManager.resolveAndRegisterProject`, which answers `null` so that this
     *    pass is what retries it);
     *  - **clear a `sessions.task_ref` naming a task no longer in `backlog_entries`**. The column is a
     *    REFERENCE, not a foreign key: `TaskService.delete` unlinks every holder first, so the ordinary
     *    case never reaches here, and this closes the racing one rather than making the delete atomic
     *    across two stores.
     *
     * **Nothing else about tasks is reconciled.** An `in_progress` entry with no linked session is
     * legitimate — a human dragged the card, or the worker session was archived — so there is nothing to
     * recover, and a pass that "fixed" it could not tell its target from that card.
     *
     * Per session, and per row, a failure is logged and the pass CONTINUES: this runs before the daemon
     * binds its server ([io.kotgent.cli.Commands]), so letting one unreadable directory or one store error
     * escape would turn a cosmetic backfill into a daemon that does not start.
     */
    private suspend fun reconcileTaskLinks(sessions: List<SessionMeta>) {
        val tasks = taskStore ?: return
        for (meta in sessions) {
            try {
                clearDanglingTaskRef(tasks, meta)
                backfillProjectId(tasks, meta)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                eprintln("warning: task reconciliation failed for session '${meta.id.value}': $e")
            }
        }
    }

    /** Drop a link whose task is gone from `backlog_entries`; a link that still resolves is left alone. */
    private suspend fun clearDanglingTaskRef(tasks: TaskStore, meta: SessionMeta) {
        val ref = meta.taskRef ?: return
        if (tasks.entry(ref) != null) return
        store.setTaskRef(meta.id, null, sortKeyOf(meta))
    }

    /**
     * Resolve [meta]'s cwd and persist the answer, registering the `projects` row FIRST — the same
     * write-both-or-neither order `SessionManager` uses, so a failure between the two leaves `project_id`
     * null and the next daemon start simply tries again.
     *
     * A row that already names a project is left alone: re-resolving every session on every start would
     * walk the filesystem once per row for an answer that only a moved `.kotgent.json` could change, and
     * would silently re-point a session whose directory has since been adopted by a nearer project.
     */
    private suspend fun backfillProjectId(tasks: TaskStore, meta: SessionMeta) {
        if (meta.projectId != null) return
        val fs = projectFs ?: return
        val resolved = resolveProject(fs, meta.cwd) ?: return
        tasks.upsertProject(resolved.id, resolved.name, resolved.root)
        store.setProjectId(meta.id, resolved.id, sortKeyOf(meta))
    }

    /**
     * The `updated_at` both task-pass writes must carry: the row's CURRENT one, never [now].
     *
     * `updated_at` is **activity** — it is what `kotgent list` sorts by (`Commands.kt`), and the task
     * store writes the same rule down for itself (`Backlog.sq`'s `restamp` stamps a fresh `rev` and
     * deliberately leaves `updated_at` alone, "the same reason `setReadCursor` leaves it alone in
     * `Sessions.sq`"). Neither of this pass's writes is activity: one is a derived backfill, the other is
     * garbage collection of a dangling reference. Passing [now] made the first daemon start after a
     * `.kotgent.json` was committed re-stamp EVERY session under that repository — archived ones included
     * — collapsing the whole list into one restart timestamp, once and permanently.
     *
     * It is RE-READ rather than taken from [meta]: the state loop above may already have stamped a fresh
     * `updated_at` for this same row (a real liveness change, which IS activity), and reusing the stale
     * snapshot would roll that back. So the pass neither advances nor rewinds the sort key. The read
     * happens only on the write path, i.e. for the rare row that actually needs one; a row that vanished
     * between the two passes falls back to the snapshot, and its write is a no-op anyway.
     */
    private suspend fun sortKeyOf(meta: SessionMeta): Long =
        store.getSession(meta.id)?.updatedAt ?: meta.updatedAt

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
