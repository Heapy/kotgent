package io.kotgent.store

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.task.BacklogEntry
import io.kotgent.task.MoveTarget
import io.kotgent.task.ProjectRecord
import io.kotgent.task.TaskActivityEntry
import io.kotgent.task.TaskState
import io.kotgent.task.TaskTracker
import io.kotgent.task.TaskUpdate
import kotlinx.coroutines.flow.SharedFlow

/**
 * The task layer's storage seam: the built-in tracker ([TaskTracker]) plus kotgent's own workflow —
 * ordering, dependencies, state transitions, the activity feed and the project registry.
 *
 * ## What it must never touch
 * The `sessions` table. `sessions.rev` is stamped from an in-memory counter owned by [SqliteEventStore],
 * so a second store writing that table would fork the counter — producing duplicate and regressing
 * revisions the browser's newest-rev-wins rule then silently drops — and would emit no `SessionUpdate`,
 * so the sidebar badge would never move. Every `sessions` write goes through [EventStore], and
 * [io.kotgent.daemon.TaskService] calls the two stores SEQUENTIALLY, never nesting their locks.
 *
 * ## Concurrency
 * One logical writer, exactly like [EventStore]: a `Mutex` serializes every method, and every
 * `db.transaction { }` runs inside it with no suspension inside the block (the native SQLDelight driver
 * confines a transaction to one thread and is not suspend-safe). Two stores now open transactions over
 * the SAME driver under DIFFERENT mutexes; the driver borrows a single writer entry, so concurrent
 * transactions serialize by blocking rather than corrupt — which is why every transaction here must stay
 * short.
 */
interface TaskStore : TaskTracker {

    /**
     * A hot, non-replaying stream of task-row changes — one per create, tracker edit, transition, move,
     * dependency edit and delete, plus one per REVERSE DEPENDENT whose derived
     * [blocked][BacklogEntry.blocked] moved. Buffered and `DROP_OLDEST` (the `_sessionUpdates` shape), so
     * a burst never suspends the writer.
     *
     * A [TaskUpdate] with a null entry means the ref was deleted; the transport turns it into a
     * `task_removed` frame.
     *
     * Do NOT copy [EventStore.reliableSessionUpdates] here: that companion exists for the push notifier,
     * which must not miss an intermediate transition. Nothing in the task layer has that requirement, and
     * an unbuffered backpressuring flow would make a slow browser stall a database write.
     */
    val taskUpdates: SharedFlow<TaskUpdate>

    // --- backlog reads ----------------------------------------------------------------------------

    /** One backlog entry with its derived `blocked`, or `null` when the ref is unknown. */
    suspend fun entry(ref: TaskRef): BacklogEntry?

    /** A project's entries in rank order, each with its derived `blocked`. */
    suspend fun listBacklog(project: ProjectId): List<BacklogEntry>

    /**
     * The first `todo` entry in [project], in rank order, with no unfinished dependency — or `null`,
     * which is the ONLY thing that reports "nothing eligible" (the CLI maps it to exit `3`).
     *
     * Two agents racing land on the same candidate; [startIfTodo] means one of them changes the row and
     * the other sees zero rows, re-queries (the row is no longer `todo`, so it is naturally excluded) and
     * takes the next one. No `skip` set is needed, because nothing puts a candidate back to `todo`
     * mid-loop — nothing compensates any more.
     */
    suspend fun nextCandidate(project: ProjectId): BacklogEntry?

    // --- backlog writes ---------------------------------------------------------------------------

    /**
     * Advance [ref] from `todo` to `in_progress`, and ONLY from `todo`. Returns whether a row changed;
     * **`false` is normal, not an error** — it means the task was already `in_progress`/`review`/`done`,
     * and the caller still makes the session link unconditionally.
     *
     * This is the single conditional write in the whole design, and its only job is to stop
     * `kotgent task next` handing the same task to two agents in a row. It is a SELECTION CONVENTION, not
     * a protected invariant: an explicit `task claim <ref>` on a task already in progress is allowed and
     * simply adds another link.
     */
    suspend fun startIfTodo(ref: TaskRef): Boolean

