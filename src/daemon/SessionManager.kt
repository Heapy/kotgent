package io.kotgent.daemon

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.core.AgentEvent
import io.kotgent.core.ControlSignal
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.reduce
import io.kotgent.store.EventStore
import io.kotgent.tmux.TmuxControl
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * How to stop a session — the `StopMode` spectrum from the plan (`Detach ≠ Kill`). [Interrupt]
 * leaves the agent alive to un-stick it; [Detach] leaves it entirely untouched (a transport concern);
 * [Kill] tears the tmux session down.
 *
 * [decision] The v1 slice keeps only these three: graduated signal escalation (ask-nicely → SIGTERM →
 * SIGKILL) is backlog, and the speculative `Graceful`/`Terminate` variants (which collapsed to the same
 * `kill-session` as [Kill] and had no caller) were dropped to keep the surface honest — they can be
 * re-introduced with the escalation logic that would justify them.
 */
enum class StopMode {
    /** Client disconnect; the agent lives on. No-op at this layer (see [SessionManager.detach]). */
    Detach,

    /** Ctrl-C to un-stick a stuck `running`; the agent lives on. */
    Interrupt,

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

/** The one agent kind the v1 slice supports (`start claude`); anything else is rejected up front. */
const val CLAUDE_AGENT_KIND: String = "claude"

/**
 * Wrap [buildClaude] in an [AgentFactory] that ACCEPTS only the `claude` agent kind and rejects every
 * other with [UnsupportedAgentException]. Without this the production factory silently ignored the
 * requested kind and always built a Claude adapter, so `start codex` launched Claude while the session
 * was persisted as a different agent. `create` runs before any tmux side effect (see
 * [SessionManager.start] / [SessionManager.resume]), so a rejection leaves nothing to clean up.
 */
fun claudeOnlyAgentFactory(buildClaude: (cwd: String) -> AgentAdapter): AgentFactory =
    AgentFactory { agentKind, cwd ->
        if (agentKind != CLAUDE_AGENT_KIND) throw UnsupportedAgentException(agentKind)
        buildClaude(cwd)
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
}

/** Thrown when an operation targets a session id that was never upserted. */
class NoSuchSessionException(val sessionId: SessionId) :
    NoSuchElementException("no such session: ${sessionId.value}")

/** Thrown when [SessionManager.resume] is asked to revive a session whose provider id is still pending. */
class ResumeBlockedException(val sessionId: SessionId) :
    IllegalStateException("resume blocked: provider session id is pending for ${sessionId.value}")

/** Thrown when a start targets an agent kind the daemon does not support (v1: only `claude`). */
class UnsupportedAgentException(val agentKind: String) :
    IllegalArgumentException("unsupported agent kind '$agentKind' (v1 supports only 'claude')")

/**
 * One cleanup step of a compensation that itself failed (e.g. the `kill-session` that should have
 * removed a just-launched agent, or the state write that should have erased a phantom row).
 *
 * It is attached to the PRIMARY failure as a suppressed exception rather than swallowed: the primary
 * error must still win (it is why we are compensating), but a silently-dropped cleanup failure means a
 * live orphan pane or a durable phantom `running` row that nothing will report until the next daemon
 * restart reconciles it. Surfacing it makes that detectable.
 */
class CompensationFailure(message: String, cause: Throwable) : IllegalStateException(message, cause)

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
 * ## Control ops are serialized PER SESSION
 * Every control op is a read-modify-write across two systems (read the cache row → act on tmux → write the
 * derived state back), so concurrent ops on the SAME session must not interleave: an `interrupt` that read
 * a live row while a `stop` killed the pane would write `ready` over `stopped` and resurrect a session
 * that has no pane. [withControlLock] gives each session id its own [Mutex], held across the whole
 * read-modify-write of [interrupt] / [resume] / [terminate]. Different sessions still proceed in parallel
 * (the lock is per id, not global).
 *
 * [start] takes the SAME lock. A freshly minted id is unreachable only until the row is published: from
 * `upsertSession` onwards the session is listable and a concurrent `stop` can target it, so an unlocked
 * start could (a) register its pane AFTER that stop unregistered it — leaving a killed session routable —
 * or (b) have its compensation overwrite the committed `stopped`. Holding the lock across the launch,
 * the publish, the pane registration AND the compensation makes start and the control ops mutually
 * exclusive for that session.
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

    /** Guards [controlLocks] itself (the per-session lock table), never a control op. */
    private val controlLocksGuard = Mutex()

