package io.kotgent.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.daemon.TaskService
import io.kotgent.db.KotgentDatabase
import io.kotgent.task.ActivityKind
import io.kotgent.task.TaskState
import io.kotgent.task.TaskUpdate
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.closedir
import platform.posix.mkdtemp
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SqliteTaskStore] — the tracker CRUD, the activity feed, the project registry, the
 * conditional `startIfTodo`, `transition`, and the `taskUpdates` emission every one of them owes.
 *
 * ## What they are built around
 * Three properties run through nearly every test here, because they are what a board connected to this
 * store depends on:
 *
 *  1. **A write that a client can see owes a fresh `rev` AND an emission.** `taskUpdates` is the only
 *     signal the board gets between reloads, so a title edit, a state change and a delete are each
 *     asserted through the flow, not only through a read-back.
 *  2. **A write that changes nothing emits nothing.** An update or a delete of an unknown ref, and a
 *     `startIfTodo` on a task that has already started, must leave the flow silent — an idempotent retry
 *     must not become a board update storm.
 *  3. **`blocked` is derived, so a change to one row moves other rows.** Closing or deleting a dependency
 *     re-stamps and re-emits its reverse dependents; nothing else would get a connected board off a
 *     blocked marker on a ready card.
 *
 * ## How an emission is observed
 * `taskUpdates` is hot with `replay = 0`, so a subscriber must exist before the write. [recording]
 * launches an `UNDISPATCHED` collector for exactly the expected number of frames and joins it after the
 * block — the join is also what lets the collector run at all, since a store call under an uncontended
 * `Mutex` need never suspend on `runBlocking`'s single-threaded event loop. A "nothing was emitted" test
 * therefore records ONE frame, performs the silent action and then a known-loud one, and asserts the
 * single frame is the loud one; a bare "the list is empty" assertion could pass simply because the
 * collector never got a turn.
 *
 * [move] is deliberately absent: it is a one-line delegation to [BacklogOrdering], which is being
 * implemented in parallel and whose body this file must not depend on.
 */
@OptIn(ExperimentalForeignApi::class)
class TaskStoreTest {

    // --- the vocabulary ---------------------------------------------------------------------------

    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val beta = ProjectId.of("7b1d5e90-4a2c-4c11-8e77-2d3f6a8b9c01")

    private val first = TaskRef("local:1")
    private val second = TaskRef("local:2")
    private val absent = TaskRef("local:404")

    /** A store with a hand-cranked clock, so every timestamp in an assertion is a chosen number. */
    private inner class Fixture(val driver: SqlDriver = inMemoryDriver(KotgentDatabase.Schema)) {
        var clock: Long = 1_000L
        val store: SqliteTaskStore = SqliteTaskStore.using(driver) { clock }
    }

    private fun test(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        withTimeout(20_000) { block(Fixture()) }
    }

    /**
     * Run [block] while collecting exactly [count] frames off [SqliteTaskStore.taskUpdates].
     *
     * `UNDISPATCHED` makes the collector subscribe before [block] runs (the flow does not replay), and
     * the `join` after it is what gives the collector a turn on the single-threaded event loop.
     */
    private suspend fun CoroutineScope.recording(
        store: SqliteTaskStore,
        count: Int,
        block: suspend () -> Unit,
    ): List<TaskUpdate> {
        val seen = mutableListOf<TaskUpdate>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { store.taskUpdates.take(count).toList(seen) }
        block()
        collector.join()
        return seen
    }

    // --- tracker CRUD -----------------------------------------------------------------------------

    @Test
    fun aCreatedTaskReadsBackThroughGetAndList() = test { f ->
        val created = f.store.create(alpha, "Wire the board", "the four columns")

        assertEquals(first, created.ref, "the built-in tracker mints local:<n> from 1")
        assertEquals("Wire the board", created.title)
        assertEquals("the four columns", created.body)
        assertNull(created.url, "the built-in tracker has no url column and never will")
        assertEquals(1_000L, created.updatedAt)

        assertEquals(created, f.store.get(first))
        assertEquals(listOf(created), f.store.list(alpha))
        assertEquals(emptyList(), f.store.list(beta), "list is scoped to one project's backlog")
        assertNull(f.store.get(absent))
    }

