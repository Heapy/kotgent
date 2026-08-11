package io.kotgent.task

import io.kotgent.core.ProjectId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Prevents browser scenarios from creating `.kotgent.json` files on the developer's disk. */
class MemoryProjectFileWriter(
    private val fs: FakeProjectFs,
    private val newId: () -> ProjectId = { ProjectId.mint() },
) : ProjectFileWriter {

    private val mutex = Mutex()

    val failOn: MutableSet<String> = mutableSetOf()

    override suspend fun ensureProjectFile(dir: String, name: String): ProjectFile = mutex.withLock {
        val directory = directoryOrRefuse(dir)
        val projectName = validatedName(dir, name)
        val target = childPath(directory, PROJECT_FILE_NAME)
        // Existing content wins before writability checks, matching the real writer's `link(2)` race.
        val existing = readExisting(dir, target)
        if (existing != null) return@withLock existing

        if (directory in failOn) {
            throw ProjectPathException(dir, "cannot write $target: the location is not writable")
        }
        val minted = ProjectFile(newId(), projectName)
        fs.writeFile(target, projectFileText(minted))
        minted
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

    private fun childPath(directory: String, name: String): String =
        if (directory == "/") "/$name" else "${directory.trimEnd('/')}/$name"
}
