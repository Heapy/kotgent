package io.kotgent.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.db.KotgentDatabase
import io.kotgent.task.ActivityKind
import io.kotgent.task.BacklogEntry
import io.kotgent.task.MoveTarget
import io.kotgent.task.ProjectRecord
import io.kotgent.task.Task
import io.kotgent.task.TaskActivityEntry
import io.kotgent.task.TaskState
import io.kotgent.task.TaskUpdate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The SQLDelight-backed [TaskStore], over the SAME [SqlDriver] the event store uses (the daemon opens one
 * `~/.kotgent/kotgent.db`). It never writes the `sessions` table — see [TaskStore]'s KDoc for why that is
 * a correctness rule and not a preference.
 *
 * ## Why this class has two collaborators
 * [BacklogOrdering] and [BacklogDependencies] each take the generated queries object, this store's
 * [mutex], its revision allocator and its emitter. The honest reason for the split is **parallel
 * execution**: it lets three agents implement the store at once without touching one file. It is not a
 * bad shape on its own — the class would otherwise be ~600 lines covering three unrelated concerns
 * (tracker CRUD, gap-based ranking, a dependency graph) — but the fleet is why it exists, and this KDoc
 * says so rather than pretending to a design rationale it did not have.
 *
 * The collaborators' `…Locked` members are **non-suspending and assume the caller already holds
 * [mutex]** (a Kotlin `Mutex` is not reentrant). Their suspending entry points take it themselves.
 *
 * ## Migration for pre-existing databases
 * The `sqldelight-gen` plugin drops `.sqm` files and leaves `Schema.migrate()` empty, so the five
 * `CREATE`s in `Tasks.sq` / `Backlog.sq` / `Projects.sq` only run on a FRESH database. An existing
 * `kotgent.db` gets them here, in [init], via `CREATE TABLE IF NOT EXISTS` — the [SqlitePushStore]
 * precedent. A whole-table create needs no `PRAGMA table_info` guard (unlike an additive column, whose
 * duplicate-column ALTER makes sqliter log a SQLITE_ERROR stack trace on every start); keep these
 * statements in exact step with the `.sq` DDL.
 *
 * Bodies below are [TODO] on purpose, and **every one of them is Task 7's** — this file has one owner.
 * Task 8 fills [BacklogOrdering] and Task 9 [BacklogDependencies], but the members here that delegate to
 * them are still lines in this file, which those tasks may not touch; each is a one-line hand-off
 * (`ordering.move(...)`, `mutex.withLock { dependencies.…Locked(...) }` for the read path), so the
 * behaviour is theirs and the wiring is Task 7's.
 */
