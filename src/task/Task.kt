package io.kotgent.task

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef

/*
 * The local task layer's vocabulary — host-free, no I/O, no storage types.
 *
 * ## Two layers, not one
 * [TaskTracker] covers only what a tracker can know: title, body, url, external state. The WORKFLOW —
 * `todo → in_progress → review → done`, ordering, dependencies, the session link and the activity feed —
 * is kotgent's own concept and lives here, keyed by [TaskRef]. GitHub has no "review" state and no
 * "position 3 in my backlog", so a fat tracker with capability flags would force the board to degrade to
 * a backlog with neither ordering nor a session link, which is the entire product. With the split, a
 * future GitHub adapter implements a five-method interface and its issues drop into the same ordered
 * backlog for free.
 */

/**
 * The workflow states of a backlog entry. Lowercase entry names because the name IS the wire and
 * storage value (`backlog_entries.state`, the JSON DTOs, the CLI's JSON output) — the same convention
 * [io.kotgent.core.SessionState] follows.
 */
enum class TaskState { todo, in_progress, review, done }

/** What an activity row records. `linked`/`unlinked` are session-link events, not state changes. */
enum class ActivityKind { created, comment, transition, linked, unlinked }

/**
 * The TRACKER's view of a task: what any tracker, local or external, can answer about it.
 *
 * [url] is `null` for every task the built-in tracker owns and always will be — `tasks` has no `url`
 * column and [TaskTracker] exposes no setter for one. It exists because an external tracker's task has a
 * canonical web address and the UI should link to it; do not add a local write path for it.
 */
data class Task(
    val ref: TaskRef,
    val title: String,
    val body: String,
    val url: String?,
    val updatedAt: Long,
)

/**
 * The LOCAL layer's row for a task: where it sits in a project's ordered backlog and how far along it is.
 *
 * [position] is a `REAL` gap-based rank (see `Ordering.kt`), not an index — a move rewrites one row, not
 * the column. [rev] is the task store's global monotonic revision, the same single-master replication
 * cursor `sessions.rev` is: every observation of an entry carries it and a client applies an observation
 * only if its rev is newer.
 *
 * [blocked] is **derived, not stored** — `state == todo` and some dependency is not `done` — computed in
 * the read path so the board does not recompute it per card. That makes it stale by construction:
 * closing or deleting task A changes the blocked-ness of everything depending on A without touching
 * those rows. Every dependency edit and every state transition therefore re-stamps and re-emits A's
 * reverse dependents; without that the board shows a blocked marker on a ready task until a reload.
 */
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

/**
 * One row of a task's append-only activity feed. [author] is the session id that made the change, or a
 * symbolic actor for a change with no session behind it (the board — see
 * [io.kotgent.daemon.TaskService.BOARD_AUTHOR]). [fromState]/[toState] are set only for
 * [ActivityKind.transition]. The feed is fetched with the task detail and deliberately does NOT ride the
 * events socket.
 */
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

/**
 * What the task-updates flow carries. A null [entry] means the ref was deleted — the transport turns
 * that into a `task_removed` frame and the client drops the row.
 */
data class TaskUpdate(val ref: TaskRef, val entry: BacklogEntry?, val rev: Long)

/**
 * A known project: the uuid committed in its `.kotgent.json`, its name, and [path].
 *
 * [path] is explicitly "the checkout the daemon saw MOST RECENTLY", not "the project's location":
 * worktrees deliberately share one uuid and overwrite one row. It is a convenience default only —
 * `start --task` prefers the caller's cwd when that resolves to the same project, falls back to this,
 * and falls back again to the caller's cwd when this no longer exists, saying which it used.
 */
data class ProjectRecord(
    val id: ProjectId,
    val name: String,
    val path: String?,
    val updatedAt: Long,
)

/**
 * Where a `POST /tasks/{ref}/move` puts an entry. [Before]/[After] name a NEIGHBOUR, never a position:
 * the caller cannot see the gap-based ranks, and resolving the neighbours plus writing the new position
 * has to happen under the store's mutex anyway.
 *
 * A move never carries a state and a `PATCH` never carries a position — a board drop that changes both
 * is deliberately two requests (the `PATCH`, then the `move`), so neither endpoint has to define what a
 * half-applied combination means.
 */
sealed interface MoveTarget {
    /** Above every entry in the project. */
    data object Top : MoveTarget

    /** Below every entry in the project. */
    data object Bottom : MoveTarget

    /** Immediately above [ref]. */
    data class Before(val ref: TaskRef) : MoveTarget

    /** Immediately below [ref]. */
    data class After(val ref: TaskRef) : MoveTarget
}
