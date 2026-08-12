package io.kotgent.task

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef

/** Names are persisted and sent on the wire verbatim. */
enum class TaskState { todo, in_progress, review, done }

enum class ActivityKind { created, comment, transition, linked, unlinked }

data class Task(
    val ref: TaskRef,
    val title: String,
    val body: String,
    val url: String?,
    val updatedAt: Long,
)

/** [blocked] is derived; dependency and state changes must re-emit reverse dependents. */
data class BacklogEntry(
    val ref: TaskRef,
    val project: ProjectId,
    val position: Double,
    val state: TaskState,
    val blocked: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val rev: Long,
)

data class TaskActivityEntry(
    val id: Long,
    val ref: TaskRef,
    val ts: Long,
    val kind: ActivityKind,
    val author: String,
    val text: String?,
    val fromState: TaskState?,
    val toState: TaskState?,
)

/** A null [entry] means that [ref] was deleted. */
data class TaskUpdate(val ref: TaskRef, val entry: BacklogEntry?, val rev: Long)

/** [path] is the most recently seen checkout; [archived] is a non-cascading delete tombstone. */
data class ProjectRecord(
    val id: ProjectId,
    val name: String,
    val path: String?,
    val updatedAt: Long,
    val archived: Boolean = false,
)

/** Result of atomic registration, which never resurrects an archived project implicitly. */
enum class ProjectRegistration {
    registered,
    refusedArchived,
}

sealed interface MoveTarget {
    data object Top : MoveTarget

    data object Bottom : MoveTarget

    data class Before(val ref: TaskRef) : MoveTarget

    data class After(val ref: TaskRef) : MoveTarget
}
