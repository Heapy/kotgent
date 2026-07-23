package io.kotgent.daemon

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.core.AgentEvent
import io.kotgent.core.ControlSignal
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.reduce
import io.kotgent.store.EventStore
import io.kotgent.tmux.TmuxControl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * How to stop a session — the `StopMode` spectrum from the plan (`Detach ≠ Kill`). Only [Interrupt]
 * leaves the agent alive to un-stick it; [Detach] leaves it entirely untouched (a transport concern);
 * the three termination modes tear the tmux session down.
 *
 * [decision] For the v1 slice the three termination modes ([Graceful]/[Terminate]/[Kill]) all collapse
 * to a single clean `tmux kill-session` — graduated signal escalation (ask-nicely → SIGTERM → SIGKILL)
 * is backlog. They are kept distinct in the enum so the transport/CLI vocabulary is stable and the
 * escalation can be filled in later without an API change.
 */
enum class StopMode {
    /** Client disconnect; the agent lives on. No-op at this layer (see [SessionManager.detach]). */
    Detach,

    /** Ctrl-C to un-stick a stuck `running`; the agent lives on. */
    Interrupt,

    /** Ask the agent to exit cleanly, then tear the session down (v1: `kill-session`). */
    Graceful,

    /** Terminate the agent (v1: `kill-session`). */
    Terminate,

    /** Force-kill the agent / session (v1: `kill-session`). */
    Kill,
}

/**
 * Builds the [AgentAdapter] used to launch/observe a session of a given agent kind in a given cwd.
 * Injected so [SessionManager] stays decoupled from any concrete provider (`ClaudeAdapter`) and is
 * unit-testable with a stub. The daemon bootstrap supplies the real factory (constructing a
 * `ClaudeAdapter` with the generated hook-settings path + the store-backed event flow).
 */
fun interface AgentFactory {
    fun create(agentKind: String, cwd: String): AgentAdapter
}

/**
 * In-memory `pane_id → SessionId` map (plan Task 13). This IS the `paneLookup` the hook ingress
 * ([io.kotgent.transport.claudeHookRoutes], Task 12) needs to correlate a `$TMUX_PANE` callback back
 * to a session. It is rebuilt from the store on daemon start ([SessionManager.rebuildRegistryFromStore])
 * and, authoritatively, from live tmux panes by the [Reconciler]; individual [register]/[unregister]
 * calls keep it current as sessions start and stop. Guarded by a [Mutex] so concurrent hook lookups and
 * daemon mutations stay consistent.
 */
class PaneRegistry {
    private val mutex = Mutex()
    private val map = HashMap<PaneId, SessionId>()

    suspend fun register(pane: PaneId, session: SessionId): Unit = mutex.withLock { map[pane] = session }

    suspend fun unregister(pane: PaneId) {
        mutex.withLock { map.remove(pane) }
    }

    /** The pane→session lookup — the shape [io.kotgent.transport.claudeHookRoutes] consumes. */
    suspend fun lookup(pane: PaneId): SessionId? = mutex.withLock { map[pane] }

    /** Atomically replace the whole table (a rebuild from the store / from live panes). */
    suspend fun replaceAll(entries: Map<PaneId, SessionId>): Unit = mutex.withLock {
        map.clear()
        map.putAll(entries)
    }

    suspend fun snapshot(): Map<PaneId, SessionId> = mutex.withLock { HashMap(map) }

    suspend fun size(): Int = mutex.withLock { map.size }
}

/** Thrown when an operation targets a session id that was never upserted. */
class NoSuchSessionException(val sessionId: SessionId) :
    NoSuchElementException("no such session: ${sessionId.value}")

/** Thrown when [SessionManager.resume] is asked to revive a session whose provider id is still pending. */
class ResumeBlockedException(val sessionId: SessionId) :
    IllegalStateException("resume blocked: provider session id is pending for ${sessionId.value}")

/**
 * The daemon session manager (plan Task 13) — wires the pieces built so far into the create/stop/
 * resume/interrupt lifecycle over `tmux` + the event store.
 *
 * Identity: a session gets a short [SessionId] and the tmux session name `kt-<id>` ([TmuxControl.sessionName]);
 * the runtime correlation handle is the [PaneId] tmux returns from `new-session`, registered into
 * [registry] so hooks can be routed back.
 *
 * ## Control signals are not persisted events
 * [ControlSignal]s (Stop/Interrupt/Resume/Detach) are NOT part of the 7-type append-only log (a Task 6
 * decision). [SessionManager] therefore applies them to the *in-memory* projection (via [reduce]) and
 * writes the resulting derived state straight to the sessions cache with [persistDerivedState]. This is
 * fine because control effects are re-derived from tmux reality on restart: the [Reconciler] reclassifies
 * every session from whether its pane is still alive plus the cached state, so a control effect that is
 * missing from the event log is reconstructed anyway. (The v1 Claude slice has no `Exited` hook, so a
 * clean kill's `stopped` classification is likewise cached here and re-derived on restart.)
 *
 * ## The terminal bridge is NOT started here
 * Per the Solution Overview / Task 9, the [io.kotgent.pty.TerminalBridge] is lazy — created on the first
 * terminal-WS subscriber and torn down on the last. [start] deliberately does not spawn it.
 */
