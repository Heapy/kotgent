@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.kotgent.host

import io.kotgent.cinterop.pty.kotgent_cache_lock
import io.kotgent.cinterop.pty.kotgent_cache_open
import io.kotgent.cinterop.pty.kotgent_cache_publish
import io.kotgent.cinterop.pty.kotgent_cache_remove
import io.kotgent.cinterop.pty.kotgent_openat_read
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.posix.*

/** Fixed slots bound cleanup work and filenames even after crashes or competing writers. */
class DiskCacheFiles(
    private val root: String,
    private val maxBytes: Long = 256L * 1024 * 1024,
    private val maxEntries: Int = 1024,
    private val maxEntryBytes: Int = 8 * 1024 * 1024,
    private val ttlMillis: Long = 7L * 24 * 60 * 60 * 1000,
) {
    init {
        require(maxBytes > 0 && maxEntries in 1..SLOTS && maxEntryBytes in 1..8 * 1024 * 1024 && ttlMillis > 0)
    }

    /** Refuse a configured cache under any repository/worktree, including a home-directory repo. */
    fun isOutside(vararg paths: String): Boolean {
        if (!root.startsWith('/') || root.substringAfterLast('/') in listOf("", ".", "..")) return false
        val canonical = canonicalWorktreePath(root) ?: run {
            val parent = canonicalWorktreePath(root.substringBeforeLast('/').ifEmpty { "/" }) ?: return false
            parent.removeSuffix("/") + "/" + root.substringAfterLast('/')
        }
        return paths.none { canonical == it || canonical.startsWith(it.removeSuffix("/") + "/") }
    }

    suspend fun get(slot: Int, now: Long): ByteArray? = access { directory ->
        require(slot in 0..<SLOTS)
        val record = record(directory, slot.toString(), now, full = true) ?: return@access null
        record.bytes
    }

    suspend fun put(slot: Int, bytes: ByteArray, now: Long) {
        require(slot in 0..<SLOTS)
        if (bytes.size > maxEntryBytes) return
        val output = "$now\n".encodeToByteArray() + bytes
        if (output.size > maxEntryBytes || output.size > maxBytes) return
        val _ = access { directory ->
            // A crashed writer can leave only this one staging name; no writer holds the lock now.
            if (!remove(directory, "pending")) return@access null
            val target = slot.toString()
            val records = mutableListOf<Record>()
            for (i in 0..<SLOTS) {
                currentCoroutineContext().ensureActive()
                record(directory, i.toString(), now, full = false)?.let { records += it }
            }
            if (!remove(directory, target)) return@access null
            val survivors = records.filter { it.name != target }.sortedBy { it.created }
            var total = survivors.sumOf { it.size }
            var count = survivors.size
            // Evict before staging: the staging record also fits the total byte budget.
            for (old in survivors) {
                if (total + output.size <= maxBytes && count < maxEntries) break
                if (!remove(directory, old.name)) return@access null
                total -= old.size
                count--
            }
            if (total + output.size > maxBytes || count >= maxEntries) return@access null
            val fd = kotgent_cache_open(directory, "pending", 1)
            if (fd < 0) return@access null
            try {
                var offset = 0
                while (offset < output.size) {
                    currentCoroutineContext().ensureActive()
                    val written = output.usePinned { write(fd, it.addressOf(offset), (output.size - offset).convert()) }
                    if (written < 0 && errno == EINTR) continue
                    if (written <= 0) return@access null
                    offset += written.toInt()
                }
                if (fsync(fd) != 0) return@access null
                currentCoroutineContext().ensureActive()
                if (kotgent_cache_publish(directory, target) != 0) return@access null
            } finally {
                close(fd)
                val _ = remove(directory, "pending")
            }
            Unit
        }
    }

    private class Unavailable : RuntimeException()

    private data class Record(val name: String, val created: Long, val size: Long, val bytes: ByteArray?)

    private fun record(directory: Int, name: String, now: Long, full: Boolean): Record? {
        val fd = kotgent_openat_read(directory, name, O_RDONLY or O_NOFOLLOW or O_CLOEXEC or O_NONBLOCK)
        if (fd < 0) {
            if (!remove(directory, name)) throw Unavailable()
            return null
        }
        val result = try {
            memScoped {
                val stat = alloc<stat>()
                if (fstat(fd, stat.ptr) != 0 || !privateFile(stat) || stat.st_size !in 1..maxEntryBytes.toLong()) return@memScoped null
                val bytes = ByteArray(if (full) stat.st_size.toInt() else minOf(32, stat.st_size.toInt()))
                var size = 0
                while (size < bytes.size) {
                    val n = bytes.usePinned { read(fd, it.addressOf(size), (bytes.size - size).convert()) }
                    if (n < 0 && errno == EINTR) continue
                    if (n <= 0) return@memScoped null
                    size += n.toInt()
                }
                val separator = bytes.indexOf(10)
                if (separator !in 1..20) return@memScoped null
                val created = bytes.copyOfRange(0, separator).decodeToString().toLongOrNull() ?: return@memScoped null
                if (created > now || created <= now - ttlMillis) return@memScoped null
                Record(name, created, stat.st_size, if (full) bytes.copyOfRange(separator + 1, size) else null)
            }
        } finally { close(fd) }
        if (result == null && !remove(directory, name)) throw Unavailable()
        return result
    }

    private fun privateFile(stat: stat): Boolean = (stat.st_mode.toInt() and S_IFMT) == S_IFREG &&
        stat.st_uid == geteuid() && (stat.st_mode.toInt() and 63) == 0 && stat.st_nlink.toLong() == 1L

    private fun remove(directory: Int, name: String): Boolean = kotgent_cache_remove(directory, name) == 0 || errno == ENOENT

    private suspend fun <T> access(block: suspend (Int) -> T?): T? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (!root.startsWith('/') || '\u0000' in root) return@withContext null
        // The caller owns the parent. Do not create missing parent trees or follow a root symlink.
        if (mkdir(root, 448u) != 0 && errno != EEXIST) return@withContext null
        val directory = open(root, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC or O_NONBLOCK)
        if (directory < 0) return@withContext null
        try {
            val safe = memScoped {
                val stat = alloc<stat>()
                fstat(directory, stat.ptr) == 0 && stat.st_uid == geteuid() && (stat.st_mode.toInt() and 63) == 0
            }
            if (!safe) return@withContext null
            val lock = kotgent_cache_open(directory, "lock", 0)
            if (lock < 0) return@withContext null
            try {
                val safeLock = memScoped { val stat = alloc<stat>(); fstat(lock, stat.ptr) == 0 && privateFile(stat) }
                if (!safeLock || kotgent_cache_lock(lock) != 0) return@withContext null
                try { block(directory) } catch (_: Unavailable) { null }
            } finally { close(lock) } // Closing releases flock; the permanent lock inode is never removed.
        } finally { close(directory) }
    }

    companion object { const val SLOTS = 1024 }
}
