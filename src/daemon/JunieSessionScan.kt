package io.kotgent.daemon

import io.kotgent.adapter.extractDominantModel
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionMeta
import io.kotgent.store.EventStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv
import platform.posix.stat

/*
 * Junie's on-disk session record — the identity/resumability half of the Junie integration, the analogue
 * of [CodexRolloutScan].
 *
 * Junie stores sessions under `$JUNIE_HOME` (default `~/.junie`):
 *
 *     sessions/index.jsonl                one JSON record per session:
 *                                         {"sessionId","createdAt","updatedAt","projectDir","taskName"}
 *     sessions/<sessionId>/events.jsonl   the session's event stream
 *     sessions/<sessionId>/state.json     the saved agent state (only once the session ran a task)
 *
 * Three things are read out of that layout:
 *
 *  1. **Provider-id discovery.** Junie has no `claude --session-id` equivalent (its `--session-id` only
 *     names an EXISTING session to resume), and its documented `SessionStart` hook payload carries no id,
 *     so a freshly launched session's id must be found on disk — see [JunieSessionScan.discoverSessionId].
 *
 *  2. **Resumability.** A dead session is resumable iff its session directory survives
 *     ([JunieSessionScan.hasSession]). The DISK is the authority, not the index: Junie prunes old session
 *     context, and a stale index row for a pruned session must not offer a revival that fails.
 *
 *  3. **Import discovery + the model.** `projectDir` from the index ([JunieSessionScan.cwdOf]) and the
 *     dominant model from the session's own `events.jsonl` ([JunieSessionScan.modelOf]).
 *
 * ## Why discovery walks DIRECTORIES and treats the index as a filter
 * The obvious design — "the newest index record whose `projectDir` is my cwd" — does not work, and this
 * was measured, not assumed: Junie writes a session's index row (and its `state.json`) only once the
 * session has actually run a TASK. On a real `~/.junie` two sessions were live in the same junie process
 * and only one had an index row; the other had 150 KB of events, no `state.json` and no row, because the
 * operator had not submitted a prompt in it. A fresh `kotgent start junie` is exactly that state, and
 * [ProviderIdCapture]'s bounded poll (20 × 250 ms) expires long before a human types their first prompt —
 * so an index-only discovery would leave essentially every junie session "id pending", i.e. unresumable.
 *
 * The session DIRECTORY, by contrast, exists from the moment junie starts. So discovery enumerates
 * directories and uses the index only to EXCLUDE a candidate it can positively attribute to a different
 * project dir. A candidate with no index row yet is kept (its cwd is simply unknown), which is what makes
 * the id bind within seconds of launch.
 *
 * ## Why creation time, not mtime
 * [CodexRolloutScan] can threshold on mtime because a rollout file belongs to one session's lifetime.
 * Here the threshold must be a BIRTH time: a long-running junie session that was started hours ago keeps
 * writing to its `events.jsonl`, so its mtime is always "just now" and an mtime threshold would happily
 * offer it as the session this launch just created. macOS records `st_birthtimespec`, which is exactly the
 * question being asked.
 */

/** Prefix of a junie session directory / id (`session-260730-015553-1j1h`). */
private const val SESSION_PREFIX = "session-"

/** The per-session event stream inside a session directory — the file whose presence IS the session. */
private const val EVENTS_FILE = "events.jsonl"

/**
 * The fields kotgent reads from one `sessions/index.jsonl` record. [projectDir] and [createdAtMillis] are
 * nullable because a record is only as good as what it carries — a missing field is "unknown", never fatal.
 */
data class JunieIndexRecord(
    val sessionId: String,
    val projectDir: String?,
    val createdAtMillis: Long?,
)

/**
 * Parse one `index.jsonl` line into a [JunieIndexRecord], or `null` when it carries no usable
 * `sessionId`. Pure and host-free.
 *
 * Field scans rather than a JSON parse, for the same reason as [rolloutCwd]: the caller reads a BOUNDED
 * TAIL of the index ([readTail]), so its first line is usually cut off — an unparseable line must be
 * skipped, not fatal. The `sessionId` is additionally required to be a usable [ProviderSessionId] shape,
 * so a garbage line cannot introduce an id kotgent would later put in a path or an argv.
 */
fun junieIndexRecord(line: String): JunieIndexRecord? {
    val id = jsonStringField(line, "sessionId") ?: return null
    if (runCatching { ProviderSessionId(id) }.isFailure) return null
    return JunieIndexRecord(
        sessionId = id,
        projectDir = jsonStringField(line, "projectDir"),
        createdAtMillis = jsonLongField(line, "createdAt"),
    )
}

/** `$JUNIE_HOME`, else `~/.junie` (falls back to a cwd-relative `.junie` if `$HOME` is unset). */
@OptIn(ExperimentalForeignApi::class)
fun defaultJunieDir(): String {
    val explicit = getenv("JUNIE_HOME")?.toKString()?.trimEnd('/')
    if (!explicit.isNullOrEmpty()) return explicit
    val home = getenv("HOME")?.toKString()?.trimEnd('/')
    return if (home.isNullOrEmpty()) ".junie" else "$home/.junie"
}