    /**
     * One [Mutex] per session id, serializing that session's control ops (see the class KDoc). Entries are
     * kept for the daemon's lifetime — one small object per session ever controlled, which is bounded by
     * the session count; dropping an idle entry would race a waiter that already holds a reference to it.
     */
    private val controlLocks = HashMap<SessionId, Mutex>()

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
     *
     * The whole launch runs under the session's control lock, so a `stop` that targets the row the instant
     * it is published cannot interleave with the rest of the start (see the class KDoc).
     */
    suspend fun start(
        agentKind: String,
        cwd: String,
        name: String? = null,
        tags: List<String> = emptyList(),
    ): SessionMeta {
        val sessionId = freshSessionId()
        val shortId = sessionId.value
        val tmuxSession = tmux.sessionName(shortId)
        // create() rejects an unsupported agent kind (UnsupportedAgentException) BEFORE any tmux side
        // effect, so a bad kind fails with nothing to clean up.
        val adapter = agentFactory.create(agentKind, cwd)
        val spec = adapter.buildLaunchSpec(LaunchMode.New)

        return withControlLock(sessionId) {
            // Null until the launch reports a pane; the compensation below tolerates that (it may have to
            // clean up a tmux session whose pane id we never learned).
            var paneId: PaneId? = null
            try {
                // The launch is INSIDE the guarded region: tmux can create the session and the call still
                // fail (e.g. `new-session -P` came back with no pane id), which outside the try would leave
                // a live, untracked agent behind with nothing to compensate it.
                paneId = tmux.newSession(shortId, cwd, shellCommand(spec.command), cols, rows)
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
                // Upsert the row BEFORE registering the pane: a hook that resolves the pane must find the
                // sessions row already present (else its append would race the row into existence). The hook
                // ingress tolerates the brief not-yet-registered window with a bounded retry (see
                // claudeHookRoutes), so the SessionStart callback is not lost during it.
                store.upsertSession(meta)
                registry.register(paneId, sessionId)

                val prealloc = spec.preallocatedSessionId
                if (prealloc != null) {
                    // Primary path: the id is known up front — bind it in the log immediately.
                    idCapture.bind(sessionId, prealloc)
                } else {
                    // Fallback path (older claude): no id yet. Poll for the SessionStart hook to deliver it
                    // in the background; until it does the session is "id pending" (provider_session_id null
                    // → resume blocked). Bounded, fire-and-forget.
                    idCapture.captureInBackground(sessionId) { store.projectionOf(sessionId).providerSessionId }
                }
                store.getSession(sessionId) ?: meta
            } catch (e: Throwable) {
                // The launch (or a step after it) failed — compensate by killing the just-created tmux
                // session so no live agent is left orphaned/untracked, AND by correcting the row: the upsert
                // above may already have committed `running` + a pane id, which would otherwise outlive the
                // failure as a durable phantom (a `running` session with no pane) until the next daemon
                // restart reconciled it. `crashed` is the honest classification — the launch did not
                // complete. The state write is a no-op if the upsert itself was what failed (no row yet).
                compensateFailedLaunch(sessionId, shortId, paneId, SessionState.crashed, null, e)
                throw e
            }
        }
    }

    /**
     * Undo a failed launch ([start] / [resume]): kill the tmux session `kt-<shortId>`, drop [paneId] from
     * the registry, and put the sessions row back to [restoreState] / [restorePaneId].
     *
     * Runs under [NonCancellable]. Compensation is itself suspending (registry + store writes), so in a
     * CANCELLED coroutine — a perfectly ordinary way for a launch to fail, e.g. the daemon shutting down
     * mid-start — every suspension point would throw immediately and the cleanup would silently not
     * happen, leaving exactly the orphan pane / phantom row it exists to prevent.
     *
     * Cleanup failures are NOT swallowed: each is attached to the primary [cause] as a suppressed
     * [CompensationFailure]. The primary error still wins (it is why we are compensating), but a dropped
     * cleanup failure means an orphan pane or a phantom row that nothing would report.
     */
    private suspend fun compensateFailedLaunch(
        sessionId: SessionId,
        shortId: String,
        paneId: PaneId?,
        restoreState: SessionState,
        restorePaneId: PaneId?,
        cause: Throwable,
    ): Unit = withContext(NonCancellable) {
        fun note(what: String, failure: Throwable) =
            cause.addSuppressed(CompensationFailure("compensation failed for '$shortId': $what", failure))

        runCatching { tmux.killSession(shortId) }.onFailure { note("kill-session", it) }
        if (paneId != null) {
            runCatching { registry.unregister(paneId) }.onFailure { note("unregister pane ${paneId.value}", it) }
        }
        runCatching { store.updateSessionState(sessionId, restoreState, EventSource.system, restorePaneId, now()) }
            .onFailure { note("restore state to $restoreState", it) }
    }

