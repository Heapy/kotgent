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
import io.kotgent.task.positionForEnd
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The SQLDelight-backed [TaskStore], over the SAME [SqlDriver] the event store uses (the daemon opens one
 * `~/.kotgent/kotgent.db`). It never writes the `sessions` table — see [TaskStore]'s KDoc for why that is
 * a correctness rule and not a preference.
 *
 * ## Why this class has two collaborators
 * [BacklogOrdering] and [BacklogDependencies] each take the generated queries object, this store's
 * [mutex], its revision allocator and its [outbox]. The honest reason for the split is **parallel
 * execution**: it lets three agents implement the store at once without touching one file. It is not a
 * bad shape on its own — the class would otherwise be ~600 lines covering three unrelated concerns
 * (tracker CRUD, gap-based ranking, a dependency graph) — but the fleet is why it exists, and this KDoc
 * says so rather than pretending to a design rationale it did not have.
 *
 * The collaborators' `…Locked` members are **non-suspending and assume the caller already holds
 * [mutex]** (a Kotlin `Mutex` is not reentrant). Their suspending entry points take it themselves.
 *
 * ## What every mutator here owes
 *  - **One transaction per logical change.** A create is three inserts, a delete is four deletes plus a
 *    re-stamp, and a transition is a state write plus its activity row plus a re-stamp — each an
 *    all-or-nothing unit, so a review can never commit without its explanation.
 *  - **A revision and an emission for every row a client can see change.** `taskUpdates` is the only
 *    signal the board gets; a write that moves a row without stamping [nextRev] and emitting leaves a
 *    connected board stale until a reload. A revision consumed by a write that touched zero rows is
 *    never persisted or emitted, so its post-restart reuse is unobservable.
 *  - **The emission happens AFTER the commit, never inside the transaction.** Every mutator's locked
 *    body runs inside [TaskUpdateOutbox.publishing], which stages each change and publishes the batch
 *    only once the body returned normally — see that class for what a rolled-back emission costs.
 *  - **The derived `blocked` is never recomputed here.** Every emitted entry comes from
 *    [BacklogDependencies.entryLocked] (or, for a brand-new task, from the fact that it can have no
 *    dependencies yet), so this file holds no second copy of that rule.
 *
 * ## Migration for pre-existing databases
 * The `sqldelight-gen` plugin drops `.sqm` files and leaves `Schema.migrate()` empty, so the five
 * `CREATE`s in `Tasks.sq` / `Backlog.sq` / `Projects.sq` only run on a FRESH database. An existing
 * `kotgent.db` gets them here, in [init], via `CREATE TABLE IF NOT EXISTS` — the [SqlitePushStore]
 * precedent. A whole-table create needs no `PRAGMA table_info` guard (unlike an additive column, whose
 * duplicate-column ALTER makes sqliter log a SQLITE_ERROR stack trace on every start); keep these
 * statements in exact step with the `.sq` DDL.
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

    /**
     * Where every mutator's [TaskUpdate]s go while its transaction is open, and what publishes them onto
     * [taskUpdates] once it has committed. Shared with the two collaborators so all three obey one rule.
     */
    private val outbox: TaskUpdateOutbox = TaskUpdateOutbox { _taskUpdates.tryEmit(it) }

    /**
     * The dependency graph: the four insert refusals, the derived `blocked` read path, `nextCandidate`,
     * and the reverse-dependent re-stamp that every state transition and dependency edit owes. Task 9.
     */
    val dependencies: BacklogDependencies = BacklogDependencies(backlog, mutex, nextRev, outbox, now)

    /**
     * Gap-based ranking: `move` plus the renormalize-and-retry-once path. Takes [dependencies] because
     * every entry it emits carries the derived `blocked`, whose one implementation lives there. Task 8.
     */
    val ordering: BacklogOrdering = BacklogOrdering(backlog, mutex, dependencies, nextRev, outbox, now)

    init {
        for (statement in CREATE_TABLES_IF_NOT_EXISTS) driver.execute(null, statement, 0)
        revCounter = backlog.maxRev().executeAsOne()
        localKeyCounter = tasks.maxLocalTaskKey().executeAsOne()
    }

    // --- TaskTracker (the built-in "local" tracker) ------------------------------------------------

    override val id: String get() = TaskRef.LOCAL_TRACKER

    override suspend fun list(project: ProjectId): List<Task> = mutex.withLock {
        tasks.selectTasksByProject(project.value) { id, title, body, _, updatedAt ->
            Task(ref = TaskRef(id), title = title, body = body, url = null, updatedAt = updatedAt)
        }.executeAsList()
    }

    override suspend fun get(ref: TaskRef): Task? = mutex.withLock { taskLocked(ref) }

    /**
     * Mint the next `local:<n>`, and in ONE transaction insert the `tasks` row, its `backlog_entries` row
     * at [io.kotgent.task.positionForEnd] with state `todo`, and its `created` activity row. Emits the
     * new entry on [taskUpdates].
     *
     * The emitted entry is BUILT rather than re-read, and its `blocked` is `false` by construction: an
     * entry inserted a statement ago can have no dependency edge, so a `selectEntry` round trip could
     * only answer what is already known here.
     *
     * [author] is written straight through onto the `created` row — the caller's session id, or
     * [io.kotgent.task.TaskTracker.BOARD_AUTHOR] when the caller had none. This store does not second-guess
     * it: who is behind a request is knowable at the route, not here.
     */
    override suspend fun create(
        project: ProjectId,
        title: String,
        body: String,
        author: String,
    ): Task = mutex.withLock {
        outbox.publishing {
            val ref = TaskRef("${TaskRef.LOCAL_TRACKER}:${++localKeyCounter}")
            val ts = now()
            val rev = nextRev()
            db.transaction {
                // Appending consumes no gap, so this is the one placement that can never need a
                // renormalization — see `Ordering.kt`. A project with no rows yet answers `null` and takes 1.0.
                val position = positionForEnd(backlog.maxPosition(project.value).executeAsOne().MAX)
                tasks.insertTask(ref.value, title, body, ts, ts)
                backlog.insertEntry(ref.value, project.value, position, TaskState.todo.name, ts, ts, rev)
                // `text` is deliberately left null: the row records WHEN a task appeared and WHO made it,
                // while the title lives in `tasks` and is always current there. Snapshotting it into an
                // append-only feed would invent content the interface never asked for.
                appendActivityLocked(ref, ActivityKind.created, author, null, null, null, ts)
                outbox.stage(
                    TaskUpdate(
                        ref = ref,
                        entry = BacklogEntry(
                            ref = ref,
                            project = project,
                            position = position,
                            state = TaskState.todo,
                            blocked = false,
                            createdAt = ts,
                            updatedAt = ts,
                            rev = rev,
                        ),
                        rev = rev,
                    ),
                )
            }
            Task(ref = ref, title = title, body = body, url = null, updatedAt = ts)
        }
    }

    /**
     * A tracker edit: `coalesce` in the statement is what makes a `null` argument mean "leave unchanged"
     * rather than "clear" (both columns are `NOT NULL`).
     *
     * The row that MOVED is `tasks`, so `tasks.updated_at` advances and `backlog_entries.updated_at`
     * deliberately does not — the entry's rank and state are untouched, and `updated_at` there is the
     * entry's own activity. What the entry does owe is a fresh `rev` and an emission: the board renders
     * the title, `TaskUpdate` carries no tracker fields, and the socket re-reads the joined row from the
     * revision bump. Without it a rename is invisible until a reload.
     */
    override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? = mutex.withLock {
        outbox.publishing {
            var updated: Task? = null
            db.transaction {
                if (!existsLocked(ref)) return@transaction
                tasks.updateTaskFields(title, body, now(), ref.value)
                updated = taskLocked(ref)
                restampAndStageLocked(ref)
            }
            updated
        }
    }

    /**
     * Cascade: the `tasks` row, its `backlog_entries` row, BOTH directions of `backlog_deps` and its
     * whole activity feed, in one transaction — then emit a null-entry [TaskUpdate].
     *
     * The `sessions` unlink is deliberately NOT here (it is a `sessions` write, hence
     * [io.kotgent.daemon.TaskService]'s, before this call). Re-stamping the reverse dependents that this
     * removal unblocked IS here: read them BEFORE the `backlog_deps` rows go away, and re-stamp them
     * AFTER, so the entry each one is re-emitted with carries the `blocked` the deletion just produced
     * rather than the one the vanished edge used to force.
     */
    override suspend fun delete(ref: TaskRef): Boolean = mutex.withLock {
        outbox.publishing {
            var removed = false
            db.transaction {
                if (!existsLocked(ref)) return@transaction
                val dependents = dependencies.dependentsOfLocked(ref)
                backlog.deleteDepsForTask(ref.value, ref.value)
                backlog.deleteEntry(ref.value)
                tasks.deleteActivityForTask(ref.value)
                tasks.deleteTask(ref.value)
                outbox.stage(TaskUpdate(ref, null, nextRev()))
                for (dependent in dependents) restampAndStageLocked(dependent)
                removed = true
            }
            removed
        }
    }

    // --- backlog reads (delegated to BacklogDependencies, which owns the derived `blocked`) ---------

    override suspend fun entry(ref: TaskRef): BacklogEntry? = mutex.withLock { dependencies.entryLocked(ref) }

    override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> =
        mutex.withLock { dependencies.listBacklogLocked(project) }

    override suspend fun nextCandidate(project: ProjectId): BacklogEntry? =
        mutex.withLock { dependencies.nextCandidateLocked(project) }

    // --- backlog writes ----------------------------------------------------------------------------

    /**
     * The conditional `todo → in_progress`, answered by the statement's own row count — **zero rows is
     * normal, not an error**: it means the task was already `in_progress`/`review`/`done` (or is unknown),
     * and [io.kotgent.daemon.TaskService.link] still makes the session link unconditionally.
     *
     * No reverse-dependent re-stamp: a dependent's `blocked` asks whether this task is `done`, and
     * `todo → in_progress` moves it no closer to that. The entry itself DOES emit — the card changes
     * column, and its own `blocked` drops to false with the state that carried it.
     */
    override suspend fun startIfTodo(ref: TaskRef): Boolean = mutex.withLock {
        outbox.publishing {
            val rev = nextRev()
            val changed = backlog.startIfTodo(now(), rev, ref.value).value > 0L
            if (changed) dependencies.entryLocked(ref)?.let { outbox.stage(TaskUpdate(ref, it, it.rev)) }
            changed
        }
    }

    /**
     * The state write, its `transition` activity row and the reverse-dependent re-stamp, all inside ONE
     * transaction — so `kotgent task review -m "…"` cannot leave a review with no explanation or a
     * comment on an unreviewed task.
     *
     * The re-stamp runs AFTER the state write, which is what makes the dependents' recomputed `blocked`
     * the post-transition one. It runs for EVERY transition, not only for one that reaches or leaves
     * `done`: a redundant emission is invisible under the client's newest-rev-wins rule, while a missing
     * one is a stale blocked marker until a reload — the same conservative trade
     * [BacklogDependencies] makes after a dependency edit.
     */
    override suspend fun transition(
        ref: TaskRef,
        to: TaskState,
        author: String,
        message: String?,
    ): BacklogEntry? = mutex.withLock {
        outbox.publishing { transitionLocked(ref, to, author, message) }
    }

    /**
     * [transition]'s body, extracted so the "unknown ref" exit is a plain `return` rather than a
     * non-local one out of [TaskUpdateOutbox.publishing] — which would skip the publish.
     */
    private fun transitionLocked(
        ref: TaskRef,
        to: TaskState,
        author: String,
        message: String?,
    ): BacklogEntry? {
        val before = dependencies.entryLocked(ref) ?: return null
        val ts = now()
        val rev = nextRev()
        var after: BacklogEntry? = null
        db.transaction {
            backlog.setState(to.name, ts, rev, ref.value)
            appendActivityLocked(ref, ActivityKind.transition, author, message, before.state, to, ts)
            // Re-read rather than copy: `blocked` is derived from the state that was just written, and
            // moving BACK to `todo` can make an entry blocked again.
            after = dependencies.entryLocked(ref)
            after?.let { outbox.stage(TaskUpdate(ref, it, it.rev)) }
            dependencies.restampDependentsLocked(ref)
        }
        return after
    }

    override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = ordering.move(ref, target)

    // --- dependencies (all delegated to BacklogDependencies) ---------------------------------------

    override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> =
        mutex.withLock { dependencies.dependenciesOfLocked(ref) }

    override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> =
        mutex.withLock { dependencies.dependentsOfLocked(ref) }

    override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> =
        mutex.withLock { dependencies.edgesLocked(project) }

    override suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef) {
        // The collaborator's suspending entry points take the store mutex themselves — taking it here
        // too would deadlock, a Kotlin Mutex being non-reentrant.
        dependencies.add(ref, dependsOn)
    }

    override suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef) {
        dependencies.remove(ref, dependsOn)
    }

    // --- activity ----------------------------------------------------------------------------------

    override suspend fun comment(ref: TaskRef, author: String, text: String): TaskActivityEntry? =
        appendActivity(ref, ActivityKind.comment, author, text)

    override suspend fun appendActivity(
        ref: TaskRef,
        kind: ActivityKind,
        author: String,
        text: String?,
        fromState: TaskState?,
        toState: TaskState?,
    ): TaskActivityEntry? = mutex.withLock {
        // Wrapped although it stages nothing today: uniformity is what keeps the rule from drifting — a
        // future emission added here would otherwise be staged and never published.
        outbox.publishing {
            val ts = now()
            var appended: TaskActivityEntry? = null
            db.transaction {
                if (!existsLocked(ref)) return@transaction
                appended = appendActivityLocked(ref, kind, author, text, fromState, toState, ts)
            }
            appended
        }
    }

    /**
     * The feed, oldest first. Emits nothing: an activity row changes no `backlog_entries` row, and the
     * feed deliberately does not ride the events socket — the detail view fetches it.
     */
    override suspend fun activity(ref: TaskRef): List<TaskActivityEntry> = mutex.withLock {
        tasks.selectActivity(ref.value) { id, taskRef, ts, kind, author, text, fromState, toState ->
            TaskActivityEntry(
                id = id,
                ref = TaskRef(taskRef),
                ts = ts,
                kind = ActivityKind.valueOf(kind),
                author = author,
                text = text,
                fromState = fromState?.let(TaskState::valueOf),
                toState = toState?.let(TaskState::valueOf),
            )
        }.executeAsList()
    }

    // --- projects ----------------------------------------------------------------------------------

    override suspend fun upsertProject(id: ProjectId, name: String, path: String?): Unit = mutex.withLock {
        // A null `path` is COALESCEd in the statement, so a caller that reached the project by uuid
        // rather than by walking a directory cannot blank the last-seen checkout.
        projects.upsertProject(id.value, name, path, now())
    }

    /**
     * Every known project, by name.
     *
     * A row whose `id` is not a canonical uuid can only come from a hand edit, and it is DROPPED rather
     * than thrown out of a read: `ProjectId.parseOrNull` is the declared read-back rule, and a board that
     * cannot list any project because one row is corrupt is worse than one that cannot list that row.
     */
    override suspend fun listProjects(): List<ProjectRecord> = mutex.withLock {
        // The generated row type rather than a mapper lambda, because a mapper must answer a non-null
        // value and the whole point here is to drop a row whose id will not parse.
        projects.selectAllProjects().executeAsList().mapNotNull { row ->
            ProjectId.parseOrNull(row.id)?.let { ProjectRecord(it, row.name, row.path, row.updated_at) }
        }
    }

    /** One project. The row is addressed BY [id], so the caller's already-normalized value is the row's. */
    override suspend fun project(id: ProjectId): ProjectRecord? = mutex.withLock {
        projects.selectProject(id.value) { _, name, path, updatedAt ->
            ProjectRecord(id = id, name = name, path = path, updatedAt = updatedAt)
        }.executeAsOneOrNull()
    }

    // --- internals ---------------------------------------------------------------------------------

    /** Whether the tracker knows [ref]. The `tasks` row is what a task's existence means here. */
    private fun existsLocked(ref: TaskRef): Boolean =
        tasks.selectTask(ref.value) { id, _, _, _, _ -> id }.executeAsOneOrNull() != null

    private fun taskLocked(ref: TaskRef): Task? =
        tasks.selectTask(ref.value) { _, title, body, _, updatedAt ->
            Task(ref = ref, title = title, body = body, url = null, updatedAt = updatedAt)
        }.executeAsOneOrNull()

    /**
     * Insert one activity row and report it with the id the insert produced.
     *
     * **Callers must already be inside a `db.transaction { }`**, and holding [mutex] is necessary but NOT
     * sufficient: `lastActivityId` is a plain `SELECT last_insert_rowid()`, which the native driver
     * routes to its `query_only` READER POOL when no transaction is bound to the calling thread — those
     * connections have never inserted anything, so every row would come back with id `0`. See that
     * query's comment in `Tasks.sq`; an in-memory database cannot reproduce it, because there the reader
     * pool IS the transaction pool.
     */
    private fun appendActivityLocked(
        ref: TaskRef,
        kind: ActivityKind,
        author: String,
        text: String?,
        fromState: TaskState?,
        toState: TaskState?,
        ts: Long,
    ): TaskActivityEntry {
        tasks.insertActivity(ref.value, ts, kind.name, author, text, fromState?.name, toState?.name)
        return TaskActivityEntry(
            id = tasks.lastActivityId().executeAsOne(),
            ref = ref,
            ts = ts,
            kind = kind,
            author = author,
            text = text,
            fromState = fromState,
            toState = toState,
        )
    }

    /**
     * Stamp one row a fresh rev and stage it — the shape [BacklogDependencies] uses for a reverse
     * dependent, repeated here because that one is private and this file may not reach into it. The entry
     * is read BEFORE the write and re-staged with the new rev: `restamp` touches no other column, so the
     * copy is exactly what a second `selectEntry` would answer, at one query instead of two. A ref with no
     * row consumes no revision and stages nothing — a null-entry [TaskUpdate] means DELETED, which a
     * re-stamp must never manufacture.
     */
    private fun restampAndStageLocked(ref: TaskRef) {
        val entry = dependencies.entryLocked(ref) ?: return
        val rev = nextRev()
        backlog.restamp(rev, ref.value)
        outbox.stage(TaskUpdate(ref, entry.copy(rev = rev), rev))
    }

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

