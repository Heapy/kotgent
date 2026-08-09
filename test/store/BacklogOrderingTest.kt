package io.kotgent.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.db.BacklogQueries
import io.kotgent.db.KotgentDatabase
import io.kotgent.task.MoveTarget
import io.kotgent.task.POSITION_EPSILON
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [BacklogOrdering] — gap-based ranking, and the renormalization that keeps it from running
 * out of room.
 *
 * ## What they are built around
 * A rank is not an index, so **the only thing a move can be checked against is the ORDER it produces**,
 * never the number it wrote. Every test below therefore reads the project's column back through
 * `selectEntriesByProject` (`ORDER BY position, task_ref` — what the board renders) and asserts the
 * sequence of refs, plus that the ranks are strictly increasing. A pair of entries that ties or inverts
 * is the actual defect this file exists to catch, and a test that asserted `1.5` would miss it while
 * failing on every legitimate change to the arithmetic.
 *
 * The second theme is that **a renormalization is only correct if the board hears all of it**. Rewriting
 * the column touches every row without any of them being edited, so a connected client holds stale
 * positions unless each rewritten row stamps its own revision and emits — which is why
 * [aCollapsedGapRenormalizesTheWholeColumnAndEmitsEveryRow] asserts one update per row and matches each
 * frame's rev against what was stored, and why
 * [sixtyMidpointInsertsBetweenOnePairStayStrictlyOrdered] drives the collapse through the front door
 * rather than seeding one.
 *
 * The collaborator is the **real** [BacklogDependencies] over the same queries and the same mutex, as
 * the production store wires it: the `blocked` on every emitted entry has one implementation and this
 * class must read through it, and sharing the mutex is what would expose a reentrant call (a Kotlin
 * `Mutex` is not reentrant, so [BacklogOrdering] may only use the collaborator's `…Locked` members).
 */
class BacklogOrderingTest {

    // --- the vocabulary -------------------------------------------------------------------------

    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val beta = ProjectId.of("7b1d5e90-4a2c-4c11-8e77-2d3f6a8b9c01")

    private val a = TaskRef("local:1")
    private val b = TaskRef("local:2")
    private val c = TaskRef("local:3")
    private val elsewhere = TaskRef("local:9")
    private val absent = TaskRef("local:404")

    /**
     * An in-memory database plus one observable [BacklogOrdering] over it. The revision allocator and
     * the emitter are the test's own — the only way to see what a move stamped and published — and the
     * clock ADVANCES on every read, so a single timestamp shared by a whole renormalization is
     * distinguishable from one per row.
     */
    private inner class Fixture {
        val driver: SqlDriver = inMemoryDriver(KotgentDatabase.Schema)
        val queries: BacklogQueries = KotgentDatabase(driver).backlogQueries
        val mutex = Mutex()
        var revCounter: Long = 0
        var clock: Long = 100
        val emitted: MutableList<TaskUpdate> = mutableListOf()

        /**
         * The store's staging buffer, with the test's list as its publisher: a mutator STAGES while its
         * transaction is open and the batch is published only after the locked body succeeded.
         */
        val outbox: TaskUpdateOutbox = TaskUpdateOutbox { emitted += it }

        /**
         * The real collaborator, over the same queries and the same mutex the store shares between the
         * two. Its own clock refuses to answer: nothing it writes is timestamped, so a move that routed
         * a timestamp through it would fail here instead of silently stamping `updated_at` twice.
         */
        val deps: BacklogDependencies = BacklogDependencies(
            queries = queries,
            mutex = mutex,
            nextRev = { ++revCounter },
            outbox = outbox,
            now = { error("BacklogDependencies must not need a clock") },
        )

        val ordering: BacklogOrdering = BacklogOrdering(
            queries = queries,
            mutex = mutex,
            dependencies = deps,
            nextRev = { ++revCounter },
            outbox = outbox,
            now = { ++clock },
        )

        /**
         * Insert one backlog row directly. `tasks` rows are deliberately not seeded: nothing the
         * ordering layer reads joins them, so a row here is exactly the input it sees.
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

        /** The project's column as the board reads it: refs in rank order. */
        fun order(project: ProjectId): List<TaskRef> =
            queries.selectEntriesByProject(project.value).executeAsList().map { TaskRef(it.task_ref) }

        fun positions(project: ProjectId): List<Double> =
            queries.selectEntriesByProject(project.value).executeAsList().map { it.position }

        fun row(ref: TaskRef) = queries.selectEntry(ref.value).executeAsOne()
    }

    /** A three-entry `alpha` backlog with a whole unit between each pair — nothing near a collapse. */
    private fun threeRankedTasks(): Fixture = Fixture().apply {
        seed(a, alpha, 1.0)
        seed(b, alpha, 2.0)
        seed(c, alpha, 3.0)
    }

    private fun test(fixture: Fixture = threeRankedTasks(), block: suspend (Fixture) -> Unit) =
        runBlocking { withTimeout(60_000) { block(fixture) } }

    /** The invariant every move owes, whatever number it wrote: no tie, no inversion. */
    private fun assertStrictlyOrdered(f: Fixture, project: ProjectId) {
        val ranks = f.positions(project)
        for ((lower, upper) in ranks.zipWithNext()) {
            assertTrue(lower < upper, "ranks must stay strictly increasing, saw $lower then $upper in $ranks")
        }
    }

    // --- the four targets -----------------------------------------------------------------------

    @Test
    fun movingToAnEndPutsTheEntryPastEveryOtherRank() = test { f ->
        assertEquals(c, f.ordering.move(c, MoveTarget.Top)?.ref)
        assertEquals(listOf(c, a, b), f.order(alpha))

        assertEquals(listOf(a, b, c), run { f.ordering.move(c, MoveTarget.Bottom); f.order(alpha) })

        // Both ends are idempotent in ORDER — the only thing a repeat spends is gap, which is the
        // renormalization's problem and not the caller's.
        f.ordering.move(a, MoveTarget.Top)
        f.ordering.move(c, MoveTarget.Bottom)
        assertEquals(listOf(a, b, c), f.order(alpha))
        assertStrictlyOrdered(f, alpha)
    }

    @Test
    fun movingBeforeOrAfterANeighbourLandsBetweenItAndItsOwnNeighbour() = test { f ->
        assertEquals(listOf(c, a, b), run { f.ordering.move(c, MoveTarget.Before(a)); f.order(alpha) })
        assertEquals(listOf(a, c, b), run { f.ordering.move(c, MoveTarget.After(a)); f.order(alpha) })
        assertEquals(listOf(a, b, c), run { f.ordering.move(c, MoveTarget.After(b)); f.order(alpha) })

        // Before the FIRST entry and after the LAST are the two cases with only one neighbour: they
        // degrade to the top and bottom rules rather than needing a missing bracket.
        assertEquals(listOf(b, a, c), run { f.ordering.move(b, MoveTarget.Before(a)); f.order(alpha) })
        assertEquals(listOf(a, c, b), run { f.ordering.move(b, MoveTarget.After(c)); f.order(alpha) })
        assertStrictlyOrdered(f, alpha)
    }

    @Test
    fun movingAnEntryOnePlaceEitherWayCrossesExactlyOneNeighbour() = test { f ->
        // The neighbour queries do not exclude the moved row, so these two are where an off-by-one
        // would show up: `after(b)` must cross b, not land back where it was.
        assertEquals(listOf(b, a, c), run { f.ordering.move(a, MoveTarget.After(b)); f.order(alpha) })
        assertEquals(listOf(b, c, a), run { f.ordering.move(a, MoveTarget.After(c)); f.order(alpha) })
        assertEquals(listOf(b, a, c), run { f.ordering.move(a, MoveTarget.Before(c)); f.order(alpha) })
        assertStrictlyOrdered(f, alpha)
    }

    @Test
    fun movingAnEntryRelativeToItselfKeepsTheOrderItAlreadyHad() = test { f ->
        // Not an error: the board can drop a card back where it came from, and "before yourself" is a
        // rank strictly between your own neighbours — i.e. exactly where you are.
        assertEquals(b, f.ordering.move(b, MoveTarget.Before(b))?.ref)
        assertEquals(listOf(a, b, c), f.order(alpha))
        assertEquals(b, f.ordering.move(b, MoveTarget.After(b))?.ref)
        assertEquals(listOf(a, b, c), f.order(alpha))
        assertStrictlyOrdered(f, alpha)
    }

    // --- what a move refuses --------------------------------------------------------------------

    @Test
    fun aMoveNamingAnUnknownRefOrNeighbourAnswersNullAndWritesNothing() = test { f ->
        f.seed(elsewhere, beta, 1.0)
        val before = f.positions(alpha)

        assertNull(f.ordering.move(absent, MoveTarget.Top), "an unknown entry cannot be ranked")
        assertNull(f.ordering.move(a, MoveTarget.Before(absent)), "a neighbour with no row is unknown")
        assertNull(f.ordering.move(a, MoveTarget.After(absent)))
        // The dangerous one: a neighbour in ANOTHER project has a perfectly good rank, and honouring it
        // would rank an alpha card against beta's column — dropping it out of the board it was dragged
        // on, at an arbitrary place in one it was never on.
        assertNull(f.ordering.move(a, MoveTarget.Before(elsewhere)), "a cross-project neighbour is refused")
        assertNull(f.ordering.move(a, MoveTarget.After(elsewhere)))

        assertEquals(before, f.positions(alpha), "a refused move rewrites nothing")
        assertEquals(emptyList(), f.emitted, "a refused move tells the board nothing")
        assertEquals(0L, f.revCounter, "a refused move spends no revision")
    }

    // --- what a move publishes --------------------------------------------------------------------

    @Test
    fun aMoveEmitsOneUpdateCarryingTheStoredRankRevisionAndDerivedBlocked() = test { f ->
        // c waits on a, which is still `todo`, so c is blocked — the emitted entry has to say so, and
        // that answer may only come from the one implementation of the rule.
        f.queries.insertDep(c.value, a.value)
        val createdAt = f.row(c).created_at

        val moved = assertNotNull(f.ordering.move(c, MoveTarget.Top))

        val update = f.emitted.single()
        assertEquals(c, update.ref)
        assertEquals(moved, update.entry, "the emitted entry is the one the caller was handed")
        assertEquals(update.rev, moved.rev, "the frame's rev is the row's rev")
        assertTrue(moved.blocked, "the derived blocked comes from the real BacklogDependencies")

        val stored = f.row(c)
        assertTrue(stored.position == moved.position, "the emitted rank is the stored rank")
        assertEquals(stored.rev, moved.rev, "the emitted rev is the stored rev")
        assertEquals(TaskState.todo.name, stored.state, "a move carries no state")
        assertEquals(createdAt, stored.created_at, "a move is not a creation")
        assertEquals(101L, stored.updated_at, "a move is an edit of the row, so it stamps the clock once")
    }

    // --- renormalization --------------------------------------------------------------------------

    @Test
    fun aCollapsedGapRenormalizesTheWholeColumnAndEmitsEveryRow() = test(
        Fixture().apply {
            // A gap the midpoint rule can still technically halve, but not by enough to be worth
            // keeping: below POSITION_EPSILON, which is the trigger.
            seed(a, alpha, 1.0)
            seed(b, alpha, 1.0 + POSITION_EPSILON / 2)
            seed(c, alpha, 3.0)
        },
    ) { f ->
        f.queries.insertDep(c.value, a.value)

        val moved = assertNotNull(f.ordering.move(c, MoveTarget.Before(b)))

        // The renormalization first: one update per row, in rank order, at `1.0, 2.0, 3.0`.
        assertEquals(4, f.emitted.size, "three rewritten rows, then the retried move")
        val renormalized = f.emitted.dropLast(1)
        assertEquals(listOf(a, b, c), renormalized.map { it.ref }, "every rewritten row emits, not just the moved one")
        assertEquals(listOf(1.0, 2.0, 3.0), renormalized.map { it.entry?.position })
        for (update in renormalized) {
            val entry = assertNotNull(update.entry, "a renormalization is not a deletion")
            assertEquals(update.rev, entry.rev, "the frame's rev is the row's rev")
            assertTrue(entry.rev > 0, "every rewritten row takes its own revision")
        }
        assertEquals(
            renormalized.map { it.rev }.sorted(),
            renormalized.map { it.rev },
            "the revisions come from the store's one allocator, in write order",
        )
        assertEquals(
            listOf(101L, 101L, 101L),
            renormalized.map { it.entry?.updatedAt },
            "one renormalization is one edit of the column, so it reads the clock once",
        )
        assertTrue(renormalized.single { it.ref == c }.entry!!.blocked, "a rewritten row keeps its derived blocked")

        // Then the retried move, on the column the renormalization left behind.
        val move = f.emitted.last()
        assertEquals(c, move.ref)
        assertEquals(moved, move.entry)
        assertTrue(move.rev > renormalized.last().rev, "the move is stamped after the rewrite it forced")
        assertEquals(listOf(a, c, b), f.order(alpha), "the retry lands where the caller asked")
        assertStrictlyOrdered(f, alpha)

        // And what the emissions claimed is what a reconnecting client would read back.
        assertEquals(f.row(a).rev, renormalized.first { it.ref == a }.rev)
        assertEquals(f.row(b).rev, renormalized.first { it.ref == b }.rev)
        assertEquals(f.row(c).rev, move.rev)
    }

    @Test
    fun aCollapsedTopRenormalizesToo() = test(
        Fixture().apply {
            // The top halves toward the zero floor, so its collapse is about the SMALLEST rank rather
            // than about a pair — thirty consecutive top moves get here on their own.
            seed(a, alpha, POSITION_EPSILON / 2)
            seed(b, alpha, 1.0)
            seed(c, alpha, 2.0)
        },
    ) { f ->
        assertEquals(c, f.ordering.move(c, MoveTarget.Top)?.ref)

        assertEquals(listOf(c, a, b), f.order(alpha))
        assertStrictlyOrdered(f, alpha)
        assertEquals(
            listOf(a, b, c, c),
            f.emitted.map { it.ref },
            "the whole column is rewritten, then the moved row is written again",
        )
        assertTrue(f.positions(alpha).first() > 0.0, "zero is the floor and is never itself assigned")
    }

    @Test
    fun sixtyMidpointInsertsBetweenOnePairStayStrictlyOrdered() = test(
        Fixture().apply {
            seed(a, alpha, 1.0)
            seed(b, alpha, 2.0)
            for (n in 1..60) seed(TaskRef("local:t$n"), alpha, 2.0 + n)
        },
    ) { f ->
        // Every move subdivides the SAME gap — the one just above a — which is the fastest way a real
        // backlog runs out of room (a human dropping card after card at the top of a column).
        for (n in 1..60) f.ordering.move(TaskRef("local:t$n"), MoveTarget.After(a))

        // Each insert lands immediately after a, so the last one moved is the first one seen.
        val expected = listOf(a) + (60 downTo 1).map { TaskRef("local:t$it") } + listOf(b)
        assertEquals(expected, f.order(alpha), "sixty subdivisions of one gap still order exactly")
        assertStrictlyOrdered(f, alpha)

        // The arithmetic is deterministic: the gap above a halves per move, so it drops below
        // POSITION_EPSILON at 2^-30 — the 31st move — and the column is rewritten exactly once. Sixty
        // moves plus one rewrite of all 62 rows is what the board must have been told.
        assertEquals(60 + 62, f.emitted.size, "one renormalization, and every row of it emitted")
    }

    @Test
    fun aRenormalizationRewritesOneProjectAndLeavesTheOtherAlone() = test(
        Fixture().apply {
            seed(a, alpha, 1.0)
            seed(b, alpha, 1.0 + POSITION_EPSILON / 2)
            seed(c, alpha, 3.0)
            // A rank in the range the rewrite would hand out, in a project that must not be touched by
            // it: renormalizing every backlog at once would silently reshuffle boards nobody dragged on.
            seed(elsewhere, beta, 2.0)
        },
    ) { f ->
        assertNotNull(f.ordering.move(c, MoveTarget.Before(b)))

        assertEquals(listOf(a, c, b), f.order(alpha))
        assertEquals(listOf(elsewhere), f.order(beta))
        assertTrue(f.row(elsewhere).position == 2.0, "another project's ranks are not this column's")
        assertEquals(0L, f.row(elsewhere).rev, "and its rows are neither re-stamped nor re-published")
        assertTrue(f.emitted.none { it.ref == elsewhere }, "nor announced")
    }

    // --- the wiring the store declares --------------------------------------------------------------

    @Test
    fun theStoresOwnOrderingEmitsOnTaskUpdates() = runBlocking {
        withTimeout(20_000) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            // Exactly the plan's shape: open the store (its init runs the CREATE TABLE IF NOT EXISTS
            // block), seed through the generated queries, and drive the public `ordering` val. Task 7's
            // own methods are still unimplemented, and none of them is touched here.
            val store = SqliteTaskStore.using(driver) { 42L }
            val queries = KotgentDatabase(driver).backlogQueries
            queries.insertEntry(a.value, alpha.value, 1.0, TaskState.todo.name, 1L, 1L, 0L)
            queries.insertEntry(b.value, alpha.value, 2.0, TaskState.todo.name, 1L, 1L, 0L)

            val seen = mutableListOf<TaskUpdate>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                store.taskUpdates.take(1).toList(seen)
            }

            val moved = store.ordering.move(b, MoveTarget.Top)
            collector.join()

            val update = seen.single()
            assertEquals(b, update.ref)
            assertEquals(moved, update.entry)
            assertTrue(update.rev > 0, "the store's own revision allocator stamped it")
            assertTrue(
                update.entry!!.position < queries.selectEntry(a.value).executeAsOne().position,
                "the store's ordering ranked it above the entry it was moved past",
            )
        }
    }
}
