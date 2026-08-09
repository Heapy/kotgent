package io.kotgent.task

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef

/**
 * The tracker seam: title, body, url, existence. Nothing about ordering, dependencies, the session link
 * or the workflow — those are kotgent's own and live in [io.kotgent.store.TaskStore], which extends this
 * interface for the built-in tracker.
 *
 * There is deliberately **no capability flag**. The built-in tracker is the only implementation, and a
 * flag with no second implementation and no reader is speculative: a future GitHub adapter either
 * implements these five methods or it is not a tracker.
 *
 * ## What "the built-in tracker and the local layer share one database" buys
 * [io.kotgent.store.SqliteTaskStore] implements BOTH sides, so [create] and [delete] can be one
 * transaction each: a create inserts the `tasks` row, its `backlog_entries` row at the end position and
 * its `created` activity row together, and a delete cascades to the entry, both directions of
 * `backlog_deps` and the feed. An external tracker's adapter would not have that luxury — it would
 * create the issue over the network and the local layer would add the entry afterwards — which is
 * exactly why the two concerns are separate interfaces even though one class answers both today.
 */
interface TaskTracker {

    /** This tracker's id — the `<tracker>` half of every [TaskRef] it mints. `"local"` for the built-in one. */
    val id: String

    /** Every task this tracker knows in [project], in no particular order (the backlog owns ordering). */
    suspend fun list(project: ProjectId): List<Task>

    /** One task, or `null` when the tracker does not know [ref]. */
    suspend fun get(ref: TaskRef): Task?

    /**
     * Create a task in [project] and return it. The built-in tracker mints the next `local:<n>` ref.
     *
     * [author] is who filed it, recorded on the `created` activity row: the calling **session id** when
     * the create came from inside a pane (`kotgent task add` runs there), and [BOARD_AUTHOR] only when
     * there genuinely is no session behind it. The default exists so the board — which has no pane and no
     * session — stays a three-argument call; a caller that HAS a session must pass it. The feed is the one
     * place the no-exclusivity design tells an operator to look to see who is doing what, so attributing
     * an agent's own card to "board" is not a missing detail, it is a confidently wrong answer.
     */
    suspend fun create(
        project: ProjectId,
        title: String,
        body: String,
        author: String = BOARD_AUTHOR,
    ): Task

    /**
     * Update the tracker fields of [ref]. A `null` argument means "leave unchanged" — not "clear" —
     * because both fields are `NOT NULL` and a `PATCH` omitting one must not blank it. Returns the
     * updated task, or `null` when [ref] is unknown.
     */
    suspend fun update(ref: TaskRef, title: String?, body: String?): Task?

    /** Remove [ref] entirely. Returns whether a task was actually removed. */
    suspend fun delete(ref: TaskRef): Boolean

    companion object {
        /**
         * The `author` [create] records when nothing but the board is behind it.
         *
         * It is spelled here rather than imported from `io.kotgent.daemon.TaskService.BOARD_AUTHOR`
         * because the layering runs `daemon → task` and a default parameter value has to be visible at
         * the interface that declares it. The two spellings are pinned equal by a test in
         * `test/store/TaskStoreTest.kt`, so the copy cannot drift.
         */
        const val BOARD_AUTHOR: String = "board"
    }
}
