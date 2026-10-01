package io.kotgent.plan

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

const val MAX_FINDING_BYTES: Int = 32 * 1024

@Serializable
enum class FindingLevel { low, medium, high }

@Serializable
data class FindingOption(
    val fix: String = "",
    val outcome: String = "",
    val cost: FindingLevel? = null,
    val fit: FindingLevel? = null,
)

@Serializable
data class VerifierOption(val cost: FindingLevel? = null, val fit: FindingLevel? = null)

@Serializable
enum class VerifierVerdict { confirmed, rejected }

@Serializable
data class FindingVerifier(
    val danger: FindingLevel? = null,
    val likelihood: FindingLevel? = null,
    val options: List<VerifierOption> = emptyList(),
    val verdict: VerifierVerdict? = null,
    val reason: String = "",
)

@Serializable
enum class FindingDecisionKind { fix_now, fix_later, wont_fix }

@Serializable
data class FindingDecision(
    val kind: FindingDecisionKind? = null,
    val optionIndex: Int? = null,
    val note: String? = null,
    val decidedBy: PlanActor? = null,
)

@Serializable
data class FindingRevision(
    val condition: String = "",
    val impact: String = "",
    val danger: FindingLevel? = null,
    val likelihood: FindingLevel? = null,
    val options: List<FindingOption> = emptyList(),
    val recommended: Int? = null,
    val location: String? = null,
    val author: PlanActor,
    val at: Long,
)

@Serializable
data class Finding(
    val id: String? = null,
    val rev: Long = 1,
    val iteration: Long = 1,
    val author: PlanActor? = null,
    val verifiedBy: PlanActor? = null,
    val notes: List<ThreadMessage> = emptyList(),
    val taskId: String = "",
    val location: String? = null,
    val condition: String = "",
    val impact: String = "",
    val danger: FindingLevel? = null,
    val likelihood: FindingLevel? = null,
    val options: List<FindingOption> = emptyList(),
    val recommended: Int? = null,
    val verifier: FindingVerifier? = null,
    val decision: FindingDecision? = null,
    val investigatorSessionId: String? = null,
    val revisions: List<FindingRevision> = emptyList(),
)

fun validateFinding(finding: Finding): List<FieldError> = buildList {
    if (finding.rev < 1) add(FieldError("rev", "must be positive"))
    if (finding.iteration < 1) add(FieldError("iteration", "must be positive"))
    finding.notes.forEach { addAll(validateThreadMessage(it)) }
    if (finding.id != null && !isPlanId(finding.id, "f_")) add(FieldError("id", "must be a finding id starting with f_"))
    if (!isPlanId(finding.taskId, "t_")) add(FieldError("taskId", "must be a task id starting with t_"))
    findingDetails("", finding.location, finding.condition, finding.impact, finding.danger, finding.likelihood, finding.options, finding.recommended)
    finding.verifier?.let { verifier ->
        present("verifier.danger", verifier.danger)
        present("verifier.likelihood", verifier.likelihood)
        if (verifier.options.isEmpty() || verifier.options.size != finding.options.size) {
            add(FieldError("verifier.options", "must contain one score for each finding option"))
        }
        verifier.options.forEachIndexed { index, option ->
            present("verifier.options[$index].cost", option.cost)
            present("verifier.options[$index].fit", option.fit)
        }
        present("verifier.verdict", verifier.verdict)
        required("verifier.reason", verifier.reason)
    }
    finding.decision?.let { decision -> decisionShape(decision, finding.options.size) }
    finding.investigatorSessionId?.let { required("investigatorSessionId", it) }
    finding.revisions.forEachIndexed { index, revision ->
        val path = "revisions[$index]."
        findingDetails(path, revision.location, revision.condition, revision.impact, revision.danger, revision.likelihood, revision.options, revision.recommended)
        actor("${path}author", revision.author)
        nonNegative("${path}at", revision.at)
    }
    if (PLAN_JSON.encodeToString(finding).encodeToByteArray().size > MAX_FINDING_BYTES) {
        add(FieldError("finding", "must be at most $MAX_FINDING_BYTES UTF-8 bytes of JSON"))
    }
}