/**
 * The one-write buffer between a task mutator's transaction and [TaskStore.taskUpdates].
 *
 * ## Why staging, when `tryEmit` would be legal
 * Every mutator in this layer publishes a [TaskUpdate] for each row a client can see change, and every
 * one of them does its writing inside a `db.transaction { }`. Publishing from *inside* that block is
 * legal — `tryEmit` never suspends — but it is wrong: **a subscriber must never see a change a rollback
 * then takes back.** Most such phantoms would self-heal, because the in-memory revision counter keeps
 * advancing and the next write re-emits the row; a rolled-back `create` is the one that never does. No
 * row was committed, so no later [TaskUpdate] and no `task_removed` ever names that ref, and the phantom
 * card sits on every connected board until somebody reloads.
 *
 * ## The contract
 * A mutator [stage]s; [publishing] wraps its whole locked body and hands the staged updates to the
 * publisher, in order, only once that body returned normally. A throw publishes nothing, and either way
 * the buffer is empty afterwards, so a failed mutator cannot leak its updates into the next one.
 *
 * One consequence is deliberate and conservative. A locked body may contain more than one commit —
 * `move` can renormalize a whole column in its own transaction and then write the moved row — and a
 * throw after the first of them publishes NOTHING, even for what committed. That leaves a connected
 * board stale (healed by the next write to those rows, or by a reload) rather than showing positions the
 * database never took, which is the direction this rule exists to choose.
 *
 * Not thread-safe, and does not need to be: every [stage] and every [publishing] runs under the task
 * store's single writer `Mutex`, and [publishing] is never nested — each mutator wraps its locked body
 * exactly once, and a mutator that delegates (`SqliteTaskStore.move` → [BacklogOrdering.move]) delegates
 * the wrapping with it.
 */
class TaskUpdateOutbox(private val publish: (TaskUpdate) -> Unit) {

    private val staged: MutableList<TaskUpdate> = mutableListOf()

    /** Record one change, to be published if — and only if — the surrounding [publishing] block succeeds. */
    fun stage(update: TaskUpdate) {
        staged += update
    }

    /** Run [block], then publish everything it staged, oldest first. A throw discards them instead. */
    fun <T> publishing(block: () -> T): T {
        try {
            val result = block()
            // By index: `publish` is the store's `tryEmit`, but iterating a MutableList this way cannot
            // be tripped by a future publisher that stages something of its own.
            for (index in staged.indices) publish(staged[index])
            return result
        } finally {
            staged.clear()
        }
    }
}
