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
import io.kotgent.store.SqliteEventStore
import io.kotgent.store.TaskStore
import io.kotgent.task.ActivityKind
import io.kotgent.task.BacklogEntry
import io.kotgent.task.MoveTarget
import io.kotgent.task.ProjectFileWriter
import io.kotgent.task.ProjectFs
import io.kotgent.task.ProjectRecord
import io.kotgent.task.Task
import io.kotgent.task.TaskActivityEntry
import io.kotgent.task.TaskState
import io.kotgent.task.TaskUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionDoneTaskTest {

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

    private suspend fun Fixture.seedNeighbour(id: SessionId, ref: TaskRef) {
        store.upsertSession(
            SessionMeta(
                id = id,
                name = "kt-${id.value}",
                agent = "claude",
                cwd = "/tmp",
                tmuxSession = "kt-${id.value}",
                state = SessionState.running,
                stateSource = EventSource.system,
                createdAt = 500L,
                updatedAt = 500L,
            ),
        )
        store.setTaskRef(id, ref, 500L)
    }


    @Test
    fun doneOnALinkedSessionClosesTheTaskUnlinksEveryHolderAndArchivesIt() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture()
            val tasks = RecordingTaskStore(f.journal).apply { seed(ref, alpha, TaskState.in_progress) }
            val mgr = managerOver(f, tasks)

            mgr.start("claude", "/tmp")
            f.store.setTaskRef(worker, ref, 1L)
            f.seedNeighbour(neighbour, ref)
            f.journal.clear()

            mgr.markDone(worker)
            val trace = f.journal.filterNot { it.startsWith("sessions.getSession(") }

            assertEquals(listOf("done01"), f.tmux.killed, "Done still kills the agent")
            assertEquals(TaskState.done, tasks.entries.getValue(ref).state, "the linked task is closed")

            val row = f.store.getSession(worker)!!
            assertTrue(row.archived, "the session is archived off the sidebar")
            assertNull(row.taskRef, "and no longer holds the task")
            assertEquals(SessionState.stopped, row.state, "the killed session is stopped")

            val other = f.store.getSession(neighbour)!!
            assertNull(other.taskRef, "every OTHER holder is unlinked too")
            assertFalse(other.archived, "but closing a task never archives somebody else's session")
            assertEquals(SessionState.running, other.state, "nor kills it")

            assertEquals(
                listOf(
                    "tasks.transition(local:1 -> done)",
                    "sessions.clearTaskRefIf(done01, local:1)",
                    "tasks.appendActivity(local:1, unlinked)",
                    "sessions.clearTaskRefIf(other1, local:1)",
                    "tasks.appendActivity(local:1, unlinked)",
                    "sessions.setArchived(done01 = true)",
                ),
                trace,
                "the task closes before the session is archived, and the two stores never nest",
            )

            assertEquals(
                listOf(ActivityKind.transition, ActivityKind.unlinked, ActivityKind.unlinked),
                tasks.activity.map { it.kind },
                "the feed records the close and one unlink per holder",
            )
            assertEquals(
                worker.value,
                tasks.activity.first().author,
                "the close is attributed to the session that finished, not to the board",
            )
        }
    }

    @Test
    fun closingFromTheBoardUnlinksTheSessionAndLeavesItAlive() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture()
            val tasks = RecordingTaskStore(f.journal).apply { seed(ref, alpha, TaskState.in_progress) }
            val mgr = managerOver(f, tasks)
            val service = TaskService(tasks, f.store, UnusedProjectFs, UnusedProjectFileWriter, now = { 2L })

            mgr.start("claude", "/tmp")
            f.store.setTaskRef(worker, ref, 1L)

            service.transition(ref, TaskState.done, TaskService.BOARD_AUTHOR, message = null)

            assertEquals(TaskState.done, tasks.entries.getValue(ref).state, "the board closed the task")
            val row = f.store.getSession(worker)!!
            assertNull(row.taskRef, "which unlinks the session")
            assertFalse(row.archived, "but leaves it in the sidebar — that is what hands it back to `task next`")
            assertEquals(SessionState.running, row.state, "and alive")
            assertTrue(f.tmux.killed.isEmpty(), "the board never touches tmux")
        }
    }

    @Test
    fun theSessionCloseAndTheBoardCloseWriteTheSameThingToTheTaskLayer() = runBlocking {
        withTimeout(20_000) {
            suspend fun closeWith(
                close: suspend (SessionManager, TaskService) -> Unit,
            ): Triple<List<String>, List<TaskActivityEntry>, List<TaskRef?>> {
                val f = Fixture()
                val tasks = RecordingTaskStore(f.journal).apply { seed(ref, alpha, TaskState.in_progress) }
                val mgr = managerOver(f, tasks)
                val service = TaskService(tasks, f.store, UnusedProjectFs, UnusedProjectFileWriter, now = { 1L })

                mgr.start("claude", "/tmp")
                f.store.setTaskRef(worker, ref, 1L)
                f.seedNeighbour(neighbour, ref)
                f.journal.clear()

                close(mgr, service)

                val trace = f.journal
                    .filterNot { it.startsWith("sessions.getSession(") }
                    .filterNot { it.startsWith("sessions.setArchived(") }
                return Triple(
                    trace,
                    tasks.activity.toList(),
                    listOf(f.store.getSession(worker)!!.taskRef, f.store.getSession(neighbour)!!.taskRef),
                )
            }

            val fromSession = closeWith { mgr, _ -> mgr.markDone(worker) }
            val fromBoard = closeWith { _, service ->
                service.transition(ref, TaskState.done, author = worker.value, message = null)
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
                    "tasks.transition(local:1 -> done)",
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
        withTimeout(20_000) {
            val f = Fixture()
            val other = TaskRef("local:2")
            val tasks = RecordingTaskStore(f.journal).apply {
                seed(ref, alpha, TaskState.in_progress)
                seed(other, alpha, TaskState.in_progress)
            }
            val mgr = managerOver(f, tasks)

            mgr.start("claude", "/tmp")
            f.store.setTaskRef(worker, ref, 1L)
            f.seedNeighbour(neighbour, ref)
            f.store.afterSessionsHoldingTask = {
                f.store.afterSessionsHoldingTask = null
                f.store.setTaskRef(neighbour, other, 700L)
            }

            mgr.markDone(worker)

            assertEquals(TaskState.done, tasks.entries.getValue(ref).state, "the task still closes")
            assertNull(f.store.getSession(worker)!!.taskRef, "the holder that stayed put is released")
            assertEquals(
                other,
                f.store.getSession(neighbour)!!.taskRef,
                "the one that moved on keeps its newer link",
            )
            assertEquals(
                listOf(ActivityKind.transition, ActivityKind.unlinked),
                tasks.activity.map { it.kind },
                "and the feed records one release, for the one holder actually released",
            )
            assertEquals(
                worker.value,
                tasks.activity.last().author,
                "…namely the session that pressed Done",
            )
        }
    }


    @Test
    fun reviewThenNextLeavesTheReviewedTaskStrandedAndMakesDoneCloseTheOtherOne() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture()
            val reviewed = ref
            val taken = TaskRef("local:2")
            val tasks = RecordingTaskStore(f.journal).apply {
                seed(reviewed, alpha, TaskState.todo)
                seed(taken, alpha, TaskState.todo, position = 2.0)
            }
            val mgr = managerOver(f, tasks)
            val service = TaskService(tasks, f.store, UnusedProjectFs, UnusedProjectFileWriter, now = { 2L })

            mgr.start("claude", "/tmp")

            service.link(worker, reviewed)
            service.transition(reviewed, TaskState.review, author = worker.value, message = "summary")
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
                tasks.activity.map { it.kind },
                "and nothing in the feed says the reviewed task lost its worker",
            )

            mgr.markDone(worker)

            assertEquals(
                TaskState.done,
                tasks.entries.getValue(taken).state,
                "Done closes what the link points at NOW — the task the agent had only just started",
            )
            assertEquals(
                TaskState.review,
                tasks.entries.getValue(reviewed).state,
                "the reviewed task is not closed by that Done…",
            )
            assertTrue(
                f.store.getSession(worker)!!.archived,
                "…and the session that was carrying it is archived off the sidebar",
            )
        }
    }


    @Test
    fun doneOnAnUnlinkedSessionNeverConsultsTheTaskStore() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture()
            val mgr = managerOver(f, RefusingTaskStore)

            mgr.start("claude", "/tmp")
            mgr.markDone(worker)

            assertEquals(listOf("done01"), f.tmux.killed, "kill as before")
            assertTrue(f.store.getSession(worker)!!.archived, "archive as before")
        }
    }

    @Test
    fun doneWithoutATaskLayerBehavesExactlyAsBefore() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture()
            val mgr = managerOver(f, tasks = null)

            mgr.start("claude", "/tmp")
            f.store.setTaskRef(worker, ref, 1L)

            mgr.markDone(worker)

            val row = f.store.getSession(worker)!!
            assertTrue(row.archived, "archive as before")
            assertEquals(ref, row.taskRef, "and nothing pretends to have closed a task")
        }
    }


    @Test
    fun cancellationBetweenTheTwoWritesCannotHalfApplyDone() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture()
            val tasks = RecordingTaskStore(f.journal).apply { seed(ref, alpha, TaskState.in_progress) }
            f.store.yieldBeforeArchive = true
            val mgr = managerOver(f, tasks)

            mgr.start("claude", "/tmp")
            f.store.setTaskRef(worker, ref, 1L)

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            tasks.duringTransition = {
                entered.complete(Unit)
                release.await()
            }

            val job = launch { mgr.markDone(worker) }
            entered.await()
            job.cancel(CancellationException("the client walked away mid-Done"))
            release.complete(Unit)
            job.join()

            assertEquals(TaskState.done, tasks.entries.getValue(ref).state, "the task still closed")
            val row = f.store.getSession(worker)!!
            assertTrue(row.archived, "and the session still archived — cancellation cannot split the pair")
            assertNull(row.taskRef, "the holder was unlinked on the way")
        }
    }


    private class JournalingEventStore(
        private val delegate: EventStore,
        private val journal: MutableList<String>,
    ) : EventStore by delegate {
        var yieldBeforeArchive: Boolean = false

        override suspend fun getSession(sessionId: SessionId): SessionMeta? {
            journal += "sessions.getSession(${sessionId.value})"
            return delegate.getSession(sessionId)
        }

        override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?, updatedAt: Long) {
            journal += "sessions.setTaskRef(${sessionId.value} -> ${taskRef?.value})"
            delegate.setTaskRef(sessionId, taskRef, updatedAt)
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
            updatedAt: Long,
        ): Boolean {
            journal += "sessions.clearTaskRefIf(${sessionId.value}, ${expectedRef.value})"
            return delegate.clearTaskRefIf(sessionId, expectedRef, updatedAt)
        }

        override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long) {
            if (yieldBeforeArchive) yield()
            journal += "sessions.setArchived(${sessionId.value} = $archived)"
            delegate.setArchived(sessionId, archived, updatedAt)
        }
    }

    private class RecordingTaskStore(private val journal: MutableList<String>) : TaskStore {
        val entries: MutableMap<TaskRef, BacklogEntry> = mutableMapOf()
        val activity: MutableList<TaskActivityEntry> = mutableListOf()

        var duringTransition: (suspend () -> Unit)? = null

        private var rev = 0L
        private var activityId = 0L

        fun seed(ref: TaskRef, project: ProjectId, state: TaskState, position: Double = 1.0) {
            entries[ref] = BacklogEntry(
                ref = ref,
                project = project,
                position = position,
                state = state,
                blocked = false,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                rev = ++rev,
            )
        }

        override val id: String = TaskRef.LOCAL_TRACKER
        override val taskUpdates: SharedFlow<TaskUpdate> = MutableSharedFlow()

        override suspend fun transition(
            ref: TaskRef,
            to: TaskState,
            author: String,
            message: String?,
        ): BacklogEntry? {
            journal += "tasks.transition(${ref.value} -> $to)"
            val existing = entries[ref] ?: return null
            val moved = existing.copy(state = to, rev = ++rev)
            entries[ref] = moved
            activity += TaskActivityEntry(
                id = ++activityId,
                ref = ref,
                ts = 0L,
                kind = ActivityKind.transition,
                author = author,
                text = message,
                fromState = existing.state,
                toState = to,
            )
            duringTransition?.invoke()
            return moved
        }

        override suspend fun appendActivity(
            ref: TaskRef,
            kind: ActivityKind,
            author: String,
            text: String?,
            fromState: TaskState?,
            toState: TaskState?,
        ): TaskActivityEntry? {
            journal += "tasks.appendActivity(${ref.value}, $kind)"
            if (ref !in entries) return null
            val row = TaskActivityEntry(++activityId, ref, 0L, kind, author, text, fromState, toState)
            activity += row
            return row
        }


        override suspend fun entry(ref: TaskRef): BacklogEntry? {
            journal += "tasks.entry(${ref.value})"
            return entries[ref]
        }

        override suspend fun nextCandidate(project: ProjectId): BacklogEntry? {
            journal += "tasks.nextCandidate($project)"
            return entries.values
                .filter { it.project == project && it.state == TaskState.todo && !it.blocked }
                .minByOrNull { it.position }
        }

        override suspend fun startIfTodo(ref: TaskRef): Boolean {
            journal += "tasks.startIfTodo(${ref.value})"
            val existing = entries[ref] ?: return false
            if (existing.state != TaskState.todo) return false
            entries[ref] = existing.copy(state = TaskState.in_progress, rev = ++rev)
            return true
        }

        override suspend fun list(project: ProjectId): List<Task> = unused("list")
        override suspend fun get(ref: TaskRef): Task? = unused("get")
        override suspend fun create(project: ProjectId, title: String, body: String, author: String): Task =
            unused("create")
        override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? = unused("update")
        override suspend fun delete(ref: TaskRef): Boolean = unused("delete")
        override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> = unused("listBacklog")
        override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = unused("move")
        override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> = unused("dependenciesOf")
        override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> = unused("dependentsOf")
        override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> =
            unused("dependencyEdges")
        override suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef) = unused("addDependency")
        override suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef) = unused("removeDependency")
        override suspend fun comment(ref: TaskRef, author: String, text: String): TaskActivityEntry? =
            unused("comment")
        override suspend fun activity(ref: TaskRef): List<TaskActivityEntry> = unused("activity")
        override suspend fun upsertProject(id: ProjectId, name: String, path: String?) = unused("upsertProject")
        override suspend fun setProjectArchived(id: ProjectId, archived: Boolean) = unused("setProjectArchived")
        override suspend fun listProjects(archived: Boolean): List<ProjectRecord> = unused("listProjects")
        override suspend fun project(id: ProjectId): ProjectRecord? = unused("project")

        private fun unused(name: String): Nothing = error("Done is not expected to call TaskStore.$name")
    }

    private object RefusingTaskStore : TaskStore {
        override val id: String = TaskRef.LOCAL_TRACKER
        override val taskUpdates: SharedFlow<TaskUpdate> = MutableSharedFlow()

        override suspend fun entry(ref: TaskRef): BacklogEntry? = unused("entry")
        override suspend fun list(project: ProjectId): List<Task> = unused("list")
        override suspend fun get(ref: TaskRef): Task? = unused("get")
        override suspend fun create(project: ProjectId, title: String, body: String, author: String): Task =
            unused("create")
        override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? = unused("update")
        override suspend fun delete(ref: TaskRef): Boolean = unused("delete")
        override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> = unused("listBacklog")
        override suspend fun nextCandidate(project: ProjectId): BacklogEntry? = unused("nextCandidate")
        override suspend fun startIfTodo(ref: TaskRef): Boolean = unused("startIfTodo")
        override suspend fun transition(
            ref: TaskRef,
            to: TaskState,
            author: String,
            message: String?,
        ): BacklogEntry? = unused("transition")
        override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = unused("move")
        override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> = unused("dependenciesOf")
        override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> = unused("dependentsOf")
        override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> =
            unused("dependencyEdges")
        override suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef) = unused("addDependency")
        override suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef) = unused("removeDependency")
        override suspend fun comment(ref: TaskRef, author: String, text: String): TaskActivityEntry? =
            unused("comment")
        override suspend fun appendActivity(
            ref: TaskRef,
            kind: ActivityKind,
            author: String,
            text: String?,
            fromState: TaskState?,
            toState: TaskState?,
        ): TaskActivityEntry? = unused("appendActivity")
        override suspend fun activity(ref: TaskRef): List<TaskActivityEntry> = unused("activity")
        override suspend fun upsertProject(id: ProjectId, name: String, path: String?) = unused("upsertProject")
        override suspend fun setProjectArchived(id: ProjectId, archived: Boolean) = unused("setProjectArchived")
        override suspend fun listProjects(archived: Boolean): List<ProjectRecord> = unused("listProjects")
        override suspend fun project(id: ProjectId): ProjectRecord? = unused("project")

        private fun unused(name: String): Nothing =
            error("an unlinked Done must not reach TaskStore.$name")
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
