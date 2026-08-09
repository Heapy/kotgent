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
 * Creating a `.kotgent.json`, atomically and never destructively.
 *
 * The publication sequence mirrors the upload path (`FileUploadRoutes.kt`): `mkstemp` sibling → write →
 * `fsync` → `chmod` → `link(2)`, unlinking the temp on EVERY path including success. Two details differ
 * on purpose:
 *
 *  - the mode is `0666 & ~umask`, **not** `0600`: this file is meant to be committed and read by every
 *    tool in the repository, unlike a token or a hook header;
 *  - a lost `link(2)` race is **not** an error. An existing file always wins, so the loser re-reads the
 *    winner's file and returns THAT descriptor — two agents running `kotgent task add` in a fresh
 *    repository at the same moment must converge on one project uuid, not fail one of them.
 *
 * The daemon writes the file and **never commits it**; the agent skill is told to mention it rather than
 * sweep it into an unrelated commit.
 */
interface ProjectFileWriter {

    /**
     * Ensure [dir] has a `.kotgent.json` and return its contents — the freshly written one, or the
     * existing/racing one. [name] is the project's display name; a fresh file gets a newly minted uuid.
     *
     * @throws ProjectPathException when [dir] is relative or is not an existing directory, or when the
     *   write itself fails. Never leaves a temp file behind, on any branch.
     */
    suspend fun ensureProjectFile(dir: String, name: String): ProjectFile
}

/** The `$schema` URL a freshly written `.kotgent.json` carries; see `schema/project.v1.json`. */
const val PROJECT_FILE_SCHEMA_URL: String =
    "https://raw.githubusercontent.com/Heapy/kotgent/main/schema/project.v1.json"

/**
 * The exact bytes (plus a trailing newline) a fresh `.kotgent.json` is published with: the `$schema`
 * hint first, then the two fields [parseProjectFile] actually reads.
 *
 * The name is emitted through kotlinx's string serializer rather than interpolated, because it is the
 * one value here that is not already constrained to a safe charset — a quote or a backslash in a
 * project name would otherwise produce a file that no longer parses. The id needs no such care: it is a
 * canonical uuid or [ProjectId] would not exist.
 */
fun projectFileText(file: ProjectFile): String =
    """
    {
      "${'$'}schema": "$PROJECT_FILE_SCHEMA_URL",
      "id": "${file.id.value}",
      "name": ${Json.encodeToString(String.serializer(), file.name)}
    }
    """.trimIndent() + "\n"

/**
 * The real writer. Stock `platform.posix` (`mkstemp`/`fsync`/`fchmod`/`link`/`unlink`/`umask`) so it
 * links into the test binary (KT-78062) and the whole sequence — including "no temp survives any
 * branch" — is testable in `$TMPDIR`.
 *
 * [newId] is the uuid source, defaulted so `Commands.kt`'s no-argument `PosixProjectFileWriter()` keeps
 * working and a test can make the minted id deterministic. It is called only on the path that actually
 * writes a file, AFTER the existence check — a call that adopts an existing project mints nothing.
 */
class PosixProjectFileWriter(
    private val newId: () -> ProjectId = { ProjectId.mint() },
) : ProjectFileWriter {

    /**
     * Reading is [PosixProjectFs]'s job, not a second `stat`/`fopen` pair: this is the same layer and
     * the same two questions ("is it a directory", "what does the file say"), so reusing it keeps one
     * answer to each. Only the WRITE half lives here, because nothing else in the task layer writes.
     */
    private val fs = PosixProjectFs()

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun ensureProjectFile(dir: String, name: String): ProjectFile {
        val directory = directoryOrRefuse(dir)
        // Validated before the filesystem is touched, the way `saveUploadedFile` validates its leaf
        // name first: a name the READER would reject (blank, >100 chars, control characters) must not
        // reach the disk at all, or the project we just "created" is invisible to every later resolve.
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
            // `fchmod`, not `chmod(temp.path, …)`: the descriptor is the file we just wrote, whatever
            // else may have happened to that name. The link below shares this inode, so the mode
            // travels with it and the published file is never briefly `0600`.
            if (fchmod(fd, committedFileMode().convert()) != 0) {
                throw ProjectPathException(dir, "cannot write $target: chmod failed (${errnoText(errno)})")
            }
            // Do not retry close on EINTR: on macOS the descriptor is already released and could be
            // reused. Mark it unavailable before checking the return, for the same reason.
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
            // Lost the race, which is not a failure: somebody else's uuid is now THE project's uuid,
            // and the alternative — failing one of two concurrent `kotgent task add`s — is worse.
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

    /** [dir] with trailing slashes dropped, or [ProjectPathException] when it cannot be written into. */
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

    /**
     * [name] trimmed, or [ProjectPathException] when [parseProjectFile] would refuse it. Trimming here
     * is what makes the round trip exact: the reader trims too, so an untrimmed value would come back
     * different from the one this call returned.
     */
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

    /**
     * The project file already at [target], `null` when there is none — and a throw when one is there
     * but does not parse. Overwriting it is not an option (`link(2)` refuses, deliberately), and
     * returning `null` would send the caller into a write it cannot win, so the honest answer is to say
     * the directory already holds a `.kotgent.json` that has to be fixed by hand.
     */
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

// --- internals ------------------------------------------------------------------------------------

private data class ProjectTemp(val fd: Int, val path: String)

/**
 * A `0600` staging file beside the target, so `link(2)` publishes within one filesystem. The name is
 * dot-prefixed and carries the pid: a leftover from a crashed daemon is recognisable and out of the way
 * of `ls`, though no branch here leaves one.
 */
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

/** `0666 & ~umask` — what the shell would have given the file, because it is meant to be committed. */
private const val COMMITTABLE_FILE_MODE: Int =
    S_IRUSR or S_IWUSR or S_IRGRP or S_IWGRP or S_IROTH or S_IWOTH

/**
 * The process umask, read the only way POSIX offers: set it to zero and put it straight back. There is
 * no `getumask(2)` on macOS. The window between the two calls is two adjacent syscalls wide and can
 * only make a concurrently created file MORE permissive than intended — every other creation path in
 * this repository passes an explicit `0600`/`0700`, which a zero umask cannot widen.
 */
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
