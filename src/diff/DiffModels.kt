package io.kotgent.diff

import kotlinx.serialization.Serializable

const val DIFF_COMMIT_PAGE_SIZE = 100
const val DIFF_MAX_FILES = 5_000
const val DIFF_MAX_PATCH_BYTES = 2 * 1024 * 1024
const val DIFF_MAX_PATCH_LINES = 20_000
const val DIFF_PROCESS_TIMEOUT_MILLIS = 10_000L
const val DIFF_PROCESS_STDOUT_BYTES = 8 * 1024 * 1024
const val DIFF_PROCESS_STDERR_BYTES = 256 * 1024

@Serializable
enum class DiffMode { local, commit, branch }

@Serializable
enum class DiffLocalScope { all, staged, unstaged }

@Serializable
enum class DiffErrorCode { invalid, unavailable, limited, stale, busy }

@Serializable
enum class DiffReason {
    invalid_request, unknown_session, not_repository, missing_worktree, foreign_worktree,
    bare_worktree, unborn_head, missing_object, missing_ref, base_required, no_merge_base,
    ambiguous_merge_base, shallow_history, git_failed, timeout, output_limit, file_limit,
    patch_limit, unsupported_encoding, unsupported_path, snapshot_changed, snapshot_expired,
    unknown_file, queue_full,
}

@Serializable
data class DiffProblem(val code: DiffErrorCode, val reason: DiffReason, val message: String)

/** Exactly one member is non-null. Empty successful lists are distinct from failed reads. */
@Serializable
data class DiffResponse<T>(val data: T? = null, val error: DiffProblem? = null) {
    init { require((data == null) != (error == null)) }
}

/** Canonical Git common-dir distinguishes clones, irrespective of project UUID. */
@Serializable
data class DiffRepository(val commonDir: String)

/** id is the canonical checkout root (bare entries use their Git directory). Never trust it as a path. */
@Serializable
data class DiffWorktree(
    val id: String,
    val path: String,
    val headOid: String? = null,
    val branchRef: String? = null,
    val current: Boolean = false,
    val detached: Boolean = false,
    val bare: Boolean = false,
    val locked: Boolean = false,
    val prunable: Boolean = false,
    val unavailable: DiffProblem? = null,
)

@Serializable
data class DiffWorktrees(
    val repository: DiffRepository,
    val currentWorktreeId: String,
    val worktrees: List<DiffWorktree>,
    val readAt: Long,
)

@Serializable
enum class DiffRefKind { local, remote }

@Serializable
data class DiffRef(val name: String, val oid: String, val kind: DiffRefKind)

@Serializable
data class DiffRefs(
    val repository: DiffRepository,
    val worktreeId: String,
    val refs: List<DiffRef>,
    val remoteDefaultRef: String? = null,
    val defaultBaseRef: String? = null,
    val readAt: Long,
)

/** Only the fields relevant to mode may be set; parent is one-based, null means first parent. */
@Serializable
data class DiffSelection(
    val mode: DiffMode = DiffMode.local,
    val scope: DiffLocalScope? = null,
    val commitOid: String? = null,
    val parent: Int? = null,
    val baseRef: String? = null,
)

/** Null leftOid is the empty tree for root/unborn, or the index for unstaged (distinguished by mode/scope). */
@Serializable
data class DiffComparison(
    val repository: DiffRepository,
    val worktreeId: String,
    val mode: DiffMode,
    val scope: DiffLocalScope? = null,
    val headOid: String? = null,
    val baseRef: String? = null,
    val baseOid: String? = null,
    val mergeBaseOid: String? = null,
    val commitOid: String? = null,
    val parent: Int? = null,
    val leftOid: String? = null,
    val rightOid: String? = null,
)

@Serializable
data class DiffCommit(
    val oid: String,
    val parents: List<String>,
    val author: String,
    val authoredAt: Long,
    val subject: String,
)

/** Reuse pinned OIDs and offset for every next page. baseOid excludes its reachable history. */
@Serializable
data class DiffCommitCursor(val headOid: String, val baseOid: String? = null, val offset: Int = 0)

@Serializable
data class DiffCommits(
    val repository: DiffRepository,
    val worktreeId: String,
    val cursor: DiffCommitCursor?,
    val commits: List<DiffCommit>,
    val nextCursor: DiffCommitCursor? = null,
    val readAt: Long,
)

@Serializable
enum class DiffChange { added, modified, deleted, renamed, copied, type_changed, untracked, conflicted }

@Serializable
enum class DiffFileKind { text, binary, symlink, submodule, conflict, unavailable, limited }

@Serializable
data class DiffFile(
    val fileId: String,
    val oldPath: String? = null,
    val newPath: String? = null,
    val change: DiffChange,
    val kind: DiffFileKind = DiffFileKind.text,
    val oldMode: String? = null,
    val newMode: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val indexStatus: String? = null,
    val worktreeStatus: String? = null,
    val problem: DiffProblem? = null,
)

/** Snapshot identity covers comparison and file content, not just status/mtime. Not a capability. */
@Serializable
data class DiffSnapshot(val id: String, val comparison: DiffComparison, val readAt: Long)

@Serializable
data class DiffFiles(
    val snapshot: DiffSnapshot,
    val files: List<DiffFile>,
    val limited: DiffProblem? = null,
)

@Serializable
enum class DiffLineKind { context, addition, deletion }

/** Text excludes LF but preserves CR; noNewline applies to this side's final line. */
@Serializable
data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
    val noNewline: Boolean = false,
)

@Serializable
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val heading: String = "",
    val lines: List<DiffLine>,
)

@Serializable
data class DiffPatch(
    val snapshot: DiffSnapshot,
    val file: DiffFile,
    val hunks: List<DiffHunk> = emptyList(),
    val limited: DiffProblem? = null,
)
