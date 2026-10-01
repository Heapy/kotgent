package io.kotgent.plan

import kotlinx.serialization.Serializable

@Serializable
data class ExecutionEvent(
    val id: Long,
    val kind: String,
    val taskId: String? = null,
    val workerSessionId: String? = null,
    val findingIds: List<String> = emptyList(),
    val rebaseOnto: String? = null,
    val at: Long,
)

@Serializable
data class PlanExecutionState(
    val orchestratorSessionId: String? = null,
    val reviews: List<TaskReview> = emptyList(),
    val findings: List<Finding> = emptyList(),
    val events: List<ExecutionEvent> = emptyList(),
    val nextEventId: Long = 1,
)

sealed interface PlanAction {
    data class Claim(val previousSessionId: String?) : PlanAction
    data class Settings(val mode: ExecutionMode? = null, val featureBranch: String? = null, val concurrency: Int? = null) : PlanAction
    data class Start(val taskId: String) : PlanAction
    data class Worker(val taskId: String, val worker: PlanWorker) : PlanAction
    data class Status(val taskId: String, val status: PlanTaskStatus) : PlanAction
    data class Feedback(val taskId: String, val findingIds: List<String> = emptyList(), val rebaseOnto: String? = null) : PlanAction
    data class StepDone(val stepId: String) : PlanAction
    data class AddFinding(val finding: Finding) : PlanAction
    data class Verify(val id: String, val rev: Long, val verifier: FindingVerifier) : PlanAction
    data class Amend(val id: String, val rev: Long, val finding: Finding) : PlanAction
    data class Note(val id: String, val body: String) : PlanAction
    data class Decide(val id: String, val rev: Long, val decision: FindingDecision) : PlanAction
    data class Send(val taskId: String) : PlanAction
    data class Investigator(val id: String, val rev: Long, val sessionId: String) : PlanAction
    data class WorkerEnded(val sessionId: String) : PlanAction
    data object Complete : PlanAction
}

enum class ExecutionFailure { invalid, conflict, forbidden }
sealed interface ExecutionChange {
    data class Accepted(val plan: Plan, val execution: PlanExecutionState) : ExecutionChange
    data class Rejected(val failure: ExecutionFailure, val errors: List<FieldError>) : ExecutionChange
}

