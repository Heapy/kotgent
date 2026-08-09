package io.kotgent.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.db.BacklogQueries
import io.kotgent.db.KotgentDatabase
import io.kotgent.task.BacklogEntry
import io.kotgent.task.DependencyRefusal
import io.kotgent.task.DependencyRefusedException
import io.kotgent.task.TaskState
import io.kotgent.task.TaskUpdate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [BacklogDependencies] — the dependency graph, the derived `blocked` read path, the candidate
 * query and the reverse-dependent re-stamp.
 *
 * ## What they are built around
 * `blocked` is not a column, so **three statements answer the same question**, and every one of them is
 * exercised here against the same seeded graph: `unfinishedDependencyCount` behind `entryLocked`, the
 * in-memory fold behind `listBacklogLocked`, and `nextCandidate`'s `NOT EXISTS` behind
 * `nextCandidateLocked`. A disagreement between them is a board card and a detail view saying different
 * things about one task, which no single-path test would notice.
 *
 * The second theme is that **an accepted edit owes an emission**. `blocked` being derived makes it stale
 * by construction: closing task A moves the blocked-ness of everything depending on A without touching
 * those rows, so the re-stamp is the only thing that gets a connected board off a wrong marker. The
 * refusal tests therefore also assert that nothing was emitted, and the no-op tests that a duplicate or
 * an absent edge emits nothing either — "no-op" has to mean the board hears nothing.
 *
 * [theStoresOwnCollaboratorEmitsOnTaskUpdates] opens a real [SqliteTaskStore] over the same driver to
 * pin the wiring that class declares (its public `dependencies`, its revision allocator, its
 * `taskUpdates`); every other test drives its own instance, because the emitter and the allocator are
 * the only way to observe what an edit stamped and published.
 */
class BacklogDependenciesTest {

    // --- the vocabulary -------------------------------------------------------------------------

    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val beta = ProjectId.of("7b1d5e90-4a2c-4c11-8e77-2d3f6a8b9c01")

    private val a = TaskRef("local:1")
    private val b = TaskRef("local:2")
    private val c = TaskRef("local:3")
    private val d = TaskRef("local:4")
    private val absent = TaskRef("local:404")

    /**
     * An in-memory database plus one observable [BacklogDependencies] over it. The revision allocator
     * and the emitter are the test's own — the only way to see what an edit stamped and published — and
     * the mutex is real, so the suspending entry points take and release it as they will in the store.
     */
    private inner class Fixture {
        val driver: SqlDriver = inMemoryDriver(KotgentDatabase.Schema)
        val queries: BacklogQueries = KotgentDatabase(driver).backlogQueries
        var revCounter: Long = 0
        val emitted: MutableList<TaskUpdate> = mutableListOf()

        /**
         * The store's staging buffer, with the test's list as its publisher. The `…Locked` members STAGE
         * rather than publish (nothing may leave a transaction that can still roll back), so a test that
         * drives one of them directly has to spend it through [publishing] — the same wrapper the store
         * puts around every locked body.
         */
        val outbox: TaskUpdateOutbox = TaskUpdateOutbox { emitted += it }

        val deps: BacklogDependencies = BacklogDependencies(
            queries = queries,
            mutex = Mutex(),
            nextRev = { ++revCounter },
            outbox = outbox,
            // Nothing this class writes is timestamped (`backlog_deps` has no timestamp column and
            // `restamp` leaves `updated_at` alone), so a clock that refuses to answer proves it.
            now = { error("BacklogDependencies must not need a clock") },
        )

        /** Run a `…Locked` mutator the way the store does: staged inside, published on the way out. */
        fun <T> publishing(block: () -> T): T = outbox.publishing(block)

        /**
         * Insert one backlog row directly. `tasks` rows are deliberately not seeded: nothing this class
         * reads joins them (only `selectBacklogWithTasks`, which is Task 7's), so a row here is exactly
         * the input the dependency layer sees.
         */
        fun seed(
            ref: TaskRef,
            project: ProjectId,
            position: Double,
            state: TaskState = TaskState.todo,
            createdAt: Long = 10L,
            updatedAt: Long = 20L,
        ) {
            queries.insertEntry(ref.value, project.value, position, state.name, createdAt, updatedAt, 0L)
        }

        /** A state change written the way Task 7's `transition` will write it, minus the activity row. */
        fun setState(ref: TaskRef, state: TaskState, updatedAt: Long = 20L) {
            queries.setState(state.name, updatedAt, ++revCounter, ref.value)
        }

        fun edgeCount(): Int =
            queries.selectDependencyEdges(alpha.value).executeAsList().size +
                queries.selectDependencyEdges(beta.value).executeAsList().size

        fun blockedOf(project: ProjectId): Map<TaskRef, Boolean> =
            deps.listBacklogLocked(project).associate { it.ref to it.blocked }
    }

