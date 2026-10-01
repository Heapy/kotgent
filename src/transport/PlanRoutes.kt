package io.kotgent.transport

import io.kotgent.core.PaneId
import io.kotgent.core.SessionId
import io.kotgent.core.TaskRef
import io.kotgent.daemon.PlanReview
import io.kotgent.daemon.PlanExecution
import io.kotgent.daemon.PlanReviewResponse
import io.kotgent.plan.*
import io.kotgent.store.EventStore
import io.kotgent.store.PlanDocument
import io.kotgent.store.PlanResult
import io.kotgent.store.PlanStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

class PlanRouting(
    val plans: PlanStore,
    val sessions: EventStore,
    val paneLookup: suspend (PaneId) -> SessionId?,
    val json: Json = TRANSPORT_JSON,
    val review: PlanReview = PlanReview(plans),
    val execution: PlanExecution = PlanExecution(plans, sessions),
)

@Serializable data class PlanBlockEditRequest(val rev: Long, val body: String)
@Serializable data class PlanViewedRequest(val rev: Long)
@Serializable data class PlanThreadRequest(val blockId: String, val kind: ThreadKind = ThreadKind.question, val body: String)
@Serializable data class PlanMessageRequest(val body: String)
@Serializable data class PlanSubmitRequest(val round: Int)
@Serializable data class PlanErrorResponse(val errors: List<FieldError> = emptyList(), val rev: Long? = null, val changedBlockIds: List<String> = emptyList())

fun Route.planRoutes(routing: PlanRouting) {
    route("/tasks/{ref}/plan") {
        val _ = install(CancelOnDisconnect)
        planExecutionRoutes(routing)
        get {
            val ref = planRef() ?: return@get
            val _ = planActor(routing) ?: return@get
            val doc = routing.plans.get(ref)
            respondPlan(routing, doc?.let { PlanResult.Accepted(it) } ?: PlanResult.Missing)
        }
        put {
            val ref = planRef() ?: return@put
            val _ = planActor(routing, sessionOnly = true) ?: return@put
            val baseRev = call.request.queryParameters["baseRev"]?.toLongOrNull()
            if (baseRev == null || baseRev < 0) {
                call.respondText("baseRev must be a non-negative integer", status = HttpStatusCode.BadRequest)
                return@put
            }
            val plan = planBody(routing, Plan.serializer(), MAX_PLAN_DOCUMENT_BYTES) ?: return@put
            if (plan.taskRef != ref) {
                call.respondText("taskRef must match the route", status = HttpStatusCode.BadRequest)
                return@put
            }
            respondPlan(routing, routing.plans.put(plan, baseRev))
        }
        patch("/blocks/{id}") {
            val ref = planRef() ?: return@patch
            val actor = planActor(routing) ?: return@patch
            val req = planBody(routing, PlanBlockEditRequest.serializer(), MAX_BLOCK_BODY_BYTES * 6 + 1024) ?: return@patch
            respondPlan(routing, routing.plans.edit(ref, call.parameters["id"].orEmpty(), req.rev, req.body, actor))
        }
        put("/blocks/{id}/viewed") {
            val ref = planRef() ?: return@put
            val _ = planActor(routing, operatorOnly = true) ?: return@put
            val req = planBody(routing, PlanViewedRequest.serializer(), 1024) ?: return@put
            respondPlan(routing, routing.plans.viewed(ref, call.parameters["id"].orEmpty(), req.rev))
        }
        delete("/blocks/{id}/viewed") {
            val ref = planRef() ?: return@delete
            val _ = planActor(routing, operatorOnly = true) ?: return@delete
            val _ = receiveBoundedText(1024) ?: return@delete
            respondPlan(routing, routing.plans.viewed(ref, call.parameters["id"].orEmpty(), null))
        }
        post("/threads") {
            val ref = planRef() ?: return@post
            val actor = planActor(routing) ?: return@post
            val req = planBody(routing, PlanThreadRequest.serializer(), MAX_THREAD_MESSAGE_BYTES * 6 + 1024) ?: return@post
            respondPlan(routing, routing.plans.thread(ref, req.blockId, req.kind, req.body, actor))
        }
        post("/threads/{id}/messages") {
            val ref = planRef() ?: return@post
            val actor = planActor(routing) ?: return@post
            val req = planBody(routing, PlanMessageRequest.serializer(), MAX_THREAD_MESSAGE_BYTES * 6 + 1024) ?: return@post
            respondPlan(routing, routing.plans.reply(ref, call.parameters["id"].orEmpty(), req.body, actor))
        }
        post("/threads/{id}/resolve") {
            val ref = planRef() ?: return@post
            val _ = planActor(routing) ?: return@post
            val _ = receiveBoundedText(1024) ?: return@post
            respondPlan(routing, routing.plans.resolve(ref, call.parameters["id"].orEmpty()))
        }
        post("/review/wait") {
            val ref = planRef() ?: return@post
            val _ = planActor(routing, sessionOnly = true) ?: return@post
            val _ = receiveBoundedText(1024) ?: return@post
            val raw = call.request.queryParameters["wait"] ?: "90"
            val wait = raw.toIntOrNull()?.takeIf { it in 0..540 }
            if (wait == null) {
                call.respondText("wait must be whole seconds from 0 to 540", status = HttpStatusCode.BadRequest)
                return@post
            }
            val afterRaw = call.request.queryParameters["afterRound"]
            val after = afterRaw?.toIntOrNull()
            if (afterRaw != null && (after == null || after < 0)) {
                call.respondText("afterRound must be a non-negative integer", status = HttpStatusCode.BadRequest)
                return@post
            }
            val [failure, response] = routing.review.wait(ref, wait.seconds, after)
            if (failure != null) respondPlan(routing, failure)
            else call.respondText(routing.json.encodeToString(PlanReviewResponse.serializer(), requireNotNull(response)), ContentType.Application.Json)
        }
        for (verdict in ReviewVerdict.entries) {
            post(if (verdict == ReviewVerdict.approved) "/review/approve" else "/review/submit") {
                val ref = planRef() ?: return@post
                val _ = planActor(routing, operatorOnly = true) ?: return@post
                val req = planBody(routing, PlanSubmitRequest.serializer(), 1024) ?: return@post
                respondPlan(routing, routing.plans.submitReview(ref, req.round, verdict))
            }
        }
    }
}