class SessionManager(
    private val tmux: TmuxControl,
    private val store: EventStore,
    /** Exposed (plan: "expose it") — this is the pane lookup the hook ingress consumes. */
    val registry: PaneRegistry,
    private val agentFactory: AgentFactory,
    private val idCapture: ProviderIdCapture,
    private val newSessionId: () -> SessionId = { SessionId(randomShortId()) },
    private val now: () -> Long = ::daemonEpochMillis,
    private val cols: Int = DEFAULT_COLS,
    private val rows: Int = DEFAULT_ROWS,
) {
    /** Convenience view of [registry] as the `paneLookup` shape [io.kotgent.transport.claudeHookRoutes] takes. */
    val paneLookup: suspend (PaneId) -> SessionId? get() = registry::lookup

    /**
     * Rebuild [registry] from the store's sessions on daemon start (plan Task 13). Registers the pane of
     * every session that still holds one and is in a live state; the [Reconciler] then refines this to
     * the authoritative live-pane set (pruning panes tmux no longer reports).
     */
    suspend fun rebuildRegistryFromStore() {
        val entries = store.listSessions()
            .filter { it.paneId != null && it.state.isAlive }
            .associate { it.paneId!! to it.id }
        registry.replaceAll(entries)
    }

    /**
     * Start a new agent session: ask the adapter how to launch it, create the tmux session, upsert the
     * `sessions` row, register the pane, and guarantee the provider id gets captured. Returns the stored
     * [SessionMeta]. Does NOT spawn the terminal bridge (lazy — see the class KDoc).
     */
    suspend fun start(
        agentKind: String,
        cwd: String,
        name: String? = null,
        tags: List<String> = emptyList(),
    ): SessionMeta {
        val sessionId = newSessionId()
        val shortId = sessionId.value
        val tmuxSession = tmux.sessionName(shortId)
        val adapter = agentFactory.create(agentKind, cwd)
        val spec = adapter.buildLaunchSpec(LaunchMode.New)
        val paneId = tmux.newSession(shortId, cwd, shellCommand(spec.command), cols, rows)

        val ts = now()
        val meta = SessionMeta(
            id = sessionId,
            name = name ?: tmuxSession,
            tags = tags,
            agent = agentKind,
            providerSessionId = spec.preallocatedSessionId,
            cwd = cwd,
            tmuxSession = tmuxSession,
            paneId = paneId,
            state = SessionState.running,
            stateSource = EventSource.system,
            createdAt = ts,
            updatedAt = ts,
        )
        store.upsertSession(meta)
        registry.register(paneId, sessionId)

        val prealloc = spec.preallocatedSessionId
        if (prealloc != null) {
            // Primary path: the id is known up front — bind it in the log immediately.
            idCapture.bind(sessionId, prealloc)
        } else {
            // Fallback path (older claude): no id yet. Poll for the SessionStart hook to deliver it in
            // the background; until it does the session is "id pending" (provider_session_id null →
            // resume blocked). Bounded, fire-and-forget.
            idCapture.captureInBackground(sessionId) { store.projectionOf(sessionId).providerSessionId }
        }
        return store.getSession(sessionId) ?: meta
    }

    /** Stop [sessionId] according to [mode] (defaults to a full [StopMode.Kill]). */
    suspend fun stop(sessionId: SessionId, mode: StopMode = StopMode.Kill) {
        when (mode) {
            StopMode.Detach -> detach(sessionId)
            StopMode.Interrupt -> interrupt(sessionId)
            StopMode.Graceful, StopMode.Terminate, StopMode.Kill -> terminate(sessionId)
        }
    }

    /**
     * Interrupt a stuck session: send Ctrl-C to un-stick a `running` that will not budge (Claude emits
     * no hook on Esc/Ctrl-C) AND apply [ControlSignal.Interrupt] to the projection (alive → `ready`,
     * approvals cleared). The session stays alive, so its pane stays registered.
     */
    suspend fun interrupt(sessionId: SessionId) {
        val meta = store.getSession(sessionId) ?: return
        tmux.sendKeys(sessionId.value, byteArrayOf(0x03)) // Ctrl-C
        val next = reduce(currentProjection(sessionId, meta), ControlSignal.Interrupt)
        persistDerivedState(meta, next.state, EventSource.user)
    }

    /**
     * Resume a dead session: build a resume launch spec (needs the captured provider id — resume is
     * blocked with [ResumeBlockedException] if it is still pending), start a fresh tmux session, and
     * apply [ControlSignal.Resume] (dead → `ready`). A no-op on an already-alive session.
     */
    suspend fun resume(sessionId: SessionId): SessionMeta {
        val meta = store.getSession(sessionId) ?: throw NoSuchSessionException(sessionId)
        if (meta.state.isAlive) return meta // already running; resume is a no-op
        val providerId = meta.providerSessionId ?: throw ResumeBlockedException(sessionId)

        val adapter = agentFactory.create(meta.agent, meta.cwd)
        val spec = adapter.buildLaunchSpec(LaunchMode.Resume(providerId))
        val paneId = tmux.newSession(sessionId.value, meta.cwd, shellCommand(spec.command), cols, rows)

        val next = reduce(currentProjection(sessionId, meta), ControlSignal.Resume)
        val updated = meta.copy(
            paneId = paneId,
            state = next.state,
            stateSource = EventSource.user,
            updatedAt = now(),
        )
        store.upsertSession(updated)
        registry.register(paneId, sessionId)
        return updated
    }

    /**
     * Detach — a no-op at this layer. Detach is a transport concern (a terminal-WS subscriber left /
     * the lazy terminal bridge tore down); the agent keeps running in tmux, so there is nothing to kill,
     * send, or persist here. Modeled as [ControlSignal.Detach] (the identity) elsewhere; kept as an
     * explicit method so the [StopMode] dispatch is total and self-documenting.
     */
    fun detach(sessionId: SessionId) {
        // Intentionally empty. See KDoc.
    }

    /** Clean-kill termination: arm the stop intent, kill the tmux session, cache the derived `stopped`. */
    private suspend fun terminate(sessionId: SessionId) {
        val meta = store.getSession(sessionId) ?: return
        tmux.killSession(sessionId.value)
        // Derive the terminal state through the real reducer: ControlSignal.Stop arms the clean-stop
        // intent, and the clean kill is an intended Exit → `stopped` (not `crashed`). We use only the
        // derived STATE; the fabricated Exited is a pure classification aid and is never persisted (so
        // the per-session seq is untouched). There is no Exited hook in the v1 Claude slice, so this
        // cached `stopped` is what the reconciler re-derives from the pane being gone on restart.
        val terminal = reduce(
            reduce(store.projectionOf(sessionId), ControlSignal.Stop),
            AgentEvent.Exited(TMUX_KILL_EXIT),
        )
        persistDerivedState(meta, terminal.state, EventSource.user)
        meta.paneId?.let { registry.unregister(it) }
    }

    /**
     * Write a control-signal-/reconciler-derived state to the sessions cache. Implemented as a full-row
     * [EventStore.upsertSession] of the derived fields (state / state_source / updated_at) rather than a
     * dedicated `updateCache` interface method: the manager already holds the full [SessionMeta], the
     * upsert is atomic under the store's single-writer mutex and preserves `created_at`, and this avoids
     * widening the Task-7 [EventStore] contract for a derived-state write. This realizes the plan's
     * "write the derived state to the sessions cache via store.updateCache(...)".
     */
    private suspend fun persistDerivedState(meta: SessionMeta, state: SessionState, source: EventSource) {
        store.upsertSession(meta.copy(state = state, stateSource = source, updatedAt = now()))
    }

    /**
     * The event-log projection with its lifecycle [Projection.state] overridden by the cache-authoritative
     * [SessionMeta.state]. Necessary because control-signal effects and reconciler classifications are NOT
     * in the append-only log (see the class KDoc), so the pure-replay projection can lag the true liveness
     * (e.g. read `running` for a session the reconciler cached as `resumable`). A [ControlSignal] whose
     * outcome depends on alive-vs-dead ([ControlSignal.Resume] / [ControlSignal.Interrupt]) must see the
     * authoritative state, so it is seeded here before [reduce].
     */
    private suspend fun currentProjection(sessionId: SessionId, meta: SessionMeta) =
        store.projectionOf(sessionId).copy(state = meta.state)

    companion object {
        const val DEFAULT_COLS: Int = 120
        const val DEFAULT_ROWS: Int = 40

        /** Exit code stamped on the pure classification Exit of a `kill-session` (SIGHUP = 128 + 1). */
        private const val TMUX_KILL_EXIT: Int = 129

        /**
         * Render an argv [List] into a single `/bin/sh`-safe command string for `tmux new-session <cmd>`
         * (tmux runs the command through the shell). Every element is POSIX single-quoted so it stays one
         * literal word — no expansion, no re-splitting (the same technique [io.kotgent.tmux.ProcessRunner]
         * uses for its own argv).
         */
        fun shellCommand(argv: List<String>): String =
            argv.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
    }
}

/** A short, tmux-name-safe session id: 8 lowercase hex chars. Injectable [random] for deterministic tests. */
fun randomShortId(random: Random = Random.Default): String {
    val bytes = random.nextBytes(4)
    return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** Default wall-clock for daemon timestamps: epoch millis (matches the store's clock). Injectable in ctors. */
@OptIn(ExperimentalTime::class)
fun daemonEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