/**
 * Reads Junie's session tree under [junieDir] (see the file header). All methods degrade to
 * `null`/`false`/empty on any filesystem trouble — a missing or unreadable `~/.junie` means "nothing
 * known", never an exception into the daemon. Nothing here WRITES: the user's junie home is read-only
 * to kotgent.
 */
@OptIn(ExperimentalForeignApi::class)
class JunieSessionScan(private val junieDir: String = defaultJunieDir()) {

    private val sessionsRoot: String get() = "${junieDir.trimEnd('/')}/sessions"

    private val indexPath: String get() = "$sessionsRoot/index.jsonl"

    /**
     * Whether the session directory for [providerSessionId] still holds its event stream — the
     * resumability probe. The DISK answers, never the index: junie prunes old session context, and a
     * stale index row for a pruned session would otherwise offer a resume that cannot work.
     */
    fun hasSession(providerSessionId: ProviderSessionId): Boolean =
        access(eventsPath(providerSessionId), F_OK) == 0

    /**
     * The id of the junie session CREATED at or after [notBeforeMillis] (epoch millis) that is not
     * attributable to a project directory other than [cwd] — newest first — or `null` if there is none.
     *
     * [notBeforeMillis] is what keeps this from binding the WRONG session: the caller passes the moment
     * its own launch began, so a junie the operator started by hand an hour ago is out of scope even
     * though it is still writing events. A small [BIRTH_SLACK_MILLIS] tolerance absorbs sub-second
     * clock/rounding differences between kotgent's clock and the filesystem's.
     *
     * The [cwd] check is a FILTER, not a requirement (see the file header): a candidate whose index row
     * names a different `projectDir` is excluded, but a candidate with no row yet — the normal state of a
     * session whose operator has not typed a prompt — is kept.
     */
    fun discoverSessionId(cwd: String, notBeforeMillis: Long): ProviderSessionId? {
        val threshold = notBeforeMillis - BIRTH_SLACK_MILLIS
        val records = indexRecords()
        return sessionDirs()
            .filter { it.createdAtMillis >= threshold }
            .sortedByDescending { it.createdAtMillis }
            .firstNotNullOfOrNull { dir ->
                val recordedCwd = records[dir.id]?.projectDir
                if (recordedCwd != null && recordedCwd != cwd) null else providerId(dir.id)
            }
    }

    /**
     * The `projectDir` junie recorded for [providerSessionId], or `null` — the Junie half of import
     * discovery ([junieSessionLocator]). Only the INDEX can answer (the event stream records no project
     * directory), and only while the session survives on disk: importing a session whose directory was
     * pruned would register a row that `resume` cannot revive. A session that never ran a task has no
     * index row and therefore no discoverable cwd — the operator passes `--cwd`, and there is nothing to
     * resume in such a session anyway.
     */
    fun cwdOf(providerSessionId: ProviderSessionId): String? {
        if (!hasSession(providerSessionId)) return null
        return indexRecords()[providerSessionId.value]?.projectDir
    }

    /**
     * The dominant model in the session's own `events.jsonl`, or `null` — the ONE model lookup, keyed by
     * ID, so a busier neighbour session can never answer for this one. Junie records a `modelUsage` list
     * per turn mixing the primary model with helper models, hence [extractDominantModel] over a bounded
     * [MODEL_SCAN_BYTES] head. A session that has not taken a turn yet carries no model at all — an
     * honest `null` that [captureJunieModelOnce]'s retry loop polls again.
     */
    fun modelOf(providerSessionId: ProviderSessionId): String? =
        readHead(eventsPath(providerSessionId), MODEL_SCAN_BYTES)?.let(::extractDominantModel)

    /** Path of the event stream that defines the session directory for [providerSessionId]. */
    private fun eventsPath(providerSessionId: ProviderSessionId): String =
        "$sessionsRoot/${providerSessionId.value}/$EVENTS_FILE"

    /**
     * The index rows keyed by session id, read from a bounded TAIL of `index.jsonl` (the newest rows are
     * at the end). A later row for the same id wins, so an append-style update supersedes its predecessor.
     */
    private fun indexRecords(): Map<String, JunieIndexRecord> {
        val text = readTail(indexPath, INDEX_TAIL_BYTES) ?: return emptyMap()
        val out = LinkedHashMap<String, JunieIndexRecord>()
        for (line in text.lineSequence()) {
            val record = junieIndexRecord(line) ?: continue
            out[record.sessionId] = record
        }
        return out
    }

    /** One session directory found on disk: its [id] and its creation time in epoch millis. */
    private data class SessionDir(val id: String, val createdAtMillis: Long)

