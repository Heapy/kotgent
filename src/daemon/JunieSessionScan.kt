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


private const val SESSION_PREFIX = "session-"

private const val EVENTS_FILE = "events.jsonl"

data class JunieIndexRecord(
    val sessionId: String,
    val projectDir: String?,
    val createdAtMillis: Long?,
)

fun junieIndexRecord(line: String): JunieIndexRecord? {
    val id = jsonStringField(line, "sessionId") ?: return null
    if (runCatching { ProviderSessionId(id) }.isFailure) return null
    return JunieIndexRecord(
        sessionId = id,
        projectDir = jsonStringField(line, "projectDir"),
        createdAtMillis = jsonLongField(line, "createdAt"),
    )
}

@OptIn(ExperimentalForeignApi::class)
fun defaultJunieDir(): String {
    val explicit = getenv("JUNIE_HOME")?.toKString()?.trimEnd('/')
    if (!explicit.isNullOrEmpty()) return explicit
    val home = getenv("HOME")?.toKString()?.trimEnd('/')
    return if (home.isNullOrEmpty()) ".junie" else "$home/.junie"
}

@OptIn(ExperimentalForeignApi::class)
class JunieSessionScan(private val junieDir: String = defaultJunieDir()) {

    private val sessionsRoot: String get() = "${junieDir.trimEnd('/')}/sessions"

    private val indexPath: String get() = "$sessionsRoot/index.jsonl"

    fun hasSession(providerSessionId: ProviderSessionId): Boolean =
        // The directory is authoritative; Junie's index can retain rows after session pruning.
        access(eventsPath(providerSessionId), F_OK) == 0

    fun discoverSessionId(cwd: String, notBeforeMillis: Long): ProviderSessionId? {
        // Junie writes the index row only after a task runs, so directories are candidates and the
        // index only rejects a positively mismatched cwd. Birth time avoids old active sessions.
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

    fun cwdOf(providerSessionId: ProviderSessionId): String? {
        if (!hasSession(providerSessionId)) return null
        return indexRecords()[providerSessionId.value]?.projectDir
    }

    fun modelOf(providerSessionId: ProviderSessionId): String? =
        // modelUsage mixes primary and helper models, hence dominant rather than first-match extraction.
        readHead(eventsPath(providerSessionId), MODEL_SCAN_BYTES)?.let(::extractDominantModel)

    private fun eventsPath(providerSessionId: ProviderSessionId): String =
        "$sessionsRoot/${providerSessionId.value}/$EVENTS_FILE"

    private fun indexRecords(): Map<String, JunieIndexRecord> {
        // Read the tail so recent rows remain visible as the append-style index grows.
        val text = readTail(indexPath, INDEX_TAIL_BYTES) ?: return emptyMap()
        val out = LinkedHashMap<String, JunieIndexRecord>()
        for (line in text.lineSequence()) {
            val record = junieIndexRecord(line) ?: continue
            out[record.sessionId] = record
        }
        return out
    }

    private data class SessionDir(val id: String, val createdAtMillis: Long)

    private fun sessionDirs(): List<SessionDir> =
        listDir(sessionsRoot).mapNotNull { name ->
            if (!name.startsWith(SESSION_PREFIX)) return@mapNotNull null
            val path = "$sessionsRoot/$name"
            if (access("$path/$EVENTS_FILE", F_OK) != 0) return@mapNotNull null
            SessionDir(name, birthMillis(path) ?: return@mapNotNull null)
        }

    private fun providerId(name: String): ProviderSessionId? =
        runCatching { ProviderSessionId(name) }.getOrNull()

    private fun birthMillis(path: String): Long? = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return@memScoped null
        val birth = st.st_birthtimespec.tv_sec * 1000L + st.st_birthtimespec.tv_nsec / 1_000_000L
        if (birth > 0L) birth else st.st_mtimespec.tv_sec * 1000L + st.st_mtimespec.tv_nsec / 1_000_000L
    }

    companion object {
        const val INDEX_TAIL_BYTES: Int = 1024 * 1024

        const val MODEL_SCAN_BYTES: Int = 256 * 1024

        const val BIRTH_SLACK_MILLIS: Long = 2_000
    }
}

fun junieVendorStoreProbe(junieDir: String = defaultJunieDir()): VendorStoreProbe {
    val scan = JunieSessionScan(junieDir)
    return VendorStoreProbe { _, _, providerSessionId -> scan.hasSession(providerSessionId) }
}

fun junieSessionLocator(junieDir: String = defaultJunieDir()): VendorSessionLocator {
    val scan = JunieSessionScan(junieDir)
    return VendorSessionLocator { _, providerSessionId -> scan.cwdOf(providerSessionId) }
}

suspend fun captureJunieModelOnce(
    store: EventStore,
    scan: JunieSessionScan,
    meta: SessionMeta,
): Boolean {
    // Provider-id discovery may land after launch; a stale launch snapshot must not choose the model.
    val providerId = store.getSession(meta.id)?.providerSessionId
        ?: meta.providerSessionId
        ?: return false
    val model = scan.modelOf(providerId) ?: return false
    // Reject a filesystem scan raced by a provider-id rebind.
    return store.setModelForProvider(meta.id, providerId, model)
}
