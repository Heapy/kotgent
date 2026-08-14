package io.kotgent.store

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.task.BacklogEntry
import io.kotgent.task.MoveTarget
import io.kotgent.task.ProjectRecord
import io.kotgent.task.ProjectRegistration
import io.kotgent.task.TaskActivityEntry
import io.kotgent.task.TaskState
import io.kotgent.task.TaskTracker
import io.kotgent.task.TaskUpdate
import kotlinx.coroutines.flow.SharedFlow

/** Local workflow state layered over tracker-owned task fields. */
interface TaskStore : TaskTracker {

    val taskUpdates: SharedFlow<TaskUpdate>


    suspend fun entry(ref: TaskRef): BacklogEntry?

    suspend fun listBacklog(project: ProjectId): List<BacklogEntry>

    /** Atomically selects an eligible card while excluding archived projects. */
    suspend fun nextCandidate(project: ProjectId): BacklogEntry?


    /** Conditionally starts a named todo card; tombstoned projects remain writable by explicit ref. */
    suspend fun startIfTodo(ref: TaskRef): Boolean

    /**
     * Conditionally starts an automatically selected todo card, atomically refusing a project tombstone
     * so deletion cannot land between [nextCandidate] and this write.
     */
    suspend fun startIfTodoInLiveProject(ref: TaskRef): Boolean

    suspend fun transition(ref: TaskRef, to: TaskState, author: String, message: String?): BacklogEntry?

    suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry?


    suspend fun dependenciesOf(ref: TaskRef): List<TaskRef>

    suspend fun dependentsOf(ref: TaskRef): List<TaskRef>

    suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>>

    suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef)

    suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef)


    suspend fun comment(ref: TaskRef, author: String, text: String): TaskActivityEntry?

    suspend fun appendActivity(
        ref: TaskRef,
        kind: io.kotgent.task.ActivityKind,
        author: String,
        text: String? = null,
        fromState: TaskState? = null,
        toState: TaskState? = null,
    ): TaskActivityEntry?

    suspend fun activity(ref: TaskRef): List<TaskActivityEntry>


    /** Atomically registers a live project without implicitly clearing its tombstone. */
    suspend fun upsertProject(id: ProjectId, name: String, path: String?): ProjectRegistration

    /** Sets or clears the tombstone; false when no such project row exists. */
    suspend fun setProjectArchived(id: ProjectId, archived: Boolean): Boolean

    suspend fun listProjects(archived: Boolean = false): List<ProjectRecord>

    /** Returns live and archived projects in one consistent read. */
    suspend fun listAllProjects(): List<ProjectRecord>

    suspend fun project(id: ProjectId): ProjectRecord?
}