private fun MutableList<FieldError>.present(path: String, value: Any?) {
    if (value == null) add(FieldError(path, "is required"))
}

private fun MutableList<FieldError>.findingDetails(
    path: String,
    location: String?,
    condition: String,
    impact: String,
    danger: FindingLevel?,
    likelihood: FindingLevel?,
    options: List<FindingOption>,
    recommended: Int?,
) {
    if (location != null) {
        val file = location.substringBeforeLast(':', "")
        val line = location.substringAfterLast(':', "").toIntOrNull()
        if (file.isBlank() || line == null || line < 1) add(FieldError("${path}location", "must be file:line with a positive line number"))
    }
    required("${path}condition", condition)
    required("${path}impact", impact)
    present("${path}danger", danger)
    present("${path}likelihood", likelihood)
    if (options.isEmpty()) add(FieldError("${path}options", "must contain at least one option"))
    options.forEachIndexed { index, option ->
        required("${path}options[$index].fix", option.fix)
        required("${path}options[$index].outcome", option.outcome)
        present("${path}options[$index].cost", option.cost)
        present("${path}options[$index].fit", option.fit)
    }
    if (recommended == null) {
        add(FieldError("${path}recommended", "is required"))
    } else if (recommended !in options.indices) {
        add(FieldError("${path}recommended", "must index a finding option"))
    }
}

private fun MutableList<FieldError>.decisionShape(decision: FindingDecision, optionCount: Int) {
    when (decision.kind) {
        null -> add(FieldError("decision.kind", "is required"))
        FindingDecisionKind.fix_now -> if (decision.optionIndex == null || decision.optionIndex !in 0 until optionCount) {
            add(FieldError("decision.optionIndex", "must index a finding option for fix_now"))
        }
        FindingDecisionKind.fix_later, FindingDecisionKind.wont_fix -> if (decision.optionIndex != null) {
            add(FieldError("decision.optionIndex", "must be absent for this decision kind"))
        }
    }
    if (decision.decidedBy == null) add(FieldError("decision.decidedBy", "is required")) else actor("decision.decidedBy", decision.decidedBy)
}

fun validateFindingDecision(
    finding: Finding,
    decision: FindingDecision,
    mode: ExecutionMode,
    workerSessionId: String?,
): List<FieldError> = buildList {
    addAll(validateFinding(finding.copy(decision = decision)))
    when (mode) {
        ExecutionMode.supervised -> if (decision.decidedBy != PlanActor.Operator && decision.decidedBy != null) {
            add(FieldError("decision.decidedBy", "only the operator may decide in supervised mode"))
        }
        ExecutionMode.autonomous -> {
            val author = decision.decidedBy
            if (author != null && (author !is PlanActor.Session || workerSessionId == null || author.sessionId != workerSessionId)) {
                add(FieldError("decision.decidedBy", "only the assigned worker may decide in autonomous mode"))
            }
            if (decision.note.isNullOrBlank()) add(FieldError("decision.note", "is required in autonomous mode"))
        }
    }
}

sealed interface FindingBatchResult {
    data class Accepted(val findings: List<Finding>) : FindingBatchResult

    data class Undecided(val findingIds: List<String?>) : FindingBatchResult

    data object OperatorRequired : FindingBatchResult
}

fun sendFindingBatch(taskId: String, findings: List<Finding>, caller: PlanActor): FindingBatchResult {
    if (caller != PlanActor.Operator) return FindingBatchResult.OperatorRequired
    val batch = findings.filter { it.taskId == taskId }
    val undecided = batch.filter { it.decision == null }
    return if (undecided.isEmpty()) FindingBatchResult.Accepted(batch) else FindingBatchResult.Undecided(undecided.map { it.id })
}