/** Caller identities have already been resolved at the edge; all state-dependent guards run here atomically. */
fun applyPlanAction(
    original: Plan,
    state: PlanExecutionState,
    action: PlanAction,
    actor: PlanActor,
    now: Long,
    newId: (String) -> String,
): ExecutionChange {
    var plan = original
    var execution = state
    val session = (actor as? PlanActor.Session)?.sessionId
    val orchestrator = session != null && session == state.orchestratorSessionId
    fun reject(message: String, kind: ExecutionFailure = ExecutionFailure.conflict, path: String = "execution") =
        ExecutionChange.Rejected(kind, listOf(FieldError(path, message)))
    fun forbidden(message: String) = reject(message, ExecutionFailure.forbidden)
    fun task(id: String) = plan.tasks.firstOrNull { it.id == id }
    fun review(id: String) = execution.reviews.lastOrNull { it.taskId == id }
    fun batch(id: String) = execution.findings.filter { it.taskId == id && it.iteration == review(id)?.iteration }
    fun event(kind: String, taskId: String? = null, worker: String? = null, findings: List<String> = emptyList(), rebase: String? = null) {
        execution = execution.copy(events = execution.events + ExecutionEvent(execution.nextEventId, kind, taskId, worker, findings, rebase, now),
            nextEventId = execution.nextEventId + 1)
    }
    fun replaceTask(next: PlanTask) { plan = plan.copy(tasks = plan.tasks.map { if (it.id == next.id) next else it }) }
    fun replaceFinding(next: Finding) {
        execution = execution.copy(findings = execution.findings.map { if (it.id == next.id) next else it })
    }
    fun currentFinding(id: String): Finding? = execution.findings.firstOrNull { it.id == id && it.iteration == review(it.taskId)?.iteration }
    fun transition(current: PlanTask, to: PlanTaskStatus): ExecutionChange.Rejected? {
        val caller = when {
            session == current.worker?.sessionId && session != null -> ExecutionCaller.Session(session)
            orchestrator -> ExecutionCaller.Orchestrator(requireNotNull(session))
            actor == PlanActor.Operator -> ExecutionCaller.Operator
            else -> ExecutionCaller.Session(session.orEmpty())
        }
        return when (val changed = transitionTask(current, to, caller)) {
            is TaskTransitionResult.Accepted -> { replaceTask(changed.task); null }
            is TaskTransitionResult.Rejected -> reject(changed.reason.name,
                if (changed.reason == TaskTransitionRejection.invalid_transition) ExecutionFailure.conflict else ExecutionFailure.forbidden)
        }
    }
    fun feedback(current: PlanTask, ids: List<String>, rebase: String?): ExecutionChange.Rejected? {
        if (!orchestrator && actor != PlanActor.Operator) return forbidden("orchestrator or operator required")
        if (current.worker == null) return reject("no worker is assigned")
        if ((ids.isEmpty()) == (rebase.isNullOrBlank())) return reject("supply findingIds or rebaseOnto", ExecutionFailure.invalid)
        if (ids.isNotEmpty()) {
            val findings = batch(requireNotNull(current.id))
            if (ids.size != ids.toSet().size || ids.toSet() != findings.map { it.id }.toSet()) return reject("feedback must name the complete current finding batch")
            if (findings.any { it.verifier == null }) return reject("all findings must be verified")
            if (review(current.id)?.mode == ExecutionMode.supervised && findings.any { it.decision == null }) return reject("all findings require operator decisions")
        }
        transition(current, PlanTaskStatus.running)?.let { return it }
        event(if (rebase == null) "feedback" else "rebase", current.id, current.worker.sessionId, ids, rebase)
        return null
    }
    if (execution.nextEventId == Long.MAX_VALUE) return reject("event sequence exhausted")
    when (action) {
        is PlanAction.Claim -> {
            if (session == null) return forbidden("calling session required")
            if (state.orchestratorSessionId != action.previousSessionId) return reject("orchestrator changed")
            if (plan.status !in setOf(PlanStatus.approved, PlanStatus.executing)) return reject("an approved plan is required")
            execution = execution.copy(orchestratorSessionId = session)
        }
        is PlanAction.Settings -> {
            if (action.mode == null && action.featureBranch == null && action.concurrency == null) return reject("supply a setting", ExecutionFailure.invalid)
            plan = plan.copy(mode = action.mode ?: plan.mode, featureBranch = action.featureBranch ?: plan.featureBranch,
                concurrency = action.concurrency ?: plan.concurrency)
            if (action.mode != null) execution = execution.copy(reviews = execution.reviews.map { r ->
                if (r == review(r.taskId)) switchReviewMode(r, action.mode) else r
            })
        }
        is PlanAction.Start -> {
            if (!orchestrator) return forbidden("orchestrator required")
            if (plan.status !in setOf(PlanStatus.approved, PlanStatus.executing)) return reject("an approved plan is required")
            val current = task(action.taskId) ?: return reject("unknown task", ExecutionFailure.invalid, "taskId")
            if (current.status == PlanTaskStatus.running) return ExecutionChange.Accepted(plan, execution)
            // The pure scheduler checks dependency completion and occupied slots for blocked retries too.
            val candidate = plan.copy(tasks = plan.tasks.map { if (it.id == current.id) it.copy(status = PlanTaskStatus.pending) else it })
            if (readyTasks(candidate).none { it.id == current.id }) return reject("task is not ready or concurrency is full")
            transition(current, PlanTaskStatus.running)?.let { return it }
            plan = plan.copy(status = PlanStatus.executing)
            event("started", current.id)
        }
        is PlanAction.Worker -> {
            if (!orchestrator) return forbidden("orchestrator required")
            val current = task(action.taskId) ?: return reject("unknown task", ExecutionFailure.invalid, "taskId")
            if (current.status != PlanTaskStatus.running) return reject("start the task before assigning a worker")
            if (action.worker.sessionId == session || plan.tasks.any { it.id != current.id && it.worker?.sessionId == action.worker.sessionId }) return reject("worker must be a distinct child session")
            replaceTask(current.copy(worker = action.worker))
        }
        is PlanAction.Status -> {
            val current = task(action.taskId) ?: return reject("unknown task", ExecutionFailure.invalid, "taskId")
            if (current.status == action.status) {
                val worker = session != null && current.worker?.sessionId == session
                if (!orchestrator && !worker) return forbidden("assigned worker or orchestrator required")
                return ExecutionChange.Accepted(plan, execution)
            }
            if (action.status == PlanTaskStatus.running) return reject("use start or feedback to resume work", ExecutionFailure.invalid)
            if (action.status == PlanTaskStatus.merging && batch(action.taskId).any { it.verifier == null || it.decision == null }) return reject("every finding must be verified and decided before merging")
            transition(current, action.status)?.let { return it }
            if (action.status == PlanTaskStatus.in_review) {
                val previous = review(action.taskId)
                val next = previous?.let(::nextTaskReview) ?: TaskReview(action.taskId, mode = plan.mode)
                execution = execution.copy(reviews = execution.reviews + next)
            }
            event(action.status.name, current.id)
        }
        is PlanAction.Feedback -> {
            val current = task(action.taskId) ?: return reject("unknown task", ExecutionFailure.invalid, "taskId")
            feedback(current, action.findingIds, action.rebaseOnto)?.let { return it }
        }
        is PlanAction.StepDone -> {
            val owner = plan.tasks.firstOrNull { t -> t.steps.any { it.id == action.stepId } }
                ?: return reject("unknown step", ExecutionFailure.invalid, "stepId")
            if (session == null || session != owner.worker?.sessionId) return forbidden("assigned worker required")
            if (owner.status != PlanTaskStatus.running) return reject("task is not running")
            replaceTask(owner.copy(steps = owner.steps.map { if (it.id == action.stepId) it.copy(done = true) else it }))
        }
        is PlanAction.AddFinding -> {
            if (session == null) return forbidden("calling session required")
            val current = task(action.finding.taskId) ?: return reject("unknown task", ExecutionFailure.invalid, "taskId")
            if (current.status != PlanTaskStatus.in_review) return reject("findings can be added only during task review")
            val round = review(action.finding.taskId) ?: return reject("no task review is open")
            if (action.finding.id != null) return reject("omit the id for a new finding", ExecutionFailure.invalid, "id")
            val finding = action.finding.copy(id = newId("f_"), rev = 1, iteration = round.iteration, author = actor,
                verifier = null, verifiedBy = null, decision = null, investigatorSessionId = null, revisions = emptyList(), notes = emptyList())
            if (execution.findings.any { it.id == finding.id }) return reject("finding id collision")
            execution = execution.copy(findings = execution.findings + finding)
        }
        is PlanAction.Verify -> {
            if (session == null) return forbidden("calling session required")
            val finding = currentFinding(action.id) ?: return reject("unknown or superseded finding")
            if (finding.rev != action.rev || finding.decision != null) return reject("finding changed or was decided")
            replaceFinding(finding.copy(verifier = action.verifier, verifiedBy = actor, rev = finding.rev + 1))
        }
        is PlanAction.Amend -> {
            if (session == null) return forbidden("calling session required")
            val finding = currentFinding(action.id) ?: return reject("unknown or superseded finding")
            if (finding.rev != action.rev) return reject("finding changed")
            if (task(finding.taskId)?.status !in setOf(PlanTaskStatus.in_review, PlanTaskStatus.awaiting_decision)) return reject("review is no longer accepting amendments")
            val revision = FindingRevision(finding.condition, finding.impact, finding.danger, finding.likelihood,
                finding.options, finding.recommended, finding.location, actor, now)
            val details = action.finding
            replaceFinding(finding.copy(condition = details.condition, impact = details.impact, danger = details.danger,
                likelihood = details.likelihood, options = details.options, recommended = details.recommended,
                location = details.location, verifier = null, verifiedBy = null, decision = null,
                revisions = finding.revisions + revision, rev = finding.rev + 1))
        }
        is PlanAction.Note -> {
            if (session == null) return forbidden("calling session required")
            val finding = currentFinding(action.id) ?: return reject("unknown or superseded finding")
            val note = ThreadMessage(actor, action.body, now)
            val errors = validateThreadMessage(note)
            if (errors.isNotEmpty()) return ExecutionChange.Rejected(ExecutionFailure.invalid, errors)
            replaceFinding(finding.copy(notes = finding.notes + note, rev = finding.rev + 1))
            event("finding_note", finding.taskId, findings = listOf(action.id))
        }
        is PlanAction.Decide -> {
            val finding = currentFinding(action.id) ?: return reject("unknown or superseded finding")
            if (finding.rev != action.rev) return reject("finding changed")
            if (finding.verifier == null) return reject("finding must be verified")
            val round = review(finding.taskId) ?: return reject("no task review is open")
            if (round.mode == ExecutionMode.supervised && task(finding.taskId)?.status !in setOf(PlanTaskStatus.in_review, PlanTaskStatus.awaiting_decision)) return reject("decisions were already sent to the worker")
            val decision = action.decision.copy(decidedBy = actor)
            val errors = validateFindingDecision(finding, decision, round.mode, task(finding.taskId)?.worker?.sessionId)
            if (errors.isNotEmpty()) return ExecutionChange.Rejected(
                if (errors.any { it.path == "decision.decidedBy" }) ExecutionFailure.forbidden else ExecutionFailure.invalid, errors)
            replaceFinding(finding.copy(decision = decision, rev = finding.rev + 1))
            event("finding_decided", finding.taskId, findings = listOf(action.id))
        }
        is PlanAction.Send -> {
            if (actor != PlanActor.Operator) return forbidden("operator required")
            val current = task(action.taskId) ?: return reject("unknown task", ExecutionFailure.invalid, "taskId")
            if (review(action.taskId)?.mode != ExecutionMode.supervised) return reject("this review is autonomous")
            val findings = batch(action.taskId)
            if (findings.isEmpty()) return reject("no findings to send")
            feedback(current, findings.map { requireNotNull(it.id) }, null)?.let { return it }
        }
        is PlanAction.Investigator -> {
            if (actor != PlanActor.Operator) return forbidden("operator required")
            val finding = currentFinding(action.id) ?: return reject("unknown or superseded finding")
            if (finding.rev != action.rev || finding.decision != null) return reject("finding changed or was decided")
            if (review(finding.taskId)?.mode != ExecutionMode.supervised) return reject("this review is autonomous")
            replaceFinding(finding.copy(investigatorSessionId = action.sessionId, rev = finding.rev + 1))
        }
        is PlanAction.WorkerEnded -> {
            for (current in plan.tasks.filter { it.worker?.sessionId == action.sessionId && it.status !in setOf(PlanTaskStatus.done, PlanTaskStatus.blocked, PlanTaskStatus.pending) }) {
                replaceTask(current.copy(status = PlanTaskStatus.blocked))
                event("worker_lost", current.id)
            }
        }
        PlanAction.Complete -> {
            if (!orchestrator) return forbidden("orchestrator required")
            if (plan.status == PlanStatus.done) return ExecutionChange.Accepted(plan, execution)
            if (plan.status != PlanStatus.executing || plan.tasks.any { it.status != PlanTaskStatus.done }) return reject("finish every task before completing the plan")
            plan = plan.copy(status = PlanStatus.done)
            event("completed")
        }
    }
    val errors = validatePlan(plan) + execution.findings.flatMap(::validateFinding)
    if (errors.isNotEmpty()) return ExecutionChange.Rejected(ExecutionFailure.invalid, errors)
    return ExecutionChange.Accepted(plan, execution)
}
