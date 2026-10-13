package io.kotgent.diff

import io.kotgent.core.SessionId
import io.kotgent.store.EventStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/** Limits include queued time; snapshots retain metadata only, never patch bytes. */
data class DiffServiceLimits(
    val activeReads: Int = 4,
    val queuedReads: Int = 16,
    val deadlineMillis: Long = 30_000,
    val snapshots: Int = 64,
    val snapshotFiles: Int = 20_000,
    val snapshotTtlMillis: Long = 120_000,
) {
    init {
        require(activeReads > 0 && queuedReads >= 0 && queuedReads <= Int.MAX_VALUE - activeReads)
        require(deadlineMillis > 0 && snapshots > 0 && snapshotFiles >= DIFF_MAX_FILES && snapshotTtlMillis > 0)
    }
}

class DiffService(
    private val sessions: EventStore,
    private val reader: DiffReader,
    private val limits: DiffServiceLimits = DiffServiceLimits(),
    private val elapsedMillis: () -> Long = monotonicMillis(),
) {
    private val admitted = Semaphore(limits.activeReads + limits.queuedReads)
    private val active = Semaphore(limits.activeReads)
    private val registryLock = Mutex()
    private data class Source(val session: SessionId, val cwd: String, val repository: DiffRepository, val root: String)
    private data class Key(val source: Source, val worktree: String, val id: String)
    private data class Entry(val files: DiffFiles, val expires: Long)
    private val registry = linkedMapOf<Key, Entry>()

    suspend fun worktrees(session: SessionId): DiffResponse<DiffWorktrees> = read {
        inventory(session).second
    }

    suspend fun refs(session: SessionId, worktree: String): DiffResponse<DiffRefs> = read {
        val [source, tree] = resolve(session, worktree)
        reader.refs(source.repository, tree).value()
    }

    suspend fun commits(session: SessionId, worktree: String, baseRef: String?, cursor: DiffCommitCursor?): DiffResponse<DiffCommits> = read {
        if (baseRef != null && (!isDiffBranchRef(baseRef) || cursor != null)) invalid()
        cursor?.let { validateDiffCursor(it)?.let { problem -> throw ReadFailure(problem) } }
        val [source, tree] = resolve(session, worktree)
        reader.commits(source.repository, tree, baseRef, cursor).value()
    }

    suspend fun files(session: SessionId, worktree: String, selection: DiffSelection): DiffResponse<DiffFiles> = read {
        validateDiffSelection(selection)?.let { throw ReadFailure(it) }
        val [source, tree] = resolve(session, worktree)
        val files = reader.files(source.repository, tree, selection).value()
        if (files.snapshot.comparison.repository != source.repository || files.snapshot.comparison.worktreeId != tree.id) invalid()
        if (sessions.getSession(session)?.cwd != source.cwd) stale()
        registryLock.withLock {
            prune()
            val key = Key(source, tree.id, files.snapshot.id)
            val _ = registry.remove(key)
            // Bound aggregate retained file metadata, as well as the number of observations.
            while (registry.isNotEmpty() && (registry.size >= limits.snapshots ||
                    registry.values.sumOf { it.files.files.size } + files.files.size > limits.snapshotFiles)) {
                val _ = registry.remove(registry.keys.first())
            }
            if (files.files.size > limits.snapshotFiles) throw ReadFailure(DiffProblem(DiffErrorCode.limited, DiffReason.file_limit, "Snapshot metadata limit reached"))
            registry[key] = Entry(files, elapsedMillis() + limits.snapshotTtlMillis)
        }
        files
    }

    suspend fun patch(session: SessionId, worktree: String, snapshot: String, fileId: String): DiffResponse<DiffPatch> = read {
        selectedFile(session, worktree, snapshot, fileId) { source, tree, files, file ->
            reader.patch(source.repository, tree, files.snapshot, file).value()
        }
    }

    suspend fun image(session: SessionId, worktree: String, snapshot: String, fileId: String,
        version: DiffImageVersion): DiffResponse<DiffImageContent> = read {
        selectedFile(session, worktree, snapshot, fileId) { source, tree, files, file ->
            reader.image(source.repository, tree, files.snapshot, file, version).value()
        }
    }

    private suspend fun <T> selectedFile(session: SessionId, worktree: String, snapshot: String, fileId: String,
        block: suspend (Source, DiffWorktree, DiffFiles, DiffFile) -> T): T {
        val [source, tree] = resolve(session, worktree)
        val files = registryLock.withLock {
            prune()
            registry[Key(source, tree.id, snapshot)]?.files
        } ?: stale()
        val file = files.files.singleOrNull { it.fileId == fileId }
            ?: throw ReadFailure(DiffProblem(DiffErrorCode.invalid, DiffReason.unknown_file, "Unknown snapshot file"))
        return block(source, tree, files, file)
    }

    private suspend fun inventory(session: SessionId): Pair<Source, DiffWorktrees> {
        val cwd = sessions.getSession(session)?.cwd ?: run {
            invalidate(session)
            throw ReadFailure(DiffProblem(DiffErrorCode.invalid, DiffReason.unknown_session, "Unknown session"))
        }
        val result = reader.worktrees(cwd)
        if (result.error != null) { invalidate(session); throw ReadFailure(result.error) }
        val inventory = result.value()
        val source = Source(session, cwd, inventory.repository, inventory.currentWorktreeId)
        registryLock.withLock {
            prune()
            val available = inventory.worktrees.filter { it.unavailable == null }.map { it.id }.toSet()
            val _ = registry.keys.removeAll { it.source.session == session && (it.source != source || it.worktree !in available) }
        }
        return source to inventory
    }

    private suspend fun resolve(session: SessionId, worktree: String): Pair<Source, DiffWorktree> {
        val [source, inventory] = inventory(session)
        val tree = inventory.worktrees.singleOrNull { it.id == worktree }
            ?: throw ReadFailure(DiffProblem(DiffErrorCode.invalid, DiffReason.foreign_worktree, "Worktree is not in this repository"))
        tree.unavailable?.let { throw ReadFailure(it) }
        // The current root was resolved from the stored cwd by this very inventory. The reader
        // still verifies it afresh before reading; only a different selected path needs discovery here.
        if (tree.id == inventory.currentWorktreeId && tree.path == inventory.currentWorktreeId) return source to tree
        // Re-resolve the selected entry itself: an inventory path can now point to another clone.
        val selectedResult = reader.worktrees(tree.path)
        if (selectedResult.error != null) { invalidate(session); throw ReadFailure(selectedResult.error) }
        val selected = selectedResult.value()
        if (selected.repository != source.repository || selected.currentWorktreeId != tree.id) {
            invalidate(session)
            throw ReadFailure(DiffProblem(DiffErrorCode.invalid, DiffReason.foreign_worktree, "Worktree repository changed"))
        }
        return source to tree
    }

    private suspend fun invalidate(session: SessionId) = registryLock.withLock {
        val _ = registry.keys.removeAll { it.source.session == session }
    }

    private fun prune() {
        val now = elapsedMillis()
        val _ = registry.entries.removeAll { it.value.expires <= now }
    }

    private suspend fun <T> read(block: suspend () -> T): DiffResponse<T> {
        if (!admitted.tryAcquire()) return DiffResponse(error = DiffProblem(DiffErrorCode.busy, DiffReason.queue_full, "Diff read queue is full"))
        return try {
            withTimeoutOrNull(limits.deadlineMillis) {
                active.withPermit {
                    try { DiffResponse(data = block()) }
                    catch (failure: ReadFailure) { DiffResponse<T>(error = failure.problem) }
                }
            } ?: DiffResponse(error = DiffProblem(DiffErrorCode.limited, DiffReason.timeout, "Diff read timed out"))
        } finally { admitted.release() }
    }

    private class ReadFailure(val problem: DiffProblem) : RuntimeException(problem.message)
    private fun <T> DiffResponse<T>.value(): T = data ?: throw ReadFailure(error!!)
    private fun invalid(): Nothing = throw ReadFailure(DiffProblem(DiffErrorCode.invalid, DiffReason.invalid_request, "Invalid diff request"))
    private fun stale(): Nothing = throw ReadFailure(DiffProblem(DiffErrorCode.stale, DiffReason.snapshot_expired, "Refresh"))
}

private fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
