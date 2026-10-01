package io.kotgent.daemon

import io.kotgent.plan.PlanActor
import io.kotgent.plan.ThreadStatus
import io.kotgent.store.PlanDocument
import io.kotgent.store.PlanResult
import io.kotgent.store.PlanStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
data class PlanReviewResponse(val verdict: String, val round: Int, val document: PlanDocument)

class PlanReview(private val plans: PlanStore, private val wake: (String) -> Unit = {}) {
    suspend fun wait(ref: String, duration: Duration, afterRound: Int? = null): Pair<PlanResult?, PlanReviewResponse?> {
        val opened = plans.openReview(ref, afterRound)
        if (opened !is PlanResult.Accepted) return opened to null
        if (opened.reviewOpened) wake("plan.review:$ref")
        val round = opened.document.review.rounds.last().n
        var current = opened.document
        withTimeoutOrNull(duration) {
            while (current.review.rounds.first { it.n == round }.verdict == null) {
                val rev = current.plan.rev
                val _ = plans.revisions.first { it[ref] != rev }
                current = plans.get(ref) ?: return@withTimeoutOrNull
            }
        }
        current = plans.get(ref) ?: return PlanResult.Missing to null
        return null to PlanReviewResponse(
            current.review.rounds.first { it.n == round }.verdict?.name ?: "pending", round, current,
        )
    }
}

fun formatPlanReview(response: PlanReviewResponse): String = buildString {
    appendLine("verdict: ${response.verdict}")
    appendLine("plan-rev: ${response.document.plan.rev}")
    appendLine("round: ${response.round}")
    for (edit in response.document.review.edits.filter { it.reviewRound == response.round && it.author == PlanActor.Operator }) {
        appendLine("\nedit: ${edit.blockId} (${edit.fromRev} -> ${edit.toRev})")
        edit.before.lines().forEach { appendLine("- $it") }
        edit.after.lines().forEach { appendLine("+ $it") }
    }
    for (thread in response.document.review.threads.filter { it.status != ThreadStatus.resolved }) {
        appendLine("\nthread: ${thread.id} block: ${thread.blockId} kind: ${thread.kind} status: ${thread.status}")
        for (message in thread.messages) {
            val author = when (val actor = message.author) {
                PlanActor.Operator -> "operator"
                is PlanActor.Session -> "session(${actor.sessionId})"
            }
            appendLine("$author: ${message.body}")
        }
    }
}.trimEnd()