    @Test
    fun createMintsSequentialRefsAndAppendsAtTheEndOfTheColumn() = test { f ->
        f.store.create(alpha, "one", "")
        f.store.create(alpha, "two", "")
        f.store.create(alpha, "three", "")
        // A different project's column is ranked independently — `maxPosition` is per project.
        val other = f.store.create(beta, "elsewhere", "")

        assertEquals(listOf("local:1", "local:2", "local:3"), f.store.listBacklog(alpha).map { it.ref.value })
        assertEquals(listOf(1.0, 2.0, 3.0), f.store.listBacklog(alpha).map { it.position })
        assertEquals(TaskRef("local:4"), other.ref, "the key counter is global, the ranking is per project")
        assertEquals(listOf(1.0), f.store.listBacklog(beta).map { it.position })
    }

    @Test
    fun createWritesTheEntryTheActivityRowAndOneEmission() = test { f ->
        val seen = recording(f.store, 1) { f.store.create(alpha, "Wire the board", "body") }

        val update = seen.single()
        assertEquals(first, update.ref)
        val entry = assertNotNull(update.entry, "a create emits the new entry, never a deletion")
        assertEquals(alpha, entry.project)
        assertEquals(TaskState.todo, entry.state)
        assertEquals(1.0, entry.position)
        assertFalse(entry.blocked, "a task created a statement ago can hold no dependency edge")
        assertEquals(1_000L, entry.createdAt)
        assertEquals(1_000L, entry.updatedAt)
        assertTrue(entry.rev > 0, "every emitted row carries the revision its write stamped")
        assertEquals(entry.rev, update.rev)
        assertEquals(entry, f.store.entry(first), "the emitted copy is what a read answers")

        val feed = f.store.activity(first)
        assertEquals(1, feed.size)
        assertEquals(ActivityKind.created, feed.single().kind)
        assertEquals(SqliteTaskStore.CREATED_BY, feed.single().author)
        assertNull(feed.single().text, "the title lives in `tasks`; the feed does not snapshot it")
    }

    @Test
    fun updateLeavesUnnamedFieldsAloneAndBumpsTheRevision() = test { f ->
        f.store.create(alpha, "old title", "old body")
        val before = assertNotNull(f.store.entry(first))
        f.clock = 2_000L

        val titleOnly = assertNotNull(f.store.update(first, title = "new title", body = null))
        assertEquals("new title", titleOnly.title)
        assertEquals("old body", titleOnly.body, "a null argument means leave unchanged, never clear")
        assertEquals(2_000L, titleOnly.updatedAt)

        val bodyOnly = assertNotNull(f.store.update(first, title = null, body = "new body"))
        assertEquals("new title", bodyOnly.title)
        assertEquals("new body", bodyOnly.body)

        val after = assertNotNull(f.store.entry(first))
        assertTrue(after.rev > before.rev, "a tracker edit stamps the entry so the socket re-reads it")
        assertEquals(
            before.updatedAt,
            after.updatedAt,
            "the row that moved is `tasks`; the entry's rank and state were not edited",
        )
    }

    @Test
    fun updateEmitsSoARenameReachesAConnectedBoard() = test { f ->
        f.store.create(alpha, "old title", "")
        val before = assertNotNull(f.store.entry(first))

        val seen = recording(f.store, 1) { f.store.update(first, title = "new title", body = null) }

        val entry = assertNotNull(seen.single().entry)
        assertEquals(first, seen.single().ref)
        assertTrue(entry.rev > before.rev, "the emission carries the freshly stamped revision")
        assertEquals(entry.rev, seen.single().rev)
    }

    @Test
    fun updatingAnUnknownRefIsNullAndSilent() = test { f ->
        f.store.create(alpha, "present", "")

        // One frame for two actions: the silent one first, then a known-loud one. If the update emitted,
        // the single frame collected would be its ref rather than the comment-free rename below.
        val seen = recording(f.store, 1) {
            assertNull(f.store.update(absent, title = "nope", body = null))
            f.store.update(first, title = "renamed", body = null)
        }

        assertEquals(first, seen.single().ref, "the unknown-ref update emitted nothing")
    }