class SqliteTaskStore private constructor(
    driver: SqlDriver,
    private val now: () -> Long,
) : TaskStore {

    private val db: KotgentDatabase = KotgentDatabase(driver)
    private val tasks get() = db.tasksQueries
    private val backlog get() = db.backlogQueries
    private val projects get() = db.projectsQueries

    /** Serializes every write and guards [revCounter] (single writer, the [SqliteEventStore] discipline). */
    private val mutex = Mutex()

    /**
     * The global backlog-row revision counter (`Backlog.sq`'s `rev`). Seeded from `maxRev` in [init]
     * (single-threaded construction), incremented only under [mutex] — every mutator stamps
     * `++revCounter`. A value consumed by a write that touched zero rows is never persisted or emitted,
     * so its post-restart reuse is unobservable.
     */
    private var revCounter: Long = 0

    /**
     * The highest `local:<n>` key minted so far, seeded from `maxLocalTaskKey` and advanced under
     * [mutex]. In memory rather than `MAX(...)+1` per insert so two concurrent creates cannot collide on
     * a key while the second one's SELECT still sees the pre-insert maximum.
     */
    private var localKeyCounter: Long = 0

    /**
     * Hot, non-replaying task-row change signal. Buffered + `DROP_OLDEST` (1024) so a burst — one
     * renormalization of a large project produces one update per row — never suspends the writer holding
     * [mutex]; the events socket guards the rest per-socket with a conflating sender.
     */
    private val _taskUpdates = MutableSharedFlow<TaskUpdate>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val taskUpdates: SharedFlow<TaskUpdate> get() = _taskUpdates

    /** Allocate the next revision. Callers hold [mutex]; handed to the collaborators so the counter has one owner. */
    private val nextRev: () -> Long = { ++revCounter }

    /** Publish one committed change. Non-suspending (`tryEmit`), so it is safe to call under [mutex]. */
    private val emit: (TaskUpdate) -> Unit = { _taskUpdates.tryEmit(it) }

    /**
     * The dependency graph: the four insert refusals, the derived `blocked` read path, `nextCandidate`,
     * and the reverse-dependent re-stamp that every state transition and dependency edit owes. Task 9.
     */
    val dependencies: BacklogDependencies = BacklogDependencies(backlog, mutex, nextRev, emit, now)

    /**
     * Gap-based ranking: `move` plus the renormalize-and-retry-once path. Takes [dependencies] because
     * every entry it emits carries the derived `blocked`, whose one implementation lives there. Task 8.
     */
    val ordering: BacklogOrdering = BacklogOrdering(backlog, mutex, dependencies, nextRev, emit, now)

    init {
        for (statement in CREATE_TABLES_IF_NOT_EXISTS) driver.execute(null, statement, 0)
        revCounter = backlog.maxRev().executeAsOne()
        localKeyCounter = tasks.maxLocalTaskKey().executeAsOne()
    }

    // --- TaskTracker (the built-in "local" tracker) ------------------------------------------------

    override val id: String get() = TaskRef.LOCAL_TRACKER

    override suspend fun list(project: ProjectId): List<Task> = TODO("Task 7: tracker list")

    override suspend fun get(ref: TaskRef): Task? = TODO("Task 7: tracker get")

    /**
     * Mint the next `local:<n>`, and in ONE transaction insert the `tasks` row, its `backlog_entries` row
     * at [io.kotgent.task.positionForEnd] with state `todo`, and its `created` activity row. Emits the
     * new entry on [taskUpdates].
     */
    override suspend fun create(project: ProjectId, title: String, body: String): Task =
        TODO("Task 7: tracker create")

    override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? =
        TODO("Task 7: tracker update")

    /**
     * Cascade: the `tasks` row, its `backlog_entries` row, BOTH directions of `backlog_deps` and its
     * whole activity feed, in one transaction — then emit a null-entry [TaskUpdate].
     *
     * The `sessions` unlink is deliberately NOT here (it is a `sessions` write, hence
     * [io.kotgent.daemon.TaskService]'s, before this call). Re-stamping the reverse dependents that this
     * removal unblocked IS here: read them BEFORE the `backlog_deps` rows go away.
     */
    override suspend fun delete(ref: TaskRef): Boolean = TODO("Task 7: tracker delete + cascade")

    // --- backlog reads (delegated to BacklogDependencies, which owns the derived `blocked`) ---------

    override suspend fun entry(ref: TaskRef): BacklogEntry? = TODO("Task 7: delegate to dependencies")

    override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> =
        TODO("Task 7: delegate to dependencies")

    override suspend fun nextCandidate(project: ProjectId): BacklogEntry? =
        TODO("Task 7: delegate to dependencies")

    // --- backlog writes ----------------------------------------------------------------------------

    override suspend fun startIfTodo(ref: TaskRef): Boolean = TODO("Task 7: conditional todo -> in_progress")

    override suspend fun transition(
        ref: TaskRef,
        to: TaskState,
        author: String,
        message: String?,
    ): BacklogEntry? = TODO("Task 7: state + activity + reverse-dependent re-stamp, one transaction")

    override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? =
        TODO("Task 7: delegate to ordering")

    // --- dependencies (all delegated to BacklogDependencies) ---------------------------------------

    override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> = TODO("Task 7: delegate to dependencies")

    override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> = TODO("Task 7: delegate to dependencies")

    override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> =
        TODO("Task 7: delegate to dependencies")

    override suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef) {
        TODO("Task 7: delegate to dependencies")
    }

    override suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef) {
        TODO("Task 7: delegate to dependencies")
    }

    // --- activity ----------------------------------------------------------------------------------

    override suspend fun comment(ref: TaskRef, author: String, text: String): TaskActivityEntry? =
        TODO("Task 7: activity append")

    override suspend fun appendActivity(
        ref: TaskRef,
        kind: ActivityKind,
        author: String,
        text: String?,
        fromState: TaskState?,
        toState: TaskState?,
    ): TaskActivityEntry? = TODO("Task 7: activity append")

    override suspend fun activity(ref: TaskRef): List<TaskActivityEntry> = TODO("Task 7: activity read")

    // --- projects ----------------------------------------------------------------------------------

    override suspend fun upsertProject(id: ProjectId, name: String, path: String?) {
        TODO("Task 7: project upsert")
    }

    override suspend fun listProjects(): List<ProjectRecord> = TODO("Task 7: project list")

    override suspend fun project(id: ProjectId): ProjectRecord? = TODO("Task 7: project read")

    companion object {
        /**
         * Mirrors of the `Tasks.sq` / `Backlog.sq` / `Projects.sq` DDL, for databases created before the
         * task layer existed. Keep in exact step with those files.
         */
        val CREATE_TABLES_IF_NOT_EXISTS: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS tasks (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "title TEXT NOT NULL, " +
                "body TEXT NOT NULL DEFAULT '', " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS task_activity (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "task_ref TEXT NOT NULL, " +
                "ts INTEGER NOT NULL, " +
                "kind TEXT NOT NULL, " +
                "author TEXT NOT NULL, " +
                "text TEXT, " +
                "from_state TEXT, " +
                "to_state TEXT)",
            "CREATE INDEX IF NOT EXISTS task_activity_by_task ON task_activity(task_ref, id)",
            "CREATE TABLE IF NOT EXISTS backlog_entries (" +
                "task_ref TEXT NOT NULL PRIMARY KEY, " +
                "project TEXT NOT NULL, " +
                "position REAL NOT NULL, " +
                "state TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL, " +
                "rev INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS backlog_entries_by_project ON backlog_entries(project, position)",
            "CREATE TABLE IF NOT EXISTS backlog_deps (" +
                "task_ref TEXT NOT NULL, " +
                "depends_on TEXT NOT NULL, " +
                "PRIMARY KEY (task_ref, depends_on))",
            "CREATE INDEX IF NOT EXISTS backlog_deps_by_dependency ON backlog_deps(depends_on, task_ref)",
            "CREATE TABLE IF NOT EXISTS projects (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "path TEXT, " +
                "updated_at INTEGER NOT NULL)",
        )

        /** In-memory task store (tests / ephemeral). The schema is created by the driver. */
        fun inMemory(now: () -> Long = ::taskStoreEpochMillis): SqliteTaskStore =
            SqliteTaskStore(inMemoryDriver(KotgentDatabase.Schema), now)

        /**
         * Store over a caller-provided driver — the daemon's file-backed one, or a shared in-memory
         * driver to simulate a restart. The caller owns schema creation.
         */
        fun using(driver: SqlDriver, now: () -> Long = ::taskStoreEpochMillis): SqliteTaskStore =
            SqliteTaskStore(driver, now)
    }
}

/** Default wall-clock for task timestamps: epoch millis. Injectable so tests stay deterministic. */
@OptIn(ExperimentalTime::class)
fun taskStoreEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
