package io.kotgent.daemon

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.cli.eprintln
import io.kotgent.core.AgentEvent
import io.kotgent.core.ControlSignal
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.ProjectId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.isCanonicalUuid
import io.kotgent.core.reduce
import io.kotgent.store.EventStore
import io.kotgent.store.TaskStore
import io.kotgent.task.ActivityKind
import io.kotgent.task.ProjectFs
import io.kotgent.task.TaskState
import io.kotgent.task.resolveProject
import io.kotgent.tmux.TmuxControl
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.cinterop.toKString
import platform.posix.free
import platform.posix.realpath
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

/** Agent kind for Claude Code (`start claude`). */
const val CLAUDE_AGENT_KIND: String = "claude"

/** Agent kind for Codex (`start codex`). */
const val CODEX_AGENT_KIND: String = "codex"

/** Agent kind for Junie (`start junie`). */
const val JUNIE_AGENT_KIND: String = "junie"

/** Agent kind for a plain login shell (`start shell`). */
const val SHELL_AGENT_KIND: String = "shell"

/**
 * The subset of launchable [kinds] that may be imported from an external provider store. A shell has
 * no outside conversation or provider identity to adopt, so it remains launchable through
 * [AgentFactory] while being deliberately absent from [SessionManager.importSession]'s gate.
 */
fun importableAgentKinds(kinds: Set<String>): Set<String> = kinds - SHELL_AGENT_KIND

/**
 * An [AgentFactory] over the agent kinds in [builders], rejecting every other kind with
 * [UnsupportedAgentException]. The gate matters: without it a factory silently ignores the requested
 * kind and builds whatever adapter it was closed over, so `start <unknown>` would launch the wrong agent
 * while the session was persisted under the requested name. `create` runs before any tmux side effect
 * (see [SessionManager.start] / [SessionManager.resume]), so a rejection leaves nothing to clean up.
 */
