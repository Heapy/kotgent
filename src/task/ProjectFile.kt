package io.kotgent.task

import io.kotgent.core.ProjectId

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
 * Bodies are [TODO] here on purpose: Task 3 of the task-backlog plan implements this file. Task 2
 * declares it so twenty-six agents can compile against the signatures at once.
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
 */
fun parseProjectFile(text: String): ProjectFile? = TODO("Task 3: project file parsing")

/**
 * The main checkout root for [dir] per the layout rules in this file's header, or `null` when [dir] is
 * not inside anything that looks like a repository. [dir] must already be canonical.
 */
fun mainCheckoutRoot(fs: ProjectFs, dir: String): String? = TODO("Task 3: main checkout root")

/**
 * Resolve the project owning [cwd], or `null`. [cwd] is canonicalized through [ProjectFs.canonicalize]
 * first, so `/repo/./sub`, an uncollapsed `..` and a symlinked prefix all converge on one answer.
 */
fun resolveProject(fs: ProjectFs, cwd: String): ResolvedProject? = TODO("Task 3: project resolution")

/**
 * The real POSIX [ProjectFs]. Stock `platform.posix` only — no cinterop, so it links into the test
 * binary (KT-78062) and one test can exercise the real implementation in `$TMPDIR` against a real `.git`
 * directory and a real `.git` worktree file.
 */
class PosixProjectFs : ProjectFs {
    override fun isDirectory(path: String): Boolean = TODO("Task 3: posix isDirectory")

    override fun readFile(path: String, maxBytes: Int): String? = TODO("Task 3: posix readFile")

    override fun canonicalize(path: String): String? = TODO("Task 3: posix realpath")
}
