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

/**
 * Session "Done" closes the task (plan Task 29) — [SessionManager.markDone] and nothing else.
 *
 * ## What these tests are built around
 *
 *  1. **"Done" on the session is what closes the task.** One session, one task, end to end: the human
 *     reviews *this* session's terminal, so pressing Done kills the agent, moves the task to `done`,
 *     unlinks every session holding it and archives this one. Closing the same task from the BOARD is
 *     the mirror image — [TaskService.transition] unlinks the holders and leaves them **alive** — and
 *     [closingFromTheBoardUnlinksTheSessionAndLeavesItAlive] asserts that contrast from the same
 *     fixture, because "the task is done and the sessions are unlinked" is true of both paths and only
 *     the session's fate tells them apart.
 *  2. **The ORDER of the two stores' writes is the contract**, so both stores journal into one shared
 *     list: the task must be closed BEFORE the session is archived (the residual the design accepts is a
 *     task `done` whose session is still visible, never an archived session whose task is still open and
 *     therefore unreachable from the sidebar), and no [EventStore] call may be made from inside a
 *     [TaskStore] one.
 *  3. **[NonCancellable] is a claim about cancellation only**, and
 *     [cancellationBetweenTheTwoWritesCannotHalfApplyDone] is what pins it. Its journaling store yields
 *     before archiving, which is what makes the assertion falsifiable: with the wrapper removed, the
 *     cancelled coroutine throws there and the row stays unarchived. Nothing here claims atomicity —
 *     a throw or a process death between the writes still leaves the documented residual, which no test
 *     can rule out and the KDoc says so.
 *  4. **A daemon without the task layer, and a session without a link, must behave exactly as before.**
 *     Both cases run against a [TaskStore] whose every member throws, so a `markDone` that consulted it
 *     speculatively fails loudly instead of passing on an empty answer.
 *
 * Every body is bounded by [withTimeout] as an anti-hang tripwire.
 */
class SessionDoneTaskTest {

    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val ref = TaskRef("local:1")
    private val worker = SessionId("done01")
    private val neighbour = SessionId("other1")
    private val provider = ProviderSessionId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")

    /** The session the manager starts, plus a second holder of the same task, over one journal. */
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

    /** A second, independent session pointing at the same task — the "every holder" in the contract. */
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

