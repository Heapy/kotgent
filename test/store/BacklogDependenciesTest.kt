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

class BacklogDependenciesTest {


    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val beta = ProjectId.of("7b1d5e90-4a2c-4c11-8e77-2d3f6a8b9c01")

    private val a = TaskRef("local:1")
    private val b = TaskRef("local:2")
    private val c = TaskRef("local:3")
    private val d = TaskRef("local:4")
    private val absent = TaskRef("local:404")

    private inner class Fixture {
        val driver: SqlDriver = inMemoryDriver(KotgentDatabase.Schema)
        val queries: BacklogQueries = KotgentDatabase(driver).backlogQueries
        var revCounter: Long = 0
        val emitted: MutableList<TaskUpdate> = mutableListOf()

        // Locked mutators stage here; publishing exposes updates only after the transaction succeeds.
        val outbox: TaskUpdateOutbox = TaskUpdateOutbox { emitted += it }

        val deps: BacklogDependencies = BacklogDependencies(
            queries = queries,
            mutex = Mutex(),
            nextRev = { ++revCounter },
            outbox = outbox,
            now = { error("BacklogDependencies must not need a clock") },
        )

        fun <T> publishing(block: () -> T): T = outbox.publishing(block)

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

        fun setState(ref: TaskRef, state: TaskState, updatedAt: Long = 20L) {
            queries.setState(state.name, updatedAt, ++revCounter, ref.value)
        }

        fun edgeCount(): Int =
            queries.selectDependencyEdges(alpha.value).executeAsList().size +
                queries.selectDependencyEdges(beta.value).executeAsList().size

        fun blockedOf(project: ProjectId): Map<TaskRef, Boolean> =
            deps.listBacklogLocked(project).associate { it.ref to it.blocked }
    }

    private fun threeTodoTasks(): Fixture = Fixture().apply {
        seed(a, alpha, 1.0)
        seed(b, alpha, 2.0)
        seed(c, alpha, 3.0)
    }

    private fun test(block: suspend (Fixture) -> Unit) = runBlocking {
        withTimeout(20_000) { block(threeTodoTasks()) }
    }


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
        val noSource = assertFailsWith<DependencyRefusedException> { f.deps.add(absent, a) }
        assertEquals(DependencyRefusal.unknownRef, noSource.refusal)

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

        assertEquals(
            DependencyRefusal.cycle,
            assertFailsWith<DependencyRefusedException> { f.deps.add(c, b) }.refusal,
        )
        assertEquals(
            DependencyRefusal.cycle,
            assertFailsWith<DependencyRefusedException> { f.deps.add(c, a) }.refusal,
        )
        assertRefusedNothingHappened(f, expectedEdges = 2)
    }

    private fun assertRefusedNothingHappened(f: Fixture, expectedEdges: Int = 0) {
        assertEquals(expectedEdges, f.edgeCount(), "a refused edge must not be written")
        assertEquals(emptyList(), f.emitted, "a refused edge must not tell the board anything")
    }


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

        assertEquals(false, f.deps.entryLocked(b)?.blocked)
        assertEquals(false, f.blockedOf(alpha)[b])
    }

    @Test
    fun aBlockedTaskBecomesTheCandidateOnceItsDependencyIsDone() = test { f ->
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
        f.queries.insertDep(b.value, absent.value)

        assertEquals(false, f.deps.entryLocked(b)?.blocked)
        assertEquals(false, f.blockedOf(alpha)[b])
        assertEquals(a, f.deps.nextCandidateLocked(alpha)?.ref)
    }


    @Test
    fun closingADependencyReStampsAndReEmitsEveryReverseDependent() = test { f ->
        f.deps.add(b, a)
        f.deps.add(c, a)
        assertEquals(mapOf(a to false, b to true, c to true), f.blockedOf(alpha))
        val updatedAtBefore = f.deps.entryLocked(b)!!.updatedAt
        f.emitted.clear()

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
        f.deps.add(b, a)
        f.deps.add(c, b)
        f.emitted.clear()

        f.publishing { f.deps.restampDependentsLocked(a) }
        assertEquals(listOf(b), f.emitted.map { it.ref }, "only the direct reverse dependents")

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


    @Test
    fun theGraphIsReadableInBothDirectionsAndIsScopedToOneProject() = test { f ->
        f.seed(d, beta, 1.0)
        f.deps.add(b, a)
        f.deps.add(c, a)
        f.queries.insertDep(d.value, absent.value)

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


    @Test
    fun reAddingAnExistingEdgeIsANoOpRatherThanAnErrorOrAnEmission() = test { f ->
        f.deps.add(b, a)
        val revAfterFirst = f.revCounter
        f.emitted.clear()

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


    @Test
    fun theStoresOwnCollaboratorEmitsOnTaskUpdates() = runBlocking {
        withTimeout(20_000) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
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
