package io.kotgent.daemon

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.ProjectId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.store.EventStore
import io.kotgent.store.FakeTaskStore
import io.kotgent.store.ForbiddingInterceptor
import io.kotgent.store.RecordingInterceptor
import io.kotgent.store.TASK_STORE_METHODS
import io.kotgent.store.SqliteEventStore
import io.kotgent.store.TaskStore
import io.kotgent.task.ActivityKind
import io.kotgent.task.ProjectFileWriter
import io.kotgent.task.ProjectFs
import io.kotgent.task.TaskActivityEntry
import io.kotgent.task.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SessionDoneTaskTest {

    private companion object {
        val UNREACHED_BY_DONE = setOf(
            "list", "get", "create", "update", "delete", "listBacklog", "move", "dependenciesOf",
            "dependentsOf", "dependencyEdges", "addDependency", "removeDependency", "comment",
            "activity", "upsertProject", "setProjectArchived", "listProjects", "listAllProjects",
            "project",
        )
    }

    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val ref = TaskRef("local:1")
    private val worker = SessionId("done01")
    private val neighbour = SessionId("other1")
    private val provider = ProviderSessionId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")

    private class Fixture {
        val journal: MutableList<String> = mutableListOf()
        val tmux = FakeTmux()
        val base = SqliteEventStore.inMemory(now = { 1L })
        val store = JournalingEventStore(base, journal)
    }

    private fun CoroutineScope.managerOver(
        fixture: Fixture,
        tasks: TaskStore?,
    ): SessionManager = SessionManager(
        fixture.tmux,
        fixture.store,
        PaneRegistry(),
        StubAgentFactory(listOf("cat"), provider),
        ProviderIdCapture(fixture.store, this),
        VendorStoreProbe { _, _, _ -> false },
        VendorSessionLocator { _, _ -> null },
        setOf("claude"),
        newSessionId = { SessionId("done01") },
        now = { 1L },
        taskStore = tasks,
    )

    private suspend fun Fixture.seedNeighbour(
        id: SessionId,
        ref: TaskRef,
        archived: Boolean = false,
        state: SessionState = if (archived) SessionState.stopped else SessionState.running,
    ) {
        store.upsertSession(
            SessionMeta(
                id = id,
                name = "kt-${id.value}",
                agent = "claude",
                cwd = "/tmp",
                tmuxSession = "kt-${id.value}",
                state = state,
                stateSource = EventSource.system,
                createdAt = 500L,
                updatedAt = 500L,
                archived = archived,
            ),
        )
        store.setTaskRef(id, ref)
    }


    @Test
    fun doneOnTheLastUnarchivedHolderClosesTheTaskUnlinksEveryHolderAndArchivesIt() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture()
            val tasks = recordingTasks(f.journal).apply {
                seedTask(ref, alpha, "title of ${ref.value}", state = TaskState.in_progress)
            }
            val mgr = managerOver(f, tasks)

            val _ = mgr.start("claude", "/tmp")
            f.store.setTaskRef(worker, ref)
            f.seedNeighbour(neighbour, ref, archived = true)
            f.journal.clear()

            mgr.markDone(worker)
            val trace = f.journal.filterNot { it.startsWith("sessions.getSession(") }

            assertEquals(listOf("done01"), f.tmux.killed, "Done still kills the agent")
            assertEquals(TaskState.done, tasks.snapshotEntries().getValue(ref).state, "the linked task is closed")

            val row = f.store.getSession(worker)!!
            assertTrue(row.archived, "the session is archived off the sidebar")
            assertNull(row.taskRef, "and no longer holds the task")
            assertEquals(SessionState.stopped, row.state, "the killed session is stopped")

            val other = f.store.getSession(neighbour)!!
            assertNull(other.taskRef, "the already-archived holder is released by the close too")

            assertEquals(
                listOf(
                    "sessions.setArchived(done01 = true)",
                    "tasks.transition(local:1, done)",
                    "sessions.clearTaskRefIf(done01, local:1)",
                    "tasks.appendActivity(local:1, unlinked)",
                    "sessions.clearTaskRefIf(other1, local:1)",
                    "tasks.appendActivity(local:1, unlinked)",
                ),
                trace,
                "the session archives before the holder count is taken, and the two stores never nest",
            )

            assertEquals(
                listOf(ActivityKind.transition, ActivityKind.unlinked, ActivityKind.unlinked),
                tasks.snapshotActivity().map { it.kind },
                "the feed records the close and one unlink per holder",
            )
            assertEquals(
                worker.value,
                tasks.snapshotActivity().first().author,
                "the close is attributed to the session that finished, not to the board",
            )
        }
    }

    @Test
    fun doneWhileAnotherLiveSessionHoldsTheTaskArchivesOnlyThisSession() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture()
            val tasks = recordingTasks(f.journal).apply {
                seedTask(ref, alpha, "title of ${ref.value}", state = TaskState.in_progress)
            }
            val mgr = managerOver(f, tasks)

            val _ = mgr.start("claude", "/tmp")
            f.store.setTaskRef(worker, ref)
            f.seedNeighbour(neighbour, ref)
            f.journal.clear()

            mgr.markDone(worker)
            val trace = f.journal.filterNot { it.startsWith("sessions.getSession(") }

            assertEquals(listOf("done01"), f.tmux.killed, "Done still kills this agent")
            assertEquals(
                TaskState.in_progress,
                tasks.snapshotEntries().getValue(ref).state,
                "the task stays open while another live session is still on it",
            )

            val row = f.store.getSession(worker)!!
            assertTrue(row.archived, "this session is archived off the sidebar")
            assertEquals(
                ref,
                row.taskRef,
                "and keeps its link, so `undone` restores a live holder that can block a later close",
            )

            val other = f.store.getSession(neighbour)!!
            assertEquals(ref, other.taskRef, "the live holder keeps working on it")
            assertFalse(other.archived, "and is neither archived")
            assertEquals(SessionState.running, other.state, "nor killed")

            assertEquals(
                listOf("sessions.setArchived(done01 = true)"),
                trace,
                "no task-layer write happens at all",
            )
            assertTrue(tasks.snapshotActivity().isEmpty(), "and the feed stays silent")
        }
    }

    @Test
    fun aCrashedHolderThatWasNeverMarkedDoneStillBlocksTheClose() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture()
            val tasks = recordingTasks(f.journal).apply {
                seedTask(ref, alpha, "title of ${ref.value}", state = TaskState.in_progress)
            }
            val mgr = managerOver(f, tasks)

            val _ = mgr.start("claude", "/tmp")
            f.store.setTaskRef(worker, ref)
            f.seedNeighbour(neighbour, ref, state = SessionState.crashed)

            mgr.markDone(worker)

            assertEquals(
                TaskState.in_progress,
                tasks.snapshotEntries().getValue(ref).state,
                "the predicate is `archived`, not liveness: a dead holder nobody closed still blocks",
            )
            assertEquals(ref, f.store.getSession(neighbour)!!.taskRef, "and keeps its link")
        }
    }

    @Test
    fun doneOnTheSecondSessionClosesTheTaskOnceTheFirstIsAlreadyDone() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture()
            val tasks = recordingTasks(f.journal).apply {
                seedTask(ref, alpha, "title of ${ref.value}", state = TaskState.in_progress)
            }
            val mgr = managerOver(f, tasks)

            val _ = mgr.start("claude", "/tmp")
            f.store.setTaskRef(worker, ref)
            f.seedNeighbour(neighbour, ref)

            mgr.markDone(worker)
            assertEquals(
                TaskState.in_progress,
                tasks.snapshotEntries().getValue(ref).state,
                "the first Done leaves the task open",
            )

            mgr.markDone(neighbour)

            assertEquals(
                TaskState.done,
                tasks.snapshotEntries().getValue(ref).state,
                "the last one to finish is the one that closes it",
            )
            assertNull(f.store.getSession(worker)!!.taskRef, "and every holder is released")
            assertNull(f.store.getSession(neighbour)!!.taskRef, "including the one that closed it")
            assertEquals(
                neighbour.value,
                tasks.snapshotActivity().first { it.kind == ActivityKind.transition }.author,
                "the close is attributed to the session that finished last",
            )
        }
    }

    @Test
    fun closingFromTheBoardUnlinksTheSessionAndLeavesItAlive() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture()
            val tasks = recordingTasks(f.journal).apply {
                seedTask(ref, alpha, "title of ${ref.value}", state = TaskState.in_progress)
            }
            val mgr = managerOver(f, tasks)
            val service = TaskService(tasks, f.store, UnusedProjectFs, UnusedProjectFileWriter)

            val _ = mgr.start("claude", "/tmp")
            f.store.setTaskRef(worker, ref)

            val _ = service.transition(ref, TaskState.done, TaskService.BOARD_AUTHOR, message = null)

            assertEquals(TaskState.done, tasks.snapshotEntries().getValue(ref).state, "the board closed the task")
            val row = f.store.getSession(worker)!!
            assertNull(row.taskRef, "which unlinks the session")
            assertFalse(row.archived, "but leaves it in the sidebar — that is what hands it back to `task next`")
            assertEquals(SessionState.running, row.state, "and alive")
            assertTrue(f.tmux.killed.isEmpty(), "the board never touches tmux")
        }
    }

    @Test
    fun theSessionCloseAndTheBoardCloseWriteTheSameThingToTheTaskLayer() = runBlocking {
        withTimeout(20.seconds) {
            suspend fun closeWith(
                close: suspend (SessionManager, TaskService) -> Unit,
            ): Triple<List<String>, List<TaskActivityEntry>, List<TaskRef?>> {
                val f = Fixture()
                val tasks = recordingTasks(f.journal).apply {
                    seedTask(ref, alpha, "title of ${ref.value}", state = TaskState.in_progress)
                }
                val mgr = managerOver(f, tasks)
                val service = TaskService(tasks, f.store, UnusedProjectFs, UnusedProjectFileWriter)

                val _ = mgr.start("claude", "/tmp")
                f.store.setTaskRef(worker, ref)
                // Archived, so the session close reaches the same last-live-holder path the board takes.
                f.seedNeighbour(neighbour, ref, archived = true)
                f.journal.clear()

                close(mgr, service)

                val trace = f.journal
                    .filterNot { it.startsWith("sessions.getSession(") }
                    .filterNot { it.startsWith("sessions.setArchived(") }
                return Triple(
                    trace,
                    tasks.snapshotActivity(),
                    listOf(f.store.getSession(worker)!!.taskRef, f.store.getSession(neighbour)!!.taskRef),
                )
            }

            val fromSession = closeWith { mgr, _ -> mgr.markDone(worker) }
            val fromBoard = closeWith { _, service ->
                val _ = service.transition(ref, TaskState.done, author = worker.value, message = null)
            }

            assertEquals(
                fromSession.first,
                fromBoard.first,
                "the two closes must issue the same store calls, in the same order",
            )
            assertEquals(fromSession.second, fromBoard.second, "and record the same activity feed")
            assertEquals(fromSession.third, fromBoard.third, "and leave the same holders unlinked")
            assertEquals(
                listOf(
                    "tasks.transition(local:1, done)",
                    "sessions.clearTaskRefIf(done01, local:1)",
                    "tasks.appendActivity(local:1, unlinked)",
                    "sessions.clearTaskRefIf(other1, local:1)",
                    "tasks.appendActivity(local:1, unlinked)",
                ),
                fromSession.first,
                "spelled out once, so a change that moves BOTH copies together is still visible here",
            )
        }
    }

    @Test
    fun doneCannotEraseAHoldersNewerLinkToADifferentTask() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture()
            val other = TaskRef("local:2")
            val tasks = recordingTasks(f.journal).apply {
                seedTask(ref, alpha, "title of ${ref.value}", state = TaskState.in_progress)
                seedTask(other, alpha, "title of ${other.value}", state = TaskState.in_progress)
            }
            val mgr = managerOver(f, tasks)

            val _ = mgr.start("claude", "/tmp")
            f.store.setTaskRef(worker, ref)
            f.seedNeighbour(neighbour, ref, archived = true)
            // The second snapshot is the one the unlink loop walks; race the clear that follows it.
            var snapshots = 0
            f.store.afterSessionsHoldingTask = {
                snapshots += 1
                if (snapshots == 2) f.store.setTaskRef(neighbour, other)
            }

            mgr.markDone(worker)

            assertEquals(TaskState.done, tasks.snapshotEntries().getValue(ref).state, "the task still closes")
            assertNull(f.store.getSession(worker)!!.taskRef, "the holder that stayed put is released")
            assertEquals(
                other,
                f.store.getSession(neighbour)!!.taskRef,
                "the one that moved on keeps its newer link",
            )
            assertEquals(
                listOf(ActivityKind.transition, ActivityKind.unlinked),
                tasks.snapshotActivity().map { it.kind },
                "and the feed records one release, for the one holder actually released",
            )
            assertEquals(
                worker.value,
                tasks.snapshotActivity().last().author,
                "…namely the session that pressed Done",
            )
        }
    }


    @Test
    fun reviewThenNextLeavesTheReviewedTaskStrandedAndMakesDoneCloseTheOtherOne() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture()
            val reviewed = ref
            val taken = TaskRef("local:2")
            val tasks = recordingTasks(f.journal).apply {
                seedTask(reviewed, alpha, "title of ${reviewed.value}", state = TaskState.todo)
                seedTask(taken, alpha, "title of ${taken.value}", state = TaskState.todo, position = 2.0)
            }
            val mgr = managerOver(f, tasks)
            val service = TaskService(tasks, f.store, UnusedProjectFs, UnusedProjectFileWriter)

            val _ = mgr.start("claude", "/tmp")

            service.link(worker, reviewed)
            val _ = service.transition(reviewed, TaskState.review, author = worker.value, message = "summary")
            assertEquals(
                reviewed,
                f.store.getSession(worker)!!.taskRef,
                "review keeps the link — that is the rule the loop has to respect",
            )

            assertEquals(taken, service.linkNext(worker, alpha)?.ref, "…and `next` hands out the other task")
            assertEquals(
                taken,
                f.store.getSession(worker)!!.taskRef,
                "which OVERWRITES the link, silently: the session now points at the new task",
            )
            assertTrue(
                f.store.sessionsHoldingTask(reviewed).isEmpty(),
                "so the reviewed task is left with no session at all — nobody's terminal to review",
            )
            assertEquals(
                listOf(ActivityKind.linked, ActivityKind.transition, ActivityKind.linked),
                tasks.snapshotActivity().map { it.kind },
                "and nothing in the feed says the reviewed task lost its worker",
            )

            mgr.markDone(worker)

            assertEquals(
                TaskState.done,
                tasks.snapshotEntries().getValue(taken).state,
                "Done closes what the link points at NOW — the task the agent had only just started",
            )
            assertEquals(
                TaskState.review,
                tasks.snapshotEntries().getValue(reviewed).state,
                "the reviewed task is not closed by that Done…",
            )
            assertTrue(
                f.store.getSession(worker)!!.archived,
                "…and the session that was carrying it is archived off the sidebar",
            )
        }
    }

    @Test
    fun linkNextRefusesASelectedCardWhenItsProjectIsTombstonedBeforeStart() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture()
            val tasks = recordingTasks(f.journal).apply {
                seedTask(ref, alpha, "title of ${ref.value}", state = TaskState.todo)
            }
            val mgr = managerOver(f, tasks)
            val service = TaskService(tasks, f.store, UnusedProjectFs, UnusedProjectFileWriter)

            val _ = mgr.start("claude", "/tmp")
            f.journal.clear()
            tasks.afterNextCandidate = {
                tasks.afterNextCandidate = null
                tasks.seedArchived(alpha, archived = true)
            }

            val taken = service.linkNext(worker, alpha)
            val trace = f.journal.toList()

            assertNull(taken, "a card selected before its project tombstone must not be handed out")
            assertEquals(TaskState.todo, tasks.snapshotEntries().getValue(ref).state, "the refused card stays todo")
            assertNull(f.store.getSession(worker)!!.taskRef, "the session stays unlinked")
            assertEquals(
                listOf(
                    "tasks.nextCandidate(${alpha.value})",
                    "tasks.startIfTodoInLiveProject(${ref.value})",
                    "tasks.nextCandidate(${alpha.value})",
                ),
                trace,
                "the automatic path records and retries the tombstone-aware operation",
            )
        }
    }


    @Test
    fun doneOnAnUnlinkedSessionNeverConsultsTheTaskStore() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture()
            val mgr = managerOver(f, refusingTasks())

            val _ = mgr.start("claude", "/tmp")
            mgr.markDone(worker)

            assertEquals(listOf("done01"), f.tmux.killed, "kill as before")
            assertTrue(f.store.getSession(worker)!!.archived, "archive as before")
        }
    }

    @Test
    fun doneWithoutATaskLayerBehavesExactlyAsBefore() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture()
            val mgr = managerOver(f, tasks = null)

            val _ = mgr.start("claude", "/tmp")
            f.store.setTaskRef(worker, ref)

            mgr.markDone(worker)

            val row = f.store.getSession(worker)!!
            assertTrue(row.archived, "archive as before")
            assertEquals(ref, row.taskRef, "and nothing pretends to have closed a task")
        }
    }


    @Test
    fun cancellationBetweenTheTwoWritesCannotHalfApplyDone() = runBlocking {
        withTimeout(20.seconds) {
            val f = Fixture()
            val tasks = recordingTasks(f.journal).apply {
                seedTask(ref, alpha, "title of ${ref.value}", state = TaskState.in_progress)
            }
            val mgr = managerOver(f, tasks)

            val _ = mgr.start("claude", "/tmp")
            f.store.setTaskRef(worker, ref)

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // Suspends after the archive and before the close, which is the gap the two stores leave.
            f.store.afterSessionsHoldingTask = {
                f.store.afterSessionsHoldingTask = null
                entered.complete(Unit)
                release.await()
            }

            val job = launch { mgr.markDone(worker) }
            entered.await()
            job.cancel(CancellationException("the client walked away mid-Done"))
            release.complete(Unit)
            job.join()

            assertEquals(TaskState.done, tasks.snapshotEntries().getValue(ref).state, "the task still closed")
            val row = f.store.getSession(worker)!!
            assertTrue(row.archived, "and the session still archived — cancellation cannot split the pair")
            assertNull(row.taskRef, "the holder was unlinked on the way")
        }
    }


    private class JournalingEventStore(
        private val delegate: EventStore,
        private val journal: MutableList<String>,
    ) : EventStore by delegate {

        override suspend fun getSession(sessionId: SessionId): SessionMeta? {
            journal += "sessions.getSession(${sessionId.value})"
            return delegate.getSession(sessionId)
        }

        override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?) {
            journal += "sessions.setTaskRef(${sessionId.value} -> ${taskRef?.value})"
            delegate.setTaskRef(sessionId, taskRef)
        }

        // Runs after the holder snapshot so a newer link can race the following conditional clear.
        var afterSessionsHoldingTask: (suspend () -> Unit)? = null

        override suspend fun sessionsHoldingTask(taskRef: TaskRef): List<SessionMeta> {
            val holders = delegate.sessionsHoldingTask(taskRef)
            afterSessionsHoldingTask?.invoke()
            return holders
        }

        override suspend fun clearTaskRefIf(
            sessionId: SessionId,
            expectedRef: TaskRef,
        ): Boolean {
            journal += "sessions.clearTaskRefIf(${sessionId.value}, ${expectedRef.value})"
            return delegate.clearTaskRefIf(sessionId, expectedRef)
        }

        override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long) {
            journal += "sessions.setArchived(${sessionId.value} = $archived)"
            delegate.setArchived(sessionId, archived, updatedAt)
        }
    }

    private fun recordingTasks(journal: MutableList<String>): FakeTaskStore =
        FakeTaskStore(now = { 1_000L }).also {
            it.interceptor = ForbiddingInterceptor(
                UNREACHED_BY_DONE,
                RecordingInterceptor(
                    describe = { call -> "tasks.${call.method}(${call.args.joinToString(", ")})" },
                    entries = journal,
                ),
            ) { store, method -> "Done is not expected to call $store.$method" }
        }

    private fun refusingTasks(): FakeTaskStore = FakeTaskStore().also {
        it.interceptor = ForbiddingInterceptor(TASK_STORE_METHODS) { store, method ->
            "an unlinked Done must not reach $store.$method"
        }
    }

    private object UnusedProjectFs : ProjectFs {
        override fun isDirectory(path: String): Boolean = error("Done must not touch the filesystem")
        override fun readFile(path: String, maxBytes: Int): String? = error("Done must not touch the filesystem")
        override fun canonicalize(path: String): String? = error("Done must not touch the filesystem")
    }

    private object UnusedProjectFileWriter : ProjectFileWriter {
        override suspend fun ensureProjectFile(dir: String, name: String) =
            error("Done must not write a project file")
    }

    private class StubAgentFactory(
        private val command: List<String>,
        private val preallocated: ProviderSessionId,
    ) : AgentFactory {
        override fun create(agentKind: String, cwd: String): AgentAdapter = object : AgentAdapter {
            override val events = emptyFlow<AgentEvent>()
            override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec = when (mode) {
                is LaunchMode.New -> LaunchSpec(command, emptyMap(), cwd, preallocated)
                is LaunchMode.Resume -> LaunchSpec(command, emptyMap(), cwd, null)
            }
        }
    }
}
