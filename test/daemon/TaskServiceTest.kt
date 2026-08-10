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

/**
 * Tests for [TaskService] — the one place the task store and the event store meet.
 *
 * ## What they are built around
 * Three properties, none of which is visible from either store alone:
 *
 *  1. **Linking is two independent writes and there is no exclusivity.** Several sessions may hold one
 *     task, a link to a task that is already `in_progress` succeeds and leaves the state alone, and the
 *     conditional `todo → in_progress` answering `false` is normal rather than a failure. A test that
 *     merely checked "the link was made" would pass against an implementation that also refused the
 *     second session, so [twoSessionsLinkTheSameTaskAndBothHoldIt] asserts the second link *and* that the
 *     first is still there.
 *  2. **The two stores' locks are never nested.** [LockWitness] is a tripwire for that, and it is
 *     honest about being one: today's implementation calls both stores from the top level, so nothing
 *     here can make it fire — it is armed against the shape the bug would actually take, a suspend
 *     lambda handed to one store that reaches the other inside it. The contention gate deliberately
 *     runs OUTSIDE the witness for the same reason: it models a caller suspended BETWEEN store calls,
 *     which the witness must not read as a nested lock.
 *  3. **The order of the calls is itself the contract**, so both fakes append to one shared [journal]:
 *     `delete` unlinking every holder BEFORE removing the task is the difference between "no dangling
 *     badge" and "a badge pointing at nothing", and no per-store assertion can see it.
 *  4. **Linking is unconditional; CLEARING is not.** Every clear here reads the ref — from the row, or
 *     from the holder list — and writes second, so a link made inside that window is newer than
 *     everything the operation read and must survive it. The three tests that pin it
 *     ([aReleaseThatRacedANewerClaimLeavesTheNewerLinkAlone],
 *     [closingATaskCannotEraseAHoldersNewerLinkToADifferentTask],
 *     [deleteCannotEraseAHoldersNewerLinkToADifferentTask]) each drive that interleaving through a
 *     deterministic gate in [FakeEventStore] rather than a timed race, and each also asserts the FEED,
 *     because a suppressed write whose `unlinked` row was still appended is the same bug wearing the
 *     other half of the costume. None of this is exclusivity: no link is ever refused, and
 *     [twoSessionsLinkTheSameTaskAndBothHoldIt] still holds.
 *
 * Every body is bounded by [withTimeout] as an anti-hang tripwire; [linkNextUnderContentionHandsTwoSessionsTwoDifferentTasks]
 * needs it most, because a lost race there is a hang, not a wrong answer.
 */
class TaskServiceTest {

    // --- the vocabulary -------------------------------------------------------------------------

    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")

    private val t1 = TaskRef("local:1")
    private val t2 = TaskRef("local:2")
    private val absent = TaskRef("local:404")

    private val s1 = SessionId("s-one")
    private val s2 = SessionId("s-two")

    private val clock = 7_000L

    /** The three collaborators plus the service over them, sharing one journal and one lock witness. */
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

    // --- no exclusivity: several sessions, one task ------------------------------------------------

    /**
     * The headline property of the whole design: a task may be linked from ANY number of sessions.
     * kotgent cannot enforce one worker per task — the operator opens a second terminal in the same
     * repository and the daemon never hears about it — so the second link is not an error, does not
     * displace the first, and puts both sessions on the card.
     */
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

    /**
     * The conditional `todo → in_progress` advance answering `false` is NORMAL, not a refusal: an
     * explicit `task claim <ref>` on a task somebody is already working on simply adds a link. The rev
     * assertion is what proves the store was not written — a state check alone would also pass if the
     * service had re-written `in_progress` over `in_progress`.
     */
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

    /**
     * A session works ONE task at a time, so pointing it at another overwrites the link with no error —
     * and, deliberately, without touching the task it was on. Recorded rather than "fixed": the previous
     * task keeps its state (nobody can infer from a re-point that the work is finished) and gets no
     * `unlinked` feed row, because `link` reads no session row at all and adding that read to the hot
     * path buys one feed line.
     */
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

    // --- selection ---------------------------------------------------------------------------------

    /**
     * The contended `task next`, which is the ONLY reason the conditional advance exists. Both sessions
     * are held just after [FakeTaskStore.nextCandidate] has answered, so both really are holding the
     * SAME candidate before either advances it — the race is real rather than incidental: one wins
     * `startIfTodo`, the loser sees zero rows, re-queries —
     * the row is no longer `todo`, so it is naturally excluded, which is why no `skip` set is needed —
     * and takes the next task.
     */
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

    /**
     * "Nothing eligible" is a `null` return and nothing else — the single signal the CLI maps to exit
     * `3`. Nothing may be written on the way there, or a `task next` in an empty project would stamp an
     * `updated_at` on a session for no reason.
     */
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