    /**
     * Every `sessions/<session-…>/` directory that still holds an `events.jsonl`, with its creation time.
     * A directory without the event stream is not a session (an interrupted create, or a pruned one).
     */
    private fun sessionDirs(): List<SessionDir> =
        listDir(sessionsRoot).mapNotNull { name ->
            if (!name.startsWith(SESSION_PREFIX)) return@mapNotNull null
            val path = "$sessionsRoot/$name"
            if (access("$path/$EVENTS_FILE", F_OK) != 0) return@mapNotNull null
            SessionDir(name, birthMillis(path) ?: return@mapNotNull null)
        }

    /** [name] as a [ProviderSessionId], or `null` if a directory name is not a usable id. */
    private fun providerId(name: String): ProviderSessionId? =
        runCatching { ProviderSessionId(name) }.getOrNull()

    /**
     * Creation time of [path] in epoch millis, or `null` if it cannot be stat'ed. macOS records a real
     * birth time; where it is absent (reported as `0`) the mtime is used as a degraded fallback — see the
     * file header for why a birth time is the right question.
     */
    private fun birthMillis(path: String): Long? = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return@memScoped null
        val birth = st.st_birthtimespec.tv_sec * 1000L + st.st_birthtimespec.tv_nsec / 1_000_000L
        if (birth > 0L) birth else st.st_mtimespec.tv_sec * 1000L + st.st_mtimespec.tv_nsec / 1_000_000L
    }

    companion object {
        /**
         * How much of the END of `index.jsonl` to read. A record is ~200 bytes, so 1 MB spans thousands of
         * sessions while keeping the read O(1) — and reading the TAIL (not the head) is what keeps the
         * NEWEST rows in the window (see [readTail]).
         */
        const val INDEX_TAIL_BYTES: Int = 1024 * 1024

        /**
         * How much of a session's `events.jsonl` to read when looking for the model. The first turns are at
         * the START of the stream (that is when model capture runs), and 256 KB spans them comfortably
         * while staying bounded — the same window [CodexRolloutScan] uses for the same job.
         */
        const val MODEL_SCAN_BYTES: Int = 256 * 1024

        /** Tolerance on the creation-time cutoff, absorbing clock/rounding skew (see [discoverSessionId]). */
        const val BIRTH_SLACK_MILLIS: Long = 2_000
    }
}

/**
 * The production [VendorStoreProbe] for Junie: a dead session is resumable iff its session directory
 * still holds an `events.jsonl` under `<junieDir>/sessions/` (see the file header). `cwd` is not part of
 * the key — junie names a session directory by id alone — so it is accepted and ignored, keeping the
 * probe interface uniform across providers.
 */
fun junieVendorStoreProbe(junieDir: String = defaultJunieDir()): VendorStoreProbe {
    val scan = JunieSessionScan(junieDir)
    return VendorStoreProbe { _, _, providerSessionId -> scan.hasSession(providerSessionId) }
}

/**
 * The production [VendorSessionLocator] for Junie: [JunieSessionScan.cwdOf] behind the uniform
 * `(agent, id)` shape (the agent kind is accepted and ignored — dispatch already chose this locator).
 */
fun junieSessionLocator(junieDir: String = defaultJunieDir()): VendorSessionLocator {
    val scan = JunieSessionScan(junieDir)
    return VendorSessionLocator { _, providerSessionId -> scan.cwdOf(providerSessionId) }
}

/**
 * One attempt of the background model-capture poll for a junie session: read this session's own ID-KEYED
 * event stream ([JunieSessionScan.modelOf]) and persist a hit. Returns `true` when a model was persisted —
 * the answer that ends the caller's retry loop. The exact shape of [captureCodexModelOnce], for the same
 * reasons, which are worth restating because both halves are load-bearing:
 *
 *  - the provider id is re-read from the ROW on every attempt, never taken from the launch-time [meta]
 *    snapshot: a fresh junie launch has no id yet and the background id discovery can land mid-poll, so
 *    only from that moment can any attempt answer. While the id is unknown an attempt persists NOTHING —
 *    there is deliberately no cwd-based guess, which could stamp a same-cwd neighbour's model permanently;
 *  - the write is atomically CONDITIONAL on the row still holding the id this attempt was keyed by
 *    ([EventStore.setModelForProvider]). The id is read, the (slow) filesystem scan runs, and only then is
 *    the model persisted; a hook `SessionBound` that displaced the id inside that window fires the
 *    ingress rebind correction, and an unconditional write here would race past its clear. A raced attempt
 *    writes zero rows and answers `false`, so the poll simply retries keyed off the row's new id.
 */
suspend fun captureJunieModelOnce(
    store: EventStore,
    scan: JunieSessionScan,
    meta: SessionMeta,
    now: () -> Long = ::daemonEpochMillis,
): Boolean {
    val providerId = store.getSession(meta.id)?.providerSessionId
        ?: meta.providerSessionId
        ?: return false // no id yet — persist nothing, keep polling (see the KDoc)
    val model = scan.modelOf(providerId) ?: return false
    return store.setModelForProvider(meta.id, providerId, model, now())
}
