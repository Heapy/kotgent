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
import io.kotgent.task.ProjectRegistration
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

enum class StopMode {
    Detach,

    Interrupt,

    Kill,
}

fun interface AgentFactory {
    fun create(agentKind: String, cwd: String): AgentAdapter
}

const val CLAUDE_AGENT_KIND: String = "claude"

const val CODEX_AGENT_KIND: String = "codex"

const val JUNIE_AGENT_KIND: String = "junie"

const val SHELL_AGENT_KIND: String = "shell"

fun importableAgentKinds(kinds: Set<String>): Set<String> = kinds - SHELL_AGENT_KIND

fun agentFactoryOf(builders: Map<String, (cwd: String) -> AgentAdapter>): AgentFactory =
    AgentFactory { agentKind, cwd ->
        val build = builders[agentKind] ?: throw UnsupportedAgentException(agentKind, builders.keys)
        build(cwd)
    }

class PaneRegistry {
    private val mutex = Mutex()
    private val map = HashMap<PaneId, SessionId>()

    suspend fun register(pane: PaneId, session: SessionId): Unit = mutex.withLock { map[pane] = session }

    suspend fun unregister(pane: PaneId) {
        mutex.withLock { map.remove(pane) }
    }

    suspend fun lookup(pane: PaneId): SessionId? = mutex.withLock { map[pane] }

    suspend fun replaceAll(entries: Map<PaneId, SessionId>): Unit = mutex.withLock {
        map.clear()
        map.putAll(entries)
    }

    suspend fun snapshot(): Map<PaneId, SessionId> = mutex.withLock { HashMap(map) }
}

class NoSuchSessionException(val sessionId: SessionId) :
    NoSuchElementException("no such session: ${sessionId.value}")

class ResumeBlockedException(val sessionId: SessionId) :
    IllegalStateException("resume blocked: provider session id is pending for ${sessionId.value}")

class UnsupportedAgentException(val agentKind: String, val supported: Set<String> = emptySet()) :
    IllegalArgumentException(
        "unsupported agent kind '$agentKind'" +
            if (supported.isEmpty()) "" else " (supported: ${supported.sorted().joinToString(", ")})",
    )

class AgentBinaryNotFoundException(val agentKind: String) :
    IllegalStateException(
        "agent '$agentKind' not found on the daemon's PATH — run `kotgent install` from a shell where " +
            "`$agentKind` is on your PATH (install `$agentKind` first if needed), then create the session again",
    )

fun requireAbsoluteBinary(agentKind: String, located: String?): String =
    // tmux changes to the session cwd before exec, so a relative result could select a cwd-local binary.
    located?.takeIf { it.startsWith("/") } ?: throw AgentBinaryNotFoundException(agentKind)

// These import failures stay unrelated so transport can map each status independently.
class UnknownAgentKindException(val agentKind: String, val supported: Set<String>) :
    RuntimeException(
        "unknown agent kind '$agentKind' (supported: ${supported.sorted().joinToString(", ")})",
    )

class ImportCwdException(message: String) : RuntimeException(message)

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

class DuplicateImportException(val existingId: SessionId, val archived: Boolean) :
    RuntimeException(
        "provider session already imported as kotgent session '${existingId.value}'" +
            if (archived) " (archived — Restore it instead of importing again)" else "",
    )

@OptIn(ExperimentalForeignApi::class)
// Filesystem canonicalization is required: lexical `..` handling is wrong across symlinks.
fun canonicalPath(path: String): String? {
    val resolved = realpath(path, null) ?: return null
    return try {
        resolved.toKString()
    } finally {
        free(resolved)
    }
}

class CompensationFailure(message: String, cause: Throwable) : IllegalStateException(message, cause)