fun agentFactoryOf(builders: Map<String, (cwd: String) -> AgentAdapter>): AgentFactory =
    AgentFactory { agentKind, cwd ->
        val build = builders[agentKind] ?: throw UnsupportedAgentException(agentKind, builders.keys)
        build(cwd)
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

/** Thrown when a start targets an agent kind the daemon does not support. */
class UnsupportedAgentException(val agentKind: String, val supported: Set<String> = emptySet()) :
    IllegalArgumentException(
        "unsupported agent kind '$agentKind'" +
            if (supported.isEmpty()) "" else " (supported: ${supported.sorted().joinToString(", ")})",
    )

/**
 * Thrown when a supported agent kind cannot be resolved to a binary on the daemon's PATH — i.e. the
 * bootstrap's `locate()` returned null under launchd's minimal env. It fails the launch fast, from the
 * factory builder (before any tmux side effect — see [SessionManager.start] / [SessionManager.resume]),
 * so an unfindable agent surfaces an actionable message instead of a pane that dies at exec (127) and
 * leaves a phantom `running` session.
 *
 * Deliberately a plain [IllegalStateException] (like [ResumeBlockedException]) and NOT a subtype of
 * [UnsupportedAgentException] / [io.kotgent.tmux.TmuxException], so the transport's existing catches do
 * not swallow it before its own dedicated mapping.
 */
class AgentBinaryNotFoundException(val agentKind: String) :
    IllegalStateException(
        "agent '$agentKind' not found on the daemon's PATH — run `kotgent install` from a shell where " +
            "`$agentKind` is on your PATH (install `$agentKind` first if needed), then create the session again",
    )

/**
 * Resolve a `command -v` result ([located]) to an **absolute** agent binary path, or fail fast with
 * [AgentBinaryNotFoundException]. `locate()` returns whatever `command -v <kind>` prints, which is only
 * absolute when the name resolved against an absolute PATH dir; a non-absolute result (a name resolved
 * via a relative dir / `.` on PATH, or a name that itself contains a slash) is a real hole here: tmux
 * runs `new-session -c <cwd>` — cd into the session cwd — *before* exec, so a relative path would exec a
 * wrong cwd-local binary, or fail at exec only after a `running` row was persisted, recreating the
 * phantom-session / 1006 failure the fail-fast exists to prevent. So a `null` OR non-absolute path is
 * treated exactly like "not found". Called from the factory builders (see [SessionManager.start] /
 * [SessionManager.resume]) so the throw lands before any tmux side effect.
 */
fun requireAbsoluteBinary(agentKind: String, located: String?): String =
    located?.takeIf { it.startsWith("/") } ?: throw AgentBinaryNotFoundException(agentKind)

/*
 * The four import failures ([SessionManager.importSession]) are deliberately STANDALONE exceptions —
 * direct [RuntimeException] subtypes, never subtypes of each other or of any existing kotgent
 * exception ([UnsupportedAgentException], [io.kotgent.tmux.TmuxException], …). The exception hierarchy
 * is load-bearing for transport mapping in this repo (see [io.kotgent.tmux.TmuxCopyModeException]):
 * with no hierarchy among them, the import route can catch each one in any order and map it to its own
 * status without an accidental parent catch swallowing a sibling.
 */

/**
 * Thrown when [SessionManager.importSession] is asked for an agent kind the daemon does not support
 * (not in `supportedAgentKinds`). Import validates against the set instead of calling
 * [AgentFactory.create], which would drag the binary check into a side-effect-free registration.
 */
class UnknownAgentKindException(val agentKind: String, val supported: Set<String>) :
    RuntimeException(
        "unknown agent kind '$agentKind' (supported: ${supported.sorted().joinToString(", ")})",
    )

/**
 * Thrown when [SessionManager.importSession] cannot settle a session's project directory: neither an
 * explicit `--cwd` nor discovery yielded one, or the directory itself no longer exists on disk.
 */
class ImportCwdException(message: String) : RuntimeException(message)

/**
 * Thrown when the vendor-store probe does not see a transcript for the exact `(agent, cwd, id)` an
 * import would store — the same triple the [Reconciler] re-probes on every daemon start, so failing
 * HERE (with the `--cwd` workaround named) beats a session that silently degrades
 * `resumable → crashed` after the next restart.
 */
class TranscriptNotFoundException(
    val agentKind: String,
    val providerSessionId: ProviderSessionId,
    val cwd: String,
) : RuntimeException(
    "no live $agentKind transcript found for session '${providerSessionId.value}' under '$cwd' — " +
        "path spelling is already canonicalized (/tmp is probed as /private/tmp), so if the session " +
        "was launched in a genuinely different directory, pass that one with --cwd; " +
        "an archived codex session is out of `codex resume`'s reach and cannot be imported",
)

/**
 * Thrown when the provider session id is already held by an existing kotgent session (archived rows
 * included — an archived duplicate means the right move is Restore, not a second import). Carries
 * [existingId] so the caller can point the operator at the session that already exists.
 */
class DuplicateImportException(val existingId: SessionId, val archived: Boolean) :
    RuntimeException(
        "provider session already imported as kotgent session '${existingId.value}'" +
            if (archived) " (archived — Restore it instead of importing again)" else "",
    )

/**
 * The filesystem-canonical form of [path] (`realpath(3)`): symlinks resolved (macOS: `/tmp` →
 * `/private/tmp`), `.`/`..` segments applied against the REAL directory tree — never lexically, which
 * crosses symlinks wrongly — and duplicate/trailing slashes dropped. `null` when [path] does not
 * resolve, which doubles as the import cwd existence gate. This is the ONE canonicalizer for the
 * import cwd: providers record their process `getcwd` (exactly the realpath form) into their on-disk
 * stores, so the probe key and the stored row must use this spelling or a valid import is falsely
 * rejected (claude) / a noncanonical path is persisted (codex). Public because tests build their
 * canonical expectations with it (toolchain 0.11: tests cannot see `internal`); stock `platform.posix`
 * links into the test binary, so KT-78062 does not apply.
 */
@OptIn(ExperimentalForeignApi::class)
fun canonicalPath(path: String): String? {
    val resolved = realpath(path, null) ?: return null
    return try {
        resolved.toKString()
    } finally {
        free(resolved)
    }
}

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
 * read-modify-write of [interrupt] / [resume] / [terminate] / [onTmuxSessionClosed]. Different sessions
 * still proceed in parallel (the lock is per id, not global).
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
    /**
     * The vendor-store transcript probe [importSession] validates against — the SAME dispatch the
     * daemon hands its [Reconciler], so an import is checked with exactly the `(agent, cwd, id)`
     * question every later daemon start will re-ask. Deliberately NO default value: every call-site
     * must choose, because a default fake is how the production Reconciler once shipped a `{ false }`
     * stub while the tests stayed green (see the ClaudeVendorStoreProbe.kt header).
     */
    private val vendorProbe: VendorStoreProbe,
    /**
     * Discovery of an imported session's cwd when the caller supplies none (see [importSession]).
     * No default value — same reasoning as [vendorProbe].
     */
    private val sessionLocator: VendorSessionLocator,
    /**
     * The agent kinds this daemon may import — production derives it from the SAME builder keys used
     * for [agentFactory], then removes kinds such as [SHELL_AGENT_KIND] that have no outside provider
     * session to adopt. [importSession] gates on this set instead of calling [AgentFactory.create]
     * (which would drag the binary fail-fast into a side-effect-free registration; [resume] owns that check).
     * No default value — same reasoning as [vendorProbe].
     */
    private val supportedAgentKinds: Set<String>,
    /**
     * Provider-specific discovery of a session's id for the FALLBACK capture path — consulted only when
     * the launch preallocated nothing AND no `SessionBound` has arrived from a hook yet.
     *
     * It exists because Codex has no `claude --session-id` equivalent: its id can only be learned after
     * the fact, and relying on the `SessionStart` hook alone would leave `resume` permanently blocked
     * whenever that hook does not fire. The daemon passes a scan of Codex's rollout files (see
     * [CodexRolloutScan]); the default returns `null`, i.e. "hook only", which is exactly right for
     * Claude and for tests.
     */
    private val discoverProviderId: suspend (SessionMeta) -> ProviderSessionId? = { null },
    /**
     * Best-effort, fire-and-forget model capture (Codex reads it from the rollout after launch; Claude
     * captures it via the hook path instead, so this is a no-op there). Called from [start] with the
     * stored meta and from every successful [resume] with the revived meta — for an IMPORTED codex
     * session, which never launched under kotgent, resume is the only place its model can ever be
     * captured. The daemon wires it to a background job that discovers and persists the model. Default
     * no-op keeps it inert in tests that do not exercise it.
     */
    private val captureModelInBackground: (SessionMeta) -> Unit = {},
    private val newSessionId: () -> SessionId = { SessionId(randomShortId()) },
    private val now: () -> Long = ::daemonEpochMillis,
    private val cols: Int = DEFAULT_COLS,
    private val rows: Int = DEFAULT_ROWS,
    /**
     * The task layer, or `null` for a daemon (or a test) without one. Two uses: upserting the `projects`
     * row whenever a session's cwd resolves to a project — without that, a project first seen through a
     * session start would have backlog rows but never appear in `GET /api/v1/projects` — and closing the
     * task a session was working on when the operator presses Done ([closeLinkedTask]).
     *
     * Nullable with a null DEFAULT so every existing construction — the suite's fakes, the transport
     * harnesses — keeps compiling untouched. Appended after [rows] rather than grouped with the other
     * collaborators for the same reason: a positional caller must not shift.
     */
    private val taskStore: TaskStore? = null,
    /**
     * Filesystem access for project resolution at `start` / `importSession`, or `null` to resolve no
     * project at all. Same nullability rationale as [taskStore].
     */
    private val projectFs: ProjectFs? = null,
) {
    /** Convenience view of [registry] as the `paneLookup` shape [io.kotgent.transport.claudeHookRoutes] takes. */
    val paneLookup: suspend (PaneId) -> SessionId? get() = registry::lookup

    /**
     * The hook ingress' provider-id REBIND correction ([io.kotgent.transport.codexHookRoutes]): called
     * after a hook-delivered `SessionBound` displaced a DIFFERENT, already-persisted provider id. The
     * displaced id came from the fallback rollout scan ([CodexRolloutScan.discoverSessionId]), whose
     * cwd+mtime match can provisionally bind a same-cwd NEIGHBOUR's id — and the id-keyed model capture
     * trusts the row's id, so a model persisted under the displaced id may be the neighbour's, with its
     * capture poll already stopped. The captured model is therefore provably suspect: clear it, then
     * re-run [captureModelInBackground], whose per-attempt row re-read ([captureCodexModelOnce]) keys
     * every lookup off the id the hook just made authoritative. An IN-FLIGHT capture that already read
     * the displaced id cannot undo this clear either: its write is atomically conditional on the row
     * still holding that id ([io.kotgent.store.EventStore.setModelForProvider]), so it writes zero rows
     * and keeps polling instead. The clear itself stays deliberately unconditional — null can only
     * remove, and the worst raced case (wiping a just-landed correct model) self-heals on the re-run's
     * immediate first attempt.
     */
    suspend fun onProviderIdRebound(sessionId: SessionId) {
        store.setModel(sessionId, null, now())
        store.getSession(sessionId)?.let(captureModelInBackground)
    }

    /**
     * Leave copy-mode on [sessionId]'s pane so **programmatic** input is not eaten by the copy-mode key
     * table; `false` means the pane is not provably clear and the caller must not claim delivery.
     * Exposed for the `POST /sessions/{id}/input` seam, which writes into the shared upstream pty and
     * would otherwise answer `ok` for bytes tmux discarded.
     *
     * Deliberately **outside** the per-session control lock: it touches only tmux's pane mode, never
     * the projection or the store, and taking the lock would make a keystroke queue behind a
     * `start`/`resume` for no benefit.
     */
    fun leaveCopyMode(sessionId: SessionId): Boolean = tmux.leaveCopyMode(sessionId.value)

    /**
     * Serializes [importSession] end-to-end, daemon-wide. Imports are rare, so one global lock is
     * simpler than a per-provider-id table — and it is what makes the duplicate check and the row
     * write atomic: two concurrent imports of the same provider id cannot both pass the check.
     */
    private val importMutex = Mutex()

    /** Guards [reservedIds] — every draw/release of an in-flight session id goes through it. */
    private val idAllocationGuard = Mutex()

    /**
     * Session ids DRAWN by [freshSessionId] but not yet durable in the store. [start] and
     * [importSession] both allocate before their `upsertSession`, under DIFFERENT locks (the
     * per-session control lock vs [importMutex]), so the store check alone leaves a window in which
     * both draw the same 32-bit id — and the later upsert would silently overwrite the earlier row and
     * splice two agents into one event log / tmux name. Reserving the id for the allocation-to-upsert
     * region closes that window; [releaseSessionId] drops the reservation when the region ends (on
     * success the stored row takes over as the authority, on failure the id is genuinely free again).
     */
    private val reservedIds = HashSet<SessionId>()

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
        // Reserved until the finally below: the row is not in the store before the upsert inside the
        // control lock, and only the reservation keeps a concurrent start/import from drawing the same id.
        val sessionId = freshSessionId()
        try {
            return startReserved(sessionId, agentKind, cwd, name, tags)
        } finally {
            releaseSessionId(sessionId)
        }
    }

    /** The body of [start], running with [sessionId] already reserved (see [freshSessionId]). */
    private suspend fun startReserved(
        sessionId: SessionId,
        agentKind: String,
        cwd: String,
        name: String?,
        tags: List<String>,
    ): SessionMeta {
        val shortId = sessionId.value
        val tmuxSession = tmux.sessionName(shortId)
        // create() rejects an unsupported or unresolvable agent kind (UnsupportedAgentException /
        // AgentBinaryNotFoundException) BEFORE any tmux side effect, so a bad kind fails with nothing
        // to clean up.
        val adapter = agentFactory.create(agentKind, cwd)
        val spec = adapter.buildLaunchSpec(LaunchMode.New)
        // Resolved OUTSIDE the control lock: a walk up the filesystem plus a `projects` upsert say nothing
        // about this session's lifecycle, and holding its lock across them would only make a concurrent
        // `stop` wait on a stat(2). It runs before the launch so the row carries the project from its very
        // first appearance — see [resolveAndRegisterProject].
        val projectId = resolveAndRegisterProject(cwd)

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
                    cliVersion = spec.cliVersion,
                    cliPath = spec.cliPath,
                    cwd = cwd,
                    tmuxSession = tmuxSession,
                    paneId = paneId,
                    state = SessionState.running,
                    stateSource = EventSource.system,
                    createdAt = ts,
                    updatedAt = ts,
                    projectId = projectId,
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
                    // Fallback path (codex always; an older claude without `--session-id`): no id yet. Poll
                    // in the background for the SessionStart hook to deliver it, and — failing that — for
                    // [discoverProviderId] to find it in the provider's own store. Until one of them lands
                    // the session is "id pending" (provider_session_id null → resume blocked). Bounded,
                    // fire-and-forget. The hook is checked first: it is authoritative for THIS session,
                    // whereas discovery infers from what the provider happened to write on disk.
                    idCapture.captureInBackground(sessionId) {
                        store.projectionOf(sessionId).providerSessionId ?: discoverProviderId(meta)
                    }
                }
                // Best-effort, fire-and-forget model capture (Codex reads the rollout post-launch; a no-op
                // for Claude, whose model arrives via the hook path).
                captureModelInBackground(meta)
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
     * A [SessionId] not already used by any stored session or event log, RESERVED for the caller until
     * its [releaseSessionId]. [randomShortId] is only 32 bits, so a collision with a dead HISTORICAL
     * session's `kt-<id>` (whose `sessions` row and event log survive) is improbable but not impossible
     * — and a collision would overwrite that row and splice this new agent into its existing log. So we
     * reject any candidate the store already knows and regenerate, bounded. The id stays 8 hex chars
     * for a stable, copy-pasteable CLI handle; the store check — not the id width — is what makes reuse
     * of a stored id impossible, and the [reservedIds] check is what makes a CONCURRENT allocation safe
     * (see its KDoc: the store cannot answer for an id whose upsert has not happened yet, and the two
     * allocation sites hold different locks). Every caller owes a `try`/`finally` [releaseSessionId].
     */
    private suspend fun freshSessionId(): SessionId = idAllocationGuard.withLock {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = newSessionId()
            if (candidate !in reservedIds &&
                store.getSession(candidate) == null &&
                store.read(candidate, Seq(0)).isEmpty()
            ) {
                reservedIds.add(candidate)
                return@withLock candidate
            }
        }
        error("could not allocate a unique session id after $MAX_ID_ATTEMPTS attempts")
    }

    /**
     * Drop [freshSessionId]'s reservation of [sessionId] (see [reservedIds]). Runs under
     * [NonCancellable]: a launch cancelled mid-flight must still release, or the id — and one
     * [MAX_ID_ATTEMPTS] slot of every future allocation — leaks until the daemon restarts.
     */
    private suspend fun releaseSessionId(sessionId: SessionId): Unit = withContext(NonCancellable) {
        idAllocationGuard.withLock { reservedIds.remove(sessionId) }
    }

    /**
     * The project owning [cwd] — its `.kotgent.json` uuid — with the `projects` row refreshed as a side
     * effect, or `null` when there is no project above [cwd] (or this daemon carries no task layer).
     *
     * ## Why the answer goes into the session row rather than through [EventStore.setProjectId]
     * [start] and [importSession] both INSERT their row, so carrying the id in the [SessionMeta] writes it
     * in the same statement instead of following the insert with a second targeted write and a second
     * `SessionUpdate`. The row is therefore never observable without its project, and the targeted setter
     * keeps its one real caller: [Reconciler]'s backfill, which patches rows that already exist.
     *
     * ## Registering the project is REQUIRED, not decorative
     * Every path that reads a `.kotgent.json` upserts the `projects` row (`TaskStore.upsertProject`):
     * without it a project first seen through a session start has backlog rows but never appears in
     * `GET /api/v1/projects`, so the board's selector can never reach its backlog. `path` is the checkout
     * this daemon saw most recently — worktrees deliberately share one uuid and overwrite one row.
     *
     * ## A failed registration answers `null`, on purpose
     * The two facts are written together or not at all. Reporting the id while the `projects` row is
     * missing would persist `sessions.project_id` and thereby remove the ONE thing that ever retries the
     * pair — [Reconciler]'s backfill only looks at rows whose `project_id` is null. So a store failure
     * degrades to "no project yet" and heals on the next daemon start, instead of pinning a session to a
     * project the board cannot list. It never fails the launch: a session that runs is worth more than the
     * index of the directory it runs in. Cancellation is rethrown — it is not a store failure.
     */
    private suspend fun resolveAndRegisterProject(cwd: String): ProjectId? {
        val fs = projectFs ?: return null
        // Pure and total by contract (`ProjectFs` degrades an unreadable path to "absent" and
        // `parseProjectFile` never throws), so an unguarded call here cannot take a launch with it.
        val resolved = resolveProject(fs, cwd) ?: return null
        val tasks = taskStore ?: return resolved.id
        return try {
            tasks.upsertProject(resolved.id, resolved.name, resolved.root)
            resolved.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            eprintln(
                "warning: could not register project '${resolved.name}' (${resolved.id.value}) " +
                    "at ${resolved.root}: $e",
            )
            null
        }
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
     * "Done" — finish with a session: kill the agent, close the task it was working on, then archive the
     * row off the sidebar (its history survives; "Restore" via [undone] brings it back, still resumable).
     *
     * Composed like [stop], NOT wrapped in [withControlLock]: [terminate] already takes the session's
     * control lock internally, and that [Mutex] is non-reentrant, so an outer lock here would deadlock.
     * [store.setArchived][EventStore.setArchived] is orthogonal (it never touches control state), so a
     * control op racing between the kill and the archive is harmless.
     *
     * ## One session, one task, end to end
     * The human reviews *this* session's terminal and diff, so "Done" on the session is what closes the
     * task ([closeLinkedTask], which also unlinks every other holder). Closing from the BOARD is the
     * mirror image and deliberately different: `TaskService.transition(done)` unlinks the sessions and
     * leaves them **alive**, which is what hands a long-lived worker session back to `task next`.
     * A session holding no link (or a daemon built without the task layer) archives exactly as before.
     *
     * ## The honest guarantee, which is NOT atomicity
     * Closing the task and archiving the session are **two writes to two stores** — deliberately
     * sequential and never nested, because `backlog_entries` and `sessions` have different writers with
     * different mutexes over one driver, and nesting their locks deadlocks a thread apiece. Wrapping
     * them in [NonCancellable] buys exactly one property: **coroutine cancellation cannot land between
     * them**, so a client that walks away mid-request cannot leave the pair half-applied. It is not a
     * transaction, it does not roll back, and it does not survive process death or a throw out of the
     * second write. Do not call this pair atomic.
     *
     * The one residual it leaves is a task marked `done` whose session is still unarchived. That state
     * is visible (the row is still in the sidebar, its badge gone), benign (the agent is already dead)
     * and self-healing: pressing Done again archives the row, and by then the session holds no link, so
     * nothing is closed twice. A throw out of the FIRST write is the same shape from the other side —
     * the session is killed but neither closed nor archived, and Done again finishes the job.
     */
    suspend fun markDone(sessionId: SessionId) {
        terminate(sessionId)
        withContext(NonCancellable) {
            closeLinkedTask(sessionId)
            store.setArchived(sessionId, true, now())
        }
    }

    /**
     * Move the task [sessionId] is linked to into [TaskState.done] and unlink **every** session holding
     * it — the session-side half of "Done", and a no-op when this daemon has no task layer
     * ([taskStore] is `null`), when the row is gone, or when it holds no link.
     *
     * Sequenced exactly like `TaskService.transition(ref, done, …)`, which is the same operation reached
     * from the board: the task store's own transaction writes the state, the `transition` activity row
     * and the reverse-dependent re-stamp, and only after it returns does the [EventStore] clear the
     * holders one at a time. **The two stores' locks are never nested** — each [EventStore] call returns
     * before the [TaskStore] call that follows it is made.
     *
     * It is spelled out here rather than delegated to `TaskService` because [SessionManager] holds the
     * two STORES, not the service: the service is constructed beside the manager in the daemon
     * bootstrap and taking it as a parameter would invert that wiring for a method that needs none of
     * its other collaborators (project resolution, the project-file writer).
     *
     * An unknown ref transitions nothing and unlinks nobody, matching `TaskService`: the task store's
     * `null` is the only place "does this task exist" is asked, and startup reconciliation is what
     * clears a `task_ref` naming a task that is gone.
     */
    private suspend fun closeLinkedTask(sessionId: SessionId) {
        val tasks = taskStore ?: return
        val ref = store.getSession(sessionId)?.taskRef ?: return
        tasks.transition(ref, TaskState.done, author = sessionId.value, message = null) ?: return
        for (holder in store.sessionsHoldingTask(ref)) {
            store.setTaskRef(holder.id, null, now())
            tasks.appendActivity(ref, ActivityKind.unlinked, author = holder.id.value)
        }
    }

    /** Un-archive a "Done" session so it reappears in the sidebar (leaves its dead/resumable state as-is). */
    suspend fun undone(sessionId: SessionId) {
        store.setArchived(sessionId, false, now())
    }

    /**
     * The [undone] half of a [resume]: a resumed session is by definition not "Done", so bring its row
     * back to the sidebar. Runs INSIDE the caller's control lock (unlike [undone], which is its own
     * operator op) and returns the meta the caller must answer with — an HTTP client merges that DTO
     * into its list verbatim, so a stale `archived = true` there would keep the revived row hidden until
     * the next resync even though the write landed.
     *
     * A no-op for the ordinary non-archived resume, so it costs one field read and no store write.
     */
    private suspend fun clearDoneOnResume(meta: SessionMeta): SessionMeta {
        if (!meta.archived) return meta
        val ts = now()
        store.setArchived(meta.id, false, ts)
        return meta.copy(archived = false, updatedAt = ts)
    }

    /**
     * Interrupt a stuck session: send Ctrl-C to un-stick a `running` that will not budge (Claude emits
     * no hook on Esc/Ctrl-C) AND apply [ControlSignal.Interrupt] to the projection (alive → `ready`,
     * approvals cleared). The projection is persisted only after [TmuxControl.sendKeys] returns with
     * verified delivery; an absent target or any other send failure leaves it unchanged. The session
     * stays alive, so its pane stays registered. Once that send returns, Ctrl-C is irreversible:
     * cancellation must not abandon the following projection read/cache write and leave stale state
     * that invites an unsafe second Ctrl-C (some agent TUIs interpret that as quit). Exactly that
     * post-delivery tail runs under [NonCancellable]; the send itself deliberately does not.
     */
    suspend fun interrupt(sessionId: SessionId): Unit = withControlLock(sessionId) {
        // The read (getSession) must happen INSIDE the lock: reading a live row outside it and then
        // reducing from that snapshot is exactly how a racing stop gets overwritten with `ready`.
        val meta = store.getSession(sessionId) ?: return@withControlLock
        tmux.sendKeys(sessionId.value, byteArrayOf(0x03)) // Ctrl-C
        withContext(NonCancellable) {
            // Delivery cannot be rolled back, so cancellation cannot split it from its derived-state write.
            val next = reduce(currentProjection(sessionId, meta), ControlSignal.Interrupt)
            persistDerivedState(meta, next.state, EventSource.user)
        }
    }

    /**
     * Resume a dead session: build a resume launch spec (needs the captured provider id — resume is
     * blocked with [ResumeBlockedException] if it is still pending), start a fresh tmux session, and
     * apply [ControlSignal.Resume] (dead → `ready`). A no-op on an already-alive session — except for
     * the `archived` clearance below, which a launch no-op still owes.
     *
     * A resume also un-archives a "Done" row ([clearDoneOnResume]). `archived` is orthogonal to control
     * state, so nothing else in this path touches it, and a resumed row that stayed archived would be a
     * live agent nobody can see: the sidebar hides it, its state advances invisibly, and only Restore
     * would ever bring it back. A "Done" session is reachable for resume from the sidebar's own "Show
     * done" section (select the row, then Resume) and from the CLI, so this is the ordinary path, not a
     * corner case.
     */
    suspend fun resume(sessionId: SessionId): SessionMeta = withControlLock(sessionId) {
        val meta = store.getSession(sessionId) ?: throw NoSuchSessionException(sessionId)
        // The cache can say "alive" even though the pane died while the daemon was up: there is no live
        // exit hook, and liveness is only reconciled at startup. Confirm real tmux liveness before
        // treating resume as a no-op — otherwise a pane that dies mid-run could not be resumed until a
        // daemon restart. A genuinely-live session is still a launch no-op, but an archived one is
        // exactly the row a lost Done → Resume left behind, so the un-archive must still run.
        if (meta.state.isAlive && isPaneAlive(meta.tmuxSession)) {
            return@withControlLock clearDoneOnResume(meta)
        }
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
            // After the state write, so the un-archive's own SessionUpdate is the LAST one a connected
            // client sees for this resume: it carries both the fresh state and archived=false, and the
            // row un-hides in one step instead of flickering back under the state signal.
            if (meta.archived) store.setArchived(sessionId, false, ts)
            registry.register(paneId, sessionId)
            val revived = meta.copy(
                paneId = paneId,
                state = next.state,
                stateSource = EventSource.user,
                archived = false,
                updatedAt = ts,
            )
            // Best-effort, fire-and-forget model capture on revival too (the same seam start() uses).
            // An IMPORTED codex session has never launched under kotgent, so this is the only place its
            // model can ever be captured — after the resumed agent starts writing its rollout again.
            // A no-op for claude (its model arrives via the hook path).
            captureModelInBackground(revived)
            return@withControlLock revived
        } catch (e: Throwable) {
            // Compensate a failure at or after the fresh agent's launch (see start()): kill it, drop the
            // pane — and PUT THE ROW BACK to its pre-resume dead state. The write above may already have
            // committed `ready` + the fresh pane id, which would otherwise survive as a durable phantom
            // (an "alive" session whose pane we just killed) until the next daemon restart.
            // `archived` is deliberately NOT restored: the state goes back to dead, and a row the
            // operator just asked to resume must stay visible to carry that failure, not vanish again.
            compensateFailedLaunch(sessionId, sessionId.value, paneId, deadState, meta.paneId, e)
            throw e
        }
    }

    /**
     * Reclassify a session after tmux reports that one of its sessions closed. The hook is only a
     * trigger: tmux and the vendor store are re-read here, so a delayed/duplicate callback cannot make
     * the hook payload itself authoritative. An unknown id is intentionally a silent no-op because the
     * socket-level hook is global and can observe tmux sessions kotgent does not own.
     *
     * This belongs in [SessionManager], not [Reconciler], because the complete read → liveness probes →
     * derived-state write must hold the SAME per-session control lock as [resume]. Without that lock, a
     * close callback can observe the old pane as dead, pause while `resume()` launches and persists a
     * fresh live pane, then overwrite `ready` with `resumable`. The next resume collides with the live
     * tmux session name, and launch compensation can then kill that live session.
     *
     * The write deliberately goes through [persistDerivedState], never a stale full-row
     * [EventStore.upsertSession]: provider hooks can advance `last_seq` and bind/replace
     * `provider_session_id`, and this liveness observation owns neither field.
     */
    suspend fun onTmuxSessionClosed(sessionId: SessionId): Unit = withControlLock(sessionId) {
        val meta = store.getSession(sessionId) ?: return@withControlLock
        val paneAlive = isPaneAlive(meta.tmuxSession)
        val stopIntent = meta.state == SessionState.stopped
        val transcriptExists =
            meta.providerSessionId?.let { vendorProbe.hasTranscript(meta.agent, meta.cwd, it) } ?: false
        val newState = Reconciler.classify(paneAlive, meta.state, stopIntent, transcriptExists)

        if (!paneAlive) meta.paneId?.let { registry.unregister(it) }
        if (newState != meta.state) persistDerivedState(meta, newState, EventSource.liveness)
    }

    /** True if tmux currently reports a LIVE (non-dead) pane for [tmuxSession] (`kt-<id>`). */
    private fun isPaneAlive(tmuxSession: String): Boolean =
        tmux.listPanes().any { it.session == tmuxSession && !it.dead }

    /**
     * Import a provider session that was started OUTSIDE kotgent (a conversation begun in a plain
     * terminal), registering it as a `resumable` row + a `SessionBound` in the event log so the
     * existing [resume] can revive it with the provider's own resume launch. Registration ONLY —
     * deliberately free of tmux side effects (no pane, no launch; [TmuxControl.sessionName] is a pure
     * formatter) and of any binary check: [resume] owns the [AgentBinaryNotFoundException] fail-fast
     * with its `kotgent install` hint, so importing a supported kind succeeds even while its binary
     * does not resolve. Returns the stored [SessionMeta].
     *
     * Validation order (each failure a distinct, hierarchy-free exception — see their KDoc):
     *  1. [agentKind] must be in [supportedAgentKinds] → [UnknownAgentKindException] (no adapter is
     *     ever built, so the binary is never touched);
     *  2. no existing kotgent session — archived included — may already hold [providerId] →
     *     [DuplicateImportException] with the existing session's id; [providerId] is first normalized
     *     to lowercase (see the body) so a re-cased id cannot dodge the check;
     *  3. the cwd: an explicit [cwd] wins; otherwise [sessionLocator] discovers it from the
     *     provider's on-disk store; neither → [ImportCwdException];
     *  4. the cwd must be an ABSOLUTE path (the CLI resolves relative paths client-side, but the Web
     *     UI / any API client can send one raw, and the daemon lives under launchd with cwd `/` — a
     *     relative path stored here would later be mis-resolved by `resume`'s `tmux new-session -c`),
     *     and is then CANONICALIZED through the filesystem ([canonicalPath], `realpath(3)`) into the
     *     ONE spelling both the probe key and the row use: `/repo/./`, an uncollapsed `--cwd ../proj`
     *     and a symlinked prefix (`/tmp` for `/private/tmp`) would each miss the Claude transcript key
     *     — falsely rejecting a valid import — or persist a noncanonical codex cwd. A path that does
     *     not resolve, or resolves to a non-directory → [ImportCwdException];
     *  5. [vendorProbe] must see the transcript for exactly `(agentKind, canonical cwd, providerId)` —
     *     the SAME triple stored in the row and re-probed by the [Reconciler] on every daemon start,
     *     so a cwd that STILL does not re-encode to the transcript after canonicalization (a genuinely
     *     different directory) fails loudly here with a `--cwd` hint instead of silently degrading
     *     `resumable → crashed` after the next restart → [TranscriptNotFoundException].
     *
     * The whole method runs under the daemon-wide [importMutex] (see its KDoc).
     *
     * Accepted residual: if [ProviderIdCapture.bind] fails AFTER `upsertSession` committed, the row
     * carries [providerId] without a `SessionBound` in the log. [resume] reads the row, so the session
     * stays functional; the replay divergence is limited to this imported session's provider id.
     *
     * A second accepted residual: a kotgent-launched session whose own id capture is still pending
     * (`provider_session_id` null — codex before the `SessionStart` hook or the rollout scan lands) is
     * invisible to the duplicate gate, so importing that same conversation's id inside that window
     * creates a second row; once the original's background bind lands, two rows share one provider id.
     * There is nothing to match against until the id is known, so this cannot be closed here.
     */
    suspend fun importSession(
        agentKind: String,
        providerId: ProviderSessionId,
        cwd: String? = null,
        name: String? = null,
        tags: List<String> = emptyList(),
    ): SessionMeta = importMutex.withLock {
        if (agentKind !in supportedAgentKinds) {
            throw UnknownAgentKindException(agentKind, supportedAgentKinds)
        }
        // UUID providers (claude, codex) mint and report lowercase ids, but ProviderSessionId accepts
        // uppercase hex and macOS's default filesystem is case-insensitive: an uppercase variant would
        // still find the on-disk transcript, yet never string-match the lowercase id hooks later report —
        // and would slip past the duplicate gate as a "different" id. Normalized once, at the import
        // boundary — but ONLY for a UUID-shaped id: lowercasing is UUID case-insensitivity, and applying
        // it blindly would corrupt a provider id whose case is significant (junie's ids are not UUIDs).
        val id = if (isCanonicalUuid(providerId.value)) {
            ProviderSessionId(providerId.value.lowercase())
        } else {
            providerId
        }
        val duplicate = store.listSessions().firstOrNull { it.providerSessionId == id }
        if (duplicate != null) throw DuplicateImportException(duplicate.id, duplicate.archived)
        val resolvedCwd = cwd
            ?: sessionLocator.cwdOf(agentKind, id)
            ?: throw ImportCwdException(
                "no on-disk record with a readable cwd found for $agentKind session '${id.value}' — " +
                    "pass the project directory explicitly with --cwd; note an archived codex " +
                    "session is out of `codex resume`'s reach and cannot be imported at all",
            )
        if (!resolvedCwd.startsWith("/")) {
            throw ImportCwdException(
                "project directory must be an absolute path: '$resolvedCwd' (the daemon runs with " +
                    "cwd '/', so a relative path would later resolve against the wrong directory)",
            )
        }
        // Canonical through the FILESYSTEM, never lexically (KDoc gate 4): realpath resolves `.`/`..`
        // and symlinks exactly the way the provider's own `getcwd` did when it recorded the transcript,
        // and its failure doubles as the existence gate.
        val canonicalCwd = canonicalPath(resolvedCwd)
            ?: throw ImportCwdException("project directory does not exist: $resolvedCwd")
        if (!isDirectory(canonicalCwd)) {
            throw ImportCwdException("project directory is not a directory: $canonicalCwd")
        }
        if (!vendorProbe.hasTranscript(agentKind, canonicalCwd, id)) {
            throw TranscriptNotFoundException(agentKind, id, canonicalCwd)
        }
        // The canonical cwd is exactly what project resolution wants (it canonicalizes anyway, so this
        // is idempotent) — an import registers the same `project_id` a `start` in that directory would.
        val projectId = resolveAndRegisterProject(canonicalCwd)

        // Reserved until the finally below: [importMutex] serializes imports against each other, but a
        // concurrent [start] holds a different lock — only the reservation keeps both from drawing the
        // same id before either upsert lands (see [reservedIds]).
        val sessionId = freshSessionId()
        try {
            val tmuxSession = tmux.sessionName(sessionId.value) // pure formatter — no tmux side effect
            val ts = now()
            val meta = SessionMeta(
                id = sessionId,
                name = name ?: tmuxSession,
                tags = tags,
                agent = agentKind,
                providerSessionId = id,
                // cliVersion/cliPath/model stay null: filling them would mean running the binary at import
                // time, which contradicts the no-binary-check rule above. `model` arrives after the first
                // resume (claude via hooks, codex via the resume model capture).
                cwd = canonicalCwd,
                tmuxSession = tmuxSession,
                paneId = null,
                state = SessionState.resumable,
                stateSource = EventSource.system,
                createdAt = ts,
                updatedAt = ts,
                projectId = projectId,
            )
            store.upsertSession(meta)
            // The append never resurrects a dead cache state (see SqliteEventStore.append), so the row
            // stays `resumable` through the bind. A bind failure here is the accepted residual (see KDoc).
            idCapture.bind(sessionId, id)
            store.getSession(sessionId) ?: meta
        } finally {
            releaseSessionId(sessionId)
        }
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
     * resume) and tmux-close reclassification are strictly serialized end-to-end — read of the cache
     * row, tmux/vendor observation or side effect, and the derived-state write all inside one critical
     * section (see the class KDoc). Only the tiny lock-table lookup takes the shared [controlLocksGuard];
     * the op itself holds the per-session lock only, so different sessions never block each other.
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