    /**
     * A [SessionId] not already used by any stored session or event log. [randomShortId] is only 32
     * bits, so a collision with a dead HISTORICAL session's `kt-<id>` (whose `sessions` row and event
     * log survive) is improbable but not impossible — and a collision would overwrite that row and
     * splice this new agent into its existing log. So we reject any candidate the store already knows
     * and regenerate, bounded. The id stays 8 hex chars for a stable, copy-pasteable CLI handle; the
     * store check — not the id width — is what makes reuse impossible.
     */
    private suspend fun freshSessionId(): SessionId {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = newSessionId()
            if (store.getSession(candidate) == null && store.read(candidate, Seq(0)).isEmpty()) return candidate
        }
        error("could not allocate a unique session id after $MAX_ID_ATTEMPTS attempts")
    }

    /** Stop [sessionId] according to [mode] (defaults to a full [StopMode.Kill]). */
    suspend fun stop(sessionId: SessionId, mode: StopMode = StopMode.Kill) {
        when (mode) {
            StopMode.Detach -> detach(sessionId)
            StopMode.Interrupt -> interrupt(sessionId)
            StopMode.Kill -> terminate(sessionId)
        }
    }

    /**
     * Interrupt a stuck session: send Ctrl-C to un-stick a `running` that will not budge (Claude emits
     * no hook on Esc/Ctrl-C) AND apply [ControlSignal.Interrupt] to the projection (alive → `ready`,
     * approvals cleared). The session stays alive, so its pane stays registered.
     */
    suspend fun interrupt(sessionId: SessionId): Unit = withControlLock(sessionId) {
        // The read (getSession) must happen INSIDE the lock: reading a live row outside it and then
        // reducing from that snapshot is exactly how a racing stop gets overwritten with `ready`.
        val meta = store.getSession(sessionId) ?: return@withControlLock
        tmux.sendKeys(sessionId.value, byteArrayOf(0x03)) // Ctrl-C
        val next = reduce(currentProjection(sessionId, meta), ControlSignal.Interrupt)
        persistDerivedState(meta, next.state, EventSource.user)
    }

    /**
     * Resume a dead session: build a resume launch spec (needs the captured provider id — resume is
     * blocked with [ResumeBlockedException] if it is still pending), start a fresh tmux session, and
     * apply [ControlSignal.Resume] (dead → `ready`). A no-op on an already-alive session.
     */
    suspend fun resume(sessionId: SessionId): SessionMeta = withControlLock(sessionId) {
        val meta = store.getSession(sessionId) ?: throw NoSuchSessionException(sessionId)
        // The cache can say "alive" even though the pane died while the daemon was up: there is no live
        // exit hook, and liveness is only reconciled at startup. Confirm real tmux liveness before
        // treating resume as a no-op — otherwise a pane that dies mid-run could not be resumed until a
        // daemon restart. A genuinely-live session is still a no-op.
        if (meta.state.isAlive && isPaneAlive(meta.tmuxSession)) return@withControlLock meta
        val providerId = meta.providerSessionId ?: throw ResumeBlockedException(sessionId)

        val adapter = agentFactory.create(meta.agent, meta.cwd)
        val spec = adapter.buildLaunchSpec(LaunchMode.Resume(providerId))
        // Reduce Resume from a DEAD seed: the cache may have claimed alive, but we confirmed the pane is
        // gone, and Resume is a no-op on an alive-seeded projection — so reclassify to a dead state first,
        // then Resume takes it to `ready`. Computed BEFORE the launch so the compensation path below can
        // restore exactly this pre-resume dead classification.
        val deadState = if (meta.state.isDead) meta.state else SessionState.crashed

        // Null until the launch reports a pane; the compensation below tolerates that (it may have to
        // clean up a tmux session whose pane id we never learned).
        var paneId: PaneId? = null
        try {
            // The launch is INSIDE the guarded region (same shape as start()): tmux can create the session
            // and the call still fail (e.g. `new-session -P` came back with no pane id), which outside the
            // try would leave a live, untracked `--resume` agent behind — its hooks would 404 forever —
            // while the row still asserted the pre-resume dead state, so the next resume would go straight
            // back to `new-session` and collide with the duplicate tmux session name.
            paneId = tmux.newSession(sessionId.value, meta.cwd, shellCommand(spec.command), cols, rows)
            val next = reduce(store.projectionOf(sessionId).copy(state = deadState), ControlSignal.Resume)
            val ts = now()
            // Update only the daemon-owned fields (state / state_source / pane_id / updated_at); do NOT
            // upsert the whole (stale) row — the freshly-revived agent may already be appending hooks that
            // advance last_seq / provider_session_id, which a full-row write would clobber.
            store.updateSessionState(sessionId, next.state, EventSource.user, paneId, ts)
            registry.register(paneId, sessionId)
            return@withControlLock meta.copy(
                paneId = paneId,
                state = next.state,
                stateSource = EventSource.user,
                updatedAt = ts,
            )
        } catch (e: Throwable) {
            // Compensate a failure at or after the fresh agent's launch (see start()): kill it, drop the
            // pane — and PUT THE ROW BACK to its pre-resume dead state. The write above may already have
            // committed `ready` + the fresh pane id, which would otherwise survive as a durable phantom
            // (an "alive" session whose pane we just killed) until the next daemon restart.
            compensateFailedLaunch(sessionId, sessionId.value, paneId, deadState, meta.paneId, e)
            throw e
        }
    }

    /** True if tmux currently reports a LIVE (non-dead) pane for [tmuxSession] (`kt-<id>`). */
    private fun isPaneAlive(tmuxSession: String): Boolean =
        tmux.listPanes().any { it.session == tmuxSession && !it.dead }

    /**
     * Detach — a no-op at this layer. Detach is a transport concern (a terminal-WS subscriber left /
     * the lazy terminal bridge tore down); the agent keeps running in tmux, so there is nothing to kill,
     * send, or persist here. Modeled as [ControlSignal.Detach] (the identity) elsewhere; kept as an
     * explicit method so the [StopMode] dispatch is total and self-documenting.
     */
    fun detach(sessionId: SessionId) {
        // Intentionally empty. See KDoc.
    }

    /**
     * Clean-kill termination: arm the stop intent, PERSIST it, then kill the tmux session.
     *
     * The persist comes FIRST on purpose. `stopped` is the only record that this teardown was intended,
     * it is cache-only (control signals are never logged) and incarnation-scoped, so the reconciler reads
     * it as the current stop intent. Killing first left a window — a daemon crash or a store failure
     * between the kill and the write — in which the pane is gone but nothing says why, and the next
     * reconcile would misclassify a clean operator stop as `resumable`/`crashed`. Written first, the
     * intent survives any failure after it: a crash before the kill leaves a live pane, which reconcile
     * corrects back to `running` (a live pane always wins over a cached dead state), and a crash after the
     * kill leaves exactly the `stopped` the operator asked for.
     */
    private suspend fun terminate(sessionId: SessionId): Unit = withControlLock(sessionId) {
        val meta = store.getSession(sessionId) ?: return@withControlLock
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
        try {
            tmux.killSession(sessionId.value)
        } catch (e: Throwable) {
            // The kill itself failed, so the session may well still be alive: roll the armed intent back
            // rather than leaving the cache claiming a `stopped` that never happened, and surface any
            // rollback failure with the primary error (see [compensateFailedLaunch]).
            withContext(NonCancellable) {
                runCatching { persistDerivedState(meta, meta.state, EventSource.system) }
                    .onFailure {
                        e.addSuppressed(
                            CompensationFailure(
                                "compensation failed for '${sessionId.value}': restore state to ${meta.state}",
                                it,
                            ),
                        )
                    }
            }
            throw e
        }
        meta.paneId?.let { registry.unregister(it) }
    }

    /**
     * Write a control-signal-/reconciler-derived state to the sessions cache via
     * [EventStore.updateSessionState], which updates ONLY the daemon-owned fields (state /
     * state_source / pane_id / updated_at) atomically under the store's writer lock. A full-row
     * [EventStore.upsertSession] of the (stale) [SessionMeta] must NOT be used here: a concurrent
     * hook [EventStore.append] advances `last_seq` / `provider_session_id` under the same lock, and a
     * stale full-row write would clobber it — regressing unread and reverting a captured provider id
     * (which would wrongly block `resume`). The pane id is carried through unchanged.
     */
    private suspend fun persistDerivedState(meta: SessionMeta, state: SessionState, source: EventSource) {
        store.updateSessionState(meta.id, state, source, meta.paneId, now())
    }

    /**
     * Run [block] holding [sessionId]'s control lock, so a session's control ops (interrupt / stop /
     * resume) are strictly serialized end-to-end — read of the cache row, tmux side effect, and the
     * derived-state write all inside one critical section (see the class KDoc). Only the tiny lock-table
     * lookup takes the shared [controlLocksGuard]; the op itself holds the per-session lock only, so
     * different sessions never block each other.
     */
    private suspend fun <T> withControlLock(sessionId: SessionId, block: suspend () -> T): T {
        val lock = controlLocksGuard.withLock { controlLocks.getOrPut(sessionId) { Mutex() } }
        return lock.withLock { block() }
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

        /** How many times [freshSessionId] regenerates on a store collision before giving up. */
        private const val MAX_ID_ATTEMPTS: Int = 8

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
