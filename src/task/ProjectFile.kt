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

/*
 * Project resolution — pure rules over a [ProjectFs].
 *
 * A project is a COMMITTED FILE, not a path: `.kotgent.json` holds a uuid and a name, so `/repo` and
 * `/repo-wt/feature` are one project with one backlog instead of two strings with two.
 *
 * Resolution order:
 *   1. Walk up from the canonical cwd to the filesystem root; the first `.kotgent.json` wins (nearest
 *      wins in a monorepo).
 *   2. Miss, but inside a repository -> look at the MAIN CHECKOUT ROOT ([mainCheckoutRoot]).
 *   3. Still nothing -> no project.
 *
 * ## Supported and unsupported git layouts
 * "Worktree-invariant for free" is true for the ORDINARY `git worktree add` layout and only that one:
 *   - `.git` is a DIRECTORY            -> this directory is the root.
 *   - `.git` is a FILE whose `gitdir:` target contains a `/worktrees/<name>` segment -> strip that
 *     segment to get the common dir; the root is the common dir's PARENT. A RELATIVE target is resolved
 *     against the directory holding the `.git` file and then canonicalized through `realpath` BEFORE any
 *     segment is examined — otherwise `../../.git/worktrees/x` never matches and a symlinked common dir
 *     matches the wrong string.
 *   - anything else                    -> treat the current directory as the root.
 *
 * Recorded UNSUPPORTED layouts, each degrading to "the current directory is the root" rather than
 * misbehaving, and each a test case rather than a surprise: `git init --separate-git-dir` (the common
 * dir's parent is the metadata directory, not the checkout), submodules (`gitdir:` points into
 * `…/.git/modules/<name>`, which has no `worktrees` segment), bare repositories, and
 * `$GIT_DIR` / `$GIT_WORK_TREE`. Two checkouts of different branches also disagree whenever
 * `.kotgent.json` is committed on one and not the other — the uuid is invariant across worktrees of a
 * repository, not across the history of a file.
 *
 * ## The common dir must be named `.git`, and that check is what makes two of those degrade
 * The `/worktrees/<name>` segment alone is NOT enough to conclude "the parent of the common dir is a
 * checkout". Two of the recorded layouts produce that very segment and a parent that is not a checkout:
 * a worktree of a BARE repository (`gitdir: /srv/repo.git/worktrees/f` -> the parent is `/srv`) and a
 * worktree of a `--separate-git-dir` checkout (`gitdir: /meta/checkout-git/worktrees/f` -> the parent is
 * `/meta`). In the ordinary layout the common dir is always `<root>/.git`, so requiring that basename is
 * exactly what separates the supported shape from the two unsupported ones — both then fall through to
 * "the current directory is the root", which for a worktree is the correct checkout even though its main
 * root is unreachable. Without it, a `.kotgent.json` sitting beside a bare repository would be adopted by
 * every worktree of it.
 */

/** The parsed contents of a `.kotgent.json`. */
data class ProjectFile(val id: ProjectId, val name: String)

/** A project resolved from a directory: its identity plus the checkout root the file was found in. */
data class ResolvedProject(val id: ProjectId, val name: String, val root: String)

/** Longest accepted project name; anything past this is rejected rather than truncated. */
const val PROJECT_NAME_MAX_LENGTH: Int = 100

/**
 * Parse a `.kotgent.json` body. Returns `null` — never throws — for malformed JSON, a missing or
 * non-uuid `id`, a blank/overlong name or a name containing control characters. The caller logs a
 * warning and reads `null` as "no project"; an exception out of the resolver would take a session start
 * with it.
 *
 * [text] is expected to be at most [PROJECT_FILE_MAX_BYTES] of input (the [ProjectFs.readFile] cap): a
 * larger file arrives TRUNCATED, which fails the JSON parse, which is the intended outcome.
 *
 * Unknown keys are ignored, so the `$schema` line the file is meant to carry (and anything a newer
 * kotgent writes) is not a parse failure. The NAME is trimmed — a hand-edited file may carry padding —
 * but the `id` is not: a uuid with whitespace inside a JSON string is a broken file, not a formatting
 * preference, and [ProjectId] is the boundary that says so. Case in the id IS normalized, by
 * [ProjectId.parseOrNull]: `projects.id` is a `TEXT` column SQLite compares binary, so two spellings of
 * one uuid would key two backlogs.
 */