    /**
     * Move [ref] to [to], writing the state change, an [ActivityKind.transition][io.kotgent.task.ActivityKind]
     * row (carrying [message] when one was given) and the re-stamp of every reverse dependent in ONE
     * transaction — so `kotgent task review -m "…"` cannot leave a review with no explanation or a
     * comment on an unreviewed task. Returns the updated entry, or `null` when [ref] is unknown.
     *
     * Unlinking the sessions that hold a task moved to `done` is NOT here: that is a `sessions` write and
     * therefore [io.kotgent.daemon.TaskService]'s, sequentially, after this returns.
     */
    suspend fun transition(ref: TaskRef, to: TaskState, author: String, message: String?): BacklogEntry?

    /**
     * Re-rank [ref] within its project. On a collapsed gap ([io.kotgent.task.needsRenormalization]) the
     * project's whole column is renormalized to `1.0, 2.0, 3.0, …` in one transaction — every rewritten
     * row stamping a fresh rev and emitting — and the move is retried once. Returns the moved entry, or
     * `null` when [ref] (or a named neighbour) is unknown.
     *
     * A move carries no state and a `PATCH` carries no position: a board drop that changes both is two
     * requests, in that order.
     */
    suspend fun move(ref: TaskRef, target: MoveTarget): BacklogEntry?

    // --- dependencies -----------------------------------------------------------------------------

    /** What [ref] depends on. */
    suspend fun dependenciesOf(ref: TaskRef): List<TaskRef>

    /** What depends on [ref] — the reverse lookup whose rows a change to [ref] re-stamps. */
    suspend fun dependentsOf(ref: TaskRef): List<TaskRef>

    /** The whole project's edge set (`ref → what it depends on`) — the cycle check's input, and the card counts. */
    suspend fun dependencyEdges(project: ProjectId): Map<TaskRef, List<TaskRef>>

    /**
     * Add "[ref] depends on [dependsOn]", then re-stamp and re-emit [ref] itself and every reverse
     * dependent whose derived `blocked` moved. Re-adding an existing edge is a no-op, not an error.
     *
     * @throws io.kotgent.task.DependencyRefusedException for any of the four validated refusals: the two
     *   refs are equal, either is not in `backlog_entries`, they belong to different projects, or the
     *   edge would close a cycle. A dangling or cross-project edge would otherwise be accepted and then
     *   read as "already satisfied" by [nextCandidate]'s join — silently unblocking a task.
     */
    suspend fun addDependency(ref: TaskRef, dependsOn: TaskRef)

    /** Remove the edge, then re-stamp and re-emit as [addDependency] does. Removing a missing edge is a no-op. */
    suspend fun removeDependency(ref: TaskRef, dependsOn: TaskRef)

    // --- activity ---------------------------------------------------------------------------------

    /**
     * Append a [ActivityKind.comment][io.kotgent.task.ActivityKind] row. [author] is the session id that
     * wrote it, or [io.kotgent.daemon.TaskService.BOARD_AUTHOR] when the browser did. Returns the stored
     * row, or `null` when [ref] is unknown.
     */
    suspend fun comment(ref: TaskRef, author: String, text: String): TaskActivityEntry?

    /**
     * Append an arbitrary activity row — the `linked` / `unlinked` kinds
     * [io.kotgent.daemon.TaskService] writes. [transition] owns the `transition` kind, and [create] owns
     * `created`; both write theirs inside their own transaction. Returns `null` for an unknown [ref].
     */
    suspend fun appendActivity(
        ref: TaskRef,
        kind: io.kotgent.task.ActivityKind,
        author: String,
        text: String? = null,
        fromState: TaskState? = null,
        toState: TaskState? = null,
    ): TaskActivityEntry?

    /** A task's feed, oldest first. Fetched with the detail view; deliberately not on the events socket. */
    suspend fun activity(ref: TaskRef): List<TaskActivityEntry>

    // --- projects ---------------------------------------------------------------------------------

    /**
     * Register or refresh a project. **Every path that reads or creates a `.kotgent.json` must call
     * this**: without it, a project created by `kotgent task add` in a fresh repository has backlog rows
     * but never appears in `GET /api/v1/projects`, so the board's selector can never reach its backlog.
     * A null [path] leaves the stored one alone (see `Projects.sq`).
     */
    suspend fun upsertProject(id: ProjectId, name: String, path: String?)

    /** Every known project, by name. The board's selector reads exactly this. */
    suspend fun listProjects(): List<ProjectRecord>

    /** One project, or `null`. */
    suspend fun project(id: ProjectId): ProjectRecord?
}
