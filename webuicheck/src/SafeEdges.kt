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

// Browser-driven directory completion, uploads, and project-file writes must never touch developer disk.
val HARNESS_DIRECTORIES: List<String> = listOf(
    "/a/b",
    "/a/c",
    "/a/.hidden",
    "/d",
    "/projects/kotgent",
    "/projects/kotgent-web",
)

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

fun harnessDirectoryCompleter(fs: FakeProjectFs): DirectoryCompleter =
    DirectoryCompleter { basePath, input ->
        completeDirectoryPaths(basePath, input) { parent -> childDirectoryNames(fs.directories, parent) }
    }

private fun childDirectoryNames(tree: Set<String>, parent: String): List<String> {
    val prefix = if (parent == "/") "/" else "${parent.trimEnd('/')}/"
    return tree.asSequence()
        .filter { it.length > prefix.length && it.startsWith(prefix) }
        .map { it.substring(prefix.length) }
        .filter { !it.contains('/') }
        .distinct()
        .toList()
}

/** Streaming in-memory uploader that preserves production size and collision failures. */
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
        const val UPLOAD_CHUNK_BYTES = 64 * 1024
    }
}