    /** A three-entry `alpha` backlog in rank order, with nothing depending on anything yet. */
    private fun threeTodoTasks(): Fixture = Fixture().apply {
        seed(a, alpha, 1.0)
        seed(b, alpha, 2.0)
        seed(c, alpha, 3.0)
    }

    private fun test(block: suspend (Fixture) -> Unit) = runBlocking {
        withTimeout(20_000) { block(threeTodoTasks()) }
    }

    // --- the four refusals ------------------------------------------------------------------------

    @Test
    fun aTaskCannotDependOnItself() = test { f ->
        val refused = assertFailsWith<DependencyRefusedException> { f.deps.add(a, a) }

        assertEquals(DependencyRefusal.self, refused.refusal)
        assertEquals(a, refused.ref)
        assertEquals(a, refused.dependsOn)
        assertRefusedNothingHappened(f)
    }

    @Test
    fun anEdgeNamingARefWithNoBacklogRowIsRefusedFromEitherSide() = test { f ->
        // The source is what the edge hangs off; a missing one would be an orphan row.
        val noSource = assertFailsWith<DependencyRefusedException> { f.deps.add(absent, a) }
        assertEquals(DependencyRefusal.unknownRef, noSource.refusal)

        // The target is the dangerous half: `nextCandidate` JOINs `depends_on` to `backlog_entries`, so
        // an edge onto a ref with no row reads as ALREADY SATISFIED and silently unblocks the source.
        val noTarget = assertFailsWith<DependencyRefusedException> { f.deps.add(a, absent) }
        assertEquals(DependencyRefusal.unknownRef, noTarget.refusal)
        assertTrue(absent.value in noTarget.message.orEmpty(), "the message names the ref that is missing")

        assertRefusedNothingHappened(f)
    }

    @Test
    fun anEdgeBetweenTwoProjectsIsRefused() = test { f ->
        f.seed(d, beta, 1.0)

        val refused = assertFailsWith<DependencyRefusedException> { f.deps.add(a, d) }
        assertEquals(DependencyRefusal.crossProject, refused.refusal)

        // And in the other direction: the refusal is about the pair, not about which one is the source.
        assertEquals(
            DependencyRefusal.crossProject,
            assertFailsWith<DependencyRefusedException> { f.deps.add(d, a) }.refusal,
        )
        assertRefusedNothingHappened(f)
    }

    @Test
    fun anEdgeThatWouldCloseACycleIsRefused() = test { f ->
        f.deps.add(a, b)
        f.deps.add(b, c)
        f.emitted.clear()

        // Direct: b already depends on c.
        assertEquals(
            DependencyRefusal.cycle,
            assertFailsWith<DependencyRefusedException> { f.deps.add(c, b) }.refusal,
        )
        // Transitive: a → b → c, so c → a closes the ring. This is the one a depth-1 check would miss,
        // and a false negative here is unrecoverable — every task on a ring blocks every other one, so
        // `nextCandidate` skips all of it forever while `task next` just answers "nothing eligible".
        assertEquals(
            DependencyRefusal.cycle,
            assertFailsWith<DependencyRefusedException> { f.deps.add(c, a) }.refusal,
        )
        assertRefusedNothingHappened(f, expectedEdges = 2)
    }