// Each session's cache read, tmux side effect, and derived-state write are serialized end-to-end.
class SessionManager(
    private val tmux: TmuxControl,
    private val store: EventStore,
    val registry: PaneRegistry,
    private val agentFactory: AgentFactory,
    private val idCapture: ProviderIdCapture,
    // Required collaborators: production must explicitly choose the same vendor view used by reconciliation.
    private val vendorProbe: VendorStoreProbe,
    private val sessionLocator: VendorSessionLocator,
    private val supportedAgentKinds: Set<String>,
    private val discoverProviderId: suspend (SessionMeta) -> ProviderSessionId? = { null },
    private val captureModelInBackground: (SessionMeta) -> Unit = {},
    private val newSessionId: () -> SessionId = { SessionId(randomShortId()) },
    private val now: () -> Long = ::daemonEpochMillis,
    private val cols: Int = DEFAULT_COLS,
    private val rows: Int = DEFAULT_ROWS,
    private val taskStore: TaskStore? = null,
    private val projectFs: ProjectFs? = null,
) {
    val paneLookup: suspend (PaneId) -> SessionId? get() = registry::lookup

    suspend fun onProviderIdRebound(sessionId: SessionId) {
        // A provisional same-cwd discovery may have captured another session's model.
        store.setModel(sessionId, null, now())
        store.getSession(sessionId)?.let(captureModelInBackground)
    }

    fun leaveCopyMode(sessionId: SessionId): Boolean = tmux.leaveCopyMode(sessionId.value)

    // Makes duplicate-provider-id check plus registration indivisible between concurrent imports.
    private val importMutex = Mutex()

    private val idAllocationGuard = Mutex()

    // Covers the interval before an allocation becomes visible in the store to a concurrent start/import.
    private val reservedIds = HashSet<SessionId>()

    private val controlLocksGuard = Mutex()

    private val controlLocks = HashMap<SessionId, Mutex>()

    suspend fun rebuildRegistryFromStore() {
        val entries = store.listSessions()
            .filter { it.paneId != null && it.state.isAlive }
            .associate { it.paneId!! to it.id }
        registry.replaceAll(entries)
    }

    suspend fun start(
        agentKind: String,
        cwd: String,
        name: String? = null,
        tags: List<String> = emptyList(),
    ): SessionMeta {
        val sessionId = freshSessionId()
        try {
            return startReserved(sessionId, agentKind, cwd, name, tags)
        } finally {
            releaseSessionId(sessionId)
        }
    }

    private suspend fun startReserved(
        sessionId: SessionId,
        agentKind: String,
        cwd: String,
        name: String?,
        tags: List<String>,
    ): SessionMeta {
        val shortId = sessionId.value
        val tmuxSession = tmux.sessionName(shortId)
        val adapter = agentFactory.create(agentKind, cwd)
        val spec = adapter.buildLaunchSpec(LaunchMode.New)
        val projectId = resolveAndRegisterProject(cwd)

        return withControlLock(sessionId) {
            var paneId: PaneId? = null
            try {
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
                // Publish the row first: hook routing may begin as soon as the pane is registered.
                store.upsertSession(meta)
                registry.register(paneId, sessionId)

                val prealloc = spec.preallocatedSessionId
                if (prealloc != null) {
                    idCapture.bind(sessionId, prealloc)
                } else {
                    idCapture.captureInBackground(sessionId) {
                        store.projectionOf(sessionId).providerSessionId ?: discoverProviderId(meta)
                    }
                }
                captureModelInBackground(meta)
                store.getSession(sessionId) ?: meta
            } catch (e: Throwable) {
                compensateFailedLaunch(sessionId, shortId, paneId, SessionState.crashed, null, e)
                throw e
            }
        }
    }

    private suspend fun compensateFailedLaunch(
        sessionId: SessionId,
        shortId: String,
        paneId: PaneId?,
        restoreState: SessionState,
        restorePaneId: PaneId?,
        cause: Throwable,
    ): Unit = withContext(NonCancellable) {
        // Cancellation cannot strand an orphan pane; cleanup failures remain visible on the primary error.
        fun note(what: String, failure: Throwable) =
            cause.addSuppressed(CompensationFailure("compensation failed for '$shortId': $what", failure))

        runCatching { tmux.killSession(shortId) }.onFailure { note("kill-session", it) }
        if (paneId != null) {
            runCatching { registry.unregister(paneId) }.onFailure { note("unregister pane ${paneId.value}", it) }
        }
        runCatching { store.updateSessionState(sessionId, restoreState, EventSource.system, restorePaneId, now()) }
            .onFailure { note("restore state to $restoreState", it) }
    }

    private suspend fun freshSessionId(): SessionId = idAllocationGuard.withLock {
        // Check both the current row and historical log; reusing either identity would splice histories.
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

    private suspend fun releaseSessionId(sessionId: SessionId): Unit = withContext(NonCancellable) {
        idAllocationGuard.withLock { reservedIds.remove(sessionId) }
    }

    /**
     * Null for two unrelated reasons, and only one of them is worth repairing later.
     *
     * A registration that FAILED leaves the row unstamped so startup reconciliation retries it — that is
     * what the warning below is for. A registration REFUSED because the project carries the delete
     * tombstone is the operator's own decision: `.kotgent.json` outlives the row, so resolution keeps
     * finding the file forever, and binding the id would resurrect a deleted project on the next start.
     * That case is quiet and final for as long as the mark stands; it is cleared by adopt or restore,
     * never by a retry.
     */
    private suspend fun resolveAndRegisterProject(cwd: String): ProjectId? {
        val fs = projectFs ?: return null
        val tasks = taskStore ?: return null
        val resolved = resolveProject(fs, cwd) ?: return null
        return try {
            // An id is returned only once its project row exists; write both or neither.
            when (tasks.upsertProject(resolved.id, resolved.name, resolved.root)) {
                ProjectRegistration.registered -> resolved.id
                ProjectRegistration.refusedArchived -> null
            }
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

    suspend fun stop(sessionId: SessionId, mode: StopMode = StopMode.Kill) {
        when (mode) {
            StopMode.Detach -> detach(sessionId)
            StopMode.Interrupt -> interrupt(sessionId)
            StopMode.Kill -> terminate(sessionId)
        }
    }

    suspend fun markDone(sessionId: SessionId) {
        terminate(sessionId)
        // These stores cannot share a transaction; NonCancellable only prevents a client cancellation gap.
        withContext(NonCancellable) {
            closeLinkedTask(sessionId)
            store.setArchived(sessionId, true, now())
        }
    }

    private suspend fun closeLinkedTask(sessionId: SessionId) {
        val tasks = taskStore ?: return
        val ref = store.getSession(sessionId)?.taskRef ?: return
        tasks.transition(ref, TaskState.done, author = sessionId.value, message = null) ?: return
        for (holder in store.sessionsHoldingTask(ref)) {
            // Preserve a newer task link made after the holder snapshot.
            if (!store.clearTaskRefIf(holder.id, ref, now())) continue
            tasks.appendActivity(ref, ActivityKind.unlinked, author = holder.id.value)
        }
    }

    suspend fun undone(sessionId: SessionId) {
        store.setArchived(sessionId, false, now())
    }

    private suspend fun clearDoneOnResume(meta: SessionMeta): SessionMeta {
        if (!meta.archived) return meta
        val ts = now()
        store.setArchived(meta.id, false, ts)
        return meta.copy(archived = false, updatedAt = ts)
    }

    suspend fun interrupt(sessionId: SessionId): Unit = withControlLock(sessionId) {
        val meta = store.getSession(sessionId) ?: return@withControlLock
        tmux.sendKeys(sessionId.value, byteArrayOf(0x03))
        // Delivery cannot be rolled back, so cancellation must not split it from the cache update.
        withContext(NonCancellable) {
            val next = reduce(currentProjection(sessionId, meta), ControlSignal.Interrupt)
            persistDerivedState(meta, next.state, EventSource.user)
        }
    }

    suspend fun resume(sessionId: SessionId): SessionMeta = withControlLock(sessionId) {
        val meta = store.getSession(sessionId) ?: throw NoSuchSessionException(sessionId)
        // Cache liveness can be stale until reconciliation; confirm the pane before making resume a no-op.
        if (meta.state.isAlive && isPaneAlive(meta.tmuxSession)) {
            return@withControlLock clearDoneOnResume(meta)
        }
        val providerId = meta.providerSessionId ?: throw ResumeBlockedException(sessionId)

        val adapter = agentFactory.create(meta.agent, meta.cwd)
        val spec = adapter.buildLaunchSpec(LaunchMode.Resume(providerId))
        val deadState = if (meta.state.isDead) meta.state else SessionState.crashed

        var paneId: PaneId? = null
        try {
            paneId = tmux.newSession(sessionId.value, meta.cwd, shellCommand(spec.command), cols, rows)
            val next = reduce(store.projectionOf(sessionId).copy(state = deadState), ControlSignal.Resume)
            val ts = now()
            // Targeted fields only: a stale full-row write could clobber concurrent hook sequence/id updates.
            store.updateSessionState(sessionId, next.state, EventSource.user, paneId, ts)
            // Emit the unarchive after state so connected clients receive one final visible row.
            if (meta.archived) store.setArchived(sessionId, false, ts)
            registry.register(paneId, sessionId)
            val revived = meta.copy(
                paneId = paneId,
                state = next.state,
                stateSource = EventSource.user,
                archived = false,
                updatedAt = ts,
            )
            captureModelInBackground(revived)
            return@withControlLock revived
        } catch (e: Throwable) {
            compensateFailedLaunch(sessionId, sessionId.value, paneId, deadState, meta.paneId, e)
            throw e
        }
    }

    suspend fun onTmuxSessionClosed(sessionId: SessionId): Unit = withControlLock(sessionId) {
        // The hook is only a trigger; tmux/vendor state is authoritative under the same lock as resume.
        val meta = store.getSession(sessionId) ?: return@withControlLock
        val paneAlive = isPaneAlive(meta.tmuxSession)
        val stopIntent = meta.state == SessionState.stopped
        val transcriptExists =
            meta.providerSessionId?.let { vendorProbe.hasTranscript(meta.agent, meta.cwd, it) } ?: false
        val newState = Reconciler.classify(paneAlive, meta.state, stopIntent, transcriptExists)

        if (!paneAlive) meta.paneId?.let { registry.unregister(it) }
        if (newState != meta.state) persistDerivedState(meta, newState, EventSource.liveness)
    }

    private fun isPaneAlive(tmuxSession: String): Boolean =
        tmux.listPanes().any { it.session == tmuxSession && !it.dead }

    suspend fun importSession(
        agentKind: String,
        providerId: ProviderSessionId,
        cwd: String? = null,
        name: String? = null,
        tags: List<String> = emptyList(),
    ): SessionMeta = importMutex.withLock {
        // Import is registration-only: binary resolution and tmux launch remain resume's responsibility.
        if (agentKind !in supportedAgentKinds) {
            throw UnknownAgentKindException(agentKind, supportedAgentKinds)
        }
        // UUIDs are case-insensitive; non-UUID provider ids may be case-sensitive.
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
        val canonicalCwd = canonicalPath(resolvedCwd)
            ?: throw ImportCwdException("project directory does not exist: $resolvedCwd")
        if (!isDirectory(canonicalCwd)) {
            throw ImportCwdException("project directory is not a directory: $canonicalCwd")
        }
        if (!vendorProbe.hasTranscript(agentKind, canonicalCwd, id)) {
            throw TranscriptNotFoundException(agentKind, id, canonicalCwd)
        }
        val projectId = resolveAndRegisterProject(canonicalCwd)

        val sessionId = freshSessionId()
        try {
            val tmuxSession = tmux.sessionName(sessionId.value)
            val ts = now()
            val meta = SessionMeta(
                id = sessionId,
                name = name ?: tmuxSession,
                tags = tags,
                agent = agentKind,
                providerSessionId = id,
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
            idCapture.bind(sessionId, id)
            store.getSession(sessionId) ?: meta
        } finally {
            releaseSessionId(sessionId)
        }
    }

    fun detach(sessionId: SessionId) {
    }

    private suspend fun terminate(sessionId: SessionId): Unit = withControlLock(sessionId) {
        val meta = store.getSession(sessionId) ?: return@withControlLock
        // Persist stop intent before kill so a crash between them cannot misclassify a clean stop.
        val terminal = reduce(
            reduce(store.projectionOf(sessionId), ControlSignal.Stop),
            AgentEvent.Exited(TMUX_KILL_EXIT),
        )
        persistDerivedState(meta, terminal.state, EventSource.user)
        try {
            tmux.killSession(sessionId.value)
        } catch (e: Throwable) {
            // A failed kill may leave the pane alive, so roll the armed intent back without cancellation.
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

    private suspend fun persistDerivedState(meta: SessionMeta, state: SessionState, source: EventSource) {
        // Never upsert the stale meta: hooks independently own lastSeq and providerSessionId.
        store.updateSessionState(meta.id, state, source, meta.paneId, now())
    }

    private suspend fun <T> withControlLock(sessionId: SessionId, block: suspend () -> T): T {
        val lock = controlLocksGuard.withLock { controlLocks.getOrPut(sessionId) { Mutex() } }
        return lock.withLock { block() }
    }

    private suspend fun currentProjection(sessionId: SessionId, meta: SessionMeta) =
        store.projectionOf(sessionId).copy(state = meta.state)

    companion object {
        const val DEFAULT_COLS: Int = 120
        const val DEFAULT_ROWS: Int = 40

        private const val MAX_ID_ATTEMPTS: Int = 8

        private const val TMUX_KILL_EXIT: Int = 129

        // tmux invokes a shell; quote each argv element as one POSIX literal word.
        fun shellCommand(argv: List<String>): String =
            argv.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
    }
}

fun randomShortId(random: Random = Random.Default): String {
    val bytes = random.nextBytes(4)
    return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

@OptIn(ExperimentalTime::class)
fun daemonEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
