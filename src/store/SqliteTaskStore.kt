package io.kotgent.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.db.KotgentDatabase
import io.kotgent.task.ActivityKind
import io.kotgent.task.ArchivedProjectException
import io.kotgent.task.BacklogEntry
import io.kotgent.task.MoveTarget
import io.kotgent.task.ProjectRecord
import io.kotgent.task.ProjectRegistration
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

class SqliteTaskStore private constructor(
    driver: SqlDriver,
    private val now: () -> Long,
) : TaskStore {

    private val db: KotgentDatabase = KotgentDatabase(driver)
    private val tasks get() = db.tasksQueries
    private val backlog get() = db.backlogQueries
    private val projects get() = db.projectsQueries

    private val mutex = Mutex()

    private var revCounter: Long = 0

    private var localKeyCounter: Long = 0

    private val _taskUpdates = MutableSharedFlow<TaskUpdate>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val taskUpdates: SharedFlow<TaskUpdate> get() = _taskUpdates

    private val nextRev: () -> Long = { ++revCounter }

    private val outbox: TaskUpdateOutbox = TaskUpdateOutbox { _taskUpdates.tryEmit(it) }

    val dependencies: BacklogDependencies = BacklogDependencies(backlog, mutex, nextRev, outbox, now)

    val ordering: BacklogOrdering = BacklogOrdering(backlog, mutex, dependencies, nextRev, outbox, now)

    init {
        for (statement in CREATE_TABLES_IF_NOT_EXISTS) driver.execute(null, statement, 0)
        // A table created before the tombstone existed needs the column added; the guard is `hasColumn`'s,
        // and Migrations.kt says why it is not tidiness.
        if (!driver.hasColumn("projects", "archived")) {
            driver.execute(null, "ALTER TABLE projects ADD COLUMN archived INTEGER NOT NULL DEFAULT 0", 0)
        }
        revCounter = backlog.maxRev().executeAsOne()
        // The native driver sends transaction-less reads to a different connection; seed and read together.
        db.transaction {
            tasks.raiseLocalKeyHighWater(tasks.maxLocalTaskKey().executeAsOne())
            localKeyCounter = tasks.localKeyHighWater().executeAsOne()
        }
    }


    override val id: String get() = TaskRef.LOCAL_TRACKER

    override suspend fun list(project: ProjectId): List<Task> = mutex.withLock {
        tasks.selectTasksByProject(project.value) { id, title, body, _, updatedAt ->
            Task(ref = TaskRef(id), title = title, body = body, url = null, updatedAt = updatedAt)
        }.executeAsList()
    }

    override suspend fun get(ref: TaskRef): Task? = mutex.withLock { taskLocked(ref) }

    override suspend fun create(
        project: ProjectId,
        title: String,
        body: String,
        author: String,
    ): Task = mutex.withLock {
        outbox.publishing {
            val key = localKeyCounter + 1
            val ref = TaskRef("${TaskRef.LOCAL_TRACKER}:$key")
            val ts = now()
            var refused = false
            db.transaction {
                // The tombstone is read by the INSERT's own transaction, exactly as upsertProject reads
                // it: the caller resolved this project in an earlier call and holds no lock in between,
                // so a delete landing in that gap would otherwise file a card into a project the board
                // no longer lists. Refusing here is what makes the check and the write one decision.
                val archived = projects.selectProjectArchived(project.value).executeAsOneOrNull()
                if (archived != null && archived != 0L) {
                    refused = true
                    return@transaction
                }
                val rev = nextRev()
                tasks.raiseLocalKeyHighWater(key)
                val position = positionForEnd(backlog.maxPosition(project.value).executeAsOne().MAX)
                tasks.insertTask(ref.value, title, body, ts, ts)
                backlog.insertEntry(ref.value, project.value, position, TaskState.todo.name, ts, ts, rev)
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
            // Thrown outside the transaction, so the rollback is SQLite's ordinary empty commit rather
            // than an exception unwinding through it, and `publishing` clears its staging without ever
            // emitting — nothing was staged, because the check runs before the first write.
            if (refused) throw ArchivedProjectException(project)
            localKeyCounter = key
            Task(ref = ref, title = title, body = body, url = null, updatedAt = ts)
        }
    }

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

    override suspend fun delete(ref: TaskRef): Boolean = mutex.withLock {
        outbox.publishing {
            var removed = false
            db.transaction {
                if (!existsLocked(ref)) return@transaction
                // Read reverse dependents before deleting the edges, then emit their newly derived state.
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


    override suspend fun entry(ref: TaskRef): BacklogEntry? = mutex.withLock { dependencies.entryLocked(ref) }

    override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> =
        mutex.withLock { dependencies.listBacklogLocked(project) }

    override suspend fun nextCandidate(project: ProjectId): BacklogEntry? =
        mutex.withLock { dependencies.nextCandidateLocked(project) }


    override suspend fun startIfTodo(ref: TaskRef, requireLiveProject: Boolean): Boolean = mutex.withLock {
        outbox.publishing { startIfTodoLocked(ref, requireLiveProject) }
    }

    private fun startIfTodoLocked(ref: TaskRef, requireLiveProject: Boolean): Boolean {
        val ts = now()
        val rev = nextRev()
        var changed = false
        db.transaction {
            // The tombstone rides in the WHERE rather than being read first: a selection that checked the
            // project and then wrote would be the same race with more steps.
            changed = if (requireLiveProject) {
                backlog.startIfTodoInLiveProject(ts, rev, ref.value).value > 0L
            } else {
                backlog.startIfTodo(ts, rev, ref.value).value > 0L
            }
            if (!changed) return@transaction
            dependencies.entryLocked(ref)?.let { outbox.stage(TaskUpdate(ref, it, it.rev)) }
            dependencies.restampDependentsLocked(ref)
        }
        return changed
    }

    override suspend fun transition(
        ref: TaskRef,
        to: TaskState,
        author: String,
        message: String?,
    ): BacklogEntry? = mutex.withLock {
        outbox.publishing { transitionLocked(ref, to, author, message) }
    }

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
            after = dependencies.entryLocked(ref)
            after?.let { outbox.stage(TaskUpdate(ref, it, it.rev)) }
            dependencies.restampDependentsLocked(ref)
        }
        return after
    }

    override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = ordering.move(ref, target)


    override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> =
        mutex.withLock { dependencies.dependenciesOfLocked(ref) }

    override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> =
        mutex.withLock { dependencies.dependentsOfLocked(ref) }

    override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> =
        mutex.withLock { dependencies.edgesLocked(project) }

    override suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef) {
        // The collaborator owns the same non-reentrant mutex.
        dependencies.add(ref, dependsOn)
    }

    override suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef) {
        dependencies.remove(ref, dependsOn)
    }


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


    override suspend fun upsertProject(id: ProjectId, name: String, path: String?): ProjectRegistration =
        mutex.withLock {
            var outcome = ProjectRegistration.registered
            // One transaction, so the tombstone read is answered by the writer's own connection and no
            // restore can land between the check and the write.
            db.transaction {
                val archived = projects.selectProjectArchived(id.value).executeAsOneOrNull()
                if (archived != null && archived != 0L) {
                    outcome = ProjectRegistration.refusedArchived
                    return@transaction
                }
                projects.upsertProject(id.value, name, path, now())
            }
            outcome
        }

    override suspend fun setProjectArchived(id: ProjectId, archived: Boolean): Boolean = mutex.withLock {
        projects.setProjectArchived(if (archived) 1L else 0L, id.value).value > 0L
    }

    override suspend fun listProjects(archived: Boolean): List<ProjectRecord> = mutex.withLock {
        projects.selectProjectsByArchived(if (archived) 1L else 0L).executeAsList().mapNotNull { row ->
            ProjectId.parseOrNull(row.id)?.let {
                ProjectRecord(it, row.name, row.path, row.updated_at, row.archived != 0L)
            }
        }
    }

    // ONE statement under ONE lock, which is how `TaskStore.listAllProjects`' single-observation
    // contract is met here.
    override suspend fun listAllProjects(): List<ProjectRecord> = mutex.withLock {
        projects.selectAllProjects().executeAsList().mapNotNull { row ->
            ProjectId.parseOrNull(row.id)?.let {
                ProjectRecord(it, row.name, row.path, row.updated_at, row.archived != 0L)
            }
        }
    }

    override suspend fun project(id: ProjectId): ProjectRecord? = mutex.withLock {
        projects.selectProject(id.value) { _, name, path, updatedAt, archived ->
            ProjectRecord(id = id, name = name, path = path, updatedAt = updatedAt, archived = archived != 0L)
        }.executeAsOneOrNull()
    }


    private fun existsLocked(ref: TaskRef): Boolean =
        tasks.selectTask(ref.value) { id, _, _, _, _ -> id }.executeAsOneOrNull() != null

    private fun taskLocked(ref: TaskRef): Task? =
        tasks.selectTask(ref.value) { _, title, body, _, updatedAt ->
            Task(ref = ref, title = title, body = body, url = null, updatedAt = updatedAt)
        }.executeAsOneOrNull()

    private fun appendActivityLocked(
        ref: TaskRef,
        kind: ActivityKind,
        author: String,
        text: String?,
        fromState: TaskState?,
        toState: TaskState?,
        ts: Long,
    ): TaskActivityEntry {
        // Must remain inside the caller's transaction: last_insert_rowid is connection-local.
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

    private fun restampAndStageLocked(ref: TaskRef) {
        val entry = dependencies.entryLocked(ref) ?: return
        val rev = nextRev()
        backlog.restamp(rev, ref.value)
        outbox.stage(TaskUpdate(ref, entry.copy(rev = rev), rev))
    }

    companion object {
        /** Migration DDL for databases predating the task layer; keep synchronized with the .sq schemas. */
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
            "CREATE TABLE IF NOT EXISTS task_local_keys (" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "minted INTEGER NOT NULL)",
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
                "updated_at INTEGER NOT NULL, " +
                "archived INTEGER NOT NULL DEFAULT 0)",
        )

        fun inMemory(now: () -> Long = ::taskStoreEpochMillis): SqliteTaskStore =
            SqliteTaskStore(inMemoryDriver(KotgentDatabase.Schema), now)

        fun using(driver: SqlDriver, now: () -> Long = ::taskStoreEpochMillis): SqliteTaskStore =
            SqliteTaskStore(driver, now)
    }
}

@OptIn(ExperimentalTime::class)
fun taskStoreEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

class TaskUpdateOutbox(private val publish: (TaskUpdate) -> Unit) {

    private val staged: MutableList<TaskUpdate> = mutableListOf()

    fun stage(update: TaskUpdate) {
        staged += update
    }

    /**
     * Publishes only after the surrounding transaction returns successfully, preventing rollback phantoms.
     * Each invocation must contain at most one commit; callers serialize access with the store mutex.
     */
    fun <T> publishing(block: () -> T): T {
        try {
            val result = block()
            for (index in staged.indices) publish(staged[index])
            return result
        } finally {
            staged.clear()
        }
    }
}