    /** A refusal must be total: no edge written, no revision spent, nothing on the flow. */
    private fun assertRefusedNothingHappened(f: Fixture, expectedEdges: Int = 0) {
        assertEquals(expectedEdges, f.edgeCount(), "a refused edge must not be written")
        assertEquals(emptyList(), f.emitted, "a refused edge must not tell the board anything")
    }

    // --- the derived `blocked`, in all three read paths ---------------------------------------------

    @Test
    fun aTodoTaskWithAnUnfinishedDependencyIsBlockedInEveryReadPath() = test { f ->
        f.deps.add(b, a)

        assertEquals(true, f.deps.entryLocked(b)?.blocked, "entryLocked asks unfinishedDependencyCount")
        assertEquals(false, f.deps.entryLocked(a)?.blocked, "a task with no dependency is never blocked")
        assertEquals(
            mapOf(a to false, b to true, c to false),
            f.blockedOf(alpha),
            "listBacklogLocked folds one edge read against the project's own rows",
        )
        assertEquals(a, f.deps.nextCandidateLocked(alpha)?.ref, "nextCandidate's NOT EXISTS skips b")
    }

    @Test
    fun onlyATodoTaskIsEverBlocked() = test { f ->
        f.deps.add(b, a)
        f.setState(b, TaskState.in_progress)

        // `blocked` is "todo AND some dependency is not done", so an entry a human already dragged into
        // another column reads unblocked even though its dependency has not moved.
        assertEquals(false, f.deps.entryLocked(b)?.blocked)
        assertEquals(false, f.blockedOf(alpha)[b])
    }

    @Test
    fun aBlockedTaskBecomesTheCandidateOnceItsDependencyIsDone() = test { f ->
        // b and c both wait on a, and a is first in rank order anyway.
        f.deps.add(b, a)
        f.deps.add(c, a)
        assertEquals(a, f.deps.nextCandidateLocked(alpha)?.ref)

        f.setState(a, TaskState.done)

        assertEquals(b, f.deps.nextCandidateLocked(alpha)?.ref, "rank order decides between two ready tasks")
        assertEquals(false, f.deps.nextCandidateLocked(alpha)?.blocked)
        assertEquals(
            mapOf(a to false, b to false, c to false),
            f.blockedOf(alpha),
            "a done dependency blocks nobody",
        )
    }

    @Test
    fun aBacklogWithNothingEligibleAnswersNull() = test { f ->
        // The empty project first: `null` is the ONLY "nothing eligible" signal, and the CLI turns it
        // into exit 3, so it must not depend on the project existing at all.
        assertNull(f.deps.nextCandidateLocked(beta), "a project with no entries has no candidate")
        assertEquals(emptyList(), f.deps.listBacklogLocked(beta))

        f.deps.add(b, a)
        f.deps.add(c, a)
        f.setState(a, TaskState.in_progress)

        assertNull(
            f.deps.nextCandidateLocked(alpha),
            "one task in flight and two blocked behind it is 'nothing eligible', not an error",
        )
    }

    @Test
    fun anEdgeOntoARefWithNoRowIsReadTheWayTheSqlReadsIt() = test { f ->
        // Unreachable through `add` — that is the unknownRef refusal — so it is written directly. What
        // matters is that all three read paths AGREE about it: the two SQL forms JOIN `depends_on` to
        // `backlog_entries` and so ignore an edge with no row on the far side, and listBacklogLocked's
        // in-memory fold must not fail safe in the other direction. A card and a detail view
        // disagreeing about one task is worse than either answer.
        f.queries.insertDep(b.value, absent.value)

        assertEquals(false, f.deps.entryLocked(b)?.blocked)
        assertEquals(false, f.blockedOf(alpha)[b])
        assertEquals(a, f.deps.nextCandidateLocked(alpha)?.ref)
    }

    // --- the re-stamp -------------------------------------------------------------------------------

