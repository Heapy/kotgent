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

// Claude keys transcripts by cwd and id; Codex and Junie key by id alone.
fun interface VendorStoreProbe {
    suspend fun hasTranscript(agent: String, cwd: String, providerSessionId: ProviderSessionId): Boolean
}

fun byAgentVendorStoreProbe(probes: Map<String, VendorStoreProbe>): VendorStoreProbe =
    VendorStoreProbe { agent, cwd, providerSessionId ->
        // Unknown providers must not be advertised as resumable.
        probes[agent]?.hasTranscript(agent, cwd, providerSessionId) ?: false
    }

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

data class ReconciledSession(
    val sessionId: SessionId,
    val previousState: SessionState,
    val newState: SessionState,
    val paneAlive: Boolean,
)

data class ReconcileResult(
    val sessions: List<ReconciledSession>,
    val livePanes: Map<PaneId, SessionId>,
)

class Reconciler(
    private val tmux: TmuxControl,
    private val store: EventStore,
    private val vendorProbe: VendorStoreProbe,
    private val registry: PaneRegistry,
    private val now: () -> Long = ::daemonEpochMillis,
    private val taskStore: TaskStore? = null,
    private val projectFs: ProjectFs? = null,
) {
    suspend fun reconcile(): ReconcileResult {
        val sessions = store.listSessions()
        // Creation currently guarantees one pane; first-wins keeps future multi-pane reports deterministic.
        val livePaneBySession: Map<String, TmuxPane> =
            tmux.listPanes().filter { !it.dead }
                .groupBy { it.session }
                .mapValues { (_, panes) -> panes.first() }

        val livePanes = LinkedHashMap<PaneId, SessionId>()
        val reconciled = ArrayList<ReconciledSession>(sessions.size)

        for (meta in sessions) {
            val livePane = livePaneBySession[meta.tmuxSession]
            val paneAlive = livePane != null
            // Cached stopped is incarnation-scoped control intent; the event log may describe an older run.
            val stopIntent = meta.state == SessionState.stopped
            val transcriptExists =
                meta.providerSessionId?.let { vendorProbe.hasTranscript(meta.agent, meta.cwd, it) } ?: false

            // Cache state also carries control-only interrupt/resume effects absent from the event log.
            val newState = classify(paneAlive, meta.state, stopIntent, transcriptExists)
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

        // Full-row state upserts must finish before task-reference cleanup, or stale snapshots can restore it.
        reconcileTaskLinks(sessions)

        registry.replaceAll(livePanes)
        return ReconcileResult(reconciled, livePanes)
    }

    private suspend fun reconcileTaskLinks(sessions: List<SessionMeta>) {
        val tasks = taskStore ?: return
        // Task metadata is best-effort at startup; one unreadable project must not prevent daemon startup.
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

    private suspend fun clearDanglingTaskRef(tasks: TaskStore, meta: SessionMeta) {
        val ref = meta.taskRef ?: return
        if (tasks.entry(ref) != null) return
        store.setTaskRef(meta.id, null, sortKeyOf(meta))
    }

    private suspend fun backfillProjectId(tasks: TaskStore, meta: SessionMeta) {
        if (meta.projectId != null) return
        val fs = projectFs ?: return
        val resolved = resolveProject(fs, meta.cwd) ?: return
        // Register first so a failure cannot leave a session referencing an absent project row.
        tasks.upsertProject(resolved.id, resolved.name, resolved.root)
        store.setProjectId(meta.id, resolved.id, sortKeyOf(meta))
    }

    // Re-read after liveness writes; derived cleanup must neither advance nor rewind activity ordering.
    private suspend fun sortKeyOf(meta: SessionMeta): Long =
        store.getSession(meta.id)?.updatedAt ?: meta.updatedAt

    companion object {
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
