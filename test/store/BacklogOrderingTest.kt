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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BacklogOrderingTest {


    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val beta = ProjectId.of("7b1d5e90-4a2c-4c11-8e77-2d3f6a8b9c01")

    private val a = TaskRef("local:1")
    private val b = TaskRef("local:2")
    private val c = TaskRef("local:3")
    private val elsewhere = TaskRef("local:9")
    private val absent = TaskRef("local:404")

    private inner class Fixture {
        val driver: SqlDriver = inMemoryDriver(KotgentDatabase.Schema)
        val queries: BacklogQueries = KotgentDatabase(driver).backlogQueries
        // Sharing this non-reentrant mutex with dependencies matches production wiring.
        val mutex = Mutex()
        var revCounter: Long = 0
        var clock: Long = 100
        val emitted: MutableList<TaskUpdate> = mutableListOf()

        // Read two fails after renormalization is staged but before the moved row can commit.
        var clockReads: Int = 0
        var failClockAtRead: Int = 0

        val outbox: TaskUpdateOutbox = TaskUpdateOutbox { emitted += it }

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
            now = {
                clockReads++
                if (clockReads == failClockAtRead) error("the clock refused to answer")
                ++clock
            },
        )

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

        fun order(project: ProjectId): List<TaskRef> =
            queries.selectEntriesByProject(project.value).executeAsList().map { TaskRef(it.task_ref) }

        fun positions(project: ProjectId): List<Double> =
            queries.selectEntriesByProject(project.value).executeAsList().map { it.position }

        fun row(ref: TaskRef) = queries.selectEntry(ref.value).executeAsOne()
    }

    private fun threeRankedTasks(): Fixture = Fixture().apply {
        seed(a, alpha, 1.0)
        seed(b, alpha, 2.0)
        seed(c, alpha, 3.0)
    }

    private fun test(fixture: Fixture = threeRankedTasks(), block: suspend (Fixture) -> Unit) =
        runBlocking { withTimeout(60_000) { block(fixture) } }

    private fun assertStrictlyOrdered(f: Fixture, project: ProjectId) {
        val ranks = f.positions(project)
        for ((lower, upper) in ranks.zipWithNext()) {
            assertTrue(lower < upper, "ranks must stay strictly increasing, saw $lower then $upper in $ranks")
        }
    }


    @Test
    fun movingToAnEndPutsTheEntryPastEveryOtherRank() = test { f ->
        assertEquals(c, f.ordering.move(c, MoveTarget.Top)?.ref)
        assertEquals(listOf(c, a, b), f.order(alpha))

        assertEquals(listOf(a, b, c), run { f.ordering.move(c, MoveTarget.Bottom); f.order(alpha) })

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

        assertEquals(listOf(b, a, c), run { f.ordering.move(b, MoveTarget.Before(a)); f.order(alpha) })
        assertEquals(listOf(a, c, b), run { f.ordering.move(b, MoveTarget.After(c)); f.order(alpha) })
        assertStrictlyOrdered(f, alpha)
    }

    @Test
    fun movingAnEntryOnePlaceEitherWayCrossesExactlyOneNeighbour() = test { f ->
        assertEquals(listOf(b, a, c), run { f.ordering.move(a, MoveTarget.After(b)); f.order(alpha) })
        assertEquals(listOf(b, c, a), run { f.ordering.move(a, MoveTarget.After(c)); f.order(alpha) })
        assertEquals(listOf(b, a, c), run { f.ordering.move(a, MoveTarget.Before(c)); f.order(alpha) })
        assertStrictlyOrdered(f, alpha)
    }

    @Test
    fun movingAnEntryRelativeToItselfKeepsTheOrderItAlreadyHad() = test { f ->
        assertEquals(b, f.ordering.move(b, MoveTarget.Before(b))?.ref)
        assertEquals(listOf(a, b, c), f.order(alpha))
        assertEquals(b, f.ordering.move(b, MoveTarget.After(b))?.ref)
        assertEquals(listOf(a, b, c), f.order(alpha))
        assertStrictlyOrdered(f, alpha)
    }


    @Test
    fun aMoveNamingAnUnknownRefOrNeighbourAnswersNullAndWritesNothing() = test { f ->
        f.seed(elsewhere, beta, 1.0)
        val before = f.positions(alpha)

        assertNull(f.ordering.move(absent, MoveTarget.Top), "an unknown entry cannot be ranked")
        assertNull(f.ordering.move(a, MoveTarget.Before(absent)), "a neighbour with no row is unknown")
        assertNull(f.ordering.move(a, MoveTarget.After(absent)))
        assertNull(f.ordering.move(a, MoveTarget.Before(elsewhere)), "a cross-project neighbour is refused")
        assertNull(f.ordering.move(a, MoveTarget.After(elsewhere)))

        assertEquals(before, f.positions(alpha), "a refused move rewrites nothing")
        assertEquals(emptyList(), f.emitted, "a refused move tells the board nothing")
        assertEquals(0L, f.revCounter, "a refused move spends no revision")
    }


    @Test
    fun aMoveEmitsOneUpdateCarryingTheStoredRankRevisionAndDerivedBlocked() = test { f ->
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


    @Test
    fun aCollapsedGapRenormalizesTheWholeColumnAndEmitsEveryRow() = test(
        Fixture().apply {
            seed(a, alpha, 1.0)
            seed(b, alpha, 1.0 + POSITION_EPSILON / 2)
            seed(c, alpha, 3.0)
        },
    ) { f ->
        f.queries.insertDep(c.value, a.value)

        val moved = assertNotNull(f.ordering.move(c, MoveTarget.Before(b)))

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

        val move = f.emitted.last()
        assertEquals(c, move.ref)
        assertEquals(moved, move.entry)
        assertTrue(move.rev > renormalized.last().rev, "the move is stamped after the rewrite it forced")
        assertEquals(listOf(a, c, b), f.order(alpha), "the retry lands where the caller asked")
        assertStrictlyOrdered(f, alpha)

        assertEquals(f.row(a).rev, renormalized.first { it.ref == a }.rev)
        assertEquals(f.row(b).rev, renormalized.first { it.ref == b }.rev)
        assertEquals(f.row(c).rev, move.rev)
    }

    @Test
    fun aCollapsedTopRenormalizesToo() = test(
        Fixture().apply {
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
        for (n in 1..60) f.ordering.move(TaskRef("local:t$n"), MoveTarget.After(a))

        val expected = listOf(a) + (60 downTo 1).map { TaskRef("local:t$it") } + listOf(b)
        assertEquals(expected, f.order(alpha), "sixty subdivisions of one gap still order exactly")
        assertStrictlyOrdered(f, alpha)

        assertEquals(60 + 62, f.emitted.size, "one renormalization, and every row of it emitted")
    }

    @Test
    fun aThrowAfterTheRewriteRollsItBackRatherThanLeavingTheBoardBehindIt() = test(
        Fixture().apply {
            seed(a, alpha, 1.0)
            seed(b, alpha, 1.0 + POSITION_EPSILON / 2)
            seed(c, alpha, 3.0)
        },
    ) { f ->
        val before = f.positions(alpha)
        f.failClockAtRead = 2

        assertFailsWith<IllegalStateException> { f.ordering.move(c, MoveTarget.Before(b)) }

        assertEquals(before, f.positions(alpha), "the rewrite rolled back with the write that failed")
        assertEquals(
            listOf(0L, 0L, 0L),
            listOf(f.row(a).rev, f.row(b).rev, f.row(c).rev),
            "…revisions included: nothing about these rows was committed",
        )
        assertEquals(emptyList(), f.emitted, "and nothing was published, which is what the database agrees with")

        assertNotNull(f.ordering.move(c, MoveTarget.Before(b)))
        assertEquals(listOf(a, c, b), f.order(alpha))
        assertStrictlyOrdered(f, alpha)
    }

    @Test
    fun aRenormalizationRewritesOneProjectAndLeavesTheOtherAlone() = test(
        Fixture().apply {
            seed(a, alpha, 1.0)
            seed(b, alpha, 1.0 + POSITION_EPSILON / 2)
            seed(c, alpha, 3.0)
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


    @Test
    fun theStoresOwnOrderingEmitsOnTaskUpdates() = runBlocking {
        withTimeout(20_000) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
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
