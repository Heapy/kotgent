package io.kotgent.plan

import io.kotgent.core.TaskRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val MAX_PLAN_DOCUMENT_BYTES: Int = 512 * 1024
const val MAX_BLOCK_BODY_BYTES: Int = 32 * 1024
const val MAX_THREAD_MESSAGE_BYTES: Int = 8 * 1024

@Serializable
data class FieldError(val path: String, val message: String)

internal val PLAN_JSON = Json { encodeDefaults = true }

fun validatePlan(plan: Plan): List<FieldError> = buildList {
    if (TaskRef.parseOrNull(plan.taskRef) == null) {
        add(FieldError("taskRef", "must be a task reference such as local:42"))
    }
    required("title", plan.title)
    nonNegative("rev", plan.rev)
    if (plan.concurrency < 1) add(FieldError("concurrency", "must be positive"))
    plan.featureBranch?.let { required("featureBranch", it) }
    val ids = mutableSetOf<String>()
    fun block(block: PlanBlock, path: String, prefix: String) {
        block.id?.let { id ->
            if (!isPlanId(id, prefix)) add(FieldError("$path.id", "must start with $prefix and have a nonempty safe suffix"))
            if (!ids.add(id)) add(FieldError("$path.id", "must be unique in the document"))
        }
        nonNegative("$path.rev", block.rev)
    }
    plan.sections.forEachIndexed { index, section ->
        val path = "sections[$index]"
        block(section, path, "s_")
        body("$path.body", section.body, MAX_BLOCK_BODY_BYTES)
    }
    plan.decisions.forEachIndexed { index, decision ->
        val path = "decisions[$index]"
        block(decision, path, "d_")
        required("$path.title", decision.title)
        body("$path.body", decision.body, MAX_BLOCK_BODY_BYTES)
    }
    plan.tasks.forEachIndexed { index, task ->
        val path = "tasks[$index]"
        block(task, path, "t_")
        if (task.ordinal < 1) add(FieldError("$path.ordinal", "must be positive"))
        body("$path.title", task.title, MAX_BLOCK_BODY_BYTES)
        task.worker?.let { worker ->
            required("$path.worker.sessionId", worker.sessionId)
            required("$path.worker.branch", worker.branch)
            required("$path.worker.worktree", worker.worktree)
        }
        task.files.forEachIndexed { fileIndex, file -> required("$path.files[$fileIndex].path", file.path) }
        task.steps.forEachIndexed { stepIndex, step ->
            val stepPath = "$path.steps[$stepIndex]"
            block(step, stepPath, "st_")
            body("$stepPath.text", step.text, MAX_BLOCK_BODY_BYTES)
        }
    }
    addAll(validatePlanDependencies(plan.tasks))
    if (PLAN_JSON.encodeToString(plan).encodeToByteArray().size > MAX_PLAN_DOCUMENT_BYTES) {
        add(FieldError("document", "must be at most $MAX_PLAN_DOCUMENT_BYTES UTF-8 bytes of JSON"))
    }
}

fun validateThreadMessage(message: ThreadMessage): List<FieldError> = buildList {
    actor("author", message.author)
    body("body", message.body, MAX_THREAD_MESSAGE_BYTES)
    nonNegative("at", message.at)
}

fun validateThread(thread: PlanThread): List<FieldError> = buildList {
    if (!isPlanId(thread.id, "th_")) add(FieldError("id", "must be a thread id starting with th_"))
    if (listOf("s_", "d_", "t_", "st_").none { isPlanId(thread.blockId, it) }) {
        add(FieldError("blockId", "must identify a plan block"))
    }
    if (thread.messages.isEmpty()) add(FieldError("messages", "must contain at least one message"))
    thread.messages.forEachIndexed { index, message ->
        addAll(validateThreadMessage(message).map { it.copy(path = "messages[$index].${it.path}") })
    }
}

internal fun isPlanId(value: String, prefix: String): Boolean =
    value.startsWith(prefix) && value.length > prefix.length &&
        value.substring(prefix.length).all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

internal fun MutableList<FieldError>.required(path: String, value: String) {
    if (value.isBlank()) add(FieldError(path, "must not be blank"))
}

internal fun MutableList<FieldError>.body(path: String, value: String, maxBytes: Int) {
    required(path, value)
    if (value.encodeToByteArray().size > maxBytes) add(FieldError(path, "must be at most $maxBytes UTF-8 bytes"))
}

internal fun MutableList<FieldError>.nonNegative(path: String, value: Long) {
    if (value < 0) add(FieldError(path, "must not be negative"))
}

internal fun MutableList<FieldError>.actor(path: String, value: PlanActor) {
    if (value is PlanActor.Session) required("$path.sessionId", value.sessionId)
}
