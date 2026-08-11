package io.kotgent.daemon

import io.kotgent.adapter.extractModel
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.isCanonicalUuid
import io.kotgent.store.EventStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.stat


private const val UUID_LENGTH = 36

private const val ROLLOUT_PREFIX = "rollout-"
private const val ROLLOUT_SUFFIX = ".jsonl"

// The timestamp also contains dashes, so the UUID is parsed from the fixed-width suffix.
fun rolloutFileSessionId(fileName: String): ProviderSessionId? {
    if (!fileName.startsWith(ROLLOUT_PREFIX) || !fileName.endsWith(ROLLOUT_SUFFIX)) return null
    val stem = fileName.substring(ROLLOUT_PREFIX.length, fileName.length - ROLLOUT_SUFFIX.length)
    if (stem.length < UUID_LENGTH + 1) return null
    val candidate = stem.substring(stem.length - UUID_LENGTH)
    if (stem[stem.length - UUID_LENGTH - 1] != '-') return null
    if (!isCanonicalUuid(candidate)) return null
    return runCatching { ProviderSessionId(candidate) }.getOrNull()
}

fun rolloutCwd(head: String): String? = jsonStringField(head, "cwd")

@OptIn(ExperimentalForeignApi::class)
fun defaultCodexDir(): String {
    val explicit = getenv("CODEX_HOME")?.toKString()?.trimEnd('/')
    if (!explicit.isNullOrEmpty()) return explicit
    val home = getenv("HOME")?.toKString()?.trimEnd('/')
    return if (home.isNullOrEmpty()) ".codex" else "$home/.codex"
}

@OptIn(ExperimentalForeignApi::class)
class CodexRolloutScan(private val codexDir: String = defaultCodexDir()) {

    // Archived rollouts are outside this root and cannot be resumed by Codex.
    private val sessionsRoot: String get() = "${codexDir.trimEnd('/')}/sessions"

    fun hasRollout(providerSessionId: ProviderSessionId): Boolean =
        rolloutFiles().any { rolloutFileSessionId(it.name) == providerSessionId }

    fun discoverSessionId(cwd: String, notBeforeMillis: Long): ProviderSessionId? {
        // The launch cutoff avoids binding an older same-cwd session; slack absorbs filesystem clock rounding.
        val threshold = notBeforeMillis - MTIME_SLACK_MILLIS
        return rolloutFiles()
            .filter { it.mtimeMillis >= threshold }
            .sortedByDescending { it.mtimeMillis }
            .firstNotNullOfOrNull { file ->
                val id = rolloutFileSessionId(file.name) ?: return@firstNotNullOfOrNull null
                val head = readHead(file.path, HEAD_BYTES) ?: return@firstNotNullOfOrNull null
                if (rolloutCwd(head) == cwd) id else null
            }
    }

    fun cwdOf(providerSessionId: ProviderSessionId): String? =
        rolloutHeadOf(providerSessionId, HEAD_BYTES)?.let(::rolloutCwd)

    fun modelOf(providerSessionId: ProviderSessionId): String? =
        rolloutHeadOf(providerSessionId, MODEL_SCAN_BYTES)?.let(::extractModel)

    private fun rolloutHeadOf(providerSessionId: ProviderSessionId, bytes: Int): String? =
        rolloutFiles().firstNotNullOfOrNull { file ->
            if (rolloutFileSessionId(file.name) != providerSessionId) return@firstNotNullOfOrNull null
            readHead(file.path, bytes)
        }

    private data class RolloutFile(val name: String, val path: String, val mtimeMillis: Long)

    private fun rolloutFiles(): List<RolloutFile> {
        // Codex's fixed date hierarchy is walked explicitly rather than recursively following surprises.
        val out = ArrayList<RolloutFile>()
        for (year in listDir(sessionsRoot)) {
            val yearPath = "$sessionsRoot/$year"
            for (month in listDir(yearPath)) {
                val monthPath = "$yearPath/$month"
                for (day in listDir(monthPath)) {
                    val dayPath = "$monthPath/$day"
                    for (file in listDir(dayPath)) {
                        if (!file.endsWith(ROLLOUT_SUFFIX)) continue
                        val path = "$dayPath/$file"
                        out.add(RolloutFile(file, path, mtimeMillis(path) ?: continue))
                    }
                }
            }
        }
        return out
    }

    private fun mtimeMillis(path: String): Long? = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return@memScoped null
        st.st_mtimespec.tv_sec * 1000L + st.st_mtimespec.tv_nsec / 1_000_000L
    }

    companion object {
        const val HEAD_BYTES: Int = 8 * 1024

        const val MODEL_SCAN_BYTES: Int = 256 * 1024

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
    now: () -> Long = ::daemonEpochMillis,
): Boolean {
    // Re-read the row because provider-id capture may land after the launch snapshot.
    val providerId = store.getSession(meta.id)?.providerSessionId
        ?: meta.providerSessionId
        ?: return false
    val model = scan.modelOf(providerId) ?: return false
    // The conditional write rejects a model scan raced by a provider-id rebind.
    return store.setModelForProvider(meta.id, providerId, model, now())
}
