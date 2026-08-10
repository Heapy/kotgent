package io.kotgent.webuicheck

import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.daemonEpochMillis
import io.kotgent.store.FakeEventStore
import io.kotgent.store.FakeTaskStore
import io.kotgent.task.FakeProjectFs
import io.kotgent.task.MemoryProjectFileWriter
import io.kotgent.transport.DirectoryCompleter
import io.kotgent.transport.FileUploadResult
import io.kotgent.transport.FileUploader
import io.kotgent.transport.MAX_UPLOAD_FILE_BYTES
import io.kotgent.transport.completeDirectoryPaths
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * The three edges that would otherwise write to the developer's filesystem, replaced in memory.
 *
 * A browser driving this fixture can reach all three: the directory completer (New session's cwd field
 * AND New project's path field — two consumers now), the upload sink (`POST /sessions/{id}/files`, the
 * palette's `f` command), and the project-file writer (`POST /projects`, which creates a real
 * `.kotgent.json` inside somebody's repository). Nothing here touches a disk, which is what makes
 * "the harness never creates a `.kotgent.json` anywhere" true by construction rather than by care.
 */

/**
 * The directory tree every scenario starts with. It is the ONE declaration of the harness filesystem:
 * [FakeProjectFs] is seeded from it (which expands ancestors itself) and the completer lists children
 * out of it, so the path a browser completes and the path project resolution then canonicalizes cannot
 * drift apart.
 *
 * The entries are chosen to exercise the completion rules rather than to look realistic: `/a/b` and
 * `/a/c` share a parent (two candidates for `/a/`), `/projects/kotgent` and `/projects/kotgent-web`
 * share a PREFIX (so a typed `kotgent` narrows to two), and `/a/.hidden` is only offered once the typed
 * segment itself starts with a dot.
 */
val HARNESS_DIRECTORIES: List<String> = listOf(
    "/a/b",
    "/a/c",
    "/a/.hidden",
    "/d",
    "/projects/kotgent",
    "/projects/kotgent-web",
)

/**
 * The five doubles, built together.
 *
 * [MemoryProjectFileWriter] publishes into the [FakeProjectFs] handed to it, and that is the whole
 * reason this is one function instead of five constructor calls at the use site: with two separate
 * trees a second `POST /projects` in the same directory would not see the first one's file and would
 * mint a SECOND project id for one directory — precisely the `link(2)` convergence the real writer has.
 *
 * The clock is the real one. A frozen clock would render every session as "56 years ago" in the
 * sidebar; the cost is that a browser assertion must read relative labels, never absolute timestamps.
 */
fun newHarnessFakes(): HarnessFakes {
    val projectFs = FakeProjectFs(dirs = HARNESS_DIRECTORIES)
    return HarnessFakes(
        tmux = FakeTmux(),
        events = FakeEventStore(now = ::daemonEpochMillis),
        tasks = FakeTaskStore(now = ::daemonEpochMillis),
        projectFs = projectFs,
        projectFiles = MemoryProjectFileWriter(projectFs),
    )
}

/**
 * The completer the server is given: the production POLICY ([completeDirectoryPaths] — prefix match,
 * hidden entries only for a dotted prefix, case-insensitive sort, the shared limit) over an in-memory
 * listing instead of `opendir`. Reusing the pure policy is the point: a browser test then asserts the
 * behaviour the daemon really has, not a second implementation of it.
 */
fun harnessDirectoryCompleter(dirs: Set<String> = harnessDirectoryTree()): DirectoryCompleter =
    DirectoryCompleter { basePath, input ->
        completeDirectoryPaths(basePath, input) { parent -> childDirectoryNames(dirs, parent) }
    }

/** [HARNESS_DIRECTORIES] with every ancestor made a directory of its own, mirroring `FakeProjectFs`. */
fun harnessDirectoryTree(dirs: List<String> = HARNESS_DIRECTORIES): Set<String> {
    val tree = LinkedHashSet<String>()
    for (dir in dirs) {
        var current = ""
        for (segment in dir.split('/')) {
            if (segment.isEmpty()) continue
            current += "/$segment"
            tree += current
        }
    }
    return tree
}

/** Immediate child directory NAMES of [parent] within [tree] — the in-memory `posixChildDirectoryNames`. */
private fun childDirectoryNames(tree: Set<String>, parent: String): List<String> {
    val prefix = if (parent == "/") "/" else "${parent.trimEnd('/')}/"
    return tree.asSequence()
        .filter { it.length > prefix.length && it.startsWith(prefix) }
        .map { it.substring(prefix.length) }
        .filter { !it.contains('/') }
        .distinct()
        .toList()
}

/**
 * The upload sink: bytes land in a map keyed by `<directory>/<name>`, never on a disk.
 *
 * It models the two answers the browser's upload loop actually branches on — a stored file reports its
 * size, and a name already present in that directory is [FileUploadResult.AlreadyExists] rather than an
 * overwrite (the real writer's no-clobber `link(2)`). The size bound and the declared-length check are
 * kept because they are the two ways a well-formed request is still refused; the deadline is not, since
 * nothing here can stall.
 */
class MemoryFileUploader(private val maxBytes: Long = MAX_UPLOAD_FILE_BYTES) : FileUploader {

    private val mutex = Mutex()
    private val files = LinkedHashMap<String, ByteArray>()

    /** Every accepted upload, in arrival order, keyed by `<directory>/<name>`. */
    suspend fun stored(): Map<String, ByteArray> = mutex.withLock { LinkedHashMap(files) }

    /** Forget everything uploaded so far, so one scenario's uploads cannot leak into the next check. */
    suspend fun clear() {
        mutex.withLock { files.clear() }
    }

    override suspend fun upload(
        directory: String,
        fileName: String,
        body: ByteReadChannel,
        expectedBytes: Long?,
    ): FileUploadResult {
        if (expectedBytes != null && expectedBytes > maxBytes) return FileUploadResult.TooLarge
        if (expectedBytes != null && expectedBytes < 0L) return FileUploadResult.LengthMismatch

        val buffer = ByteArray(UPLOAD_CHUNK_BYTES)
        val chunks = ArrayList<ByteArray>()
        var received = 0L
        while (true) {
            val count = try {
                body.readAvailable(buffer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return FileUploadResult.Failed("request body read failed: ${e.message ?: e::class.simpleName}")
            }
            if (count < 0) break
            if (count == 0) continue
            if (received > maxBytes - count.toLong()) return FileUploadResult.TooLarge
            chunks += buffer.copyOf(count)
            received += count
        }
        if (expectedBytes != null && received != expectedBytes) return FileUploadResult.LengthMismatch

        val key = "${directory.trimEnd('/')}/$fileName"
        return mutex.withLock {
            if (key in files) {
                FileUploadResult.AlreadyExists
            } else {
                files[key] = joinChunks(chunks, received.toInt())
                FileUploadResult.Stored(received)
            }
        }
    }

    private fun joinChunks(chunks: List<ByteArray>, total: Int): ByteArray {
        if (chunks.size == 1) return chunks[0]
        val joined = ByteArray(total)
        var at = 0
        for (chunk in chunks) {
            chunk.copyInto(joined, at)
            at += chunk.size
        }
        return joined
    }

    private companion object {
        /** Read granularity. Nothing here is I/O-bound, so this only bounds the copy loop's overhead. */
        const val UPLOAD_CHUNK_BYTES = 64 * 1024
    }
}
