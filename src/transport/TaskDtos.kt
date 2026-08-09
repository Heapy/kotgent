package io.kotgent.transport

import io.kotgent.core.SessionMeta
import io.kotgent.core.TaskRef
import io.kotgent.task.BacklogEntry
import io.kotgent.task.ProjectRecord
import io.kotgent.task.Task
import io.kotgent.task.TaskActivityEntry
import kotlinx.serialization.Serializable

/*
 * The task layer's wire shapes.
 *
 * Every id crosses the wire as a plain `String`, never as a value class. A value class's generated
 * serializer decodes by calling its constructor, whose `require` throws `IllegalArgumentException` —
 * NOT `SerializationException` — so a malformed ref inside a request body would sail past a route's
 * decode catch and surface as a 500. `ImportSessionRequest` records the same trap; handlers parse with
 * `TaskRef.parseOrNull` / `ProjectId.parseOrNull` and answer 400 themselves.
 *
 * Every row-shaped DTO carries `rev`, the task store's global monotonic revision, because the client
 * applies any observation of a row — an HTTP response or a WS frame — only if its rev is newer than the
 * row it holds. That is what makes responses and frames mergeable in any arrival order.
 */

/** A tracker's view of a task. [url] is always null for the built-in tracker (see `io.kotgent.task.Task`). */
@Serializable
data class TaskDto(
    val ref: String,
    val title: String,
    val body: String,
    val url: String? = null,
    val updatedAt: Long,
)

/**
 * One board row: the backlog entry joined with its tracker fields.
 *
 * [blocked] is derived server-side (`state == todo` and some dependency is not `done`) so the board does
 * not recompute it per card — and every reverse dependent of a task whose state changed is re-stamped
 * and re-emitted, or a card would keep a stale marker until a reload.
 *
 * [dependsOn] is carried so a card can show its dependency count and the detail view its edges without a
 * second request. **Linked sessions are deliberately NOT here**: the browser already holds the whole
 * session list from the `/events` socket and matches on `taskRef`, which keeps the card's session dots
 * live for free and keeps this DTO one query per project instead of one per card. The detail view is the
 * exception — see [TaskDetailDto.sessions].
 */
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

/** One activity row. [kind] is an `ActivityKind` name; [fromState]/[toState] are set only for a transition. */
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

/**
 * A session linked to a task, as the detail view renders it. Deliberately narrower than [SessionDto]:
 * the card needs a name, a state dot and a link target, not the whole row.
 */
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

/**
 * `GET /api/v1/tasks/{ref}`: the entry, the project's last-seen path, both directions of its
 * dependencies, every session holding it, and its activity feed.
 *
 * The feed rides HTTP and **not** the events socket, on purpose: it is unbounded, it is only ever read
 * on one open detail view, and putting it on the socket would make every connected tab pay for a comment
 * nobody is looking at.
 */
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

/**
 * A known project. [path] is "the checkout the daemon saw most recently", not "the project's location" —
 * worktrees share one uuid and overwrite one row.
 */
@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    val path: String? = null,
    val updatedAt: Long,
)

/**
 * `GET /api/v1/whoami`: what the calling pane resolves to.
 *
 * It exists for one purpose — an agent knows only its pane, so a ref-less `task show` / `comment` /
 * `review` / `unlink` resolves its subject through this. When `--session <id>` is given the CLI skips
 * this call entirely: it already knows the id and sends it in the body. `/whoami` is pane resolution,
 * not a session lookup.
 */
@Serializable
data class WhoamiDto(
    val sessionId: String? = null,
    val projectId: String? = null,
    val taskRef: String? = null,
)

// --- domain → wire mappers -------------------------------------------------------------------------

/*
 * These live HERE, once, and not in the route file that happens to need one first.
 *
 * `TaskReadRoutes.kt`, `TaskWriteRoutes.kt`, `TaskLinkRoutes.kt` and `EventsWs.kt` are four files in the
 * package `io.kotgent.transport`, owned by four different agents working at the same time, and all four
 * need the same four conversions. An extension function is package-level, so two agents writing
 * `fun BacklogEntry.toDto(...)` in their own file is a redeclaration error the moment the branches merge
 * — the one failure mode the contracts wave exists to prevent. `SessionUpdate.toDto()` in `EventsWs.kt`
 * is the precedent: one mapper, one home, every caller shares it.
 *
 * They have real bodies rather than `TODO()`: a mapping is not a task's design work, and a stub here
 * would make every route's own test fail on a `NotImplementedError` from somebody else's file.
 */

