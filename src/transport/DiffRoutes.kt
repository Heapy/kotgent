package io.kotgent.transport

import io.kotgent.core.SessionId
import io.kotgent.diff.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Mounted inside the shared authenticated route, before any session or filesystem access. */
fun Route.diffRoutes(service: DiffService, json: Json = TRANSPORT_JSON) {
    val cursorJson = Json(json) { ignoreUnknownKeys = false; coerceInputValues = false }
    route("/sessions/{id}/diff") {
        val _ = install(CancelOnDisconnect)
        get("/worktrees") { call.diffRead<DiffWorktrees>(json, emptySet()) { service.worktrees(it) } }
        get("/refs") { call.diffRead<DiffRefs>(json, setOf("worktreeId")) { service.refs(it, call.requiredDiff("worktreeId")) } }
        get("/commits") {
            call.diffRead<DiffCommits>(json, setOf("worktreeId", "baseRef", "cursor")) {
                val cursor = call.request.queryParameters["cursor"]?.let { value -> cursorJson.decodeFromString<DiffCommitCursor>(value) }
                service.commits(it, call.requiredDiff("worktreeId"), call.request.queryParameters["baseRef"], cursor)
            }
        }
        get("/files") {
            call.diffRead<DiffFiles>(json, setOf("worktreeId", "mode", "scope", "commitOid", "parent", "baseRef")) {
                val q = call.request.queryParameters
                val selection = DiffSelection(
                    mode = q["mode"]?.let(DiffMode::valueOf) ?: DiffMode.local,
                    scope = q["scope"]?.let(DiffLocalScope::valueOf),
                    commitOid = q["commitOid"], parent = q["parent"]?.toInt(), baseRef = q["baseRef"],
                )
                service.files(it, call.requiredDiff("worktreeId"), selection)
            }
        }
        get("/patch") {
            call.diffRead<DiffPatch>(json, setOf("worktreeId", "snapshot", "fileId")) {
                service.patch(it, call.requiredDiff("worktreeId"), call.requiredDiff("snapshot"), call.requiredDiff("fileId"))
            }
        }
    }
}

private fun ApplicationCall.requiredDiff(name: String): String = requireNotNull(request.queryParameters[name])

private suspend inline fun <reified T> ApplicationCall.diffRead(
    json: Json,
    allowed: Set<String>,
    block: (SessionId) -> DiffResponse<T>,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    val result = try {
        val query = request.queryParameters
        require(query.entries().all { [key, values] -> key in allowed && values.size == 1 &&
            values.single().isNotEmpty() && values.single().length <= 4096 && '\u0000' !in values.single() })
        block(SessionId(requireNotNull(parameters["id"])))
    } catch (_: SerializationException) {
        DiffResponse<T>(error = DiffProblem(DiffErrorCode.invalid, DiffReason.invalid_request, "Invalid diff query"))
    } catch (_: IllegalArgumentException) {
        DiffResponse<T>(error = DiffProblem(DiffErrorCode.invalid, DiffReason.invalid_request, "Invalid diff query"))
    }
    val status = when {
        result.error == null -> HttpStatusCode.OK
        result.error.reason == DiffReason.unknown_session -> HttpStatusCode.NotFound
        else -> when (result.error.code) {
            DiffErrorCode.invalid -> HttpStatusCode.BadRequest
            DiffErrorCode.unavailable -> HttpStatusCode.UnprocessableEntity
            DiffErrorCode.limited -> HttpStatusCode.PayloadTooLarge
            DiffErrorCode.stale -> HttpStatusCode.Conflict
            DiffErrorCode.busy -> HttpStatusCode.TooManyRequests
        }
    }
    respondText(json.encodeToString(result), ContentType.Application.Json, status)
}
