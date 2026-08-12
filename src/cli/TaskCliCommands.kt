package io.kotgent.cli

import io.kotgent.task.MoveTarget

/*
 * These commands emit JSON. An explicit session bypasses pane resolution; optional task refs resolve
 * through the current pane when omitted.
 */

data class TaskAdd(
    val title: String,
    val body: String?,
    val project: String?,
    val session: String?,
) : CliCommand

data class TaskList(val project: String?, val session: String?) : CliCommand

data class TaskShow(val ref: String?, val session: String?) : CliCommand

/** Exits 3 only when no task is eligible. */
data class TaskNext(val project: String?, val session: String?) : CliCommand

/** Claiming is non-exclusive; an in-progress task may have multiple linked sessions. */
data class TaskClaim(val ref: String, val session: String?) : CliCommand

data class TaskComment(val ref: String?, val message: String, val session: String?) : CliCommand

data class TaskReview(val ref: String?, val message: String?, val session: String?) : CliCommand

/** Closing a task unlinks its sessions but does not archive them. */
data class TaskDone(val ref: String?, val message: String?, val session: String?) : CliCommand

/** Unlinking does not change task state. */
data class TaskUnlink(val ref: String?, val session: String?) : CliCommand

data class TaskMove(val ref: String, val target: MoveTarget, val session: String?) : CliCommand

data class TaskDep(
    val ref: String,
    val on: String,
    val remove: Boolean,
    val session: String?,
) : CliCommand

data class TaskDelete(val ref: String, val session: String?) : CliCommand

/** [archived] asks for the deleted projects instead of the live ones. */
data class ProjectList(val archived: Boolean) : CliCommand

/** Project initialization writes but never commits `.kotgent.json`. */
data class ProjectInit(val path: String?, val name: String?) : CliCommand

/**
 * The two sides of the delete tombstone, as one command carrying which side — the [TaskDep] shape. A
 * delete sets a mark the daemon respects; the file, the tasks and the sessions are untouched. Clearing
 * it by uuid is the only way back for an orphan whose directory is gone; restoring a project you are
 * standing in is already `project init`, whose adopt clears the mark.
 *
 * The uuid is required rather than derived from the cwd: in a worktree an operator can easily be standing
 * somewhere other than they think, and this is the one command where that matters.
 */
data class ProjectArchive(val id: String, val archived: Boolean) : CliCommand