    // ---- Done on a linked session -----------------------------------------------------------------

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
            // Snapshotted before the assertions below read rows of their own. The row READS are dropped:
            // `terminate` and the task close each take one, and neither is part of the write ordering.
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
                // Holders come back oldest-first (`created_at, id`): the worker was created at 1, the
                // neighbour seeded at 500. Both are cleared by the same generic loop, and each
                // EventStore call returns before the TaskStore call that follows it.
                listOf(
                    "tasks.transition(local:1 -> done)",
                    "sessions.setTaskRef(done01 -> null)",
                    "tasks.appendActivity(local:1, unlinked)",
                    "sessions.setTaskRef(other1 -> null)",
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

    // ---- Done with no task to close ---------------------------------------------------------------

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
            // A link that a daemon built without the task layer cannot possibly close: the row still
            // archives, and the ref is left alone rather than half-cleared.
            f.store.setTaskRef(worker, ref, 1L)

            mgr.markDone(worker)

            val row = f.store.getSession(worker)!!
            assertTrue(row.archived, "archive as before")
            assertEquals(ref, row.taskRef, "and nothing pretends to have closed a task")
        }
    }

    // ---- The NonCancellable claim, and only that claim ---------------------------------------------

    @Test
    fun cancellationBetweenTheTwoWritesCannotHalfApplyDone() = runBlocking {
        withTimeout(20_000) {
            val f = Fixture()
            val tasks = RecordingTaskStore(f.journal).apply { seed(ref, alpha, TaskState.in_progress) }
            // The archive suspends before it writes — the shape a contended store mutex takes, and what
            // makes this test falsifiable: without NonCancellable the cancelled coroutine throws there.
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

    // ---- fakes -------------------------------------------------------------------------------------

    /**
     * The real [SqliteEventStore], journalling the three calls whose ORDER relative to the task store is
     * the contract. Everything else is delegated: the point of using the real store here is that
     * `sessionsHoldingTask` really is a query over the rows the manager wrote.
     */
    private class JournalingEventStore(
        private val delegate: EventStore,
        private val journal: MutableList<String>,
    ) : EventStore by delegate {
        /** See [cancellationBetweenTheTwoWritesCannotHalfApplyDone]. */
        var yieldBeforeArchive: Boolean = false

        override suspend fun getSession(sessionId: SessionId): SessionMeta? {
            journal += "sessions.getSession(${sessionId.value})"
            return delegate.getSession(sessionId)
        }

        override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?, updatedAt: Long) {
            journal += "sessions.setTaskRef(${sessionId.value} -> ${taskRef?.value})"
            delegate.setTaskRef(sessionId, taskRef, updatedAt)
        }

        override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long) {
            if (yieldBeforeArchive) yield()
            journal += "sessions.setArchived(${sessionId.value} = $archived)"
            delegate.setArchived(sessionId, archived, updatedAt)
        }
    }

    /**
     * An in-memory [TaskStore] covering exactly the two methods the Done path calls, plus the three
     * [TaskService.transition] needs for the board contrast. Every other member throws: a `markDone`
     * that grew a call this fixture never modelled must fail loudly rather than read an empty list as an
     * answer — the same reasoning as [EventStore]'s three throwing defaults.
     */
    private class RecordingTaskStore(private val journal: MutableList<String>) : TaskStore {
        val entries: MutableMap<TaskRef, BacklogEntry> = mutableMapOf()
        val activity: MutableList<TaskActivityEntry> = mutableListOf()

        /** Runs INSIDE [transition], after the write — the window a cancellation is delivered in. */
        var duringTransition: (suspend () -> Unit)? = null

        private var rev = 0L
        private var activityId = 0L

        fun seed(ref: TaskRef, project: ProjectId, state: TaskState) {
            entries[ref] = BacklogEntry(
                ref = ref,
                project = project,
                position = 1.0,
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

        override suspend fun entry(ref: TaskRef): BacklogEntry? = unused("entry")
        override suspend fun list(project: ProjectId): List<Task> = unused("list")
        override suspend fun get(ref: TaskRef): Task? = unused("get")
        override suspend fun create(project: ProjectId, title: String, body: String): Task = unused("create")
        override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? = unused("update")
        override suspend fun delete(ref: TaskRef): Boolean = unused("delete")
        override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> = unused("listBacklog")
        override suspend fun nextCandidate(project: ProjectId): BacklogEntry? = unused("nextCandidate")
        override suspend fun startIfTodo(ref: TaskRef): Boolean = unused("startIfTodo")
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
        override suspend fun listProjects(): List<ProjectRecord> = unused("listProjects")
        override suspend fun project(id: ProjectId): ProjectRecord? = unused("project")

        private fun unused(name: String): Nothing = error("Done is not expected to call TaskStore.$name")
    }

    /** A task layer that refuses every question — the tripwire for a Done that consults it speculatively. */
    private object RefusingTaskStore : TaskStore {
        override val id: String = TaskRef.LOCAL_TRACKER
        override val taskUpdates: SharedFlow<TaskUpdate> = MutableSharedFlow()

        override suspend fun entry(ref: TaskRef): BacklogEntry? = unused("entry")
        override suspend fun list(project: ProjectId): List<Task> = unused("list")
        override suspend fun get(ref: TaskRef): Task? = unused("get")
        override suspend fun create(project: ProjectId, title: String, body: String): Task = unused("create")
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
        override suspend fun listProjects(): List<ProjectRecord> = unused("listProjects")
        override suspend fun project(ref: ProjectId): ProjectRecord? = unused("project")

        private fun unused(name: String): Nothing =
            error("an unlinked Done must not reach TaskStore.$name")
    }

    /** [TaskService] carries these for the write routes and never calls either from [TaskService.transition]. */
    private object UnusedProjectFs : ProjectFs {
        override fun isDirectory(path: String): Boolean = error("Done must not touch the filesystem")
        override fun readFile(path: String, maxBytes: Int): String? = error("Done must not touch the filesystem")
        override fun canonicalize(path: String): String? = error("Done must not touch the filesystem")
    }

    private object UnusedProjectFileWriter : ProjectFileWriter {
        override suspend fun ensureProjectFile(dir: String, name: String) =
            error("Done must not write a project file")
    }

    /** An [AgentFactory] yielding a canned `cat` pane with a preallocated provider id. */
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
