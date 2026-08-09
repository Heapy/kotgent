package io.kotgent.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.ProjectId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.db.KotgentDatabase
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session→task link columns (Task 10): [EventStore.setTaskRef], [EventStore.setProjectId] and
 * [EventStore.sessionsHoldingTask], driven against [SqliteEventStore] over in-memory SQLite.
 *
 * These live in their own file rather than in `EventStoreTest` because the task backlog was built by a
 * fleet of parallel agents over exclusively-owned files; the split has no semantic meaning.
 *
 * What each test pins is a rule that is easy to break silently:
 *  - the two setters are TARGETED (the `setArchived`/`setModel` shape), so a concurrent hook append's
 *    `state` / `last_seq` / `provider_session_id` — advanced under the same writer lock — survive them;
 *  - both stamp `rev` and EMIT, because the sidebar's task badge is rendered from the live signal and a
 *    link written by `kotgent task claim` inside a pane has to move it without a reload;
 *  - a full-row `upsert` carrying a null link must NOT clear a stored one (`Sessions.sq` COALESCEs both
 *    columns): the caller may be writing a `SessionMeta` snapshot it read before the link landed;
 *  - the columns are additive, so a database written by a pre-task-layer binary must open and migrate.
 *
 * Every DB/flow interaction is bounded by [withTimeout], like the other store suites.
 */
class EventStoreTaskLinkTest {

