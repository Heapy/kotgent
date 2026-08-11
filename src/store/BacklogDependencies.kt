package io.kotgent.store

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.db.BacklogQueries
import io.kotgent.task.BacklogEntry
import io.kotgent.task.DependencyRefusal
import io.kotgent.task.DependencyRefusedException
import io.kotgent.task.TaskState
import io.kotgent.task.TaskUpdate
import io.kotgent.task.wouldCycle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns dependency validation and the derived [BacklogEntry.blocked] value. Edges are validated before
 * insertion because the candidate joins intentionally treat a missing dependency row as satisfied.
 */
class BacklogDependencies(
    private val queries: BacklogQueries,
    private val mutex: Mutex,
    private val nextRev: () -> Long,
    private val outbox: TaskUpdateOutbox,
    @Suppress("unused") private val now: () -> Long,
) {


    fun entryLocked(ref: TaskRef): BacklogEntry? {
        val row = queries.selectEntry(ref.value).executeAsOneOrNull() ?: return null
        val state = TaskState.valueOf(row.state)
        return BacklogEntry(
            ref = ref,
            project = ProjectId.of(row.project),
            position = row.position,
            state = state,
            blocked = state == TaskState.todo &&
                queries.unfinishedDependencyCount(ref.value).executeAsOne() > 0L,
            createdAt = row.created_at,
            updatedAt = row.updated_at,
            rev = row.rev,
        )
    }

    fun listBacklogLocked(project: ProjectId): List<BacklogEntry> {
        val rows = queries.selectEntriesByProject(project.value).executeAsList()
        if (rows.isEmpty()) return emptyList()
        val present = HashSet<String>(rows.size)
        val done = HashSet<String>()
        for (row in rows) {
            present += row.task_ref
            if (row.state == DONE_STATE) done += row.task_ref
        }
        val dependencies = HashMap<String, MutableList<String>>()
        for (edge in queries.selectDependencyEdges(project.value).executeAsList()) {
            dependencies.getOrPut(edge.task_ref) { mutableListOf() } += edge.depends_on
        }
        return rows.map { row ->
            val state = TaskState.valueOf(row.state)
            BacklogEntry(
                ref = TaskRef(row.task_ref),
                project = project,
                position = row.position,
                state = state,
                blocked = state == TaskState.todo && dependencies[row.task_ref].orEmpty().any {
                    it in present && it !in done
                },
                createdAt = row.created_at,
                updatedAt = row.updated_at,
                rev = row.rev,
            )
        }
    }

    fun nextCandidateLocked(project: ProjectId): BacklogEntry? {
        val row = queries.nextCandidate(project.value).executeAsOneOrNull() ?: return null
        return BacklogEntry(
            ref = TaskRef(row.task_ref),
            project = project,
            position = row.position,
            state = TaskState.valueOf(row.state),
            blocked = false,
            createdAt = row.created_at,
            updatedAt = row.updated_at,
            rev = row.rev,
        )
    }

    fun dependenciesOfLocked(ref: TaskRef): List<TaskRef> =
        queries.selectDependencies(ref.value).executeAsList().map(::TaskRef)

    fun dependentsOfLocked(ref: TaskRef): List<TaskRef> =
        queries.selectDependents(ref.value).executeAsList().map(::TaskRef)

    fun edgesLocked(project: ProjectId): Map<TaskRef, List<TaskRef>> = edgesOfProjectLocked(project.value)

    fun restampDependentsLocked(ref: TaskRef) {
        for (dependent in dependentsOfLocked(ref)) restampLocked(dependent)
    }


    suspend fun add(ref: TaskRef, dependsOn: TaskRef): Unit = mutex.withLock {
        outbox.publishing { addLocked(ref, dependsOn) }
    }

    private fun addLocked(ref: TaskRef, dependsOn: TaskRef) {
        if (ref == dependsOn) {
            refuse(DependencyRefusal.self, ref, dependsOn, "a task cannot depend on itself: '${ref.value}'")
        }
        val source = queries.selectEntry(ref.value).executeAsOneOrNull()
            ?: refuse(DependencyRefusal.unknownRef, ref, dependsOn, unknownRefMessage(ref))
        val target = queries.selectEntry(dependsOn.value).executeAsOneOrNull()
            ?: refuse(DependencyRefusal.unknownRef, ref, dependsOn, unknownRefMessage(dependsOn))
        if (source.project != target.project) {
            refuse(
                DependencyRefusal.crossProject, ref, dependsOn,
                "'${ref.value}' and '${dependsOn.value}' are in different projects — a dependency " +
                    "edge is resolved within one project's backlog",
            )
        }
        val edges = edgesOfProjectLocked(source.project)
        if (dependsOn in edges[ref].orEmpty()) return
        if (wouldCycle(edges, ref, dependsOn)) {
            refuse(
                DependencyRefusal.cycle, ref, dependsOn,
                "'${ref.value}' cannot depend on '${dependsOn.value}': the edge would close a " +
                    "dependency cycle, and every task on a cycle blocks every other one forever",
            )
        }
        queries.transaction {
            queries.insertDep(ref.value, dependsOn.value)
            restampAfterEditLocked(ref)
        }
    }

    suspend fun remove(ref: TaskRef, dependsOn: TaskRef): Unit = mutex.withLock {
        outbox.publishing { removeLocked(ref, dependsOn) }
    }

    private fun removeLocked(ref: TaskRef, dependsOn: TaskRef) {
        if (dependsOn !in dependenciesOfLocked(ref)) return
        queries.transaction {
            queries.deleteDep(ref.value, dependsOn.value)
            restampAfterEditLocked(ref)
        }
    }


    private fun restampAfterEditLocked(ref: TaskRef) {
        // The edited row and its reverse dependents can change without their stored backlog fields changing.
        restampLocked(ref)
        restampDependentsLocked(ref)
    }

    private fun restampLocked(ref: TaskRef) {
        val entry = entryLocked(ref) ?: return
        val rev = nextRev()
        queries.restamp(rev, ref.value)
        outbox.stage(TaskUpdate(ref, entry.copy(rev = rev), rev))
    }

    private fun edgesOfProjectLocked(project: String): Map<TaskRef, List<TaskRef>> {
        val edges = LinkedHashMap<TaskRef, MutableList<TaskRef>>()
        for (edge in queries.selectDependencyEdges(project).executeAsList()) {
            edges.getOrPut(TaskRef(edge.task_ref)) { mutableListOf() } += TaskRef(edge.depends_on)
        }
        return edges
    }

    private fun refuse(
        refusal: DependencyRefusal,
        ref: TaskRef,
        dependsOn: TaskRef,
        message: String,
    ): Nothing = throw DependencyRefusedException(refusal, ref, dependsOn, message)

    private fun unknownRefMessage(missing: TaskRef): String =
        "no such task '${missing.value}' — a dependency edge must name two entries that are already " +
            "in the backlog, or it would read as already satisfied and silently unblock a task"

    private companion object {
        val DONE_STATE: String = TaskState.done.name
    }
}
