package io.kotgent.daemon

import io.kotgent.core.ProjectId
import io.kotgent.core.SessionId
import io.kotgent.core.TaskRef
import io.kotgent.store.EventStore
import io.kotgent.store.TaskStore
import io.kotgent.task.ActivityKind
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
 * ## What this class deliberately does NOT validate
 * Neither the session nor the task is checked for existence. [EventStore.setTaskRef] is a documented
 * no-op on a missing row and [TaskStore.appendActivity] answers `null` for an unknown ref, so a bad
 * argument here writes nothing and reports nothing — which is exactly why the ROUTES check first
 * (`resolveCallerSession`'s KDoc says so: "a route that writes on the caller's behalf checks it, because
 * a silent no-op on a missing row is exactly what `link` must not do"). Putting the check here as well
 * would mean a second read of both rows on every call and a second, differently-worded 404.
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
     *
     * The [TaskStore.startIfTodo] answer is deliberately **discarded**: `false` means the task was
     * already `in_progress`/`review`/`done` and the link is made all the same. Reading it as a failure
     * is the exclusivity this design does not have.
     */
    suspend fun link(sessionId: SessionId, ref: TaskRef) {
        tasks.startIfTodo(ref)
        sessions.setTaskRef(sessionId, ref, now())
        tasks.appendActivity(ref, ActivityKind.linked, author = sessionId.value)
    }

    /**
     * Take the next eligible task in [project] for [sessionId] and link it, or return `null` when nothing
     * is eligible — which is the ONLY "nothing eligible" signal, and what the CLI maps to exit `3`.
     *
     * Loops over [TaskStore.nextCandidate]: two agents racing land on the same candidate, one wins
     * [TaskStore.startIfTodo] and the loser re-queries (the row is no longer `todo`, so it is naturally
     * excluded) and takes the next. The loop ends when the query returns nothing. No `skip` set is
     * needed — nothing puts a candidate back to `todo` mid-loop, because nothing compensates.
     *
     * The returned entry is **re-read after the transition**, not the candidate row: the candidate was
     * `todo` by definition and this call is what made it `in_progress`, so handing back the pre-write
     * snapshot would print a state and a rev that were already wrong when they were read. A ref that
     * vanished inside that window falls back to the candidate rather than reporting "nothing eligible" —
     * a null return is the ONE signal the CLI maps to exit `3`, and the link really was made.
     */
    suspend fun linkNext(sessionId: SessionId, project: ProjectId): BacklogEntry? {
        while (true) {
            val candidate = tasks.nextCandidate(project) ?: return null
            if (!tasks.startIfTodo(candidate.ref)) continue
            sessions.setTaskRef(sessionId, candidate.ref, now())
            tasks.appendActivity(candidate.ref, ActivityKind.linked, author = sessionId.value)
            return tasks.entry(candidate.ref) ?: candidate
        }
    }

    /**
     * Drop [sessionId]'s link and leave the task's state **alone**. Whether the work is finished is not
     * something kotgent can infer from a session detaching, and several sessions may still be linked.
     *
     * The row is read first only to learn WHICH ref to attribute the `unlinked` activity row to; a
     * session that holds no link is a no-op that writes nothing at all, so a double `release` does not
     * stamp a second `updated_at` or a second feed entry.
     *
     * The clear is [EventStore.clearTaskRefIf], **conditional on the ref that read answered**: a `claim`
     * or a `next` landing between the two calls has pointed this session at a NEWER task, and clearing
     * unconditionally would erase that link while the feed claimed an unlink from the old one. Zero rows
     * is then the whole outcome — no `unlinked` row either, because none happened.
     */
    suspend fun unlink(sessionId: SessionId) {
        val ref = sessions.getSession(sessionId)?.taskRef ?: return
        if (!sessions.clearTaskRefIf(sessionId, ref, now())) return
        tasks.appendActivity(ref, ActivityKind.unlinked, author = sessionId.value)
    }

    /**
     * Move [ref] to [to] through [TaskStore.transition] (state + activity + reverse-dependent re-stamp,
     * one task-store transaction), then — for [TaskState.done] only — unlink every session holding it,
     * sequentially, afterwards.
     *
     * Closing from the board unlinks the sessions and leaves them **alive**, which is what hands a
     * long-lived worker session back to `task next`. Archiving a session is the session's own "Done", not
     * this.
     *
     * An unknown [ref] returns `null` and unlinks nobody: [TaskStore.transition]'s own `null` is the
     * only place that question is asked.
     */
    suspend fun transition(
        ref: TaskRef,
        to: TaskState,
        author: String,
        message: String? = null,
    ): BacklogEntry? {
        val entry = tasks.transition(ref, to, author, message) ?: return null
        if (to == TaskState.done) unlinkEveryHolder(ref, feed = true)
        return entry
    }

    /**
     * Unlink every session holding [ref] first, then delete the task through the tracker (which cascades
     * to its entry, both directions of its dependencies and its feed). The ordinary case therefore leaves
     * no dangling badge; the racing case is covered by `task_ref` being a reference, not a foreign key.
     *
     * The holders are unlinked even when the ref turns out to be unknown — the reads and the clear are
     * cheap, and a `sessions` row pointing at a task that is already gone is precisely the dangling badge
     * this method exists to avoid. No `unlinked` activity rows are written: the very next statement
     * deletes the feed they would land in.
     */
    suspend fun delete(ref: TaskRef): Boolean {
        unlinkEveryHolder(ref, feed = false)
        return tasks.delete(ref)
    }

    /**
     * Clear `sessions.task_ref` on every session holding [ref], one sequential [EventStore] call each,
     * optionally appending an `unlinked` activity row per holder.
     *
     * Two properties are deliberate. The clear is [EventStore.clearTaskRefIf], **conditional on [ref]**:
     * `sessionsHoldingTask` is a snapshot, and a holder re-pointed at a different task between that list
     * and its own clear is newer than everything this loop read. An unconditional clear erased that link
     * — leaving the newer task `in_progress` with no terminal behind it — and then wrote an `unlinked`
     * row naming the OLD ref, so the feed described a write that had not happened. The check rides in the
     * statement's `WHERE`, and the `unlinked` row is gated on its answer: a holder that slipped away is
     * simply not one of this close's holders. That refusal is a lost-update rule, not exclusivity —
     * nothing here refuses to MAKE a link, and a task may still be held by any number of sessions.
     * And the loop **never nests the two stores' locks**: each [EventStore] call returns before the
     * [TaskStore] call that follows it is made.
     */
    private suspend fun unlinkEveryHolder(ref: TaskRef, feed: Boolean) {
        for (holder in sessions.sessionsHoldingTask(ref)) {
            if (!sessions.clearTaskRefIf(holder.id, ref, now())) continue
            if (feed) tasks.appendActivity(ref, ActivityKind.unlinked, author = holder.id.value)
        }
    }

    companion object {
        /**
         * The `author` recorded for a change with no session behind it — the board creating a task,
         * dragging a card or closing one. Not a session id, and deliberately not blank: an activity feed
         * that cannot say who acted is worth less than one that says "the board".
         */
        const val BOARD_AUTHOR: String = "board"
    }
}
