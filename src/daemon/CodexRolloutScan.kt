package io.kotgent.daemon

import io.kotgent.adapter.extractModel
import io.kotgent.adapter.codex.extractCodexRateLimits
import io.kotgent.host.fileTimes
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.UsageObservation
import io.kotgent.host.DecodedFileRead
import io.kotgent.host.RegularFileStatus
import io.kotgent.host.regularFileStatus
import io.kotgent.store.EventStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.getenv


fun rolloutCwd(head: String): String? = jsonStringField(head, "cwd")

@OptIn(ExperimentalForeignApi::class)
fun defaultCodexDir(): String {
    val explicit = getenv("CODEX_HOME")?.toKString()?.trimEnd('/')
    if (!explicit.isNullOrEmpty()) return explicit
    val home = getenv("HOME")?.toKString()?.trimEnd('/')
    return if (home.isNullOrEmpty()) ".codex" else "$home/.codex"
}

@OptIn(ExperimentalForeignApi::class)
class CodexRolloutScan(
    private val codexDir: String = defaultCodexDir(),
    private val visitEntries: suspend (String, suspend (String) -> Boolean) -> Boolean = ::visitDirectoryEntries,
) {

    // Archived rollouts are outside this root and cannot be resumed by Codex.
    private val sessionsRoot: String get() = "${codexDir.trimEnd('/')}/sessions"
    private val reader = CodexRolloutReader()
    private val usageLookupMutex = Mutex()
    private val usagePaths = LinkedHashMap<ProviderSessionId, RolloutLocation>()

    fun hasRollout(providerSessionId: ProviderSessionId): Boolean =
        rolloutFiles().any { rolloutFileSessionId(it.location.canonicalPath.substringAfterLast('/')) == providerSessionId }

    fun discoverSessionId(cwd: String, notBeforeMillis: Long): ProviderSessionId? {
        // The launch cutoff avoids binding an older same-cwd session; slack absorbs filesystem clock rounding.
        val threshold = notBeforeMillis - MTIME_SLACK_MILLIS
        return rolloutFiles()
            .filter { it.mtimeMillis >= threshold }
            .sortedByDescending { it.mtimeMillis }
            .firstNotNullOfOrNull { file ->
                val id = rolloutFileSessionId(file.location.canonicalPath.substringAfterLast('/')) ?: return@firstNotNullOfOrNull null
                val head = readHead(file.location, HEAD_BYTES) ?: return@firstNotNullOfOrNull null
                if (rolloutCwd(head) == cwd) id else null
            }
    }

    fun cwdOf(providerSessionId: ProviderSessionId): String? =
        rolloutHeadOf(providerSessionId, HEAD_BYTES)?.let(::rolloutCwd)

    fun modelOf(providerSessionId: ProviderSessionId): String? =
        rolloutHeadOf(providerSessionId, MODEL_SCAN_BYTES)?.let(::extractModel)

    suspend fun rateLimitsOf(providerSessionId: ProviderSessionId): List<UsageObservation> = usageLookupMutex.withLock {
        currentCoroutineContext().ensureActive()
        var tail = usagePaths.remove(providerSessionId)?.let { location ->
            reader.tail(location, TOKEN_COUNT_TAIL_BYTES).also {
                if (it != DecodedFileRead.Missing) usagePaths[providerSessionId] = location
            }
        }
        if (tail == null || tail == DecodedFileRead.Missing) {
            val _ = visitEntries(sessionsRoot) { year ->
                val yearPath = "$sessionsRoot/$year"
                visitEntries(yearPath) { month ->
                    val monthPath = "$yearPath/$month"
                    visitEntries(monthPath) { day ->
                        val dayPath = "$monthPath/$day"
                        visitEntries(dayPath) file@ { name ->
                            // ID lookup needs neither neighbouring rollouts' stat calls nor their contents.
                            if (rolloutFileSessionId(name) != providerSessionId) return@file true
                            val location = RolloutLocation.fromPath("$dayPath/$name") ?: return@file true
                            tail = reader.tail(location, TOKEN_COUNT_TAIL_BYTES)
                            if (tail == DecodedFileRead.Missing) return@file true
                            if (usagePaths.size >= MAX_USAGE_PATHS) {
                                val _ = usagePaths.remove(usagePaths.keys.first())
                            }
                            usagePaths[providerSessionId] = location
                            // A present authoritative file ends lookup even if it has no usable quota.
                            false
                        }
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        // A record without ordering evidence cannot become fresh merely because its tail was scanned.
        (tail as? DecodedFileRead.Window)?.let {
            extractCodexRateLimits(it.bytes, providerSessionId.value, it.logicalOffset).filter { it.source != null }
        }
            ?: emptyList()
    }

    private fun rolloutHeadOf(providerSessionId: ProviderSessionId, bytes: Int): String? =
        rolloutFiles().firstNotNullOfOrNull { file ->
            if (rolloutFileSessionId(file.location.canonicalPath.substringAfterLast('/')) != providerSessionId) return@firstNotNullOfOrNull null
            readHead(file.location, bytes)
        }

    private fun readHead(location: RolloutLocation, bytes: Int): String? =
        (reader.prefix(location, bytes) as? DecodedFileRead.Window)?.bytes?.decodeToString()

    private data class RolloutFile(val location: RolloutLocation, val mtimeMillis: Long)

    private fun rolloutFiles(): List<RolloutFile> {
        // Codex's fixed date hierarchy is walked explicitly rather than recursively following surprises.
        val out = ArrayList<RolloutFile>()
        val seen = HashSet<RolloutLocation>()
        for (year in listDir(sessionsRoot)) {
            val yearPath = "$sessionsRoot/$year"
            for (month in listDir(yearPath)) {
                val monthPath = "$yearPath/$month"
                for (day in listDir(monthPath)) {
                    val dayPath = "$monthPath/$day"
                    for (file in listDir(dayPath)) {
                        val location = RolloutLocation.fromPath("$dayPath/$file") ?: continue
                        if (!seen.add(location)) continue
                        val selected = resolveRollout(location) ?: continue
                        if (regularFileStatus(selected.path) != RegularFileStatus.REGULAR) continue
                        out.add(RolloutFile(location, mtimeMillis(selected.path) ?: continue))
                    }
                }
            }
        }
        return out
    }

    private fun mtimeMillis(path: String): Long? = fileTimes(path)?.modifiedMillis

    companion object {
        const val HEAD_BYTES: Int = 8 * 1024

        const val MODEL_SCAN_BYTES: Int = 256 * 1024

        const val TOKEN_COUNT_TAIL_BYTES: Int = 256 * 1024

        private const val MAX_USAGE_PATHS: Int = 128

        const val MTIME_SLACK_MILLIS: Long = 2_000
    }
}

fun codexVendorStoreProbe(codexDir: String = defaultCodexDir()): VendorStoreProbe {
    val scan = CodexRolloutScan(codexDir)
    return VendorStoreProbe { _, _, providerSessionId -> scan.hasRollout(providerSessionId) }
}

suspend fun captureCodexModelOnce(
    store: EventStore,
    scan: CodexRolloutScan,
    meta: SessionMeta,
): Boolean {
    // Re-read the row because provider-id capture may land after the launch snapshot.
    val providerId = store.getSession(meta.id)?.providerSessionId
        ?: meta.providerSessionId
        ?: return false
    val model = scan.modelOf(providerId) ?: return false
    // The conditional write rejects a model scan raced by a provider-id rebind.
    return store.setModelForProvider(meta.id, providerId, model)
}
