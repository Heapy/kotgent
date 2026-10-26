package io.kotgent.transport

import io.kotgent.daemon.ProjectLookup
import io.kotgent.daemon.ProjectLookupService
import io.kotgent.daemon.ProjectLookupValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class ProjectLookupRequest(val paths: List<String>)

@Serializable
data class ProjectLookupDto(val path: String, val status: String, val projectId: String? = null)

/** POST carries a bounded batch; the operation itself is read-only. */
fun Route.projectLookupRoutes(service: ProjectLookupService, json: Json = TRANSPORT_JSON) {
    post("/projects/resolve") {
        val body = receiveBoundedText(2 * 1024 * 1024) ?: return@post
        val request = try {
            json.decodeFromString(ProjectLookupRequest.serializer(), body)
        } catch (_: SerializationException) {
            call.respondText("invalid project lookup body", status = HttpStatusCode.BadRequest)
            return@post
        }
        val rows = try {
            service.lookupProjects(request.paths)
        } catch (e: ProjectLookupValidationException) {
            call.respondText(e.message ?: "invalid project paths", status = HttpStatusCode.BadRequest)
            return@post
        }
        call.respondText(
            json.encodeToString(ListSerializer(ProjectLookupDto.serializer()),
                rows.map { row ->
                    when (row) {
                        is ProjectLookup.Found -> ProjectLookupDto(row.path, "found", row.projectId.value)
                        is ProjectLookup.Absent -> ProjectLookupDto(row.path, "absent")
                        is ProjectLookup.Failed -> ProjectLookupDto(row.path, "failed")
                    }
                }),
            ContentType.Application.Json,
        )
    }
}