    @Test
    fun closingADependencyReStampsAndReEmitsEveryReverseDependent() = test { f ->
        f.deps.add(b, a)
        f.deps.add(c, a)
        assertEquals(mapOf(a to false, b to true, c to true), f.blockedOf(alpha))
        val updatedAtBefore = f.deps.entryLocked(b)!!.updatedAt
        f.emitted.clear()

        // What Task 7's `transition` does inside its transaction: write the state, then hand the ref to
        // the re-stamp so the rows whose derived `blocked` just moved are re-published.
        f.setState(a, TaskState.done)
        f.publishing { f.deps.restampDependentsLocked(a) }

        assertEquals(listOf(b, c), f.emitted.map { it.ref }, "one update per reverse dependent")
        for (update in f.emitted) {
            val entry = update.entry ?: error("a re-stamp is not a deletion")
            assertEquals(update.rev, entry.rev, "the frame's rev is the row's rev")
            assertFalse(entry.blocked, "the whole point: the dependents are ready now")
            assertEquals(entry.rev, f.deps.entryLocked(entry.ref)?.rev, "the new rev is what was stored")
        }
        assertTrue(
            f.emitted[0].rev < f.emitted[1].rev,
            "every re-stamped row takes its own revision from the store's one allocator",
        )
        assertEquals(
            updatedAtBefore,
            f.deps.entryLocked(b)!!.updatedAt,
            "a re-stamp leaves updated_at alone — the derived state moved, the row was not edited",
        )
    }

    @Test
    fun theReStampIsOneLevelDeepAndSkipsARefWithNoRow() = test { f ->
        // c → b → a. Closing a moves b's blocked-ness; c's asks about b's STATE, which did not change.
        f.deps.add(b, a)
        f.deps.add(c, b)
        f.emitted.clear()

        f.publishing { f.deps.restampDependentsLocked(a) }
        assertEquals(listOf(b), f.emitted.map { it.ref }, "only the direct reverse dependents")

        // A ref nobody depends on costs nothing, and neither does one with no row: a null-entry
        // TaskUpdate means DELETED, so a re-stamp must never manufacture one.
        f.emitted.clear()
        val revBefore = f.revCounter
        f.publishing { f.deps.restampDependentsLocked(c) }
        f.publishing { f.deps.restampDependentsLocked(absent) }
        assertEquals(emptyList(), f.emitted)
        assertEquals(revBefore, f.revCounter, "no revision is spent on a row that is not there")
    }

    @Test
    fun anAcceptedEdgeReStampsTheRefItselfSoItsOwnMarkerMoves() = test { f ->
        f.deps.add(b, a)

        val update = f.emitted.single()
        assertEquals(b, update.ref, "the edited ref is the one whose blocked and dependsOn just changed")
        assertEquals(true, update.entry?.blocked)
        assertEquals(update.rev, f.deps.entryLocked(b)?.rev)
    }

    // --- the graph reads ----------------------------------------------------------------------------

    @Test
    fun theGraphIsReadableInBothDirectionsAndIsScopedToOneProject() = test { f ->
        f.seed(d, beta, 1.0)
        f.deps.add(b, a)
        f.deps.add(c, a)
        f.queries.insertDep(d.value, absent.value) // another project's edge, written past the refusals

        assertEquals(listOf(a), f.deps.dependenciesOfLocked(b))
        assertEquals(listOf(b, c), f.deps.dependentsOfLocked(a), "the reverse lookup, in ref order")
        assertEquals(emptyList(), f.deps.dependenciesOfLocked(a))
        assertEquals(
            mapOf(b to listOf(a), c to listOf(a)),
            f.deps.edgesLocked(alpha),
            "the cycle walk's input carries this project's edges and nobody else's",
        )
        assertEquals(mapOf(d to listOf(absent)), f.deps.edgesLocked(beta))
    }