    // --- delete -----------------------------------------------------------------------------------

    @Test
    fun deleteRemovesTheTaskItsEntryItsFeedAndBothDirectionsOfItsEdges() = test { f ->
        f.store.create(alpha, "one", "")
        f.store.create(alpha, "two", "")
        f.store.comment(first, author = "s-1", text = "a note")
        f.store.addDependency(second, first)

        assertTrue(f.store.delete(first))

        assertNull(f.store.get(first))
        assertNull(f.store.entry(first))
        assertEquals(emptyList(), f.store.activity(first), "the feed goes with the task")
        assertEquals(emptyList(), f.store.dependenciesOf(second), "the reverse edge is cascaded too")
        assertEquals(emptyMap(), f.store.dependencyEdges(alpha))
        assertEquals(listOf(second), f.store.listBacklog(alpha).map { it.ref })
    }

    @Test
    fun deleteEmitsANullEntryAndReStampsWhatItUnblocked() = test { f ->
        f.store.create(alpha, "one", "")
        f.store.create(alpha, "two", "")
        f.store.addDependency(second, first)
        assertTrue(assertNotNull(f.store.entry(second)).blocked, "the dependency is not done yet")

        val seen = recording(f.store, 2) { assertTrue(f.store.delete(first)) }

        val removal = seen.first { it.ref == first }
        assertNull(removal.entry, "a null entry is what the transport turns into `task_removed`")
        assertTrue(removal.rev > 0)

        val freed = seen.first { it.ref == second }
        val entry = assertNotNull(freed.entry)
        assertFalse(entry.blocked, "the re-stamp is read AFTER the edge is gone, so blocked is the new one")
        assertEquals(entry.rev, freed.rev)
    }

    @Test
    fun aMutatorWhoseTransactionRollsBackPublishesNothingItStaged() = test { f ->
        // The rule the whole [TaskUpdateOutbox] exists for: a subscriber must never see a change a
        // rollback then takes back. `delete` is where it is REACHABLE — it stages the null-entry removal
        // and only then re-stamps the dependents it unblocked, so a throw in that tail rolls the delete
        // back with an update already staged. (Publishing from inside the transaction, as every mutator
        // but `renormalize` used to, put a `task_removed` on every connected board for a task that is
        // still there — and unlike a state change nothing later corrects it: no row changed, so no
        // further update and no second `task_removed` ever names that ref again.)
        f.store.create(alpha, "one", "")
        f.store.create(alpha, "two", "")
        f.store.addDependency(second, first)

        // A value no production write can produce, and the one read on the delete's tail that refuses
        // it: `entryLocked` parses `backlog_entries.project` with `ProjectId.of`, which throws loudly on
        // a corrupted column rather than making a card vanish.
        f.driver.execute(null, "UPDATE backlog_entries SET project = 'not-a-uuid' WHERE task_ref = '${second.value}'", 0)

        val seen = recording(f.store, 1) {
            assertFailsWith<IllegalArgumentException> { f.store.delete(first) }
            // The known-loud action: a single frame recorded means the rolled-back delete produced none.
            f.store.update(first, title = "still here", body = null)
        }

        val only = seen.single()
        assertEquals(first, only.ref)
        assertNotNull(
            only.entry,
            "the one frame is the rename, not the removal the rolled-back delete staged",
        )
        assertNotNull(f.store.get(first), "and the row really is still there — the transaction rolled back")
        assertNotNull(f.store.entry(first))
    }

    @Test
    fun deletingAnUnknownRefIsFalseAndSilent() = test { f ->
        f.store.create(alpha, "present", "")

        val seen = recording(f.store, 1) {
            assertFalse(f.store.delete(absent))
            f.store.update(first, title = "renamed", body = null)
        }

        assertEquals(first, seen.single().ref, "the unknown-ref delete emitted nothing")
        assertNotNull(f.store.get(first), "and removed nothing")
    }

