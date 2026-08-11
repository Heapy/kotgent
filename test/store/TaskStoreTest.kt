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
import io.kotgent.task.TaskTracker
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
import platform.posix.getenv
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

@OptIn(ExperimentalForeignApi::class)
class TaskStoreTest {


    private val alpha = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")
    private val beta = ProjectId.of("7b1d5e90-4a2c-4c11-8e77-2d3f6a8b9c01")

    private val first = TaskRef("local:1")
    private val second = TaskRef("local:2")
    private val absent = TaskRef("local:404")

    private inner class Fixture(val driver: SqlDriver = inMemoryDriver(KotgentDatabase.Schema)) {
        var clock: Long = 1_000L
        val store: SqliteTaskStore = SqliteTaskStore.using(driver) { clock }
    }

    private fun test(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        withTimeout(20_000) { block(Fixture()) }
    }

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
        assertEquals(TaskTracker.BOARD_AUTHOR, feed.single().author)
        assertNull(feed.single().text, "the title lives in `tasks`; the feed does not snapshot it")
    }

    @Test
    fun aCreateRecordsItsAuthorAndFallsBackToTheBoardOnlyWhenThereIsNone() = test { f ->
        val filedByAnAgent = f.store.create(alpha, "an agent's own card", "", author = "s-7")
        val filedByTheBoard = f.store.create(alpha, "the board's card", "")

        assertEquals("s-7", f.store.activity(filedByAnAgent.ref).single().author)
        assertEquals(ActivityKind.created, f.store.activity(filedByAnAgent.ref).single().kind)
        assertEquals(
            TaskTracker.BOARD_AUTHOR,
            f.store.activity(filedByTheBoard.ref).single().author,
            "the default is the board, which is the honest answer only when no session is behind the call",
        )
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

        val seen = recording(f.store, 1) {
            assertNull(f.store.update(absent, title = "nope", body = null))
            f.store.update(first, title = "renamed", body = null)
        }

        assertEquals(first, seen.single().ref, "the unknown-ref update emitted nothing")
    }


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
        f.store.create(alpha, "one", "")
        f.store.create(alpha, "two", "")
        f.store.addDependency(second, first)

        f.driver.execute(null, "UPDATE backlog_entries SET project = 'not-a-uuid' WHERE task_ref = '${second.value}'", 0)

        val seen = recording(f.store, 1) {
            assertFailsWith<IllegalArgumentException> { f.store.delete(first) }
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
    fun startIfTodoReStampsItsReverseDependentsTheWayEveryOtherTransitionDoes() = test { f ->
        f.store.create(alpha, "dependency", "")
        f.store.create(alpha, "dependent", "")
        f.store.addDependency(second, first)
        val before = assertNotNull(f.store.entry(second))

        val seen = recording(f.store, 2) {
            assertTrue(f.store.startIfTodo(first))
            f.store.update(first, title = "the loud one", body = null)
        }

        assertEquals(listOf(first, second), seen.map { it.ref }, "the started row, then what depends on it")
        assertEquals(TaskState.in_progress, assertNotNull(seen[0].entry).state)
        val freed = assertNotNull(seen[1].entry)
        assertTrue(freed.rev > assertNotNull(seen[0].entry).rev, "each row stamps its own revision")
        assertEquals(freed.rev, seen[1].rev)
        assertEquals(freed.rev, assertNotNull(f.store.entry(second)).rev, "and the row really carries it")
        assertTrue(freed.blocked, "an in_progress dependency is still not done, so the marker stays")
        assertEquals(before.updatedAt, freed.updatedAt, "a re-stamp is not an edit — `updated_at` is activity")
    }

    @Test
    fun startIfTodoEmitsOnlyWhenItActuallyMovedTheRow() = test { f ->
        f.store.create(alpha, "one", "")
        f.store.create(alpha, "two", "")

        val seen = recording(f.store, 2) {
            assertTrue(f.store.startIfTodo(first))
            assertFalse(f.store.startIfTodo(first))
            assertTrue(f.store.startIfTodo(second))
        }

        assertEquals(listOf(first, second), seen.map { it.ref })
        assertEquals(TaskState.in_progress, assertNotNull(seen[0].entry).state)
        assertFalse(assertNotNull(seen[0].entry).blocked, "an entry that is not `todo` is never blocked")
    }


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
        KotgentDatabase(f.driver).projectsQueries.upsertProject("not-a-uuid", "corrupt", "/c", 1L)

        assertEquals(listOf("alfa"), f.store.listProjects().map { it.name })
        assertEquals(listOf(alpha), f.store.listProjects().map { it.id })
    }


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
    fun aRefFreedByADeleteIsNeverMintedAgainAfterARestart() = test { _ ->
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        val store = SqliteTaskStore.using(driver) { 1L }
        store.create(alpha, "one", "")
        store.create(alpha, "two", "")
        assertTrue(store.delete(second), "the row carrying the high-water mark goes away")

        val reopened = SqliteTaskStore.using(driver) { 2L }
        val next = reopened.create(alpha, "three", "")

        assertEquals(TaskRef("local:3"), next.ref, "a freed key is spent, not recycled")
        assertEquals("three", assertNotNull(reopened.get(next.ref)).title)
        assertTrue(reopened.delete(next.ref))
        assertEquals(
            TaskRef("local:4"),
            SqliteTaskStore.using(driver) { 3L }.create(alpha, "four", "").ref,
            "deletion may never lower the mark, however many times it is repeated",
        )
    }

    @Test
    fun aDatabaseWhoseKeysPredateTheAllocatorIsSeededFromThem() = test { _ ->
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        val tasks = KotgentDatabase(driver).tasksQueries
        tasks.insertTask("local:1", "one", "", 1L, 1L)
        tasks.insertTask("local:7", "seven", "", 1L, 1L)
        tasks.insertTask("gh:1234", "an adopted ref", "", 1L, 1L)

        assertEquals(
            TaskRef("local:8"),
            SqliteTaskStore.using(driver) { 1L }.create(alpha, "next", "").ref,
            "the seed is the tracker's own maximum, and another tracker's key is not a number here",
        )
    }

    @Test
    fun openingOverADatabaseThatPredatesTheTaskTablesCreatesThem() = test { _ ->
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


    @Test
    fun theTrackersFallbackAuthorIsTheSameSymbolicActorTheServiceUses() {
        assertEquals(TaskService.BOARD_AUTHOR, TaskTracker.BOARD_AUTHOR)
    }

    private inline fun withTempDbDir(block: (String) -> Unit) {
        val dir = memScoped {
            val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
            val template = "$tmp/kotgent-taskstore-test-XXXXXX"
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
