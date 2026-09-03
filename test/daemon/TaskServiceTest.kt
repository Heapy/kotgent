package io.kotgent.daemon

import io.kotgent.core.EventSource
import io.kotgent.core.ProjectId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.store.FakeEventStore
import io.kotgent.store.FakeStoreCall
import io.kotgent.store.FakeStoreInterceptor
import io.kotgent.store.FakeTaskStore
import io.kotgent.store.ForbiddingInterceptor
import io.kotgent.store.RecordingInterceptor
import io.kotgent.task.ActivityKind
import io.kotgent.task.BacklogEntry
import io.kotgent.task.ProjectFileWriter
import io.kotgent.task.ProjectFs
import io.kotgent.task.TaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TaskServiceTest {

    private companion object {
        // The whole surface TaskService has no business reaching; `subscribe` is not suspend and so
        // cannot be guarded, which is why the store list below stops short of it.
        val UNREACHED = setOf(
            "TaskStore.list", "TaskStore.get", "TaskStore.create", "TaskStore.update",
            "TaskStore.listBacklog", "TaskStore.move", "TaskStore.dependenciesOf",
            "TaskStore.dependentsOf", "TaskStore.dependencyEdges", "TaskStore.addDependency",
            "TaskStore.removeDependency", "TaskStore.comment", "TaskStore.activity",
            "TaskStore.upsertProject", "TaskStore.setProjectArchived", "TaskStore.listProjects",
            "TaskStore.listAllProjects", "TaskStore.project",
            "EventStore.upsertSession", "EventStore.updateSessionState", "EventStore.setModel",
            "EventStore.setModelForProvider", "EventStore.markRead", "EventStore.setProjectId",
            "EventStore.listSessions", "EventStore.append", "EventStore.read", "EventStore.projectionOf",
        )

        fun refusal(store: String, method: String): String =
            "TaskService is not expected to call $store.$method"

        fun render(call: FakeStoreCall): String {
            val subject = if (call.store == "TaskStore") "tasks" else "sessions"
            return "$subject.${call.method}(${call.args.joinToString(", ")})"
        }
    }


    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")

    private val t1 = TaskRef("local:1")
    private val t2 = TaskRef("local:2")
    private val absent = TaskRef("local:404")

    private val s1 = SessionId("s-one")
    private val s2 = SessionId("s-two")

    private inner class Fixture {
        val recorder = RecordingInterceptor(describe = ::render)
        private val chain = LockWitness(ForbiddingInterceptor(UNREACHED, recorder, ::refusal))
        val tasks = FakeTaskStore(now = { 1_000L }).also { it.interceptor = chain }
        val sessions = FakeEventStore().also { it.interceptor = chain }
        val service = TaskService(
            tasks = tasks,
            sessions = sessions,
            projectFs = UnusedProjectFs,
            projectFiles = UnusedProjectFileWriter,
        )

        suspend fun journal(): List<String> = recorder.journal()

        fun seedTask(ref: TaskRef, state: TaskState = TaskState.todo, position: Double = 1.0) {
            tasks.seedTask(ref, alpha, "title of ${ref.value}", state = state, position = position)
        }

        fun seedSession(id: SessionId, createdAt: Long, taskRef: TaskRef? = null) {
            sessions.seedSession(
                SessionMeta(
                    id = id,
                    name = id.value,
                    agent = "claude",
                    cwd = "/tmp/repo",
                    tmuxSession = "kt-${id.value}",
                    state = SessionState.running,
                    stateSource = EventSource.system,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                    taskRef = taskRef,
                ),
            )
        }

        suspend fun linkOf(id: SessionId): TaskRef? = sessions.snapshotSessions().getValue(id).taskRef

        suspend fun stateOf(ref: TaskRef): TaskState? = tasks.snapshotEntries()[ref]?.state

        suspend fun revOf(ref: TaskRef): Long = tasks.snapshotEntries().getValue(ref).rev

        suspend fun feed(ref: TaskRef): List<Pair<ActivityKind, String>> =
            tasks.snapshotActivity().filter { it.ref == ref }.map { it.kind to it.author }
    }


    @Test
    fun twoSessionsLinkTheSameTaskAndBothHoldIt() = runBlocking {
        withTimeout(5.seconds) {
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
        withTimeout(5.seconds) {
            val f = Fixture()
            f.seedTask(t1, state = TaskState.in_progress)
            f.seedSession(s1, createdAt = 1_000L)
            val revBefore = f.revOf(t1)

            f.service.link(s1, t1)

            assertEquals(TaskState.in_progress, f.stateOf(t1))
            assertEquals(revBefore, f.revOf(t1), "a zero-row advance must write nothing")
            assertEquals(t1, f.linkOf(s1), "the link is made regardless")
            assertEquals(
                listOf(
                    "tasks.startIfTodo(local:1)",
                    "sessions.setTaskRef(s-one, local:1)",
                    "tasks.appendActivity(local:1, linked)",
                ),
                f.journal(),
                "two independent sequential writes, then the feed row",
            )
        }
    }

    @Test
    fun pointingASessionAtAnotherTaskOverwritesTheLinkAndLeavesTheOldTaskAlone() = runBlocking {
        withTimeout(5.seconds) {
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
        withTimeout(5.seconds) {
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
    fun aDeleteLandingBetweenTheSelectionAndItsStartHandsOutNothingAndNeedsNoNewControlFlow() =
        runBlocking {
            withTimeout(5.seconds) {
                val f = Fixture()
                f.seedSession(s1, createdAt = 1_000L)
                f.seedTask(t1)

                // Interleave deletion after selection and before start.
                f.tasks.afterNextCandidate = { call ->
                    if (call == 0) f.tasks.seedArchived(alpha, archived = true)
                }

                assertNull(
                    f.service.linkNext(s1, alpha),
                    "the refused start sends the loop back to a candidate query that now answers null, " +
                        "so `task next` degrades to its ordinary 'nothing eligible'",
                )

                assertNull(f.linkOf(s1), "no session was linked to a deleted project's card")
                assertEquals(TaskState.todo, f.stateOf(t1), "and the card never started")
                assertEquals(
                    listOf(
                        "tasks.nextCandidate(${alpha.value})",
                        "tasks.startIfTodoInLiveProject(${t1.value})",
                        "tasks.nextCandidate(${alpha.value})",
                    ),
                    f.journal(),
                    "the existing arbitration is the whole mechanism: one refused start, one re-query, " +
                        "and no session stamp or activity row in between",
                )
            }
        }

    @Test
    fun linkNextOnAnEmptyBacklogReportsNothingEligibleAndWritesNothing() = runBlocking {
        withTimeout(5.seconds) {
            val f = Fixture()
            f.seedSession(s1, createdAt = 1_000L)

            assertNull(f.service.linkNext(s1, alpha))

            assertNull(f.linkOf(s1))
            assertEquals(listOf("tasks.nextCandidate(${alpha.value})"), f.journal(), "one query, no writes")
        }
    }

    @Test
    fun linkNextWithEveryTaskAlreadyStartedReportsNothingEligible() = runBlocking {
        withTimeout(5.seconds) {
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
        withTimeout(5.seconds) {
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
        withTimeout(5.seconds) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedTask(t2, position = 2.0)
            f.seedSession(s1, createdAt = 1_000L)
            f.service.link(s1, t1)
            f.recorder.clear()

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
                "sessions.getSession(s-one)",
                f.journal().first(),
                "the ref it acted on came from its own read, taken before the racing link ran: ${f.journal()}",
            )
            assertTrue(
                f.journal().contains("sessions.clearTaskRefIf(s-one, local:1)"),
                "and the clear really was attempted, keyed by the ref that read answered: ${f.journal()}",
            )
        }
    }

    @Test
    fun unlinkOfASessionHoldingNoTaskWritesNothing() = runBlocking {
        withTimeout(5.seconds) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedSession(s1, createdAt = 1_000L)

            assertFalse(f.service.unlink(s1), "a session holding nothing cleared nothing")

            assertEquals(listOf("sessions.getSession(s-one)"), f.journal())
            assertTrue(f.tasks.snapshotActivity().isEmpty())
        }
    }


    @Test
    fun transitionToDoneUnlinksEveryHolder() = runBlocking {
        withTimeout(5.seconds) {
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
            assertFalse(
                f.sessions.snapshotSessions().getValue(s1).archived,
                "closing a task never archives a session",
            )
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
        withTimeout(5.seconds) {
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

            val _ = f.service.transition(t1, TaskState.done, author = TaskService.BOARD_AUTHOR)

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
        withTimeout(5.seconds) {
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
        withTimeout(5.seconds) {
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
        withTimeout(5.seconds) {
            val f = Fixture()
            f.seedSession(s1, createdAt = 1_000L)

            assertNull(f.service.transition(absent, TaskState.done, author = TaskService.BOARD_AUTHOR))

            assertEquals(listOf("tasks.transition(local:404, done)"), f.journal())
        }
    }


    @Test
    fun deleteUnlinksEveryHolderBeforeRemovingTheTask() = runBlocking {
        withTimeout(5.seconds) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedSession(s1, createdAt = 1_000L)
            f.seedSession(s2, createdAt = 2_000L)
            f.service.link(s1, t1)
            f.service.link(s2, t1)
            f.recorder.clear()

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
                f.journal(),
                "every holder is released first, and no feed row is written into a feed being deleted",
            )
        }
    }

    @Test
    fun deleteClearsADanglingHolderEvenWhenTheTaskIsAlreadyGone() = runBlocking {
        withTimeout(5.seconds) {
            val f = Fixture()
            f.seedSession(s1, createdAt = 1_000L)
            f.seedSession(s1, createdAt = 1_000L, taskRef = absent)

            assertFalse(f.service.delete(absent))
            assertNull(f.linkOf(s1))
        }
    }

    // Detects accidental nesting of the two production store critical sections without real mutexes.
    private class LockWitness(
        private val delegate: FakeStoreInterceptor,
    ) : FakeStoreInterceptor {
        private var taskStoreHeld = false
        private var eventStoreHeld = false

        override suspend fun <T> around(call: FakeStoreCall, body: suspend () -> T): T {
            val what = "${call.store}.${call.method}"
            if (call.store == "TaskStore") {
                check(!eventStoreHeld) { "$what took the task store's lock while the event store's was held" }
                check(!taskStoreHeld) { "$what re-entered the task store" }
                taskStoreHeld = true
                try {
                    return delegate.around(call, body)
                } finally {
                    taskStoreHeld = false
                }
            }
            check(!taskStoreHeld) { "$what took the event store's lock while the task store's was held" }
            check(!eventStoreHeld) { "$what re-entered the event store" }
            eventStoreHeld = true
            try {
                return delegate.around(call, body)
            } finally {
                eventStoreHeld = false
            }
        }
    }

    private object UnusedProjectFs : ProjectFs {
        override fun isDirectory(path: String): Boolean = error("TaskService must not touch the filesystem")
        override fun readFile(path: String, maxBytes: Int): String =
            error("TaskService must not touch the filesystem")
        override fun canonicalize(path: String): String = error("TaskService must not touch the filesystem")
    }

    private object UnusedProjectFileWriter : ProjectFileWriter {
        override suspend fun ensureProjectFile(dir: String, name: String) =
            error("TaskService must not write a project file")
    }
}
