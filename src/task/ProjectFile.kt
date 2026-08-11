package io.kotgent.task

import io.kotgent.cli.eprintln
import io.kotgent.core.ProjectId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.free
import platform.posix.realpath
import platform.posix.stat

/** A committed `.kotgent.json` is the project identity; the nearest file wins in a monorepo. */
data class ProjectFile(val id: ProjectId, val name: String)

data class ResolvedProject(val id: ProjectId, val name: String, val root: String)

const val PROJECT_NAME_MAX_LENGTH: Int = 100

fun parseProjectFile(text: String): ProjectFile? {
    val body = runCatching { PROJECT_FILE_JSON.decodeFromString(ProjectFileBody.serializer(), text) }
        .getOrNull() ?: return null
    val id = ProjectId.parseOrNull(body.id) ?: return null
    val name = body.name.trim()
    if (name.isEmpty() || name.length > PROJECT_NAME_MAX_LENGTH) return null
    if (name.any { it.code < 0x20 || it.code == 0x7f }) return null
    return ProjectFile(id, name)
}

fun mainCheckoutRoot(fs: ProjectFs, dir: String): String? {
    var current = absoluteDirOrNull(dir) ?: return null
    while (true) {
        val dotGit = pathIn(current, GIT_ENTRY_NAME)
        if (fs.isDirectory(dotGit)) return current
        val gitFile = fs.readFile(dotGit, GITDIR_FILE_MAX_BYTES)
        if (gitFile != null) return worktreeMainRoot(fs, current, gitFile) ?: current
        current = parentOf(current) ?: return null
    }
}

fun resolveProject(fs: ProjectFs, cwd: String): ResolvedProject? {
    val canonical = fs.canonicalize(cwd)?.let(::absoluteDirOrNull) ?: return null
    val visited = ArrayList<String>()
    var next: String? = canonical
    while (next != null) {
        val dir = next
        visited += dir
        val here = readProjectIn(fs, dir)
        if (here != null) return ResolvedProject(here.id, here.name, dir)
        next = parentOf(dir)
    }
    val root = mainCheckoutRoot(fs, canonical) ?: return null
    if (root in visited) return null
    return readProjectIn(fs, root)?.let { ResolvedProject(it.id, it.name, root) }
}

class PosixProjectFs : ProjectFs {

    @OptIn(ExperimentalForeignApi::class)
    override fun isDirectory(path: String): Boolean = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return@memScoped false
        (st.st_mode.toInt() and S_IFMT) == S_IFDIR
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun readFile(path: String, maxBytes: Int): String? {
        if (maxBytes <= 0) return null
        val fp = fopen(path, "rb") ?: return null
        try {
            val buffer = ByteArray(maxBytes)
            val read = buffer.usePinned { fread(it.addressOf(0), 1.convert(), maxBytes.convert(), fp) }
            val n = read.toInt()
            if (n <= 0) return null
            return buffer.decodeToString(0, n)
        } finally {
            fclose(fp)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun canonicalize(path: String): String? {
        val resolved = realpath(path, null) ?: return null
        return try {
            resolved.toKString()
        } finally {
            free(resolved)
        }
    }
}


private const val GIT_ENTRY_NAME = ".git"

private const val WORKTREES_SEGMENT = "worktrees"

private const val GITDIR_PREFIX = "gitdir:"

@Serializable
private data class ProjectFileBody(val id: String = "", val name: String = "")

private val PROJECT_FILE_JSON: Json = Json { ignoreUnknownKeys = true }

private fun absoluteDirOrNull(dir: String): String? {
    if (!dir.startsWith('/')) return null
    val trimmed = dir.trimEnd('/')
    return trimmed.ifEmpty { "/" }
}

private fun parentOf(dir: String): String? {
    if (dir == "/") return null
    val parent = dir.substringBeforeLast('/')
    return parent.ifEmpty { "/" }
}

private fun pathIn(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"

private fun readProjectIn(fs: ProjectFs, dir: String): ProjectFile? {
    val path = pathIn(dir, PROJECT_FILE_NAME)
    val text = fs.readFile(path, PROJECT_FILE_MAX_BYTES) ?: return null
    val parsed = parseProjectFile(text)
    if (parsed == null) {
        eprintln(
            "warning: ignoring $path — expected {\"id\": \"<uuid>\", \"name\": \"<name>\"} " +
                "(a name of at most $PROJECT_NAME_MAX_LENGTH characters, no control characters)",
        )
    }
    return parsed
}

private fun worktreeMainRoot(fs: ProjectFs, gitFileDir: String, gitFileText: String): String? {
    val target = gitdirTarget(gitFileText) ?: return null
    val absolute = if (target.startsWith('/')) target else "$gitFileDir/$target"
    // Resolve relative targets and symlinks before recognizing Git's ordinary `worktrees/<name>` layout.
    val canonical = fs.canonicalize(absolute)?.let(::absoluteDirOrNull) ?: return null
    val segments = canonical.split('/')
    if (segments.size < 2 || segments[segments.size - 2] != WORKTREES_SEGMENT) return null
    val commonDir = segments.subList(0, segments.size - 2).joinToString("/")
    if (commonDir.substringAfterLast('/') != GIT_ENTRY_NAME) return null
    return parentOf(commonDir)
}

private fun gitdirTarget(gitFileText: String): String? {
    for (line in gitFileText.lineSequence()) {
        val trimmed = line.trim()
        if (!trimmed.startsWith(GITDIR_PREFIX)) continue
        return trimmed.removePrefix(GITDIR_PREFIX).trim().ifEmpty { null }
    }
    return null
}
