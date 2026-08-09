package io.kotgent.daemon

import io.kotgent.core.ProjectId
import io.kotgent.core.SessionId
import io.kotgent.core.TaskRef
import io.kotgent.store.EventStore
import io.kotgent.store.TaskStore
import io.kotgent.task.BacklogEntry
import io.kotgent.task.ProjectFileWriter
import io.kotgent.task.ProjectFs
import io.kotgent.task.TaskState

/**
 * The one place the task store and the event store meet.
 *
 * ## The rule that shapes every method here
 * `sessions` has exactly one writer ([EventStore]) and `backlog_entries` has another ([TaskStore]), each
 * with its own mutex over the same driver. So the two are called **sequentially, never nested**: a
 * transaction of one store must never be open while the other's lock is being taken, or two callers
 * arriving from opposite directions deadlock a `Dispatchers.Default` thread apiece.
 *
 * ## Linking is two independent writes, and neither is conditional on the other
 * ```
 * task store:  UPDATE backlog_entries SET state='in_progress' … WHERE task_ref=? AND state='todo'
 * event store: UPDATE sessions        SET task_ref=?           … WHERE id=?
 * ```
 * Zero rows from the first is NORMAL — the task was already in progress — and the link is still made.
 * There is no compensation, no ordering requirement and no residual to reconcile: a crash between them
 * leaves either a task `in_progress` with no session (indistinguishable from, and as legitimate as, a
 * card a human dragged into that column) or a session pointing at a task still marked `todo` (fixed by
 * the next link or a board drag). Neither is an inconsistency worth code.
 *
 * kotgent deliberately enforces **no exclusivity**. It cannot: the operator opens a second terminal in
 * the same repository and the daemon never hears about it, and an invariant that only holds against your
 * own API is not an invariant. A task may be linked from any number of sessions, the board shows all of
 * them, and an explicit `task claim <ref>` on a task already in progress is allowed.
 *
 * Bodies are [TODO] on purpose: Task 11 of the task-backlog plan implements this file.
 *
 * @param projectFs carried here — unused by this class's own methods — so [io.kotgent.transport.KotgentServer]
 *   needs only the two nullable task parameters the plan specifies, and the write routes can still reach
 *   project resolution. Same for [projectFiles].
 */
class TaskService(
    private val tasks: TaskStore,
    private val sessions: EventStore,
    val projectFs: ProjectFs,
    val projectFiles: ProjectFileWriter,
    private val now: () -> Long = ::daemonEpochMillis,
) {

    /**
     * Link [sessionId] to [ref]: the conditional `todo → in_progress` transition, then the unconditional
     * `sessions.task_ref` write, then a `linked` activity row. Overwrites whatever the session pointed at
     * before — a session works one task at a time and there is no error case.
     */
    suspend fun link(sessionId: SessionId, ref: TaskRef) {
        TODO("Task 11: two independent writes")
    }

    /**
     * Take the next eligible task in [project] for [sessionId] and link it, or return `null` when nothing
     * is eligible — which is the ONLY "nothing eligible" signal, and what the CLI maps to exit `3`.
     *
     * Loops over [TaskStore.nextCandidate]: two agents racing land on the same candidate, one wins
     * [TaskStore.startIfTodo] and the loser re-queries (the row is no longer `todo`, so it is naturally
     * excluded) and takes the next. The loop ends when the query returns nothing. No `skip` set is
     * needed — nothing puts a candidate back to `todo` mid-loop, because nothing compensates.
     */
    suspend fun linkNext(sessionId: SessionId, project: ProjectId): BacklogEntry? =
        TODO("Task 11: contended selection loop")

    /**
     * Drop [sessionId]'s link and leave the task's state **alone**. Whether the work is finished is not
     * something kotgent can infer from a session detaching, and several sessions may still be linked.
     */
    suspend fun unlink(sessionId: SessionId) {
        TODO("Task 11: unlink one session")
    }

    /**
     * Move [ref] to [to] through [TaskStore.transition] (state + activity + reverse-dependent re-stamp,
     * one task-store transaction), then — for [TaskState.done] only — unlink every session holding it,
     * sequentially, afterwards.
     *
     * Closing from the board unlinks the sessions and leaves them **alive**, which is what hands a
     * long-lived worker session back to `task next`. Archiving a session is the session's own "Done", not
     * this.
     */
    suspend fun transition(
        ref: TaskRef,
        to: TaskState,
        author: String,
        message: String? = null,
    ): BacklogEntry? = TODO("Task 11: transition, unlinking holders on done")

    /**
     * Unlink every session holding [ref] first, then delete the task through the tracker (which cascades
     * to its entry, both directions of its dependencies and its feed). The ordinary case therefore leaves
     * no dangling badge; the racing case is covered by `task_ref` being a reference, not a foreign key.
     */
    suspend fun delete(ref: TaskRef): Boolean = TODO("Task 11: unlink holders, then delete")

    companion object {
        /**
         * The `author` recorded for a change with no session behind it — the board creating a task,
         * dragging a card or closing one. Not a session id, and deliberately not blank: an activity feed
         * that cannot say who acted is worth less than one that says "the board".
         */
        const val BOARD_AUTHOR: String = "board"
    }
}