/**
 * A backlog entry joined with its tracker row.
 *
 * [task] is nullable and a null renders the bare ref as the title — the same honest degradation the Web
 * UI's badge makes. A `tasks` row and a `backlog_entries` row are created in one transaction, so the
 * only way to observe the mismatch is the microsecond window `sessions.task_ref`'s "reference, not a
 * foreign key" rule already documents; answering `500` for it would be worse than showing the ref.
 *
 * [dependsOn] is passed in rather than read here: the list view resolves the whole project's edges in
 * ONE query and hands each entry its slice, and a mapper that fetched per card would undo that.
 */
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

/** A known project, for the board selector and the detail view's project path. */
fun ProjectRecord.toDto(): ProjectDto = ProjectDto(
    id = id.value,
    name = name,
    path = path,
    updatedAt = updatedAt,
)

/** One activity row. Enum names cross the wire, matching `backlog_entries.state`'s storage convention. */
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

/**
 * A session as the task detail view renders it. Deliberately NOT [SessionMeta.toDto] — that is the full
 * row, and the detail view needs a name, a state dot and a link target. Named `toLinkedSessionDto` rather
 * than overloading `toDto`, because `SessionMeta.toDto()` already exists in this package
 * (`ControlRoutes.kt`) and two extensions on one receiver differing only in return type do not compile.
 */
fun SessionMeta.toLinkedSessionDto(): LinkedSessionDto = LinkedSessionDto(
    id = id.value,
    name = name,
    agent = agent,
    state = state.name,
    needsAttention = state.needsAttention,
    alive = state.isAlive,
    archived = archived,
)

// --- request bodies --------------------------------------------------------------------------------

/**
 * `POST /api/v1/tasks`. [project] is optional because the BOARD has neither a pane nor a session and
 * creating tasks is its headline job. Resolution order, in exactly this order:
 * explicit [project] → the calling session's `project_id` → `resolveProject(session cwd)` → create the
 * file at `mainCheckoutRoot(session cwd)` → `400` naming `--project`, and **only** when no session
 * resolves at all.
 */
@Serializable
data class CreateTaskRequest(
    val title: String,
    val body: String = "",
    val project: String? = null,
    val sessionId: String? = null,
)

/**
 * `PATCH /api/v1/tasks/{ref}`. A null field means "leave unchanged", never "clear".
 *
 * [message] is what makes `kotgent task review -m "…"` ONE operation: the transition and its activity
 * row commit in one task-store transaction, so a failure cannot leave a review with no explanation or a
 * comment on an unreviewed task. It is meaningful only alongside [state].
 */
@Serializable
data class PatchTaskRequest(
    val title: String? = null,
    val body: String? = null,
    val state: String? = null,
    val message: String? = null,
    val sessionId: String? = null,
)

/**
 * `POST /api/v1/tasks/{ref}/move` — `{ before | after | top | bottom }`, exactly one of them. It carries
 * no state: a board drop that changes both column and rank is the `PATCH` first, then this.
 */
@Serializable
data class MoveTaskRequest(
    val before: String? = null,
    val after: String? = null,
    val top: Boolean = false,
    val bottom: Boolean = false,
)

/** `POST /api/v1/tasks/{ref}/deps` — [action] is `"add"` or `"remove"`, [on] the other ref. */
@Serializable
data class DepsRequest(
    val action: String,
    val on: String,
)

/** `POST /api/v1/tasks/{ref}/comment`. Requires session identity — an activity row must be attributable. */
@Serializable
data class CommentRequest(
    val text: String,
    val sessionId: String? = null,
)

/**
 * `POST /api/v1/tasks/{ref}/link` and `…/unlink`. Both require session identity, from the
 * `X-Kotgent-Tmux-Pane` header or from [sessionId] here.
 */
@Serializable
data class LinkRequest(
    val sessionId: String? = null,
)

/** `POST /api/v1/tasks/next`. [project] defaults to the calling session's. */
@Serializable
data class NextTaskRequest(
    val project: String? = null,
    val sessionId: String? = null,
)

/**
 * The answer to `POST /api/v1/tasks/next`. A null [task] is **"nothing eligible"** and is NOT an error
 * status — the CLI maps it to exit `3`, and an error status could not be told apart from a real failure.
 */
@Serializable
data class NextTaskResponse(
    val task: BacklogEntryDto? = null,
)

/**
 * `POST /api/v1/projects` — create or adopt a project at [path].
 *
 * This writes a file at a browser-supplied ABSOLUTE path, which departs from the "a session-cwd write,
 * never an arbitrary-path API" rule uploads follow. The departure is deliberate and bounded: the path
 * must be absolute and an existing directory, the only file written is `.kotgent.json`, publication is
 * `link(2)` so an existing file always wins, and the mode is `0666 & ~umask` because the file is meant
 * to be committed. It is no wider than the New-session dialog, which already directs the daemon to `cd`
 * anywhere. Recorded here so the upload rule is not read as having quietly eroded.
 */
@Serializable
data class CreateProjectRequest(
    val path: String,
    val name: String? = null,
)