    private fun meta(id: SessionId, createdAt: Long = 100L): SessionMeta = SessionMeta(
        id = id,
        name = "test-${id.value}",
        agent = "claude",
        cwd = "/tmp/${id.value}",
        tmuxSession = "kt-${id.value}",
        state = SessionState.running,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private val projectA = ProjectId.of("0F2C7A4E-1C3D-4F7A-9B21-6F0A2D9C1E34")
    private val projectB = ProjectId.of("11111111-2222-4333-8444-555555555555")

    @Test
    fun linkingATaskIsATargetedWriteThatBumpsTheRevisionAndEmitsTheNewRef() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("link01")
            store.upsertSession(meta(sid))
            // Give the row real reducer-owned values, so "targeted" is falsifiable: a full-row write here
            // would clobber exactly these three.
            store.append(sid, AgentEvent.SessionBound(ProviderSessionId("prov-1")), EventSource.hook)
            store.append(sid, AgentEvent.ToolCall("grep"), EventSource.hook)
            val before = store.getSession(sid)!!

            val updates = mutableListOf<SessionUpdate>()
            val collector = launch { store.sessionUpdates.collect { updates.add(it) } }
            yield()

            store.setTaskRef(sid, TaskRef("local:42"), updatedAt = 900L)
            repeat(20) { yield() }

            val after = store.getSession(sid)!!
            assertEquals(TaskRef("local:42"), after.taskRef, "the link is persisted")
            assertEquals(before.state, after.state, "state is untouched")
            assertEquals(before.lastSeq, after.lastSeq, "last_seq is untouched")
            assertEquals(
                before.providerSessionId,
                after.providerSessionId,
                "provider_session_id is untouched",
            )
            assertEquals(900L, after.updatedAt, "the caller's timestamp lands (a link IS activity)")
            assertTrue(after.rev > before.rev, "the write stamps a newer revision")

            assertEquals(1, updates.size, "exactly one signal for one write: $updates")
            assertEquals(TaskRef("local:42"), updates.single().taskRef, "the signal carries the new ref")
            assertEquals(after.rev, updates.single().rev, "…and the committed row's revision")
            assertEquals(before.lastSeq, updates.single().lastSeq, "…and the unchanged reducer fields")
            collector.cancel()
        }
    }

    @Test
    fun aNullRefClearsTheLinkAndTheClearIsBroadcast() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("link02")
            store.upsertSession(meta(sid))
            store.setTaskRef(sid, TaskRef("local:7"), updatedAt = 2L)

            val updates = mutableListOf<SessionUpdate>()
            val collector = launch { store.sessionUpdates.collect { updates.add(it) } }
            yield()

            // The targeted setter is the ONLY way a link goes away — startup reconciliation clears a ref
            // that no longer names a backlog entry through exactly this call.
            store.setTaskRef(sid, null, updatedAt = 3L)
            repeat(20) { yield() }

            assertNull(store.getSession(sid)!!.taskRef, "null clears the column")
            assertEquals(1, updates.size, "the clear emits: $updates")
            assertNull(updates.single().taskRef, "…and the signal is authoritative for the null")
            collector.cancel()
        }
    }

    @Test
    fun pointingASessionAtAnotherTaskOverwritesTheLinkWithNoErrorCase() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("link03")
            store.upsertSession(meta(sid))
            store.setTaskRef(sid, TaskRef("local:1"), updatedAt = 2L)
            // A session works ONE task at a time; re-pointing it is an ordinary overwrite, never a 409.
            store.setTaskRef(sid, TaskRef("local:2"), updatedAt = 3L)

            assertEquals(TaskRef("local:2"), store.getSession(sid)!!.taskRef, "the newer link wins")
            assertEquals(
                emptyList(),
                store.sessionsHoldingTask(TaskRef("local:1")).map { it.id },
                "and the session stops holding the old task",
            )
        }
    }

    @Test
    fun aFullRowUpsertCarryingNullLinkColumnsNeverClearsThem() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("link04")
            store.upsertSession(meta(sid))
            store.setTaskRef(sid, TaskRef("local:9"), updatedAt = 2L)
            store.setProjectId(sid, projectA, updatedAt = 3L)

            // A caller writing a SessionMeta it snapshotted BEFORE the link landed — the defensive case
            // `Sessions.sq`'s COALESCE(excluded.x, sessions.x) exists for. Note the daemon really does this:
            // the Reconciler re-upserts metas it read moments earlier.
            store.upsertSession(meta(sid).copy(state = SessionState.ready))

            val after = store.getSession(sid)!!
            assertEquals(TaskRef("local:9"), after.taskRef, "a null in the snapshot keeps the stored link")
            assertEquals(projectA, after.projectId, "…and the stored project")
            assertEquals(SessionState.ready, after.state, "while the rest of the row is refreshed as usual")

            // A NON-null value in the snapshot still wins — COALESCE only defends against the null.
            store.upsertSession(meta(sid).copy(taskRef = TaskRef("local:10"), projectId = projectB))
            val replaced = store.getSession(sid)!!
            assertEquals(TaskRef("local:10"), replaced.taskRef, "a carried ref overwrites")
            assertEquals(projectB, replaced.projectId, "a carried project overwrites")
        }
    }

    @Test
    fun theProjectIsATargetedWriteWithTheSameEmissionContract() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("proj01")
            store.upsertSession(meta(sid))
            store.append(sid, AgentEvent.SessionBound(ProviderSessionId("prov-2")), EventSource.hook)
            val before = store.getSession(sid)!!

            val updates = mutableListOf<SessionUpdate>()
            val collector = launch { store.sessionUpdates.collect { updates.add(it) } }
            yield()

            store.setProjectId(sid, projectA, updatedAt = 500L)
            store.setProjectId(sid, null, updatedAt = 600L)
            repeat(20) { yield() }

            val after = store.getSession(sid)!!
            assertNull(after.projectId, "the second write cleared it")
            assertEquals(before.lastSeq, after.lastSeq, "last_seq is untouched")
            assertEquals(
                before.providerSessionId,
                after.providerSessionId,
                "provider_session_id is untouched",
            )
            assertTrue(after.rev > before.rev, "both writes stamped revisions")

            assertEquals(2, updates.size, "one signal per write: $updates")
            assertEquals(projectA, updates.first().projectId, "the set is broadcast")
            assertNull(updates.last().projectId, "and so is the clear")
            collector.cancel()
        }
    }

    @Test
    fun aProjectIdIsStoredLowerCasedAndAnUnreadableOneDegradesToNoProject() = runBlocking {
        withTimeout(20_000) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            val store = SqliteEventStore.using(driver, now = { 1L })
            val sid = SessionId("proj02")
            store.upsertSession(meta(sid))

            // ProjectId normalizes case on the way IN (SQLite compares TEXT binary, so two spellings of one
            // uuid would key two backlogs), which is why the mixed-case literal above reads back lowercased.
            store.setProjectId(sid, projectA, updatedAt = 2L)
            assertEquals(
                "0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34",
                store.getSession(sid)!!.projectId!!.value,
                "the stored value is the lower-cased uuid",
            )

            // A column somebody edited by hand: the READ must not throw. `toMeta` uses parseOrNull, so an
            // unparseable value degrades honestly to "no project".
            driver.execute(null, "UPDATE sessions SET project_id = 'not-a-uuid' WHERE id = '${sid.value}'", 0)
            assertNull(store.getSession(sid)!!.projectId, "an unparseable project reads as null, not a throw")
        }
    }

    @Test
    fun anUnreadableTaskRefDegradesToNoTaskInsteadOfBreakingEverySessionRead() = runBlocking {
        withTimeout(20_000) {
            // The same rule as the project column on the adjacent line of `toMeta`, and it matters MORE
            // here: `task_ref` is documented as a loose REFERENCE rather than a foreign key, so a hand
            // edit, a restored backup or a future external tracker's charset can leave a value TaskRef's
            // constructor rejects. Reading it through the constructor would make `toMeta` throw, which
            // takes down `listSessions()` — and `Reconciler.reconcile` calls that BEFORE the daemon binds
            // its server, so one bad cell would stop the daemon from starting at all, naming no row.
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            val store = SqliteEventStore.using(driver, now = { 1L })
            val healthy = SessionId("ref-ok")
            val corrupt = SessionId("ref-bad")
            store.upsertSession(meta(healthy))
            store.upsertSession(meta(corrupt))
            store.setTaskRef(healthy, TaskRef("local:7"), updatedAt = 2L)
            store.setTaskRef(corrupt, TaskRef("local:8"), updatedAt = 3L)

            // Values no production write can produce: a bare word (no ':' half) and a charset the ref
            // rejects precisely because a ref travels in URLs and in argv.
            driver.execute(null, "UPDATE sessions SET task_ref = '../etc' WHERE id = '${corrupt.value}'", 0)

            assertNull(store.getSession(corrupt)!!.taskRef, "an unparseable ref reads as null, not a throw")
            assertEquals(
                listOf(null, TaskRef("local:7")),
                store.listSessions().sortedBy { it.id.value }.map { it.taskRef },
                "…and the whole list still reads, the healthy row's link included",
            )

            // The hand-built SessionUpdate in `append` reads the same column and owes the same rule: the
            // pre-transaction row is what it carries, so a corrupt cell there would throw out of a hook.
            val updates = mutableListOf<SessionUpdate>()
            val collector = launch { store.sessionUpdates.collect { updates.add(it) } }
            yield()
            store.append(corrupt, AgentEvent.ToolCall("grep"), EventSource.hook)
            repeat(20) { yield() }
            assertEquals(1, updates.size, "the append still emits: $updates")
            assertNull(updates.single().taskRef, "…carrying 'no task' rather than throwing")
            collector.cancel()
        }
    }

    @Test
    fun everySessionLinkedToATaskIsReturnedOldestFirst() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val younger = SessionId("hold-b")
            val older = SessionId("hold-a")
            val other = SessionId("hold-c")
            store.upsertSession(meta(younger, createdAt = 200L))
            store.upsertSession(meta(older, createdAt = 100L))
            store.upsertSession(meta(other, createdAt = 150L))

            // No exclusivity by design: kotgent cannot enforce one worker per task, so two sessions holding
            // the same ref is the normal case, not a conflict.
            store.setTaskRef(younger, TaskRef("local:5"), updatedAt = 2L)
            store.setTaskRef(older, TaskRef("local:5"), updatedAt = 3L)
            store.setTaskRef(other, TaskRef("local:6"), updatedAt = 4L)

            assertEquals(
                listOf(older, younger),
                store.sessionsHoldingTask(TaskRef("local:5")).map { it.id },
                "ordered by created_at, oldest first",
            )
            assertEquals(
                listOf(other),
                store.sessionsHoldingTask(TaskRef("local:6")).map { it.id },
                "and scoped to the ref asked for",
            )
            assertEquals(
                emptyList(),
                store.sessionsHoldingTask(TaskRef("local:404")).map { it.id },
                "a ref nobody holds answers empty rather than throwing",
            )
            // The rows are full metas, not bare ids — the task detail view renders them.
            assertEquals("test-hold-a", store.sessionsHoldingTask(TaskRef("local:5")).first().name)
        }
    }

    @Test
    fun aLinkWriteForASessionThatDoesNotExistIsASilentNoOp() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val missing = SessionId("ghost")

            val updates = mutableListOf<SessionUpdate>()
            val collector = launch { store.sessionUpdates.collect { updates.add(it) } }
            yield()

            store.setTaskRef(missing, TaskRef("local:1"), updatedAt = 2L) // must not throw
            store.setProjectId(missing, projectA, updatedAt = 3L) // nor this
            repeat(20) { yield() }

            assertNull(store.getSession(missing), "no row is conjured")
            assertTrue(updates.isEmpty(), "and nothing is broadcast for a row that does not exist: $updates")
            collector.cancel()
        }
    }

    @Test
    fun theInitMigrationAddsTheTaskLinkColumnsToAPreExistingTable() = runBlocking {
        withTimeout(20_000) {
            // A database written by a binary from before the task layer: it already has `archived` and `rev`
            // (0.6.0) but neither link column. The in-memory create() path never exercises the two ALTERs.
            val driver = inMemoryDriver(preTaskLinkSchema)
            val store = SqliteEventStore.using(driver, now = { 1L })
            val sid = SessionId("mig-task")
            store.upsertSession(meta(sid)) // would fail with "no such column: task_ref" without the ALTER
            assertNull(store.getSession(sid)!!.taskRef, "an existing row reads as 'no task'")
            assertNull(store.getSession(sid)!!.projectId, "…and 'no project', which is exactly true")

            store.setTaskRef(sid, TaskRef("local:3"), updatedAt = 2L)
            store.setProjectId(sid, projectA, updatedAt = 3L)
            assertEquals(TaskRef("local:3"), store.getSession(sid)!!.taskRef, "both columns are writable")
            assertEquals(projectA, store.getSession(sid)!!.projectId)
            assertEquals(
                listOf(sid),
                store.sessionsHoldingTask(TaskRef("local:3")).map { it.id },
                "and queryable after the migration",
            )

            // Re-opening over the now-migrated DB must see the columns via PRAGMA table_info and skip the
            // ALTER — a fired duplicate-column ALTER makes sqliter log a SQLITE_ERROR stack trace on every
            // daemon start for a pure no-op (and here would propagate as a failure).
            val reopened = SqliteEventStore.using(driver, now = { 4L })
            assertEquals(
                TaskRef("local:3"),
                reopened.getSession(sid)!!.taskRef,
                "a second open over the migrated DB still reads the link",
            )
        }
    }

    /**
     * The `sessions`/`events` schema BEFORE `task_ref` / `project_id` — i.e. a 0.6.0 database, `archived`
     * and `rev` included — so this exercises only the two ALTERs Task 10's columns need.
     */
    private val preTaskLinkSchema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(
                null,
                "CREATE TABLE events (session_id TEXT NOT NULL, seq INTEGER NOT NULL, ts INTEGER NOT NULL, " +
                    "type TEXT NOT NULL, source TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY (session_id, seq))",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, tags TEXT NOT NULL, " +
                    "agent TEXT NOT NULL, provider_session_id TEXT, model TEXT, cli_version TEXT, cli_path TEXT, " +
                    "cwd TEXT NOT NULL, repository TEXT, worktree TEXT, branch TEXT, tmux_session TEXT NOT NULL, " +
                    "pane_id TEXT, state TEXT NOT NULL, state_source TEXT, last_seq INTEGER NOT NULL, " +
                    "read_cursor INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
                    "archived INTEGER NOT NULL DEFAULT 0, rev INTEGER NOT NULL DEFAULT 0)",
                0,
            )
            driver.execute(null, "CREATE INDEX events_session_seq ON events(session_id, seq)", 0)
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