fun parseProjectFile(text: String): ProjectFile? {
    val body = runCatching { PROJECT_FILE_JSON.decodeFromString(ProjectFileBody.serializer(), text) }
        .getOrNull() ?: return null
    val id = ProjectId.parseOrNull(body.id) ?: return null
    val name = body.name.trim()
    if (name.isEmpty() || name.length > PROJECT_NAME_MAX_LENGTH) return null
    // A JSON string cannot carry a raw control character, but a unicode escape decodes to one
    // and DEL is not escaped at all. The name reaches a terminal, a notification and the board.
    if (name.any { it.code < 0x20 || it.code == 0x7f }) return null
    return ProjectFile(id, name)
}

/**
 * The main checkout root for [dir] per the layout rules in this file's header, or `null` when [dir] is
 * not inside anything that looks like a repository. [dir] must already be canonical.
 *
 * A non-absolute [dir] answers `null` rather than walking a relative path apart: every caller gets its
 * value from [ProjectFs.canonicalize], so a relative one is a bug upstream and must not be guessed at.
 */
fun mainCheckoutRoot(fs: ProjectFs, dir: String): String? {
    var current = absoluteDirOrNull(dir) ?: return null
    while (true) {
        val dotGit = pathIn(current, GIT_ENTRY_NAME)
        if (fs.isDirectory(dotGit)) return current
        val gitFile = fs.readFile(dotGit, GITDIR_FILE_MAX_BYTES)
        // A `.git` FILE names the checkout either way: the ordinary worktree layout reaches the MAIN
        // root, and every unsupported one degrades to this directory, which IS a checkout root.
        if (gitFile != null) return worktreeMainRoot(fs, current, gitFile) ?: current
        current = parentOf(current) ?: return null
    }
}

/**
 * Resolve the project owning [cwd], or `null`. [cwd] is canonicalized through [ProjectFs.canonicalize]
 * first, so `/repo/./sub`, an uncollapsed `..` and a symlinked prefix all converge on one answer; a
 * [cwd] that does not resolve at all (it was deleted, or was never there) is `null` rather than a throw.
 *
 * A `.kotgent.json` that exists but does not parse warns and is SKIPPED, so the walk continues upward:
 * "reads as no project" is the whole rule, and stopping there would make a broken file in a monorepo
 * subdirectory hide the repository's real project instead of merely failing to add one.
 */
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
    // Step 2: a worktree's main checkout root is NOT an ancestor of the worktree, so it is the one
    // directory the walk above could not have covered. Every other answer it can give already was.
    val root = mainCheckoutRoot(fs, canonical) ?: return null
    if (root in visited) return null
    return readProjectIn(fs, root)?.let { ResolvedProject(it.id, it.name, root) }
}

/**
 * The real POSIX [ProjectFs]. Stock `platform.posix` only — no cinterop, so it links into the test
 * binary (KT-78062) and one test can exercise the real implementation in `$TMPDIR` against a real `.git`
 * directory and a real `.git` worktree file.
 *
 * These three are deliberately re-implemented here rather than reusing `VendorStoreFs`'s `isDirectory` /
 * `readHead` or `SessionManager`'s `canonicalPath`: the layering runs `daemon -> task`, and importing
 * them back would make the pure rules in this file depend on the daemon they are called from.
 */
class PosixProjectFs : ProjectFs {

