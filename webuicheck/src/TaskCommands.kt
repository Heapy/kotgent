package io.kotgent.webuicheck

import io.kotgent.core.TaskRef
import io.kotgent.daemon.TaskService
import io.kotgent.task.TaskState
import kotlinx.coroutines.runBlocking


fun handleTaskCommand(words: List<String>, ctx: HarnessContext): Boolean {
    return when (words.firstOrNull()) {
        "task" -> runBlocking { applyTaskState(ctx, words) }
        "task-add" -> runBlocking { addTask(ctx, words) }
        "task-del" -> runBlocking { deleteTask(ctx, words) }
        "task-race" -> runBlocking { raceTask(ctx, words) }
        else -> false
    }
}

private suspend fun applyTaskState(ctx: HarnessContext, words: List<String>): Boolean {
    if (words.size != 3) return reject("usage: task <ref> <state>; states: ${taskStateNames()}")
    val ref = TaskRef.parseOrNull(words[1]) ?: return rejectRef(words[1])
    val state = taskStateOrNull(words[2])
        ?: return reject("task: '${words[2]}' is not a task state; expected one of ${taskStateNames()}")
    return taskService(ctx).transition(ref, state, TASK_COMMAND_AUTHOR) != null ||
        reject("task: no task '${ref.value}' in this scenario")
}

// The ref must be unseen so EventsWs emits a full task_row rather than a task_update patch.
private suspend fun addTask(ctx: HarnessContext, words: List<String>): Boolean {
    if (words.size != 2 && words.size != 3) return reject("usage: task-add <ref> [position]")
    val ref = TaskRef.parseOrNull(words[1]) ?: return rejectRef(words[1])
    val position = if (words.size == 3) {
        words[2].toDoubleOrNull()
            ?: return reject("task-add: '${words[2]}' is not a rank; expected a number such as 2.5")
    } else {
        null
    }
    val tasks = ctx.fakes.tasks
    if (tasks.entry(ref) != null) {
        return reject(
            "task-add: '${ref.value}' already exists — the socket has carried it in its opening " +
                "snapshot, so re-adding it would emit a `task_update` and not the `task_row` you asked for",
        )
    }
    val project = tasks.listProjects().firstOrNull()?.id
        ?: return reject("task-add: this scenario registers no project to file '${ref.value}' under")
    tasks.addTask(ref, project, title = "Added ${ref.value}", position = position)
    return true
}

private suspend fun deleteTask(ctx: HarnessContext, words: List<String>): Boolean {
    if (words.size != 2) return reject("usage: task-del <ref>")
    val ref = TaskRef.parseOrNull(words[1]) ?: return rejectRef(words[1])
    return taskService(ctx).delete(ref) || reject("task-del: no task '${ref.value}' in this scenario")
}

// Direct store access produces one newer revision without TaskService's unlink side effects. The
// scenario must use a ref with no dependents, whose restamps would add unrelated frames.
private suspend fun raceTask(ctx: HarnessContext, words: List<String>): Boolean {
    if (words.size != 2) return reject("usage: task-race <ref>")
    val ref = TaskRef.parseOrNull(words[1]) ?: return rejectRef(words[1])
    val current = ctx.fakes.tasks.entry(ref)
        ?: return reject("task-race: no task '${ref.value}' in this scenario")
    val next = TaskState.entries[(current.state.ordinal + 1) % TaskState.entries.size]
    return ctx.fakes.tasks.transition(ref, next, TASK_COMMAND_AUTHOR, message = null) != null ||
        reject("task-race: '${ref.value}' refused the step to ${next.name}")
}

private fun taskService(ctx: HarnessContext): TaskService = ctx.taskService

private fun taskStateOrNull(word: String): TaskState? = TaskState.entries.firstOrNull { it.name == word }

private fun taskStateNames(): String = TaskState.entries.joinToString(" ") { it.name }

private fun rejectRef(word: String): Boolean =
    reject("'$word' is not a task ref; expected <tracker>:<key>, e.g. local:3")

private const val TASK_COMMAND_AUTHOR: String = "webuicheck"
