package io.kotgent.task

/**
 * The three filesystem questions project resolution asks, behind an interface so the rules in
 * `ProjectFile.kt` are pure and testable against a fake tree.
 *
 * There is deliberately **no `git` subprocess** anywhere below this seam: the daemon's PATH is a
 * snapshot taken at `kotgent install` and `git` may simply not be on it, and a pure rule can be tested
 * against a fake filesystem while `git rev-parse` cannot. Everything the resolver needs — is `.git` a
 * directory or a file, what does that file say, where does a relative `gitdir:` really point — is
 * answerable with these three calls.
 *
 * Every method **degrades instead of throwing**: an unreadable path, a permission error or a broken
 * symlink reads as "absent". A resolver must never throw into the daemon.
 */
interface ProjectFs {

    /** Whether [path] exists and is a directory (a symlink to one counts). */
    fun isDirectory(path: String): Boolean

    /**
     * The first [maxBytes] of [path] as text, or `null` when it does not exist or cannot be read.
     *
     * The cap is the caller's, not a default: `.kotgent.json` is read at
     * [PROJECT_FILE_MAX_BYTES] because it arrives with somebody else's repository, and a `.git` file is
     * read at [GITDIR_FILE_MAX_BYTES] because it holds one `gitdir:` line.
     */
    fun readFile(path: String, maxBytes: Int): String?

    /**
     * [path] with every symlink and `.`/`..` component resolved (`realpath(3)`), or `null` when it does
     * not exist. Load-bearing twice: a relative `gitdir:` target must be canonicalized BEFORE its
     * `/worktrees/<name>` segment is looked for, and a session's cwd must converge on the one spelling
     * providers record, or two worktrees of the same checkout would key different rows.
     */
    fun canonicalize(path: String): String?
}

/** The project descriptor file, committed at the project root. */
const val PROJECT_FILE_NAME: String = ".kotgent.json"

/**
 * How much of a `.kotgent.json` is read. It is untrusted input — it arrives with somebody's repository —
 * and the repo bounds every untrusted intake (1024 bytes for `/auth/exchange`, 100 MiB per upload).
 */
const val PROJECT_FILE_MAX_BYTES: Int = 8 * 1024

/** How much of a `.git` FILE is read: it holds a single `gitdir: <path>` line. */
const val GITDIR_FILE_MAX_BYTES: Int = 4 * 1024
