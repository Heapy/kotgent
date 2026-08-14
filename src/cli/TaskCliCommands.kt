package io.kotgent.cli

import io.kotgent.task.MoveTarget

data class TaskAdd(
    val title: String,
    val body: String?,
    val project: String?,
    val session: String?,
) : CliCommand

data class TaskList(val project: String?, val session: String?) : CliCommand

data class TaskShow(val ref: String?, val session: String?) : CliCommand

data class TaskNext(val project: String?, val session: String?) : CliCommand

data class TaskClaim(val ref: String, val session: String?) : CliCommand

data class TaskComment(val ref: String?, val message: String, val session: String?) : CliCommand

data class TaskReview(val ref: String?, val message: String?, val session: String?) : CliCommand

data class TaskDone(val ref: String?, val message: String?, val session: String?) : CliCommand

data class TaskUnlink(val ref: String?, val session: String?) : CliCommand

data class TaskMove(val ref: String, val target: MoveTarget, val session: String?) : CliCommand

data class TaskDep(
    val ref: String,
    val on: String,
    val remove: Boolean,
    val session: String?,
) : CliCommand

data class TaskDelete(val ref: String, val session: String?) : CliCommand

data class ProjectList(val archived: Boolean) : CliCommand

data class ProjectInit(val path: String?, val name: String?) : CliCommand

data class ProjectArchive(val id: String, val archived: Boolean) : CliCommand
