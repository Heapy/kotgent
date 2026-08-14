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

// The two stores have independent mutexes; calls must remain sequential and never nest transactions.
// Links are deliberately non-exclusive: multiple sessions may hold the same task.
class TaskService(
    private val tasks: TaskStore,
    private val sessions: EventStore,
    val projectFs: ProjectFs,
    val projectFiles: ProjectFileWriter,
) {

    suspend fun link(sessionId: SessionId, ref: TaskRef) {
        tasks.startIfTodo(ref, requireLiveProject = false)
        sessions.setTaskRef(sessionId, ref)
        tasks.appendActivity(ref, ActivityKind.linked, author = sessionId.value)
    }

    suspend fun linkNext(sessionId: SessionId, project: ProjectId): BacklogEntry? {
        // A competing claimant or concurrent archive loses the start and re-queries.
        while (true) {
            val candidate = tasks.nextCandidate(project) ?: return null
            if (!tasks.startIfTodo(candidate.ref, requireLiveProject = true)) continue
            sessions.setTaskRef(sessionId, candidate.ref)
            tasks.appendActivity(candidate.ref, ActivityKind.linked, author = sessionId.value)
            return tasks.entry(candidate.ref) ?: candidate
        }
    }

    suspend fun unlink(sessionId: SessionId): Boolean {
        val ref = sessions.getSession(sessionId)?.taskRef ?: return false
        if (!sessions.clearTaskRefIf(sessionId, ref)) return false
        tasks.appendActivity(ref, ActivityKind.unlinked, author = sessionId.value)
        return true
    }

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

    suspend fun delete(ref: TaskRef): Boolean {
        unlinkEveryHolder(ref, feed = false)
        return tasks.delete(ref)
    }

    private suspend fun unlinkEveryHolder(ref: TaskRef, feed: Boolean) {
        // The conditional clear protects a newer link made after sessionsHoldingTask's snapshot.
        for (holder in sessions.sessionsHoldingTask(ref)) {
            if (!sessions.clearTaskRefIf(holder.id, ref)) continue
            if (feed) tasks.appendActivity(ref, ActivityKind.unlinked, author = holder.id.value)
        }
    }

    companion object {
        const val BOARD_AUTHOR: String = "board"
    }
}