    // --- activity ---------------------------------------------------------------------------------

    @Test
    fun theFeedIsOrderedAppendOnlyAndCarriesNonZeroIds() = test { f ->
        f.store.create(alpha, "one", "")
        f.clock = 2_000L
        val comment = assertNotNull(f.store.comment(first, author = "s-1", text = "first note"))
        f.clock = 3_000L
        val linked = assertNotNull(f.store.appendActivity(first, ActivityKind.linked, author = "s-2"))

        assertTrue(comment.id > 0, "lastActivityId is read inside the insert's own transaction")
        assertTrue(linked.id > comment.id, "ids are the feed's append-only order")

        val feed = f.store.activity(first)
        assertEquals(listOf(ActivityKind.created, ActivityKind.comment, ActivityKind.linked), feed.map { it.kind })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), feed.map { it.ts })
        assertEquals(comment, feed[1], "the returned row is exactly what a read answers")
        assertEquals(linked, feed[2])
        assertEquals("first note", feed[1].text)
        assertEquals("s-2", feed[2].author)
        assertNull(feed[2].fromState, "only a transition carries states")
    }

    @Test
    fun onAFileBackedDatabaseTheActivityIdStillComesFromTheInsertsOwnConnection() = runBlocking {
        withTimeout(20_000) {
            // The ONE test in the suite that is not over `inMemoryDriver`, and it has to be.
            // `Tasks.sq`'s `lastActivityId` carries a contract that holding the writer mutex is NOT
            // enough for: the read must be inside the same `db.transaction { }` as its insert, because
            // the native driver routes a transaction-less SELECT to its `query_only` READER POOL, whose
            // connections have never inserted anything. For an EPHEMERAL database the driver makes
            // `readerPool = transactionPool`, so every other test here would pass with the rule broken —
            // a future fourth call site of `appendActivityLocked` placed outside a transaction would
            // ship `id = 0` on every activity row in production with the whole suite green.
            withTempDbDir { dir ->
                val driver = NativeSqliteDriver(
                    schema = KotgentDatabase.Schema,
                    name = "tasks-activity-test.db",
                    onConfiguration = { it.copy(extendedConfig = it.extendedConfig.copy(basePath = dir)) },
                )
                try {
                    val store = SqliteTaskStore.using(driver) { 1_000L }
                    store.create(alpha, "one", "")
                    val comment = assertNotNull(store.comment(first, author = "s-1", text = "a note"))

                    assertTrue(comment.id > 0, "the id is the insert's own rowid, not the reader pool's 0")
                    assertEquals(
                        listOf(comment.id),
                        store.activity(first).filter { it.kind == ActivityKind.comment }.map { it.id },
                        "…and it is the id the feed reads back, so the detail view can key on it",
                    )

                    // The other half, and what gives the assertion above its teeth: the SAME query run
                    // WITHOUT a transaction answers 0 on this database. Should a future driver stop
                    // doing that, this line fails and `Tasks.sq`'s contract can be relaxed — which is
                    // the signal worth having, rather than a comment nothing checks.
                    assertEquals(
                        0L,
                        KotgentDatabase(driver).tasksQueries.lastActivityId().executeAsOne(),
                        "a transaction-less last_insert_rowid() is answered by a connection that never inserted",
                    )
                } finally {
                    driver.close()
                }
            }
        }
    }

    @Test
    fun anActivityRowForAnUnknownRefIsNullAndWritesNothing() = test { f ->
        assertNull(f.store.comment(absent, author = "s-1", text = "into the void"))
        assertNull(f.store.appendActivity(absent, ActivityKind.linked, author = "s-1"))
        assertEquals(emptyList(), f.store.activity(absent))
    }

    // --- startIfTodo ------------------------------------------------------------------------------

    @Test
    fun startIfTodoAdvancesExactlyOnceAndOnlyFromTodo() = test { f ->
        f.store.create(alpha, "one", "")
        f.clock = 2_000L

        assertTrue(f.store.startIfTodo(first), "the first caller moves the row")
        assertEquals(TaskState.in_progress, assertNotNull(f.store.entry(first)).state)
        assertEquals(2_000L, assertNotNull(f.store.entry(first)).updatedAt)

        assertFalse(f.store.startIfTodo(first), "zero rows is NORMAL — the task had already started")
        assertFalse(f.store.startIfTodo(absent), "and an unknown ref is the same non-error answer")
    }

    @Test
    fun startIfTodoEmitsOnlyWhenItActuallyMovedTheRow() = test { f ->
        f.store.create(alpha, "one", "")
        f.store.create(alpha, "two", "")

        val seen = recording(f.store, 2) {
            assertTrue(f.store.startIfTodo(first))
            // Silent: already started. If it emitted, the second frame would be `first` again.
            assertFalse(f.store.startIfTodo(first))
            assertTrue(f.store.startIfTodo(second))
        }

        assertEquals(listOf(first, second), seen.map { it.ref })
        assertEquals(TaskState.in_progress, assertNotNull(seen[0].entry).state)
        assertFalse(assertNotNull(seen[0].entry).blocked, "an entry that is not `todo` is never blocked")
    }

    // --- transition -------------------------------------------------------------------------------

    @Test
    fun transitionWritesTheStateAndExactlyOneActivityRowCarryingItsMessage() = test { f ->
        f.store.create(alpha, "one", "")
        f.clock = 2_000L

        val entry = assertNotNull(f.store.transition(first, TaskState.review, author = "s-1", message = "have a look"))
        assertEquals(TaskState.review, entry.state)
        assertEquals(2_000L, entry.updatedAt)

        val feed = f.store.activity(first)
        assertEquals(2, feed.size, "the created row plus exactly one transition row")
        val row = feed.last()
        assertEquals(ActivityKind.transition, row.kind)
        assertEquals("s-1", row.author)
        assertEquals("have a look", row.text)
        assertEquals(TaskState.todo, row.fromState)
        assertEquals(TaskState.review, row.toState)
        assertTrue(row.id > 0)
    }

    @Test
    fun aTransitionWithNoMessageStillRecordsTheMove() = test { f ->
        f.store.create(alpha, "one", "")
        assertNotNull(f.store.transition(first, TaskState.done, author = "board", message = null))

        val row = f.store.activity(first).last()
        assertEquals(ActivityKind.transition, row.kind)
        assertNull(row.text)
        assertEquals(TaskState.done, row.toState)
    }

    @Test
    fun transitionEmitsTheMovedEntryAndReStampsEveryReverseDependent() = test { f ->
        f.store.create(alpha, "dependency", "")
        f.store.create(alpha, "dependent", "")
        f.store.addDependency(second, first)
        assertTrue(assertNotNull(f.store.entry(second)).blocked)

        val seen = recording(f.store, 2) {
            f.store.transition(first, TaskState.done, author = "s-1", message = null)
        }

        val moved = seen.first { it.ref == first }
        assertEquals(TaskState.done, assertNotNull(moved.entry).state)
        assertEquals(assertNotNull(moved.entry).rev, moved.rev)

        val freed = seen.first { it.ref == second }
        assertFalse(assertNotNull(freed.entry).blocked, "closing a dependency unblocks what waited on it")
        assertTrue(assertNotNull(freed.entry).rev > assertNotNull(moved.entry).rev, "each row stamps its own")
    }

    @Test
    fun aTransitionBackToTodoCanMakeAnEntryBlockedAgain() = test { f ->
        f.store.create(alpha, "dependency", "")
        f.store.create(alpha, "dependent", "")
        f.store.addDependency(second, first)
        f.store.startIfTodo(second)
        assertFalse(assertNotNull(f.store.entry(second)).blocked, "only a `todo` entry is ever blocked")

        val seen = recording(f.store, 1) {
            f.store.transition(second, TaskState.todo, author = "board", message = null)
        }

        assertTrue(
            assertNotNull(seen.single().entry).blocked,
            "the emitted entry is re-read after the state write, not copied from before it",
        )
    }

    @Test
    fun transitioningAnUnknownRefIsNullAndSilent() = test { f ->
        f.store.create(alpha, "present", "")

        val seen = recording(f.store, 1) {
            assertNull(f.store.transition(absent, TaskState.done, author = "s-1", message = "x"))
            f.store.update(first, title = "renamed", body = null)
        }

        assertEquals(first, seen.single().ref, "the unknown-ref transition emitted nothing")
        assertEquals(emptyList(), f.store.activity(absent), "and wrote no orphan activity row")
    }

    // --- the delegating members ---------------------------------------------------------------------

    @Test
    fun theBacklogAndDependencyMembersAnswerThroughTheCollaborator() = test { f ->
        f.store.create(alpha, "one", "")
        f.store.create(alpha, "two", "")

        f.store.addDependency(second, first)
        assertEquals(listOf(first), f.store.dependenciesOf(second))
        assertEquals(listOf(second), f.store.dependentsOf(first))
        assertEquals(mapOf(second to listOf(first)), f.store.dependencyEdges(alpha))
        assertTrue(assertNotNull(f.store.entry(second)).blocked)
        assertEquals(first, assertNotNull(f.store.nextCandidate(alpha)).ref, "the blocked one is skipped")

        f.store.removeDependency(second, first)
        assertEquals(emptyList(), f.store.dependenciesOf(second))
        assertFalse(assertNotNull(f.store.entry(second)).blocked)
        assertNull(f.store.nextCandidate(beta), "an empty backlog is the only 'nothing eligible' signal")
    }

    // --- projects ---------------------------------------------------------------------------------

    @Test
    fun aProjectUpsertRefreshesTheNameAndKeepsTheLastSeenPathWhenNoneIsGiven() = test { f ->
        f.store.upsertProject(alpha, "kotgent", "/repo")
        assertEquals("kotgent", assertNotNull(f.store.project(alpha)).name)
        assertEquals("/repo", assertNotNull(f.store.project(alpha)).path)
        assertEquals(1_000L, assertNotNull(f.store.project(alpha)).updatedAt)

        f.clock = 2_000L
        f.store.upsertProject(alpha, "kotgent-renamed", "/repo-wt/feature")
        assertEquals("kotgent-renamed", assertNotNull(f.store.project(alpha)).name)
        assertEquals(
            "/repo-wt/feature",
            assertNotNull(f.store.project(alpha)).path,
            "worktrees share one uuid and deliberately overwrite the one row",
        )
        assertEquals(2_000L, assertNotNull(f.store.project(alpha)).updatedAt)

        f.store.upsertProject(alpha, "kotgent", null)
        assertEquals(
            "/repo-wt/feature",
            assertNotNull(f.store.project(alpha)).path,
            "a caller that reached the project by uuid cannot blank the last-seen checkout",
        )
    }

    @Test
    fun listProjectsIsByNameAndAnUnknownIdIsNull() = test { f ->
        f.store.upsertProject(beta, "zulu", null)
        f.store.upsertProject(alpha, "alfa", "/a")

        assertEquals(listOf("alfa", "zulu"), f.store.listProjects().map { it.name })
        assertEquals(listOf(alpha, beta), f.store.listProjects().map { it.id })
        assertNull(f.store.project(ProjectId.of("11111111-2222-4333-8444-555555555555")))
    }

    @Test
    fun aProjectRowWhoseIdIsNotAUuidIsDroppedRatherThanThrownOutOfARead() = test { f ->
        f.store.upsertProject(alpha, "alfa", "/a")
        // Only a hand edit can produce this row — `upsertProject` takes a ProjectId — so it is written
        // through the generated query directly. The read must degrade instead of failing: `parseOrNull`
        // is the declared read-back rule, and a board that lists nothing because ONE row is corrupt is
        // worse than one that cannot list that row.
        KotgentDatabase(f.driver).projectsQueries.upsertProject("not-a-uuid", "corrupt", "/c", 1L)

        assertEquals(listOf("alfa"), f.store.listProjects().map { it.name })
        assertEquals(listOf(alpha), f.store.listProjects().map { it.id })
    }

    // --- opening and re-opening ---------------------------------------------------------------------

    @Test
    fun aReOpenedStoreResumesTheRevisionAndTheLocalKeyCounter() = test { _ ->
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        val store = SqliteTaskStore.using(driver) { 1L }
        store.create(alpha, "one", "")
        store.create(alpha, "two", "")
        val before = assertNotNull(store.entry(second))

        val reopened = SqliteTaskStore.using(driver) { 2L }
        val third = reopened.create(alpha, "three", "")

        assertEquals(TaskRef("local:3"), third.ref, "the key counter is seeded from maxLocalTaskKey")
        assertTrue(
            assertNotNull(reopened.entry(third.ref)).rev > before.rev,
            "the revision counter is seeded from maxRev and continues past the persisted maximum",
        )
        assertEquals(3.0, assertNotNull(reopened.entry(third.ref)).position, "and the column keeps its ranks")
    }

    @Test
    fun openingOverADatabaseThatPredatesTheTaskTablesCreatesThem() = test { _ ->
        // The sqldelight-gen plugin drops `.sqm` files and leaves Schema.migrate() empty, so a database
        // created before the task layer existed has none of these five tables. `CREATE TABLE IF NOT
        // EXISTS` in init is the whole migration, and unlike an additive column's ALTER it neither fails
        // nor logs when the tables are already there — which the second open below is what pins.
        val driver = inMemoryDriver(preTaskSchema)
        val store = SqliteTaskStore.using(driver) { 1L }
        val created = store.create(alpha, "one", "")
        store.comment(created.ref, author = "s-1", text = "a note")
        store.upsertProject(alpha, "kotgent", "/repo")

        val reopened = SqliteTaskStore.using(driver) { 2L }
        assertEquals(created, reopened.get(created.ref), "the second open is a no-op over the same tables")
        assertEquals(2, reopened.activity(created.ref).size)
        assertEquals(listOf("kotgent"), reopened.listProjects().map { it.name })
    }

    // --- the one copied string ------------------------------------------------------------------------

    @Test
    fun theCreatedRowsAuthorIsTheSameSymbolicActorTheServiceUses() {
        // `TaskTracker.create` takes no author, so the store has to name one. It spells the constant out
        // rather than importing `io.kotgent.daemon` (the layering runs daemon -> store), and this is the
        // assertion that keeps the copy from drifting.
        assertEquals(TaskService.BOARD_AUTHOR, SqliteTaskStore.CREATED_BY)
    }

    /**
     * A throwaway directory for the one file-backed database this suite opens, deleted with everything
     * SQLite left in it (the `.db`, and the `-wal` / `-shm` a WAL journal adds).
     */
    private inline fun withTempDbDir(block: (String) -> Unit) {
        val dir = memScoped {
            val template = "/tmp/kotgent-taskstore-test-XXXXXX"
            val encoded = template.encodeToByteArray()
            val chars = allocArray<ByteVar>(encoded.size + 1)
            encoded.forEachIndexed { index, byte -> chars[index] = byte }
            chars[encoded.size] = 0
            mkdtemp(chars)?.toKString() ?: error("could not create the task-store test directory")
        }
        try {
            block(dir)
        } finally {
            val handle = opendir(dir)
            if (handle != null) {
                val names = buildList {
                    while (true) {
                        val entry = readdir(handle) ?: break
                        val name = entry.pointed.d_name.toKString()
                        if (name != "." && name != "..") add(name)
                    }
                }
                closedir(handle)
                for (name in names) unlink("$dir/$name")
            }
            rmdir(dir)
        }
    }

    /** A database whose schema predates the whole task layer — no `tasks`, `backlog_*` or `projects`. */
    private val preTaskSchema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(
                null,
                "CREATE TABLE events (session_id TEXT NOT NULL, seq INTEGER NOT NULL, ts INTEGER NOT NULL, " +
                    "type TEXT NOT NULL, source TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY (session_id, seq))",
                0,
            )
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }
}