    /** Every candidate already taken is the same answer as an empty project: the query runs dry. */
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

    // --- release -----------------------------------------------------------------------------------

    /**
     * `release` says nothing about whether the work is finished — kotgent cannot infer that from a
     * session detaching, and other sessions may still be linked — so the task's state is untouched and
     * the other holder keeps its link.
     */
    @Test
    fun unlinkDropsOneSessionsLinkAndLeavesTheTasksStateAlone() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedSession(s1, createdAt = 1_000L)
            f.seedSession(s2, createdAt = 2_000L)
            f.service.link(s1, t1)
            f.service.link(s2, t1)

            f.service.unlink(s1)

            assertNull(f.linkOf(s1))
            assertEquals(t1, f.linkOf(s2), "the other holder is untouched")
            assertEquals(TaskState.in_progress, f.stateOf(t1), "detaching is not finishing")
            assertEquals(
                listOf(ActivityKind.linked to s1.value, ActivityKind.linked to s2.value, ActivityKind.unlinked to s1.value),
                f.feed(t1),
            )
        }
    }

    /**
     * The lost-update rule on the single-session path.
     *
     * `unlink` reads the row to learn WHICH ref to attribute its `unlinked` row to, and writes second. A
     * `claim` or a `task next` landing in that window has pointed this session at a NEWER task, and the
     * older release must not erase it: doing so would leave `local:2` `in_progress` with no terminal
     * behind it while the feed claimed a release of `local:1` for a write that destroyed the other link.
     *
     * The interleaving is deterministic, not timed: [FakeEventStore.afterGetSession] runs once the read
     * has answered and the store is released, which is exactly the window the two-call shape opens.
     * Falsifiable — with the unconditional `setTaskRef(s1, null, …)` the link is gone and `local:2` is
     * orphaned, which is the defect this test was written for.
     */
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
                f.sessions.afterGetSession = null // the interleaving happens once, not on every read
                f.service.link(s1, t2)
            }

            f.service.unlink(s1)

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

    /** A second `release` writes nothing at all — no `updated_at` bump and no duplicate feed row. */
    @Test
    fun unlinkOfASessionHoldingNoTaskWritesNothing() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedTask(t1)
            f.seedSession(s1, createdAt = 1_000L)

            f.service.unlink(s1)

            assertEquals(listOf("sessions.getSession(s-one)"), f.journal)
            assertTrue(f.tasks.activity.isEmpty())
        }
    }

    // --- transition --------------------------------------------------------------------------------

    /**
     * Closing a task from the board hands every worker session back to `task next`: the links go, the
     * sessions stay ALIVE (archiving one is the session's own "Done", not this), and the feed records
     * both the transition and who was released.
     */
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

    /**
     * The same lost-update rule on the BULK path, where the stale thing is the holder LIST.
     *
     * `sessionsHoldingTask` is a snapshot and the loop clears one holder at a time, so a session
     * re-pointed anywhere inside that walk is newer than everything the close read. `s-two` takes
     * `local:2` after the list is taken; closing `local:1` must release `s-one` and leave `s-two` alone,
     * because the write it would make is not the write it decided to make.
     *
     * Two assertions carry it and both are needed: the surviving link (against the unconditional clear)
     * and the feed, which must record exactly ONE release — an `unlinked` row for `s-two` would be the
     * feed describing a write that did not happen. Falsifiable: with `setTaskRef(holder.id, null, …)`
     * both fail, `s-two` is orphaned and `local:2` is left `in_progress` with no terminal.
     */
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

    /**
     * `delete`'s half of the same rule. It writes no feed rows at all (the next statement deletes the
     * feed), so the surviving link is the whole observable — and the deletion still goes through: a
     * holder that slipped away is not a reason to keep the task.
     */
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

    /** Only `done` releases the workers: a move to `review` keeps the session that is being reviewed. */
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

    /** An unknown ref is the store's `null`, and nothing downstream of it runs. */
    @Test
    fun transitionOfAnUnknownRefIsNullAndUnlinksNobody() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            f.seedSession(s1, createdAt = 1_000L)

            assertNull(f.service.transition(absent, TaskState.done, author = TaskService.BOARD_AUTHOR))

            assertEquals(listOf("tasks.transition(local:404 -> done)"), f.journal)
        }
    }

    // --- delete ------------------------------------------------------------------------------------

    /**
     * Delete unlinks every holder BEFORE removing the task, which is the whole point of the ordering:
     * the other way round leaves a badge pointing at nothing until reconciliation. The journal is the
     * only place that order is observable.
     */
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

    /**
     * A dangling link — the task went away while a link write was in flight — is cleared by the delete
     * that follows, even though the task itself is already gone and the answer is `false`.
     */
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

    // --- fakes -------------------------------------------------------------------------------------

    /**
     * The structural half of "never nest the two stores' locks".
     *
     * In production each store has its own `Mutex` over the same driver, so a call into one from inside
     * the other's critical section blocks a `Dispatchers.Default` thread and two callers arriving from
     * opposite directions deadlock. Here a boolean per store is enough — `runBlocking`'s event loop is
     * single-threaded, so a flag set for the duration of a fake call means exactly "that store is inside
     * a call right now".
     *
     * **What it can and cannot catch.** No test below can make it fire, and that is not a defect in the
     * test: [TaskService] calls both stores from the top level, one returning before the next is made,
     * so there is nothing to nest. It is armed against the shape a real regression would take — a store
     * method that takes a suspend lambda (`tasks.transaction { sessions.setTaskRef(…) }`) and a caller
     * that reaches the other store inside it. Nothing on either interface offers that today; the day one
     * does, this fails here instead of deadlocking a daemon under load.
     */
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

    /**
     * An in-memory [TaskStore] covering exactly the six methods [TaskService] calls; every other member
     * throws, so a service that grew a call this test never modelled fails loudly rather than reading an
     * empty list as an answer — the same reasoning as [EventStore]'s three throwing defaults.
     */
    private class FakeTaskStore(
        private val journal: MutableList<String>,
        private val witness: LockWitness,
    ) : TaskStore {
        val entries: MutableMap<TaskRef, BacklogEntry> = mutableMapOf()
        val activity: MutableList<TaskActivityEntry> = mutableListOf()

        /** How many times [nextCandidate] was asked — the contention test's proof that the loser re-queried. */
        var nextCandidateCalls: Int = 0

        /**
         * Run after [nextCandidate] has READ its answer and released the store, receiving the zero-based
         * call index.
         *
         * Both halves of that placement are load-bearing. **After the read**, because the race the
         * design handles is two callers holding the SAME candidate — a gate before the read just
         * serializes them, and the second one sees a candidate the first has already advanced (which is
         * how the first draft of this test passed with two queries instead of three). **Outside**
         * [LockWitness.inTaskStore], because it models a caller suspended BETWEEN store calls, which is
         * the one thing the witness must not read as a nested lock.
         */
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
            // The index is taken outside the safe call below: `hook?.invoke(i++)` would not evaluate its
            // argument at all when the hook is null, so the counter would stop counting.
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
        override suspend fun listProjects(): List<ProjectRecord> = unused("listProjects")
        override suspend fun project(id: ProjectId): ProjectRecord? = unused("project")

        private fun unused(name: String): Nothing =
            error("TaskService is not expected to call TaskStore.$name")
    }

    /**
     * An in-memory [EventStore] modelling the session link only. It overrides all three task-link
     * members — the interface's defaults throw precisely so a fake that forgot one cannot make a green
     * test out of a link that persisted nothing.
     */
    private class FakeEventStore(
        private val journal: MutableList<String>,
        private val witness: LockWitness,
    ) : EventStore {
        val rows: MutableMap<SessionId, SessionMeta> = mutableMapOf()

        /** Nothing here ever archives a session; the set exists so a test can assert that. */
        val archived: MutableSet<SessionId> = mutableSetOf()

        override suspend fun getSession(sessionId: SessionId): SessionMeta? {
            val row = witness.inEventStore("getSession") {
                journal += "sessions.getSession(${sessionId.value})"
                rows[sessionId]
            }
            afterGetSession?.invoke()
            return row
        }

        /**
         * Run after [getSession] has READ its answer and released the store — the deterministic stand-in
         * for "somebody re-pointed this session between the read and the clear".
         *
         * Placed exactly like [FakeTaskStore.afterNextCandidate], and for the same two reasons. **After
         * the read**, because the window this models opens once the caller is holding a ref it will act
         * on; a gate before the read would just serialize the two and there would be nothing stale. And
         * **outside** [LockWitness.inEventStore], because it models a caller suspended BETWEEN store
         * calls, which the witness must not read as a nested lock.
         */
        var afterGetSession: (suspend () -> Unit)? = null

        /** [afterGetSession]'s counterpart for the bulk paths, whose snapshot is the list, not one row. */
        var afterSessionsHoldingTask: (suspend () -> Unit)? = null

        override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?, updatedAt: Long) =
            witness.inEventStore("setTaskRef") {
                journal += "sessions.setTaskRef(${sessionId.value} -> ${taskRef?.value})"
                val row = rows[sessionId]
                if (row != null) rows[sessionId] = row.copy(taskRef = taskRef, updatedAt = updatedAt)
            }

        /**
         * The conditional clear, modelled as the ONE atomic step it is in SQL: the comparison and the
         * write happen together inside the witness, so no gate can be placed between them here either.
         */
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

    /**
     * [TaskService] carries the project filesystem and the project-file writer for the WRITE ROUTES and
     * never calls either itself — so the fakes refuse to answer, which is what pins that.
     */
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