    @Test
    fun anUnknownRefHasNoEntryAndNoEdges() = test { f ->
        assertNull(f.deps.entryLocked(absent))
        assertEquals(emptyList(), f.deps.dependenciesOfLocked(absent))
        assertEquals(emptyList(), f.deps.dependentsOfLocked(absent))
    }

    @Test
    fun anEntryCarriesTheWholeRowNotJustItsDerivedHalf() = test { f ->
        f.seed(d, alpha, 4.5, state = TaskState.review, createdAt = 111L, updatedAt = 222L)

        assertEquals(
            BacklogEntry(
                ref = d,
                project = alpha,
                position = 4.5,
                state = TaskState.review,
                blocked = false,
                createdAt = 111L,
                updatedAt = 222L,
                rev = 0L,
            ),
            f.deps.entryLocked(d),
        )
        assertEquals(
            listOf(a, b, c, d),
            f.deps.listBacklogLocked(alpha).map { it.ref },
            "the project list is in rank order",
        )
    }

    // --- the two no-ops -----------------------------------------------------------------------------

    @Test
    fun reAddingAnExistingEdgeIsANoOpRatherThanAnErrorOrAnEmission() = test { f ->
        f.deps.add(b, a)
        val revAfterFirst = f.revCounter
        f.emitted.clear()

        // An idempotent retry of a request that already landed. The cycle walk answers `false` for an
        // edge already in the graph (that is `wouldCycle`'s contract), so this must not be refused —
        // and it must not re-publish either, or a retry storm becomes a board update storm.
        f.deps.add(b, a)

        assertEquals(1, f.edgeCount())
        assertEquals(emptyList(), f.emitted)
        assertEquals(revAfterFirst, f.revCounter, "a no-op spends no revision")
    }

    @Test
    fun removingAnEdgeThatIsNotThereIsANoOp() = test { f ->
        f.deps.remove(b, a)
        f.deps.remove(absent, a)

        assertEquals(emptyList(), f.emitted)
        assertEquals(0L, f.revCounter)
    }

    @Test
    fun removingAnEdgeUnblocksTheRefAndReEmitsIt() = test { f ->
        f.deps.add(c, a)
        f.deps.add(c, b)
        assertEquals(true, f.deps.entryLocked(c)?.blocked)
        f.emitted.clear()

        f.deps.remove(c, a)

        assertEquals(listOf(b), f.deps.dependenciesOfLocked(c), "only the named edge went away")
        assertEquals(true, f.deps.entryLocked(c)?.blocked, "b still holds it")
        assertEquals(listOf(c), f.emitted.map { it.ref })

        f.emitted.clear()
        f.deps.remove(c, b)

        assertEquals(emptyList(), f.deps.dependenciesOfLocked(c))
        assertEquals(false, f.emitted.single().entry?.blocked, "the last edge going away unblocks it")
        assertEquals(false, f.blockedOf(alpha)[c], "and every read path agrees")
    }

    // --- the wiring the store declares --------------------------------------------------------------

    @Test
    fun theStoresOwnCollaboratorEmitsOnTaskUpdates() = runBlocking {
        withTimeout(20_000) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            // Exactly the plan's shape: open the store (its init runs the CREATE TABLE IF NOT EXISTS
            // block), seed through the generated queries, and drive the public `dependencies` val.
            // Task 7's own methods are still TODO(), and none of them is touched here.
            val store = SqliteTaskStore.using(driver) { 0L }
            val queries = KotgentDatabase(driver).backlogQueries
            queries.insertEntry(a.value, alpha.value, 1.0, TaskState.todo.name, 1L, 1L, 0L)
            queries.insertEntry(b.value, alpha.value, 2.0, TaskState.todo.name, 1L, 1L, 0L)

            val seen = mutableListOf<TaskUpdate>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                store.taskUpdates.take(1).toList(seen)
            }

            store.dependencies.add(b, a)
            collector.join()

            val update = seen.single()
            assertEquals(b, update.ref)
            assertEquals(true, update.entry?.blocked)
            assertTrue(update.rev > 0, "the store's own revision allocator stamped it")
        }
    }
}
