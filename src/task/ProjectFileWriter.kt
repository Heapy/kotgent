package io.kotgent.task

import io.kotgent.core.ProjectId
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.S_IRGRP
import platform.posix.S_IROTH
import platform.posix.S_IRUSR
import platform.posix.S_IWGRP
import platform.posix.S_IWOTH
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.errno
import platform.posix.fchmod
import platform.posix.fsync
import platform.posix.getpid
import platform.posix.link
import platform.posix.mkstemp
import platform.posix.strerror
import platform.posix.umask
import platform.posix.unlink
import platform.posix.write

/**
 * Publishes a sibling temp with `link(2)`, so an existing file is never replaced and concurrent creators
 * adopt the winner's project id. The committed file uses `0666 & ~umask`, not secret-file permissions.
 */
interface ProjectFileWriter {

    suspend fun ensureProjectFile(dir: String, name: String): ProjectFile
}

const val PROJECT_FILE_SCHEMA_URL: String =
    "https://raw.githubusercontent.com/Heapy/kotgent/main/schema/project.v1.json"

fun projectFileText(file: ProjectFile): String =
    """
    {
      "${'$'}schema": "$PROJECT_FILE_SCHEMA_URL",
      "id": "${file.id.value}",
      "name": ${Json.encodeToString(String.serializer(), file.name)}
    }
    """.trimIndent() + "\n"

class PosixProjectFileWriter(
    private val newId: () -> ProjectId = { ProjectId.mint() },
) : ProjectFileWriter {

    private val fs = PosixProjectFs()

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun ensureProjectFile(dir: String, name: String): ProjectFile {
        val directory = directoryOrRefuse(dir)
        val projectName = validatedName(dir, name)
        val target = childPath(directory, PROJECT_FILE_NAME)
        readExisting(dir, target)?.let { return it }

        val minted = ProjectFile(newId(), projectName)
        val bytes = projectFileText(minted).encodeToByteArray()
        val temp = createTemp(directory)
            ?: throw ProjectPathException(
                dir,
                "cannot create $PROJECT_FILE_NAME in '$directory': " +
                    "no temporary file could be created (${errnoText(errno)})",
            )
        var fd = temp.fd
        try {
            writeAll(fd, bytes)?.let { throw ProjectPathException(dir, "cannot write $target: $it") }
            if (fsync(fd) != 0) {
                throw ProjectPathException(dir, "cannot write $target: fsync failed (${errnoText(errno)})")
            }
            if (fchmod(fd, committedFileMode().convert()) != 0) {
                throw ProjectPathException(dir, "cannot write $target: chmod failed (${errnoText(errno)})")
            }
            // Do not retry close after EINTR on macOS: the descriptor may already have been released and reused.
            val closing = fd
            fd = -1
            if (close(closing) != 0) {
                throw ProjectPathException(dir, "cannot write $target: close failed (${errnoText(errno)})")
            }

            if (link(temp.path, target) == 0) return minted
            val linkError = errno
            if (linkError != EEXIST) {
                throw ProjectPathException(dir, "cannot create $target: ${errnoText(linkError)}")
            }
            return readExisting(dir, target)
                ?: throw ProjectPathException(
                    dir,
                    "$target appeared while it was being created but cannot be read back",
                )
        } finally {
            if (fd >= 0) close(fd)
            unlink(temp.path)
        }
    }

    private fun directoryOrRefuse(dir: String): String {
        if (!dir.startsWith('/')) {
            throw ProjectPathException(
                dir,
                "cannot create $PROJECT_FILE_NAME in '$dir': the path must be absolute",
            )
        }
        val directory = dir.trimEnd('/').ifEmpty { "/" }
        if (!fs.isDirectory(directory)) {
            throw ProjectPathException(
                dir,
                "cannot create $PROJECT_FILE_NAME in '$dir': not an existing directory",
            )
        }
        return directory
    }

    private fun validatedName(dir: String, name: String): String {
        val trimmed = name.trim()
        val problem = when {
            trimmed.isEmpty() -> "must not be blank"
            trimmed.length > PROJECT_NAME_MAX_LENGTH ->
                "must be at most $PROJECT_NAME_MAX_LENGTH characters"

            trimmed.any { it.code < 0x20 || it.code == 0x7f } -> "must not contain control characters"
            else -> null
        }
        if (problem != null) {
            throw ProjectPathException(
                dir,
                "cannot create $PROJECT_FILE_NAME in '$dir': the project name $problem",
            )
        }
        return trimmed
    }

    private fun readExisting(dir: String, target: String): ProjectFile? {
        val text = fs.readFile(target, PROJECT_FILE_MAX_BYTES) ?: return null
        return parseProjectFile(text) ?: throw ProjectPathException(
            dir,
            "$target already exists but is not a project file: expected " +
                "{\"id\": \"<uuid>\", \"name\": \"<name>\"} " +
                "(a name of at most $PROJECT_NAME_MAX_LENGTH characters, no control characters)",
        )
    }
}


private data class ProjectTemp(val fd: Int, val path: String)

@OptIn(ExperimentalForeignApi::class)
private fun createTemp(directory: String): ProjectTemp? = memScoped {
    val template = childPath(directory, ".kotgent-project-${getpid()}-XXXXXX")
    val encoded = template.encodeToByteArray()
    val chars = allocArray<ByteVar>(encoded.size + 1)
    encoded.forEachIndexed { index, byte -> chars[index] = byte }
    chars[encoded.size] = 0
    val fd = mkstemp(chars)
    if (fd < 0) null else ProjectTemp(fd, chars.toKString())
}

@OptIn(ExperimentalForeignApi::class)
private fun writeAll(fd: Int, buffer: ByteArray): String? = buffer.usePinned { pinned ->
    var offset = 0
    while (offset < buffer.size) {
        val written = write(fd, pinned.addressOf(offset), (buffer.size - offset).convert()).toInt()
        if (written > 0) {
            offset += written
        } else if (written < 0 && errno == EINTR) {
            continue
        } else {
            return@usePinned "write failed after $offset of ${buffer.size} bytes: ${errnoText(errno)}"
        }
    }
    null
}

private const val COMMITTABLE_FILE_MODE: Int =
    S_IRUSR or S_IWUSR or S_IRGRP or S_IWGRP or S_IROTH or S_IWOTH

/** POSIX has no getumask; all other creation paths use explicit restrictive modes during this brief window. */
@OptIn(ExperimentalForeignApi::class)
private fun committedFileMode(): UInt {
    val mask = umask(0u.convert()).toUInt()
    umask(mask.convert())
    return COMMITTABLE_FILE_MODE.toUInt() and mask.inv()
}

private fun childPath(directory: String, name: String): String =
    if (directory == "/") "/$name" else "${directory.trimEnd('/')}/$name"

@OptIn(ExperimentalForeignApi::class)
private fun errnoText(code: Int): String = strerror(code)?.toKString() ?: "errno=$code"
