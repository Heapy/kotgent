package io.kotgent.diff

/**
 * Read-only, cancellable Git boundary. No transport, shell, POSIX, or session-store types.
 * The service supplies stored session cwd, rechecks inventory/common-dir on every request, and
 * passes only worktrees and snapshot/file pairs it has resolved, never client-supplied paths.
 * Implementations bound every operation and propagate coroutine cancellation.
 */
interface DiffReader {
    suspend fun worktrees(cwd: String): DiffResponse<DiffWorktrees>

    suspend fun refs(repository: DiffRepository, worktree: DiffWorktree): DiffResponse<DiffRefs>

    /** Null cursor pins HEAD and optional baseRef; subsequent pages reuse cursor, not moving refs. */
    suspend fun commits(
        repository: DiffRepository,
        worktree: DiffWorktree,
        baseRef: String? = null,
        cursor: DiffCommitCursor? = null,
    ): DiffResponse<DiffCommits>

    suspend fun files(
        repository: DiffRepository,
        worktree: DiffWorktree,
        selection: DiffSelection,
    ): DiffResponse<DiffFiles>

    /** Validate snapshot and selected file bytes before and after reading; changed/expired is stale. */
    suspend fun patch(
        repository: DiffRepository,
        worktree: DiffWorktree,
        snapshot: DiffSnapshot,
        file: DiffFile,
    ): DiffResponse<DiffPatch>
    /** Same snapshot validation as patch; only a supported regular raster can return bytes. */
    suspend fun image(
        repository: DiffRepository,
        worktree: DiffWorktree,
        snapshot: DiffSnapshot,
        file: DiffFile,
        version: DiffImageVersion,
    ): DiffResponse<DiffImageContent> = DiffResponse(error = DiffProblem(
        DiffErrorCode.unavailable, DiffReason.unsupported_encoding, "Image preview unavailable"))
}