internal suspend fun RoutingContext.planRef(): String? {
    val ref = call.parameters["ref"].orEmpty()
    if (TaskRef.parseOrNull(ref) != null) return ref
    call.respondText("invalid task reference", status = HttpStatusCode.BadRequest)
    return null
}

internal suspend fun RoutingContext.planActor(routing: PlanRouting, sessionOnly: Boolean = false, operatorOnly: Boolean = false): PlanActor? {
    val actor = when (val caller = resolveCallerIdentity(routing.paneLookup, call.request.queryParameters["sessionId"])) {
        CallerIdentity.Absent -> PlanActor.Operator
        is CallerIdentity.Rejected -> {
            call.respondText(caller.reason, status = HttpStatusCode.BadRequest)
            return null
        }
        is CallerIdentity.Resolved -> {
            if (routing.sessions.getSession(caller.id)?.state?.isAlive != true) {
                call.respondText("the calling session is not running", status = HttpStatusCode.BadRequest)
                return null
            }
            PlanActor.Session(caller.id.value)
        }
    }
    if ((sessionOnly && actor == PlanActor.Operator) || (operatorOnly && actor != PlanActor.Operator)) {
        call.respondText(if (operatorOnly) "operator action required" else "calling session required", status = HttpStatusCode.Forbidden)
        return null
    }
    return actor
}

@OptIn(ExperimentalSerializationApi::class)
internal suspend fun <T> RoutingContext.planBody(routing: PlanRouting, serializer: KSerializer<T>, limit: Int): T? {
    val body = receiveBoundedText(limit) ?: return null
    return try {
        routing.json.decodeFromString(serializer, body)
    } catch (e: SerializationException) {
        val errors = if (e is MissingFieldException) e.missingFields.map { FieldError(it, "is required") }
            else listOf(FieldError("body", "invalid JSON or field value"))
        respondPlan(routing, PlanResult.Invalid(errors))
        null
    } catch (_: IllegalArgumentException) {
        call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
        null
    }
}

internal suspend fun RoutingContext.respondPlan(routing: PlanRouting, result: PlanResult) {
    when (result) {
        is PlanResult.Accepted -> call.respondText(routing.json.encodeToString(PlanDocument.serializer(), result.document), ContentType.Application.Json)
        is PlanResult.Forbidden -> call.respondText(routing.json.encodeToString(PlanErrorResponse.serializer(), PlanErrorResponse(errors = result.errors)), ContentType.Application.Json, HttpStatusCode.Forbidden)
        is PlanResult.Invalid -> call.respondText(routing.json.encodeToString(PlanErrorResponse.serializer(), PlanErrorResponse(errors = result.errors)), ContentType.Application.Json, HttpStatusCode.BadRequest)
        is PlanResult.Conflict -> call.respondText(routing.json.encodeToString(PlanErrorResponse.serializer(), PlanErrorResponse(errors = result.errors, rev = result.rev, changedBlockIds = result.changedBlockIds)), ContentType.Application.Json, HttpStatusCode.Conflict)
        PlanResult.Missing -> call.respondText("no such plan or block", status = HttpStatusCode.NotFound)
    }
}
