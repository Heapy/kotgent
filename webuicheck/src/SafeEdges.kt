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
 *
 * [newHarnessFakes] lives here rather than beside `HarnessFakes` for the same reason: two of the five
 * doubles it builds are two of those edges, and they have to share ONE tree (see its own note). The
 * remaining three come along because a fixture is assembled once, not edge by edge.
 */

/**
 * The directory tree every scenario STARTS with — the seed, not the whole filesystem.
 *
 * [FakeProjectFs] is built from it (expanding ancestors itself) and then a scenario adds its own
 * directories on top: `/w/terminal`, `/repo/board`, `/repo/empty`, `/repo/detail`, `/repo/linked`,
 * `/repo/deep`, `/w/restart-*`. The completer therefore lists children out of the LIVE tree
 * ([harnessDirectoryCompleter]), never out of this list, which is what actually makes the path a
 * browser completes and the path project resolution then canonicalizes the same set. Reading this list
 * directly would have made them differ by every directory a scenario ever added.
 *
 * The entries are chosen to exercise the completion rules rather than to look realistic: `/a/b` and
 * `/a/c` share a parent (two candidates for `/a/`), `/projects/kotgent` and `/projects/kotgent-web`
 * share a PREFIX (so a typed `kotgent` narrows to two), and `/a/.hidden` is only offered once the typed
 * segment itself starts with a dot. Those three rules are pinned by a browser test
 * (`SessionDialogsTest.theWorkingDirectoryCompletesFromTheDaemonAndCommitsWithTheKeyboard`, which runs
 * on the `empty` scenario precisely so the seed IS the whole tree there); do not remove an entry.
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
 * hidden entries only for a dotted prefix, case-insensitive sort, the shared limit) over [fs]'s live
 * listing instead of `opendir`. Reusing the pure policy is the point: a browser test then asserts the
 * behaviour the daemon really has, not a second implementation of it.
 *
 * **[fs], not [HARNESS_DIRECTORIES].** The listing is re-read from the tree on every keystroke, so a
 * directory a scenario declared (or a `POST /projects` published a `.kotgent.json` into) is completable
 * the moment it exists. Snapshotting the seed instead made the completer answer about a filesystem the
 * rest of the harness had already moved past — `/repo/board` resolved as a project and could not be
 * typed.
 */
fun harnessDirectoryCompleter(fs: FakeProjectFs): DirectoryCompleter =
    DirectoryCompleter { basePath, input ->
        completeDirectoryPaths(basePath, input) { parent -> childDirectoryNames(fs.directories, parent) }
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
 *
 * ## It keeps NAMES, not bytes, and that is deliberate rather than lazy
 * The browser tier is a DIFFERENT PROCESS, so a `stored()` accessor it could never call would be an
 * inspection API for nobody: what a browser test reads is the response the route returns — a size, or
 * the `409` this set's `AlreadyExists` produces. The only thing the second upload of one name needs from
 * the first is that the KEY is there, so retaining up to [MAX_UPLOAD_FILE_BYTES] of body per file bought
 * nothing and made a fixture's memory a function of what a test happened to pick. The body is still READ
 * to the end and counted — the size the route reports is evidence about the content, and a channel left
 * undrained is not the same request.
 */
class MemoryFileUploader : FileUploader {

    private val mutex = Mutex()
    private val stored = LinkedHashSet<String>()

    override suspend fun upload(
        directory: String,
        fileName: String,
        body: ByteReadChannel,
        expectedBytes: Long?,
    ): FileUploadResult {
        if (expectedBytes != null && expectedBytes > MAX_UPLOAD_FILE_BYTES) return FileUploadResult.TooLarge
        if (expectedBytes != null && expectedBytes < 0L) return FileUploadResult.LengthMismatch

        val buffer = ByteArray(UPLOAD_CHUNK_BYTES)
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
            if (received > MAX_UPLOAD_FILE_BYTES - count.toLong()) return FileUploadResult.TooLarge
            received += count
        }
        if (expectedBytes != null && received != expectedBytes) return FileUploadResult.LengthMismatch

        val key = "${directory.trimEnd('/')}/$fileName"
        return mutex.withLock {
            if (!stored.add(key)) FileUploadResult.AlreadyExists else FileUploadResult.Stored(received)
        }
    }

    private companion object {
        /** Read granularity. Nothing here is I/O-bound, so this only bounds the copy loop's overhead. */
        const val UPLOAD_CHUNK_BYTES = 64 * 1024
    }
}
