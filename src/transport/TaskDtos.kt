package io.kotgent.transport

import io.kotgent.core.SessionMeta
import io.kotgent.core.TaskRef
import io.kotgent.task.BacklogEntry
import io.kotgent.task.ProjectRecord
import io.kotgent.task.Task
import io.kotgent.task.TaskActivityEntry
import kotlinx.serialization.Serializable

// IDs stay Strings on the wire: value-class constructors throw IllegalArgumentException outside the
// SerializationException route handlers map. Row revisions merge HTTP and WebSocket data newest-wins.
@Serializable
data class TaskDto(
    val ref: String,
    val title: String,
    val body: String,
    val url: String? = null,
    val updatedAt: Long,
)

@Serializable
data class BacklogEntryDto(
    val ref: String,
    val project: String,
    val title: String,
    val body: String,
    val url: String? = null,
    val position: Double,
    val state: String,
    val blocked: Boolean,
    val dependsOn: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val rev: Long,
)

@Serializable
data class ActivityEntryDto(
    val id: Long,
    val ref: String,
    val ts: Long,
    val kind: String,
    val author: String,
    val text: String? = null,
    val fromState: String? = null,
    val toState: String? = null,
)

@Serializable
data class LinkedSessionDto(
    val id: String,
    val name: String,
    val agent: String,
    val state: String,
    val needsAttention: Boolean,
    val alive: Boolean,
    val archived: Boolean = false,
)

@Serializable
data class TaskDetailDto(
    val task: BacklogEntryDto,
    val projectName: String? = null,
    val projectPath: String? = null,
    val dependsOn: List<String> = emptyList(),
    val dependents: List<String> = emptyList(),
    val sessions: List<LinkedSessionDto> = emptyList(),
    val activity: List<ActivityEntryDto> = emptyList(),
)

/** [archived] is the delete tombstone; the board lists live projects and the restore dialog the rest. */
@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    val path: String? = null,
    val updatedAt: Long,
    val archived: Boolean = false,
)

@Serializable
data class WhoamiDto(
    val sessionId: String? = null,
    val projectId: String? = null,
    val taskRef: String? = null,
)



fun BacklogEntry.toDto(task: Task?, dependsOn: List<TaskRef> = emptyList()): BacklogEntryDto = BacklogEntryDto(
    ref = ref.value,
    project = project.value,
    title = task?.title ?: ref.value,
    body = task?.body ?: "",
    url = task?.url,
    position = position,
    state = state.name,
    blocked = blocked,
    dependsOn = dependsOn.map { it.value },
    createdAt = createdAt,
    updatedAt = updatedAt,
    rev = rev,
)

fun ProjectRecord.toDto(): ProjectDto = ProjectDto(
    id = id.value,
    name = name,
    path = path,
    updatedAt = updatedAt,
    archived = archived,
)

fun TaskActivityEntry.toDto(): ActivityEntryDto = ActivityEntryDto(
    id = id,
    ref = ref.value,
    ts = ts,
    kind = kind.name,
    author = author,
    text = text,
    fromState = fromState?.name,
    toState = toState?.name,
)

fun SessionMeta.toLinkedSessionDto(): LinkedSessionDto = LinkedSessionDto(
    id = id.value,
    name = name,
    agent = agent,
    state = state.name,
    needsAttention = state.needsAttention,
    alive = state.isAlive,
    archived = archived,
)


@Serializable
data class CreateTaskRequest(
    val title: String,
    val body: String = "",
    val project: String? = null,
    val sessionId: String? = null,
)

@Serializable
data class PatchTaskRequest(
    val title: String? = null,
    val body: String? = null,
    val state: String? = null,
    val message: String? = null,
    val sessionId: String? = null,
)

@Serializable
data class MoveTaskRequest(
    val before: String? = null,
    val after: String? = null,
    val top: Boolean = false,
    val bottom: Boolean = false,
)

@Serializable
data class DepsRequest(
    val action: String,
    val on: String,
)

@Serializable
data class CommentRequest(
    val text: String,
    val sessionId: String? = null,
)

@Serializable
data class LinkRequest(
    val sessionId: String? = null,
)

@Serializable
data class NextTaskRequest(
    val project: String? = null,
    val sessionId: String? = null,
)

@Serializable
data class NextTaskResponse(
    val task: BacklogEntryDto? = null,
)

@Serializable
data class CreateProjectRequest(
    val path: String,
    val name: String? = null,
)
