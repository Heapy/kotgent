package io.kotgent.transport

import io.kotgent.daemon.PlanExecutionResponse
import io.kotgent.plan.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable data class PlanInvestigateRequest(val rev: Long, val agent: String = "claude")
@Serializable data class PlanSettingsRequest(val mode: ExecutionMode? = null, val featureBranch: String? = null, val concurrency: Int? = null)
@Serializable data class PlanTaskStatusRequest(val status: PlanTaskStatus)
@Serializable data class PlanFeedbackRequest(val findingIds: List<String> = emptyList(), val rebaseOnto: String? = null)
@Serializable data class PlanFindingVerifyRequest(val rev: Long, val verifier: FindingVerifier)
@Serializable data class PlanFindingAmendRequest(val rev: Long, val finding: Finding)
@Serializable data class PlanFindingDecisionRequest(val rev: Long, val decision: FindingDecision)
@Serializable data class PlanFindingSendRequest(val taskId: String)

/** Installed inside the shared authenticated /tasks/{ref}/plan route. */
internal fun Route.planExecutionRoutes(routing: PlanRouting) {
    patch("/settings") {
        val ref = planRef() ?: return@patch
        val actor = planActor(routing) ?: return@patch
        val body = planBody(routing, PlanSettingsRequest.serializer(), 8192) ?: return@patch
        respondPlan(routing, routing.execution.execute(ref, PlanAction.Settings(body.mode, body.featureBranch, body.concurrency), actor))
    }
    post("/claim") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing, sessionOnly = true) ?: return@post
        val _ = receiveBoundedText(1024) ?: return@post
        respondPlan(routing, routing.execution.claim(ref, actor))
    }
    post("/complete") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing, sessionOnly = true) ?: return@post
        val _ = receiveBoundedText(1024) ?: return@post
        respondPlan(routing, routing.execution.execute(ref, PlanAction.Complete, actor))
    }
    for (verb in listOf("start", "finish", "block")) post("/tasks/{id}/$verb") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing, sessionOnly = true) ?: return@post
        val _ = receiveBoundedText(1024) ?: return@post
        val id = call.parameters["id"].orEmpty()
        val action = when (verb) {
            "start" -> PlanAction.Start(id)
            "finish" -> PlanAction.Status(id, PlanTaskStatus.in_review)
            else -> PlanAction.Status(id, PlanTaskStatus.blocked)
        }
        respondPlan(routing, routing.execution.execute(ref, action, actor))
    }
    post("/tasks/{id}/status") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing, sessionOnly = true) ?: return@post
        val body = planBody(routing, PlanTaskStatusRequest.serializer(), 1024) ?: return@post
        respondPlan(routing, routing.execution.execute(ref, PlanAction.Status(call.parameters["id"].orEmpty(), body.status), actor))
    }
    post("/tasks/{id}/worker") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing, sessionOnly = true) ?: return@post
        val body = planBody(routing, PlanWorker.serializer(), 16 * 1024) ?: return@post
        respondPlan(routing, routing.execution.execute(ref, PlanAction.Worker(call.parameters["id"].orEmpty(), body), actor))
    }
    post("/tasks/{id}/feedback") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing) ?: return@post
        val body = planBody(routing, PlanFeedbackRequest.serializer(), MAX_FINDING_BYTES) ?: return@post
        respondPlan(routing, routing.execution.execute(ref, PlanAction.Feedback(call.parameters["id"].orEmpty(), body.findingIds, body.rebaseOnto), actor))
    }
    post("/steps/{id}/done") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing, sessionOnly = true) ?: return@post
        val _ = receiveBoundedText(1024) ?: return@post
        respondPlan(routing, routing.execution.execute(ref, PlanAction.StepDone(call.parameters["id"].orEmpty()), actor))
    }
    post("/findings") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing, sessionOnly = true) ?: return@post
        val body = planBody(routing, Finding.serializer(), MAX_FINDING_BYTES) ?: return@post
        respondPlan(routing, routing.execution.execute(ref, PlanAction.AddFinding(body), actor))
    }
    post("/findings/{id}/verify") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing, sessionOnly = true) ?: return@post
        val body = planBody(routing, PlanFindingVerifyRequest.serializer(), MAX_FINDING_BYTES) ?: return@post
        respondPlan(routing, routing.execution.execute(ref, PlanAction.Verify(call.parameters["id"].orEmpty(), body.rev, body.verifier), actor))
    }
    post("/findings/{id}/amend") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing, sessionOnly = true) ?: return@post
        val body = planBody(routing, PlanFindingAmendRequest.serializer(), MAX_FINDING_BYTES) ?: return@post
        respondPlan(routing, routing.execution.execute(ref, PlanAction.Amend(call.parameters["id"].orEmpty(), body.rev, body.finding), actor))
    }
    post("/findings/{id}/note") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing, sessionOnly = true) ?: return@post
        val body = planBody(routing, PlanMessageRequest.serializer(), MAX_THREAD_MESSAGE_BYTES * 6 + 1024) ?: return@post
        respondPlan(routing, routing.execution.execute(ref, PlanAction.Note(call.parameters["id"].orEmpty(), body.body), actor))
    }
    post("/findings/{id}/investigate") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing, operatorOnly = true) ?: return@post
        val body = planBody(routing, PlanInvestigateRequest.serializer(), 1024) ?: return@post
        respondPlan(routing, routing.execution.investigate(ref, call.parameters["id"].orEmpty(), body.rev, body.agent, actor))
    }
    post("/findings/{id}/decide") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing) ?: return@post
        val body = planBody(routing, PlanFindingDecisionRequest.serializer(), MAX_FINDING_BYTES) ?: return@post
        respondPlan(routing, routing.execution.execute(ref, PlanAction.Decide(call.parameters["id"].orEmpty(), body.rev, body.decision), actor))
    }
    post("/findings/send") {
        val ref = planRef() ?: return@post
        val actor = planActor(routing, operatorOnly = true) ?: return@post
        val body = planBody(routing, PlanFindingSendRequest.serializer(), 1024) ?: return@post
        respondPlan(routing, routing.execution.execute(ref, PlanAction.Send(body.taskId), actor))
    }
    for (path in listOf("/wait", "/tasks/{id}/wait")) post(path) {
        val ref = planRef() ?: return@post
        val actor = planActor(routing, sessionOnly = true) ?: return@post
        val _ = receiveBoundedText(1024) ?: return@post
        val wait = (call.request.queryParameters["wait"] ?: "90").toIntOrNull()?.takeIf { it in 0..540 }
        val after = (call.request.queryParameters["after"] ?: "0").toLongOrNull()?.takeIf { it >= 0 }
        if (wait == null || after == null) {
            call.respondText("wait must be 0–540 seconds and after a non-negative cursor", status = HttpStatusCode.BadRequest)
            return@post
        }
        val [failure, response] = routing.execution.wait(ref, call.parameters["id"], actor, after, wait.seconds)
        if (failure != null) respondPlan(routing, failure)
        else call.respondText(routing.json.encodeToString(PlanExecutionResponse.serializer(), requireNotNull(response)), ContentType.Application.Json)
    }
}