    @OptIn(ExperimentalForeignApi::class)
    override fun isDirectory(path: String): Boolean = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return@memScoped false
        (st.st_mode.toInt() and S_IFMT) == S_IFDIR
    }

    /**
     * An EMPTY file reads as `null`, the same answer as an absent one. Nothing here can use zero bytes:
     * empty JSON is not a project and an empty `.git` file names no gitdir, so both degrade identically.
     */
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

// --- internals ------------------------------------------------------------------------------------

/** `.git`, whether it is the repository directory or the one-line file a linked worktree carries. */
private const val GIT_ENTRY_NAME = ".git"

/** The segment a linked worktree's private directory always sits under, inside the common dir. */
private const val WORKTREES_SEGMENT = "worktrees"

/** The only key a `.git` FILE carries. */
private const val GITDIR_PREFIX = "gitdir:"

@Serializable
private data class ProjectFileBody(val id: String = "", val name: String = "")

/**
 * Defaults rather than required fields on purpose: a file missing `id` or `name` must reach the same
 * `null` as one whose values are wrong, through the same validation, instead of via a thrown
 * `SerializationException` that a second `runCatching` would have to mean the same thing.
 */
private val PROJECT_FILE_JSON: Json = Json { ignoreUnknownKeys = true }

/** [dir] as an absolute path with trailing slashes dropped, or `null` when it is not absolute. */
private fun absoluteDirOrNull(dir: String): String? {
    if (!dir.startsWith('/')) return null
    val trimmed = dir.trimEnd('/')
    return trimmed.ifEmpty { "/" }
}

/** [dir]'s parent, or `null` at the filesystem root. [dir] must come from [absoluteDirOrNull]. */
private fun parentOf(dir: String): String? {
    if (dir == "/") return null
    val parent = dir.substringBeforeLast('/')
    return parent.ifEmpty { "/" }
}

/** [name] inside [dir], without the doubled slash `"/" + "/x"` would produce at the root. */
private fun pathIn(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"

/**
 * The `.kotgent.json` in [dir], or `null` when there is none or it does not parse. A file that exists
 * and fails to parse WARNS: it is committed, so somebody edited it and wants to know why their project
 * disappeared. An absent file is silent — that is the ordinary state of most directories.
 */
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

/**
 * The MAIN checkout root behind a linked worktree's `.git` file, or `null` when [gitFileText] does not
 * describe the one supported layout — in which case the caller degrades to the directory holding the
 * file. [gitFileDir] is the directory the `.git` file lives in, which is what a relative target resolves
 * against.
 *
 * The order is load-bearing: resolve-relative, then canonicalize, and only THEN look at segments. A
 * relative `../../repo/.git/worktrees/f` carries no matchable prefix until it is joined and collapsed,
 * and a symlinked common dir matches a string that is not where the checkout actually is.
 */
private fun worktreeMainRoot(fs: ProjectFs, gitFileDir: String, gitFileText: String): String? {
    val target = gitdirTarget(gitFileText) ?: return null
    val absolute = if (target.startsWith('/')) target else "$gitFileDir/$target"
    val canonical = fs.canonicalize(absolute)?.let(::absoluteDirOrNull) ?: return null
    val segments = canonical.split('/')
    // ".../worktrees/<name>": the name is the last segment, so `worktrees` is the second to last.
    if (segments.size < 2 || segments[segments.size - 2] != WORKTREES_SEGMENT) return null
    val commonDir = segments.subList(0, segments.size - 2).joinToString("/")
    // The bare-repository and --separate-git-dir guard; see this file's header.
    if (commonDir.substringAfterLast('/') != GIT_ENTRY_NAME) return null
    return parentOf(commonDir)
}

/** The `gitdir:` value in a `.git` file, or `null` when the line is absent, empty or truncated away. */
private fun gitdirTarget(gitFileText: String): String? {
    for (line in gitFileText.lineSequence()) {
        val trimmed = line.trim()
        if (!trimmed.startsWith(GITDIR_PREFIX)) continue
        return trimmed.removePrefix(GITDIR_PREFIX).trim().ifEmpty { null }
    }
    return null
}
