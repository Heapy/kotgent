package io.kotgent.task

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef

/** Tracker-owned fields only; workflow, ordering, and dependencies belong to [io.kotgent.store.TaskStore]. */
interface TaskTracker {

    val id: String

    suspend fun list(project: ProjectId): List<Task>

    suspend fun get(ref: TaskRef): Task?

    suspend fun create(
        project: ProjectId,
        title: String,
        body: String,
        author: String = BOARD_AUTHOR,
    ): Task

    /** Null fields are left unchanged. */
    suspend fun update(ref: TaskRef, title: String?, body: String?): Task?

    suspend fun delete(ref: TaskRef): Boolean

    companion object {
        const val BOARD_AUTHOR: String = "board"
    }
}
