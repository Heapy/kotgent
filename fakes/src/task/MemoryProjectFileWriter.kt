package io.kotgent.task

import io.kotgent.core.ProjectId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [ProjectFileWriter] that publishes into a [FakeProjectFs] and touches no disk at all.
 *
 * It exists because `ProjectFileWriter` is the THIRD disk-writing edge a browser can reach (after the
 * directory completer and the upload sink), and the one that creates a file in somebody's repository:
 * `POST /projects` writes a `.kotgent.json` at a browser-supplied absolute path. A harness that left the
 * real `PosixProjectFileWriter` wired would scatter project files across the developer's filesystem
 * every time a board test typed a path — which is exactly what "the harness never creates a
 * `.kotgent.json` anywhere on disk" forbids.
 *
 * ## What it models faithfully, because the flow depends on it
 *  - **An existing file always wins, by being PARSED** — not by answering [newId] regardless. The real
 *    writer loses the `link(2)` race and re-reads the winner's file, so two creates in one directory must
 *    converge on ONE uuid; a fake that minted its own would hide precisely that.
 *  - **The refusals and their wording**: a relative path, a non-directory, a name the READER would reject
 *    (blank, over [PROJECT_NAME_MAX_LENGTH], control characters), and a `.kotgent.json` that is there but
 *    does not parse. Each is a [ProjectPathException] carrying the real writer's sentence, so a browser
 *    assertion on the message means something.
 *  - **The order of the checks**: the existing file is read BEFORE any write is attempted, so adoption
 *    still wins in a directory [failOn] marks unwritable — the real writer reads before it creates its
 *    temp file.
 *
 * The one message that deliberately differs is the unwritable-location refusal: the real one carries an
 * `errno` text, and a fake inventing one would be a confidently wrong answer.
 */
class MemoryProjectFileWriter(
    private val fs: FakeProjectFs,
    /** The uuid source. Injectable so a scenario's project id is deterministic across runs. */
    private val newId: () -> ProjectId = { ProjectId.mint() },
) : ProjectFileWriter {

    private val mutex = Mutex()

    /** `(dir, name)` per call, in order. */
    val calls: MutableList<Pair<String, String>> = mutableListOf()

    /** Directories the writer refuses, standing in for an unwritable location. Set before the server runs. */
    val failOn: MutableSet<String> = mutableSetOf()

    override suspend fun ensureProjectFile(dir: String, name: String): ProjectFile = mutex.withLock {
        calls += dir to name
        val directory = directoryOrRefuse(dir)
        // Validated before anything is written, like the real writer: a name the READER would reject must
        // not reach the tree at all, or the project we just "created" is invisible to every later resolve.
        val projectName = validatedName(dir, name)
        val target = childPath(directory, PROJECT_FILE_NAME)
        val existing = readExisting(dir, target)
        if (existing != null) return@withLock existing

        if (directory in failOn) {
            throw ProjectPathException(dir, "cannot write $target: the location is not writable")
        }
        val minted = ProjectFile(newId(), projectName)
        fs.writeFile(target, projectFileText(minted))
        minted
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
     * [name] trimmed, or [ProjectPathException] when [parseProjectFile] would refuse it. Trimming here is
     * what makes the round trip exact: the reader trims too, so an untrimmed value would come back
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
     * The project file already at [target], `null` when there is none — and a throw when one is there but
     * does not parse. Overwriting it is not an option (the real `link(2)` refuses, deliberately), and
     * answering `null` would send the caller into a write it cannot win.
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

    private fun childPath(directory: String, name: String): String =
        if (directory == "/") "/$name" else "${directory.trimEnd('/')}/$name"
}
