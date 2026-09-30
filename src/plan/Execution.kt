package io.kotgent.plan

import kotlinx.serialization.Serializable

private data class DependencyFrame(val id: String, var nextEdge: Int = 0)

fun validatePlanDependencies(tasks: List<PlanTask>): List<FieldError> = buildList {
    val indices = tasks.mapIndexedNotNull { index, task -> task.id?.let { it to index } }.toMap()
    tasks.forEachIndexed { index, task ->
        task.dependsOn.forEachIndexed { edgeIndex, dependency ->
            if (dependency !in indices) {
                add(FieldError("tasks[$index].dependsOn[$edgeIndex]", "must identify a task in the document"))
            }
        }
    }
    val colors = mutableMapOf<String, Int>()
    val pending = ArrayDeque<DependencyFrame>()
    for (id in indices.keys) {
        if (colors[id] == 2) continue
        colors[id] = 1
        pending.addLast(DependencyFrame(id))
        while (pending.isNotEmpty()) {
            val frame = pending.last()
            val index = indices.getValue(frame.id)
            val dependencies = tasks[index].dependsOn
            if (frame.nextEdge == dependencies.size) {
                colors[frame.id] = 2
                pending.removeLast()
                continue
            }
            val edgeIndex = frame.nextEdge++
            val next = dependencies[edgeIndex]
            if (next !in indices) continue
            when (colors[next]) {
                1 -> add(FieldError("tasks[$index].dependsOn[$edgeIndex]", "must not form a dependency cycle"))
                2 -> Unit
                else -> {
                    colors[next] = 1
                    pending.addLast(DependencyFrame(next))
                }
            }
        }
    }
}

private val ACTIVE_TASK_STATUSES = setOf(
    PlanTaskStatus.running,
    PlanTaskStatus.in_review,
    PlanTaskStatus.awaiting_decision,
    PlanTaskStatus.merging,
)

fun readyTasks(plan: Plan): List<PlanTask> {
    if (plan.concurrency <= 0 || validatePlanDependencies(plan.tasks).isNotEmpty()) return emptyList()
    val slots = (plan.concurrency - plan.tasks.count { it.status in ACTIVE_TASK_STATUSES }).coerceAtLeast(0)
    val tasksById = plan.tasks.associateBy { it.id }
    return plan.tasks.filter { task ->
        task.id != null && task.status == PlanTaskStatus.pending &&
            task.dependsOn.all { tasksById[it]?.status == PlanTaskStatus.done }
    }.sortedBy { it.ordinal }.take(slots)
}

sealed interface ExecutionCaller {
    data class Orchestrator(val sessionId: String) : ExecutionCaller

    data class Session(val sessionId: String) : ExecutionCaller

    data object Operator : ExecutionCaller
}

enum class TaskTransitionRejection { invalid_transition, orchestrator_required, worker_required, wrong_worker, feedback_caller_required }

sealed interface TaskTransitionResult {
    data class Accepted(val task: PlanTask) : TaskTransitionResult

    data class Rejected(val reason: TaskTransitionRejection) : TaskTransitionResult
}

private enum class TransitionOwner { orchestrator, worker, feedback }

fun transitionTask(task: PlanTask, to: PlanTaskStatus, caller: ExecutionCaller): TaskTransitionResult {
    val owner = when (task.status) {
        PlanTaskStatus.pending, PlanTaskStatus.blocked -> when (to) {
            PlanTaskStatus.running -> TransitionOwner.orchestrator
            else -> null
        }
        PlanTaskStatus.running -> when (to) {
            PlanTaskStatus.in_review, PlanTaskStatus.blocked -> TransitionOwner.worker
            else -> null
        }
        PlanTaskStatus.in_review -> when (to) {
            PlanTaskStatus.awaiting_decision, PlanTaskStatus.merging -> TransitionOwner.orchestrator
            PlanTaskStatus.running -> TransitionOwner.feedback
            PlanTaskStatus.blocked -> TransitionOwner.worker
            else -> null
        }
        PlanTaskStatus.awaiting_decision -> when (to) {
            PlanTaskStatus.merging -> TransitionOwner.orchestrator
            PlanTaskStatus.running -> TransitionOwner.feedback
            PlanTaskStatus.blocked -> TransitionOwner.worker
            else -> null
        }
        PlanTaskStatus.merging -> when (to) {
            PlanTaskStatus.done -> TransitionOwner.orchestrator
            PlanTaskStatus.running -> TransitionOwner.feedback
            PlanTaskStatus.blocked -> TransitionOwner.worker
            else -> null
        }
        PlanTaskStatus.done -> null
    } ?: return TaskTransitionResult.Rejected(TaskTransitionRejection.invalid_transition)
    val rejection = when (owner) {
        TransitionOwner.orchestrator -> if (caller is ExecutionCaller.Orchestrator) null else TaskTransitionRejection.orchestrator_required
        TransitionOwner.worker -> when {
            caller !is ExecutionCaller.Session || task.worker == null -> TaskTransitionRejection.worker_required
            caller.sessionId != task.worker.sessionId -> TaskTransitionRejection.wrong_worker
            else -> null
        }
        TransitionOwner.feedback -> if (caller is ExecutionCaller.Orchestrator || caller == ExecutionCaller.Operator) {
            null
        } else {
            TaskTransitionRejection.feedback_caller_required
        }
    }
    return if (rejection == null) TaskTransitionResult.Accepted(task.copy(status = to)) else TaskTransitionResult.Rejected(rejection)
}

@Serializable
data class TaskReview(
    val taskId: String,
    val iteration: Long = 1,
    val mode: ExecutionMode,
    val nextMode: ExecutionMode = mode,
)

fun switchReviewMode(review: TaskReview, mode: ExecutionMode): TaskReview = review.copy(nextMode = mode)

fun nextTaskReview(review: TaskReview): TaskReview = TaskReview(review.taskId, review.iteration + 1, review.nextMode)
