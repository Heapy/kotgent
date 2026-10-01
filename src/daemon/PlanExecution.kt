package io.kotgent.daemon

import io.kotgent.core.SessionId
import io.kotgent.plan.*
import io.kotgent.store.EventStore
import io.kotgent.store.PlanDocument
import io.kotgent.store.PlanResult
import io.kotgent.store.PlanStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
data class PlanExecutionResponse(val event: String, val cursor: Long, val events: List<ExecutionEvent>, val document: PlanDocument)

class PlanExecution(private val plans: PlanStore, private val sessions: EventStore) {
    suspend fun claim(ref: String, actor: PlanActor): PlanResult {
        val doc = plans.get(ref) ?: return PlanResult.Missing
        if (actor !is PlanActor.Session) return forbidden("calling session required")
        val previous = doc.execution.orchestratorSessionId
        if (previous != null && previous != actor.sessionId && sessions.getSession(SessionId(previous))?.state?.isAlive == true) {
            return forbidden("the current orchestrator is still running")
        }
        return plans.execute(ref, PlanAction.Claim(previous), actor)
    }

    suspend fun execute(ref: String, action: PlanAction, actor: PlanActor): PlanResult {
        if (action is PlanAction.Worker) {
            val doc = plans.get(ref) ?: return PlanResult.Missing
            val worker = sessions.getSession(SessionId(action.worker.sessionId))
                ?: return invalid("sessionId", "unknown worker session")
            if (!worker.state.isAlive || worker.archived || worker.parentSessionId?.value != doc.execution.orchestratorSessionId || worker.parentSessionId == null) {
                return invalid("sessionId", "worker must be a live child of the orchestrator")
            }
            if (worker.cwd.trimEnd('/') != action.worker.worktree.trimEnd('/') || !action.worker.worktree.startsWith('/')) {
                return invalid("worktree", "must be the worker's absolute working directory")
            }
            val old = doc.plan.tasks.firstOrNull { it.id == action.taskId }?.worker?.sessionId
            if (old != null && old != worker.id.value && sessions.getSession(SessionId(old))?.state?.isAlive == true) {
                return invalid("sessionId", "stop the previous worker before replacing it")
            }
        }
        return plans.execute(ref, action, actor)
    }

    /** Events are retained until task deletion. The caller acknowledges only by advancing its next cursor. */
    suspend fun wait(ref: String, taskId: String?, actor: PlanActor, after: Long, duration: Duration): Pair<PlanResult?, PlanExecutionResponse?> {
        var doc = plans.get(ref) ?: return PlanResult.Missing to null
        fun refusal(current: PlanDocument): PlanResult? {
            if (after < 0 || after >= current.execution.nextEventId) return invalid("after", "must name an existing event cursor")
            val session = (actor as? PlanActor.Session)?.sessionId
            val owner = if (taskId == null) current.execution.orchestratorSessionId
                else current.plan.tasks.firstOrNull { it.id == taskId }?.worker?.sessionId
            return if (session == null || owner != session) forbidden(if (taskId == null) "orchestrator required" else "assigned worker required") else null
        }
        fun pending(current: PlanDocument): List<ExecutionEvent> = current.execution.events.filter {
            it.id > after && if (taskId == null) it.workerSessionId == null
                else it.taskId == taskId && it.workerSessionId == (actor as PlanActor.Session).sessionId
        }
        refusal(doc)?.let { return it to null }
        withTimeoutOrNull(duration) {
            while (pending(doc).isEmpty()) {
                val rev = doc.plan.rev
                val _ = plans.revisions.first { it[ref] != rev }
                doc = plans.get(ref) ?: return@withTimeoutOrNull
                if (refusal(doc) != null) return@withTimeoutOrNull
            }
        }
        doc = plans.get(ref) ?: return PlanResult.Missing to null
        refusal(doc)?.let { return it to null }
        val events = pending(doc)
        return null to PlanExecutionResponse(events.firstOrNull()?.kind ?: "pending", doc.execution.nextEventId - 1, events, doc)
    }

    private fun forbidden(reason: String) = PlanResult.Forbidden(listOf(FieldError("caller", reason)))
    private fun invalid(path: String, reason: String) = PlanResult.Invalid(listOf(FieldError(path, reason)))
}

fun formatPlanExecution(response: PlanExecutionResponse): String = buildString {
    appendLine("event: ${response.event}")
    appendLine("cursor: ${response.cursor}")
    appendLine("plan-rev: ${response.document.plan.rev}")
    for (event in response.events) {
        appendLine("${event.id}: ${event.kind}${event.taskId?.let { " task: $it" }.orEmpty()}")
        if (event.findingIds.isNotEmpty()) appendLine("findings: ${event.findingIds.joinToString(" ")}")
        event.rebaseOnto?.let { appendLine("rebase-onto: $it") }
    }
}.trimEnd()
