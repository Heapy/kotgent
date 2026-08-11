package io.kotgent.store

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.task.ActivityKind
import io.kotgent.task.BacklogEntry
import io.kotgent.task.DependencyRefusal
import io.kotgent.task.DependencyRefusedException
import io.kotgent.task.MoveTarget
import io.kotgent.task.ProjectRecord
import io.kotgent.task.Task
import io.kotgent.task.TaskActivityEntry
import io.kotgent.task.TaskState
import io.kotgent.task.TaskTracker
import io.kotgent.task.TaskUpdate
import io.kotgent.task.needsRenormalization
import io.kotgent.task.positionBetween
import io.kotgent.task.positionForEnd
import io.kotgent.task.positionForTop
import io.kotgent.task.wouldCycle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FakeTaskStore(
    private val now: () -> Long = { 1_000L },
) : TaskStore {

    private val mutex = Mutex()
    private val projects = LinkedHashMap<ProjectId, ProjectRecord>()
    private val tasks = LinkedHashMap<TaskRef, Task>()
    private val entries = LinkedHashMap<TaskRef, BacklogEntry>()
    private val deps = LinkedHashMap<TaskRef, MutableList<TaskRef>>()
    private val activityRows = mutableListOf<TaskActivityEntry>()
    private var revCounter = 0L
    private var nextKey = 0
    private var activityId = 0L

    override val id: String = TaskRef.LOCAL_TRACKER

    private val updates = MutableSharedFlow<TaskUpdate>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val taskUpdates: SharedFlow<TaskUpdate> = updates

    // Publish only after a compound mutation finishes so subscribers never observe partial derived state.
    private val staged = mutableListOf<TaskUpdate>()

    private fun stage(update: TaskUpdate) {
        staged += update
    }

    private fun <T> publishing(block: () -> T): T {
        try {
            val result = block()
            for (index in staged.indices) updates.tryEmit(staged[index])
            return result
        } finally {
            staged.clear()
        }
    }


    // Seed helpers are used only before the server starts; runtime methods take the mutex.
    fun seedProject(id: ProjectId, name: String, path: String? = null) {
        projects[id] = ProjectRecord(id, name, path, now())
    }

    fun seedTask(
        ref: TaskRef,
        project: ProjectId,
        title: String,
        body: String = "body of $title",
        state: TaskState = TaskState.todo,
        position: Double? = null,
    ) {
        tasks[ref] = Task(ref, title, body, url = null, updatedAt = now())
        entries[ref] = BacklogEntry(
            ref, project, position ?: endPosition(project), state, blocked = false,
            createdAt = now(), updatedAt = now(), rev = ++revCounter,
        )
        ref.key.toIntOrNull()?.let { if (it > nextKey) nextKey = it }
        reseedBlocked()
    }

    fun seedDependency(ref: TaskRef, dependsOn: TaskRef) {
        val edges = deps.getOrPut(ref) { mutableListOf() }
        if (dependsOn !in edges) edges += dependsOn
        reseedBlocked()
    }

    fun seedActivity(
        ref: TaskRef,
        kind: ActivityKind,
        author: String,
        text: String? = null,
        fromState: TaskState? = null,
        toState: TaskState? = null,
    ) {
        activityRows += TaskActivityEntry(++activityId, ref, now(), kind, author, text, fromState, toState)
    }


    /** Harness-only create with a caller-known ref, needed to name the later socket update. */
    suspend fun addTask(
        ref: TaskRef,
        project: ProjectId,
        title: String,
        position: Double? = null,
    ): BacklogEntry = mutex.withLock {
        publishing {
            tasks[ref] = Task(ref, title, "body of $title", url = null, updatedAt = now())
            val row = BacklogEntry(
                ref, project, position ?: endPosition(project), TaskState.todo, blocked = false,
                createdAt = now(), updatedAt = now(), rev = ++revCounter,
            )
            entries[ref] = row
            ref.key.toIntOrNull()?.let { if (it > nextKey) nextKey = it }
            stage(TaskUpdate(ref, row, row.rev))
            row
        }
    }

    suspend fun renormalize(project: ProjectId): Unit = mutex.withLock {
        publishing { renormalizeLocked(project) }
    }


    override suspend fun list(project: ProjectId): List<Task> = mutex.withLock {
        entries.values.filter { it.project == project }
            .mapNotNull { tasks[it.ref] }
            .sortedBy { it.ref.value }
    }

    override suspend fun get(ref: TaskRef): Task? = mutex.withLock { tasks[ref] }

    override suspend fun create(project: ProjectId, title: String, body: String, author: String): Task =
        mutex.withLock {
            publishing {
                val ref = TaskRef("${TaskRef.LOCAL_TRACKER}:${++nextKey}")
                val created = Task(ref, title, body, url = null, updatedAt = now())
                tasks[ref] = created
                val entry = BacklogEntry(
                    ref, project, endPosition(project), TaskState.todo, blocked = false,
                    createdAt = now(), updatedAt = now(), rev = ++revCounter,
                )
                entries[ref] = entry
                activityRows += TaskActivityEntry(
                    ++activityId, ref, now(), ActivityKind.created, author, null, null, null,
                )
                stage(TaskUpdate(ref, entry, entry.rev))
                created
            }
        }

    override suspend fun update(ref: TaskRef, title: String?, body: String?): Task? = mutex.withLock {
        publishing {
            val existing = tasks[ref] ?: return@publishing null
            val updated = existing.copy(
                title = title ?: existing.title, body = body ?: existing.body, updatedAt = now(),
            )
            tasks[ref] = updated
            restampLocked(ref)
            updated
        }
    }

    override suspend fun delete(ref: TaskRef): Boolean = mutex.withLock {
        publishing {
            if (tasks.remove(ref) == null) return@publishing false
            val dependents = dependentsLocked(ref)
            entries.remove(ref)
            deps.remove(ref)
            deps.values.forEach { it.remove(ref) }
            activityRows.removeAll { it.ref == ref }
            // The removal gets the lower revision, matching the real store's observable update order.
            stage(TaskUpdate(ref, null, ++revCounter))
            restampAllLocked(dependents)
            true
        }
    }


    override suspend fun entry(ref: TaskRef): BacklogEntry? = mutex.withLock { entries[ref] }

    override suspend fun listBacklog(project: ProjectId): List<BacklogEntry> = mutex.withLock {
        entries.values.filter { it.project == project }.sortedWith(RANK_ORDER)
    }

    override suspend fun nextCandidate(project: ProjectId): BacklogEntry? = mutex.withLock {
        entries.values
            .filter { it.project == project && it.state == TaskState.todo && !it.blocked }
            .minWithOrNull(RANK_ORDER)
    }


    override suspend fun startIfTodo(ref: TaskRef): Boolean = mutex.withLock {
        publishing {
            val existing = entries[ref] ?: return@publishing false
            if (existing.state != TaskState.todo) return@publishing false
            writeStateLocked(existing, TaskState.in_progress)
            true
        }
    }

    override suspend fun transition(
        ref: TaskRef,
        to: TaskState,
        author: String,
        message: String?,
    ): BacklogEntry? = mutex.withLock {
        publishing {
            val existing = entries[ref] ?: return@publishing null
            activityRows += TaskActivityEntry(
                ++activityId, ref, now(), ActivityKind.transition, author, message, existing.state, to,
            )
            writeStateLocked(existing, to)
        }
    }

    override suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry? = mutex.withLock {
        publishing {
            val existing = entries[ref] ?: return@publishing null
            val neighbour = when (target) {
                is MoveTarget.Before -> target.ref
                is MoveTarget.After -> target.ref
                else -> null
            }
            if (neighbour != null && entries[neighbour]?.project != existing.project) return@publishing null

            val rank = rankForLocked(existing, target) ?: run {
                // Adjacent floating-point ranks eventually collapse; normalize before retrying once.
                renormalizeLocked(existing.project)
                rankForLocked(entries.getValue(ref), target)
                    ?: error("a renormalized column must always subdivide")
            }
            val row = entries.getValue(ref).copy(position = rank, updatedAt = now(), rev = ++revCounter)
            entries[ref] = row
            stage(TaskUpdate(ref, row, row.rev))
            row
        }
    }


    override suspend fun dependenciesOf(ref: TaskRef): List<TaskRef> =
        mutex.withLock { deps[ref].orEmpty().sortedBy { it.value } }

    override suspend fun dependentsOf(ref: TaskRef): List<TaskRef> = mutex.withLock { dependentsLocked(ref) }

    override suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>> = mutex.withLock {
        deps.entries
            .filter { entries[it.key]?.project == project }
            .sortedBy { it.key.value }
            .associateTo(LinkedHashMap()) { entry -> entry.key to entry.value.sortedBy { it.value } }
    }

    override suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef): Unit = mutex.withLock {
        publishing {
            fun refuse(refusal: DependencyRefusal, why: String): Nothing = throw DependencyRefusedException(
                refusal, ref, dependsOn,
                "cannot add '${ref.value}' depends on '${dependsOn.value}': $why (${refusal.name})",
            )
            if (ref == dependsOn) refuse(DependencyRefusal.self, "a task cannot depend on itself")
            val from = entries[ref] ?: refuse(DependencyRefusal.unknownRef, "no such task '${ref.value}'")
            val to = entries[dependsOn]
                ?: refuse(DependencyRefusal.unknownRef, "no such task '${dependsOn.value}'")
            if (from.project != to.project) {
                refuse(DependencyRefusal.crossProject, "they belong to different projects")
            }
            val edges = deps.getOrPut(ref) { mutableListOf() }
            if (dependsOn in edges) return@publishing
            if (wouldCycle(edgeSnapshotLocked(), ref, dependsOn)) {
                refuse(DependencyRefusal.cycle, "it would close a ring")
            }
            edges += dependsOn
            restampAfterEditLocked(ref)
        }
    }

    override suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef): Unit = mutex.withLock {
        publishing {
            if (deps[ref]?.remove(dependsOn) != true) return@publishing
            restampAfterEditLocked(ref)
        }
    }


    override suspend fun comment(ref: TaskRef, author: String, text: String): TaskActivityEntry? =
        mutex.withLock {
            if (ref !in entries) return@withLock null
            val row = TaskActivityEntry(++activityId, ref, now(), ActivityKind.comment, author, text, null, null)
            activityRows += row
            row
        }

    override suspend fun appendActivity(
        ref: TaskRef,
        kind: ActivityKind,
        author: String,
        text: String?,
        fromState: TaskState?,
        toState: TaskState?,
    ): TaskActivityEntry? = mutex.withLock {
        if (ref !in entries) return@withLock null
        val row = TaskActivityEntry(++activityId, ref, now(), kind, author, text, fromState, toState)
        activityRows += row
        row
    }

    override suspend fun activity(ref: TaskRef): List<TaskActivityEntry> =
        mutex.withLock { activityRows.filter { it.ref == ref } }


    override suspend fun upsertProject(id: ProjectId, name: String, path: String?): Unit = mutex.withLock {
        val existing = projects[id]
        projects[id] = ProjectRecord(id, name, path ?: existing?.path, now())
    }

    override suspend fun listProjects(): List<ProjectRecord> =
        mutex.withLock { projects.values.sortedBy { it.name } }

    override suspend fun project(id: ProjectId): ProjectRecord? = mutex.withLock { projects[id] }


    private fun writeStateLocked(existing: BacklogEntry, to: TaskState): BacklogEntry {
        val stamped = existing.copy(state = to, updatedAt = now(), rev = ++revCounter)
        val row = stamped.copy(blocked = derivedBlocked(stamped))
        entries[existing.ref] = row
        stage(TaskUpdate(row.ref, row, row.rev))
        restampDependentsLocked(existing.ref)
        return row
    }

    private fun restampLocked(ref: TaskRef) {
        val existing = entries[ref] ?: return
        val stamped = existing.copy(rev = ++revCounter)
        val row = stamped.copy(blocked = derivedBlocked(stamped))
        entries[ref] = row
        stage(TaskUpdate(row.ref, row, row.rev))
    }

    private fun restampDependentsLocked(ref: TaskRef) = restampAllLocked(dependentsLocked(ref))

    private fun restampAllLocked(refs: Collection<TaskRef>) = refs.forEach { restampLocked(it) }

    private fun restampAfterEditLocked(ref: TaskRef) {
        restampLocked(ref)
        restampDependentsLocked(ref)
    }

    private fun reseedBlocked() {
        for (ref in entries.keys.toList()) {
            val existing = entries.getValue(ref)
            entries[ref] = existing.copy(blocked = derivedBlocked(existing))
        }
    }

    private fun derivedBlocked(entry: BacklogEntry): Boolean =
        entry.state == TaskState.todo &&
            deps[entry.ref].orEmpty().any { dependency ->
                val row = entries[dependency]
                row != null && row.project == entry.project && row.state != TaskState.done
            }

    private fun dependentsLocked(ref: TaskRef): List<TaskRef> =
        deps.filterValues { ref in it }.keys.sortedBy { it.value }

    private fun edgeSnapshotLocked(): Map<TaskRef, List<TaskRef>> = deps.mapValues { it.value.toList() }

    private fun endPosition(project: ProjectId): Double =
        positionForEnd(entries.values.filter { it.project == project }.maxOfOrNull { it.position })

    private fun rankForLocked(existing: BacklogEntry, target: MoveTarget): Double? {
        val siblings = entries.values
            .filter { it.project == existing.project && it.ref != existing.ref }
            .map { it.position }
            .sorted()
        return when (target) {
            MoveTarget.Top -> {
                val min = siblings.firstOrNull() ?: return positionForTop(null)
                if (needsRenormalization(0.0, min)) null else positionForTop(min)
            }

            MoveTarget.Bottom -> positionForEnd(siblings.lastOrNull())

            is MoveTarget.Before -> {
                val upper = entries.getValue(target.ref).position
                val lower = siblings.lastOrNull { it < upper } ?: 0.0
                if (needsRenormalization(lower, upper)) null else positionBetween(lower, upper)
            }

            is MoveTarget.After -> {
                val lower = entries.getValue(target.ref).position
                val upper = siblings.firstOrNull { it > lower } ?: return positionForEnd(lower)
                if (needsRenormalization(lower, upper)) null else positionBetween(lower, upper)
            }
        }
    }

    private fun renormalizeLocked(project: ProjectId) {
        entries.values
            .filter { it.project == project }
            .sortedWith(RANK_ORDER)
            .forEachIndexed { index, entry ->
                val row = entry.copy(position = index + 1.0, rev = ++revCounter)
                entries[entry.ref] = row
                stage(TaskUpdate(row.ref, row, row.rev))
            }
    }

    private companion object {
        val RANK_ORDER: Comparator<BacklogEntry> = compareBy({ it.position }, { it.ref.value })
    }
}
