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

    /**
     * The next eligible card, or null when there is none — including when [project] carries the delete
     * tombstone, which withdraws its whole backlog from selection.
     *
     * That last clause must be answered by the same read that picks the candidate. A caller that reads
     * [project] first and selects afterwards holds no lock in between, so a delete landing in that gap
     * would hand out work from a project the board no longer lists. Implementations therefore decide it
     * atomically; a project with no row at all is not a tombstone and is left alone.
     */
    suspend fun nextCandidate(project: ProjectId): BacklogEntry?


    /**
     * Conditional selection for `task next`; false is normal and does not forbid linking.
     *
     * [requireLiveProject] is the difference between the two callers, and it is about WHO CHOSE THE CARD.
     * `POST /tasks/next` has the daemon pick one out of the project, so it passes true and this refuses a
     * card whose project carries the delete tombstone — the same rule [nextCandidate] applies, one step
     * later, because selection and start are separate calls and a delete can land between them.
     * `POST /tasks/{ref}/link` starts the card its caller NAMED and passes false: a tombstone withdraws a
     * project as a source of work, not the cards an agent already holds.
     *
     * When true, the tombstone must be decided by the same statement that writes, for the same reason
     * [nextCandidate] gives. A project with no row at all is not a tombstone either way.
     */
    suspend fun startIfTodo(ref: TaskRef, requireLiveProject: Boolean = false): Boolean

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


    /**
     * Registers the project, or refuses because it carries the delete tombstone. The decision is made
     * under the implementation's own lock so no call site has to read before it writes.
     */
    suspend fun upsertProject(id: ProjectId, name: String, path: String?): ProjectRegistration

    /** Sets or clears the tombstone; false when no such project row exists. */
    suspend fun setProjectArchived(id: ProjectId, archived: Boolean): Boolean

    /** One side of the split: the board wants the live projects, the restore dialog the archived ones. */
    suspend fun listProjects(archived: Boolean = false): List<ProjectRecord>

    /**
     * Both sides at once, read as ONE observation — for a caller that needs a consistent list rather
     * than a selector's. Asking [listProjects] twice is not the same thing: a delete landing between the
     * two reads answers the same project on both sides and a restore on neither, and the `/events` task
     * baseline that needs both cannot survive either (it is one-shot per socket, so a duplicated or
     * missing row stays wrong until the page is reloaded). Implementations must answer from a single
     * consistent read.
     */
    suspend fun listAllProjects(): List<ProjectRecord>

    suspend fun project(id: ProjectId): ProjectRecord?
}
