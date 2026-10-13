package io.kotgent.diff

import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.store.FakeEventStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiffServiceTest {
    private val session = SessionId("diff-service")
    private suspend fun store(): FakeEventStore = FakeEventStore().also {
        it.upsertSession(SessionMeta(session, name = "", agent = "shell", cwd = "/repo", tmuxSession = "unused",
            state = SessionState.running, createdAt = 0, updatedAt = 0))
    }
    private class Reader : DiffReader by GitDiffReader() {
        var repository = DiffRepository("/repo/.git")
        var root = "/repo"
        var observation = "content"
        var readAt = 0L
        var wait: (suspend () -> Unit)? = null
        override suspend fun worktrees(cwd: String): DiffResponse<DiffWorktrees> {
            wait?.invoke()
            return DiffResponse(data = DiffWorktrees(repository, root, listOf(DiffWorktree(root, root)), readAt))
        }
        override suspend fun files(repository: DiffRepository, worktree: DiffWorktree, selection: DiffSelection) =
            DiffResponse(data = DiffFiles(DiffSnapshot(observation, localDiffComparison(repository, root, null), readAt),
                listOf(DiffFile("file", newPath = "a", change = DiffChange.untracked))))
        override suspend fun patch(repository: DiffRepository, worktree: DiffWorktree, snapshot: DiffSnapshot, file: DiffFile) =
            DiffResponse(data = DiffPatch(snapshot, file))
    }

    @Test fun identitySurvivesRevalidationButRegistryIsSessionBoundAndExpires() = runBlocking<Unit> {
        val reader = Reader()
        var time = 0L
        val store = store()
        val service = DiffService(store, reader, DiffServiceLimits(snapshotTtlMillis = 10), { time })
        val first = assertNotNull(service.files(session, "/repo", DiffSelection()).data)
        reader.readAt = 5
        assertEquals(first.snapshot.id, service.files(session, "/repo", DiffSelection()).data?.snapshot?.id)
        val other = SessionId("other")
        store.upsertSession(assertNotNull(store.getSession(session)).copy(id = other))
        assertEquals(DiffReason.snapshot_expired, service.patch(other, "/repo", "content", "file").error?.reason)
        assertEquals(DiffReason.unknown_file, service.patch(session, "/repo", "content", "../a").error?.reason)
        time = 10
        assertEquals(DiffReason.snapshot_expired, service.patch(session, "/repo", "content", "file").error?.reason)
        assertNotNull(service.files(session, "/repo", DiffSelection()).data)
        assertNotNull(service.patch(session, "/repo", "content", "file").data)
    }

    @Test fun evictionAndSourceChangesCannotReuseSnapshots() = runBlocking<Unit> {
        val reader = Reader()
        val store = store()
        val service = DiffService(store, reader, DiffServiceLimits(snapshots = 1))
        assertNotNull(service.files(session, "/repo", DiffSelection()).data)
        reader.observation = "second"
        assertNotNull(service.files(session, "/repo", DiffSelection()).data)
        assertEquals(DiffErrorCode.stale, service.patch(session, "/repo", "content", "file").error?.code)
        reader.repository = DiffRepository("/new/.git")
        assertEquals(DiffErrorCode.stale, service.patch(session, "/repo", "second", "file").error?.code)
        reader.repository = DiffRepository("/repo/.git")
        assertEquals(DiffErrorCode.stale, service.patch(session, "/repo", "second", "file").error?.code)
        assertNotNull(service.files(session, "/repo", DiffSelection()).data)
        reader.root = "/linked"
        assertNotNull(service.worktrees(session).data)
        reader.root = "/repo"
        assertEquals(DiffErrorCode.stale, service.patch(session, "/repo", "second", "file").error?.code)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun queueCancellationAndDeadlinesReleaseCapacity() = runTest(timeout = 5.seconds) {
        val reader = Reader()
        val entered = CompletableDeferred<Unit>()
        reader.wait = { entered.complete(Unit); awaitCancellation() }
        val service = DiffService(store(), reader, DiffServiceLimits(activeReads = 1, queuedReads = 1, deadlineMillis = 100))
        val first = async { service.worktrees(session) }
        entered.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { service.worktrees(session) }
        assertEquals(DiffReason.queue_full, service.worktrees(session).error?.reason)
        queued.cancelAndJoin()
        advanceTimeBy(99); runCurrent()
        assertFalse(first.isCompleted, "the active read keeps its full deadline")
        advanceTimeBy(1); runCurrent()
        assertTrue(first.isCompleted, "the deadline releases the active slot")
        assertEquals(DiffReason.timeout, first.await().error?.reason)
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { service.worktrees(session) }
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        reader.wait = null
        assertNotNull(service.worktrees(session).data)
    }
}
