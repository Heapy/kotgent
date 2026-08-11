package io.kotgent.daemon

import io.kotgent.core.EventSource
import io.kotgent.core.ProjectId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.Seq
import io.kotgent.core.TaskRef
import io.kotgent.store.EventStore
import io.kotgent.store.SessionUpdate
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskServiceTest {


    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")

    private val t1 = TaskRef("local:1")
    private val t2 = TaskRef("local:2")
    private val absent = TaskRef("local:404")

    private val s1 = SessionId("s-one")
    private val s2 = SessionId("s-two")

    private val clock = 7_000L

    private inner class Fixture {
        val journal: MutableList<String> = mutableListOf()
        val witness = LockWitness()
        val tasks = FakeTaskStore(journal, witness)
        val sessions = FakeEventStore(journal, witness)
        val service = TaskService(
            tasks = tasks,
            sessions = sessions,
            projectFs = UnusedProjectFs,
            projectFiles = UnusedProjectFileWriter,
            now = { clock },
        )

        fun seedTask(ref: TaskRef, state: TaskState = TaskState.todo, position: Double = 1.0) {
            tasks.entries[ref] = BacklogEntry(
                ref = ref,
                project = alpha,
                position = position,
                state = state,
                blocked = false,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                rev = tasks.bumpRev(),
            )
        }

        fun seedSession(id: SessionId, createdAt: Long) {
            sessions.rows[id] = SessionMeta(
                id = id,
                name = id.value,
                agent = "claude",
                cwd = "/tmp/repo",
                tmuxSession = "kt-${id.value}",
                state = SessionState.running,
                stateSource = EventSource.system,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        }

        fun linkOf(id: SessionId): TaskRef? = sessions.rows.getValue(id).taskRef

        fun stateOf(ref: TaskRef): TaskState? = tasks.entries[ref]?.state

        fun feed(ref: TaskRef): List<Pair<ActivityKind, String>> =
            tasks.activity.filter { it.ref == ref }.map { it.kind to it.author }
    }


    @Test
    fun twoSessionsLinkTheSameTaskAndBothHoldIt() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedSession(s1, createdAt = 1_000L)
            f.seedSession(s2, createdAt = 2_000L)

            f.service.link(s1, t1)
            f.service.link(s2, t1)

            assertEquals(
                listOf(s1, s2),
                f.sessions.sessionsHoldingTask(t1).map { it.id },
                "linking is many-sessions-to-one-task: the second link must not displace the first",
            )
            assertEquals(t1, f.linkOf(s1))
            assertEquals(t1, f.linkOf(s2))
            assertEquals(TaskState.in_progress, f.stateOf(t1))
            assertEquals(
                listOf(ActivityKind.linked to s1.value, ActivityKind.linked to s2.value),
                f.feed(t1),
                "each link is attributed to the session that made it",
            )
        }
    }

    @Test
    fun aLinkToATaskAlreadyInProgressSucceedsAndLeavesItsStateAlone() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1, state = TaskState.in_progress)
            f.seedSession(s1, createdAt = 1_000L)
            val revBefore = f.tasks.entries.getValue(t1).rev

            f.service.link(s1, t1)

            assertEquals(TaskState.in_progress, f.stateOf(t1))
            assertEquals(revBefore, f.tasks.entries.getValue(t1).rev, "a zero-row advance must write nothing")
            assertEquals(t1, f.linkOf(s1), "the link is made regardless")
            assertEquals(
                listOf("tasks.startIfTodo(local:1)", "sessions.setTaskRef(s-one -> local:1)", "tasks.appendActivity(local:1, linked)"),
                f.journal,
                "two independent sequential writes, then the feed row",
            )
        }
    }

    @Test
    fun pointingASessionAtAnotherTaskOverwritesTheLinkAndLeavesTheOldTaskAlone() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedTask(t2, position = 2.0)
            f.seedSession(s1, createdAt = 1_000L)

            f.service.link(s1, t1)
            f.service.link(s1, t2)

            assertEquals(t2, f.linkOf(s1))
            assertTrue(f.sessions.sessionsHoldingTask(t1).isEmpty(), "the old link is gone")
            assertEquals(TaskState.in_progress, f.stateOf(t1), "the abandoned task keeps its state")
            assertEquals(listOf(ActivityKind.linked to s1.value), f.feed(t1))
        }
    }


    @Test
    fun linkNextUnderContentionHandsTwoSessionsTwoDifferentTasks() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1, position = 1.0)
            f.seedTask(t2, position = 2.0)
            f.seedSession(s1, createdAt = 1_000L)
            f.seedSession(s2, createdAt = 2_000L)

            val arrived = Channel<Unit>(Channel.UNLIMITED)
            val release = CompletableDeferred<Unit>()
            f.tasks.afterNextCandidate = { call ->
                if (call < 2) {
                    arrived.send(Unit)
                    release.await()
                }
            }

            var first: BacklogEntry? = null
            var second: BacklogEntry? = null
            val a = launch { first = f.service.linkNext(s1, alpha) }
            val b = launch { second = f.service.linkNext(s2, alpha) }
            arrived.receive()
            arrived.receive()
            release.complete(Unit)
            a.join()
            b.join()

            assertEquals(t1, first?.ref, "the winner of the conditional advance keeps the shared candidate")
            assertEquals(t2, second?.ref, "the loser re-queries and takes the next one")
            assertEquals(t1, f.linkOf(s1))
            assertEquals(t2, f.linkOf(s2))
            assertEquals(TaskState.in_progress, f.stateOf(t1))
            assertEquals(TaskState.in_progress, f.stateOf(t2))
            assertEquals(
                TaskState.in_progress,
                first?.state,
                "the returned entry is re-read after the advance, not the pre-write candidate",
            )
            assertEquals(3, f.tasks.nextCandidateCalls, "two contended queries plus the loser's retry")
        }
    }

    @Test
    fun linkNextOnAnEmptyBacklogReportsNothingEligibleAndWritesNothing() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedSession(s1, createdAt = 1_000L)

            assertNull(f.service.linkNext(s1, alpha))

            assertNull(f.linkOf(s1))
            assertEquals(listOf("tasks.nextCandidate($alpha)"), f.journal, "one query, no writes")
        }
    }

    @Test
    fun linkNextWithEveryTaskAlreadyStartedReportsNothingEligible() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1, state = TaskState.in_progress)
            f.seedTask(t2, state = TaskState.done, position = 2.0)
            f.seedSession(s1, createdAt = 1_000L)

            assertNull(f.service.linkNext(s1, alpha))
            assertNull(f.linkOf(s1))
        }
    }


    @Test
    fun unlinkDropsOneSessionsLinkAndLeavesTheTasksStateAlone() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedSession(s1, createdAt = 1_000L)
            f.seedSession(s2, createdAt = 2_000L)
            f.service.link(s1, t1)
            f.service.link(s2, t1)

            assertTrue(f.service.unlink(s1), "a clear that landed reports that it did")

            assertNull(f.linkOf(s1))
            assertEquals(t1, f.linkOf(s2), "the other holder is untouched")
            assertEquals(TaskState.in_progress, f.stateOf(t1), "detaching is not finishing")
            assertEquals(
                listOf(ActivityKind.linked to s1.value, ActivityKind.linked to s2.value, ActivityKind.unlinked to s1.value),
                f.feed(t1),
            )
        }
    }

    @Test
    fun aReleaseThatRacedANewerClaimLeavesTheNewerLinkAlone() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedTask(t2, position = 2.0)
            f.seedSession(s1, createdAt = 1_000L)
            f.service.link(s1, t1)
            f.journal.clear()

            f.sessions.afterGetSession = {
                f.sessions.afterGetSession = null
                f.service.link(s1, t2)
            }

            assertFalse(f.service.unlink(s1), "a release that wrote nothing says so — the route answers 409 on it")

            assertEquals(t2, f.linkOf(s1), "the newer link survives a release keyed by the older ref")
            assertEquals(TaskState.in_progress, f.stateOf(t2), "…and its task keeps its worker")
            assertEquals(
                listOf(ActivityKind.linked to s1.value),
                f.feed(t1),
                "no `unlinked` row is written for a release that wrote nothing",
            )
            assertEquals(
                listOf("sessions.getSession(s-one)"),
                f.journal.filter { it.startsWith("sessions.getSession") },
                "the ref it acted on came from that one read",
            )
            assertTrue(
                f.journal.contains("sessions.clearTaskRefIf(s-one, local:1)"),
                "and the clear really was attempted, keyed by the ref that read answered: ${f.journal}",
            )
        }
    }

    @Test
    fun unlinkOfASessionHoldingNoTaskWritesNothing() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedSession(s1, createdAt = 1_000L)

            assertFalse(f.service.unlink(s1), "a session holding nothing cleared nothing")

            assertEquals(listOf("sessions.getSession(s-one)"), f.journal)
            assertTrue(f.tasks.activity.isEmpty())
        }
    }


    @Test
    fun transitionToDoneUnlinksEveryHolder() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedSession(s1, createdAt = 1_000L)
            f.seedSession(s2, createdAt = 2_000L)
            f.service.link(s1, t1)
            f.service.link(s2, t1)

            val moved = f.service.transition(t1, TaskState.done, author = TaskService.BOARD_AUTHOR)

            assertEquals(TaskState.done, moved?.state)
            assertTrue(f.sessions.sessionsHoldingTask(t1).isEmpty(), "no holder is left pointing at a closed task")
            assertNull(f.linkOf(s1))
            assertNull(f.linkOf(s2))
            assertFalse(f.sessions.archived.contains(s1), "closing a task never archives a session")
            assertEquals(
                listOf(
                    ActivityKind.transition to TaskService.BOARD_AUTHOR,
                    ActivityKind.unlinked to s1.value,
                    ActivityKind.unlinked to s2.value,
                ),
                f.feed(t1).drop(2),
                "the transition commits first, then one release per holder",
            )
        }
    }

    @Test
    fun closingATaskCannotEraseAHoldersNewerLinkToADifferentTask() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedTask(t2, position = 2.0)
            f.seedSession(s1, createdAt = 1_000L)
            f.seedSession(s2, createdAt = 2_000L)
            f.service.link(s1, t1)
            f.service.link(s2, t1)

            f.sessions.afterSessionsHoldingTask = {
                f.sessions.afterSessionsHoldingTask = null
                f.service.link(s2, t2)
            }

            f.service.transition(t1, TaskState.done, author = TaskService.BOARD_AUTHOR)

            assertNull(f.linkOf(s1), "the holder that stayed put is released")
            assertEquals(t2, f.linkOf(s2), "the one that moved on keeps its newer link")
            assertEquals(TaskState.in_progress, f.stateOf(t2), "…and its task keeps its worker")
            assertEquals(
                listOf(ActivityKind.transition to TaskService.BOARD_AUTHOR, ActivityKind.unlinked to s1.value),
                f.feed(t1).drop(2),
                "the feed records one release, for the one holder actually released",
            )
        }
    }

    @Test
    fun deleteCannotEraseAHoldersNewerLinkToADifferentTask() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedTask(t2, position = 2.0)
            f.seedSession(s1, createdAt = 1_000L)
            f.service.link(s1, t1)

            f.sessions.afterSessionsHoldingTask = {
                f.sessions.afterSessionsHoldingTask = null
                f.service.link(s1, t2)
            }

            assertTrue(f.service.delete(t1), "the task is still deleted")

            assertEquals(t2, f.linkOf(s1), "but the link the delete never read is left alone")
            assertNull(f.stateOf(t1), "…and the task really is gone")
        }
    }

    @Test
    fun transitionToReviewKeepsEveryLink() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedSession(s1, createdAt = 1_000L)
            f.service.link(s1, t1)

            val moved = f.service.transition(t1, TaskState.review, author = s1.value, message = "please look")

            assertEquals(TaskState.review, moved?.state)
            assertEquals(t1, f.linkOf(s1), "one session, one task, end to end — through review")
        }
    }

    @Test
    fun transitionOfAnUnknownRefIsNullAndUnlinksNobody() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedSession(s1, createdAt = 1_000L)

            assertNull(f.service.transition(absent, TaskState.done, author = TaskService.BOARD_AUTHOR))

            assertEquals(listOf("tasks.transition(local:404 -> done)"), f.journal)
        }
    }


    @Test
    fun deleteUnlinksEveryHolderBeforeRemovingTheTask() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedSession(s1, createdAt = 1_000L)
            f.seedSession(s2, createdAt = 2_000L)
            f.service.link(s1, t1)
            f.service.link(s2, t1)
            f.journal.clear()

            assertTrue(f.service.delete(t1))

            assertNull(f.linkOf(s1))
            assertNull(f.linkOf(s2))
            assertEquals(
                listOf(
                    "sessions.sessionsHoldingTask(local:1)",
                    "sessions.clearTaskRefIf(s-one, local:1)",
                    "sessions.clearTaskRefIf(s-two, local:1)",
                    "tasks.delete(local:1)",
                ),
                f.journal,
                "every holder is released first, and no feed row is written into a feed being deleted",
            )
        }
    }

    @Test
    fun deleteClearsADanglingHolderEvenWhenTheTaskIsAlreadyGone() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedSession(s1, createdAt = 1_000L)
            f.sessions.rows[s1] = f.sessions.rows.getValue(s1).copy(taskRef = absent)

            assertFalse(f.service.delete(absent))
            assertNull(f.linkOf(s1))
        }
    }

    // Detects accidental nesting of the two production store critical sections without real mutexes.
    private class LockWitness {
        private var taskStoreHeld = false
        private var eventStoreHeld = false

        suspend fun <T> inTaskStore(what: String, body: suspend () -> T): T {
            check(!eventStoreHeld) { "$what took the task store's lock while the event store's was held" }
            check(!taskStoreHeld) { "$what re-entered the task store" }
            taskStoreHeld = true
            try {
                return body()
            } finally {
                taskStoreHeld = false
            }
        }

        suspend fun <T> inEventStore(what: String, body: suspend () -> T): T {
            check(!taskStoreHeld) { "$what took the event store's lock while the task store's was held" }
            check(!eventStoreHeld) { "$what re-entered the event store" }
            eventStoreHeld = true
            try {
                return body()
            } finally {
                eventStoreHeld = false
            }
        }
    }

    private class FakeTaskStore(
        private val journal: MutableList<String>,
        private val witness: LockWitness,
    ) : TaskStore {
        val entries: MutableMap<TaskRef, BacklogEntry> = mutableMapOf()
        val activity: MutableList<TaskActivityEntry> = mutableListOf()

        var nextCandidateCalls: Int = 0

        // Runs after the read and outside the witness so two callers can hold the same candidate.
        var afterNextCandidate: (suspend (Int) -> Unit)? = null

        private var rev = 0L
        private var activityId = 0L

        fun bumpRev(): Long = ++rev

        override val id: String = TaskRef.LOCAL_TRACKER
        override val taskUpdates: SharedFlow<TaskUpdate> = MutableSharedFlow()

        override suspend fun entry(ref: TaskRef): BacklogEntry? = witness.inTaskStore("entry") {
            journal += "tasks.entry(${ref.value})"
            entries[ref]
        }

        override suspend fun nextCandidate(project: ProjectId): BacklogEntry? {
            val call = nextCandidateCalls++
            val candidate = witness.inTaskStore("nextCandidate") {
                journal += "tasks.nextCandidate($project)"
                entries.values
                    .filter { it.project == project && it.state == TaskState.todo && !it.blocked }
                    .minByOrNull { it.position }
            }
            afterNextCandidate?.invoke(call)
            return candidate
        }

        override suspend fun startIfTodo(ref: TaskRef): Boolean = witness.inTaskStore("startIfTodo") {
            journal += "tasks.startIfTodo(${ref.value})"
            val existing = entries[ref]
            if (existing == null || existing.state != TaskState.todo) {
                false
            } else {
                entries[ref] = existing.copy(state = TaskState.in_progress, rev = ++rev)
                true
            }
        }

        override suspend fun transition(
            ref: TaskRef,
            to: TaskState,
            author: String,
            message: String?,
        ): BacklogEntry? = witness.inTaskStore("transition") {
            journal += "tasks.transition(${ref.value} -> $to)"
            val existing = entries[ref]
            if (existing == null) {
                null
            } else {
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
                moved
            }
        }

        override suspend fun appendActivity(
            ref: TaskRef,
            kind: ActivityKind,
            author: String,
            text: String?,
            fromState: TaskState?,
            toState: TaskState?,
        ): TaskActivityEntry? = witness.inTaskStore("appendActivity") {
            journal += "tasks.appendActivity(${ref.value}, $kind)"
            if (ref !in entries) {
                null
            } else {
                val row = TaskActivityEntry(++activityId, ref, 0L, kind, author, text, fromState, toState)
                activity += row
                row
            }
        }

        override suspend fun delete(ref: TaskRef): Boolean = witness.inTaskStore("delete") {
            journal += "tasks.delete(${ref.value})"
            activity.removeAll { it.ref == ref }
            entries.remove(ref) != null
        }

        override suspend fun list(project: ProjectId): List<Task> = unused("list")
        override suspend fun get(ref: TaskRef): Task? = unused("get")
        override suspend fun create(project: ProjectId, title: String, body: String, author: String): Task =
            unused("create")
        override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? = unused("update")
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

        private fun unused(name: String): Nothing =
            error("TaskService is not expected to call TaskStore.$name")
    }

    private class FakeEventStore(
        private val journal: MutableList<String>,
        private val witness: LockWitness,
    ) : EventStore {
        val rows: MutableMap<SessionId, SessionMeta> = mutableMapOf()

        val archived: MutableSet<SessionId> = mutableSetOf()

        override suspend fun getSession(sessionId: SessionId): SessionMeta? {
            val row = witness.inEventStore("getSession") {
                journal += "sessions.getSession(${sessionId.value})"
                rows[sessionId]
            }
            afterGetSession?.invoke()
            return row
        }

        // Post-read, outside-witness gates model a newer link appearing before a conditional clear.
        var afterGetSession: (suspend () -> Unit)? = null

        var afterSessionsHoldingTask: (suspend () -> Unit)? = null

        override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?, updatedAt: Long) =
            witness.inEventStore("setTaskRef") {
                journal += "sessions.setTaskRef(${sessionId.value} -> ${taskRef?.value})"
                val row = rows[sessionId]
                if (row != null) rows[sessionId] = row.copy(taskRef = taskRef, updatedAt = updatedAt)
            }

        override suspend fun clearTaskRefIf(
            sessionId: SessionId,
            expectedRef: TaskRef,
            updatedAt: Long,
        ): Boolean = witness.inEventStore("clearTaskRefIf") {
            journal += "sessions.clearTaskRefIf(${sessionId.value}, ${expectedRef.value})"
            val row = rows[sessionId]
            if (row == null || row.taskRef != expectedRef) {
                false
            } else {
                rows[sessionId] = row.copy(taskRef = null, updatedAt = updatedAt)
                true
            }
        }

        override suspend fun sessionsHoldingTask(taskRef: TaskRef): List<SessionMeta> {
            val holders = witness.inEventStore("sessionsHoldingTask") {
                journal += "sessions.sessionsHoldingTask(${taskRef.value})"
                rows.values.filter { it.taskRef == taskRef }.sortedBy { it.createdAt }
            }
            afterSessionsHoldingTask?.invoke()
            return holders
        }

        override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long) {
            if (archived) this.archived += sessionId else this.archived -= sessionId
        }

        override suspend fun upsertSession(meta: SessionMeta) = unused("upsertSession")
        override suspend fun updateSessionState(
            sessionId: SessionId,
            state: SessionState,
            stateSource: EventSource,
            paneId: io.kotgent.core.PaneId?,
            updatedAt: Long,
        ) = unused("updateSessionState")
        override suspend fun setModel(sessionId: SessionId, model: String?, updatedAt: Long) = unused("setModel")
        override suspend fun setModelForProvider(
            sessionId: SessionId,
            providerSessionId: io.kotgent.core.ProviderSessionId,
            model: String,
            updatedAt: Long,
        ): Boolean = unused("setModelForProvider")
        override suspend fun markRead(sessionId: SessionId, seq: Seq) = unused("markRead")
        override suspend fun setProjectId(sessionId: SessionId, projectId: ProjectId?, updatedAt: Long) =
            unused("setProjectId")
        override suspend fun listSessions(): List<SessionMeta> = unused("listSessions")
        override suspend fun append(
            sessionId: SessionId,
            event: io.kotgent.core.AgentEvent,
            source: EventSource,
        ): Seq = unused("append")
        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<io.kotgent.store.StoredEvent> =
            unused("read")
        override suspend fun projectionOf(sessionId: SessionId): io.kotgent.core.Projection =
            unused("projectionOf")
        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<io.kotgent.store.StoredEvent> =
            unused("subscribe")
        override val sessionUpdates: SharedFlow<SessionUpdate> = MutableSharedFlow()

        private fun unused(name: String): Nothing =
            error("TaskService is not expected to call EventStore.$name")
    }

    private object UnusedProjectFs : ProjectFs {
        override fun isDirectory(path: String): Boolean = error("TaskService must not touch the filesystem")
        override fun readFile(path: String, maxBytes: Int): String? =
            error("TaskService must not touch the filesystem")
        override fun canonicalize(path: String): String? = error("TaskService must not touch the filesystem")
    }

    private object UnusedProjectFileWriter : ProjectFileWriter {
        override suspend fun ensureProjectFile(dir: String, name: String) =
            error("TaskService must not write a project file")
    }
}
