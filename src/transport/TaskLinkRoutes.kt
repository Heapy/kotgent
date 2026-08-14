package io.kotgent.transport

import io.kotgent.core.ProjectId
import io.kotgent.core.SessionMeta
import io.kotgent.core.TaskRef
import io.kotgent.task.BacklogEntry
import io.kotgent.task.MalformedTaskRefException
import io.kotgent.task.NoProjectException
import io.kotgent.task.NoSessionException
import io.kotgent.task.UnknownProjectException
import io.kotgent.task.UnknownTaskException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException

fun Route.taskLinkRoutes(routing: TaskRouting) {
    // Named-card linking remains open so deletion cannot strand work already held by an agent.
    post("/tasks/{ref}/link") {
        val ref = taskLinkRefParam() ?: return@post
        val req = taskLinkBody(routing, LinkRequest.serializer(), LinkRequest()) ?: return@post
        val session = taskLinkCallerSession(routing, req.sessionId) ?: return@post
        if (routing.tasks.entry(ref) == null) {
            refuseTaskLink(UnknownTaskException(ref), HttpStatusCode.NotFound)
            return@post
        }
        routing.service.link(session.id, ref)
        call.respondText("ok")
    }

    post("/tasks/{ref}/unlink") {
        val ref = taskLinkRefParam() ?: return@post
        val req = taskLinkBody(routing, LinkRequest.serializer(), LinkRequest()) ?: return@post
        val session = taskLinkCallerSession(routing, req.sessionId) ?: return@post
        val held = session.taskRef
        when {
            held == null -> call.respondText("ok")
            held == ref -> if (routing.service.unlink(session.id)) {
                call.respondText("ok")
            } else {
                call.respondText(
                    "session '${session.id.value}' no longer holds '${ref.value}' — its link changed " +
                        "while this request ran and nothing was cleared; re-read it and unlink what it " +
                        "holds now",
                    status = HttpStatusCode.Conflict,
                )
            }
            else -> call.respondText(
                "session '${session.id.value}' is linked to '${held.value}', not '${ref.value}' — " +
                    "unlink that one, or pass its ref",
                status = HttpStatusCode.Conflict,
            )
        }
    }

    // Automatic selection is withdrawn by deletion; the store closes the write race and the route diagnoses it.
    post("/tasks/next") {
        val req = taskLinkBody(routing, NextTaskRequest.serializer(), NextTaskRequest()) ?: return@post
        val session = taskLinkCallerSession(routing, req.sessionId) ?: return@post
        val requested = req.project?.takeIf { it.isNotBlank() }
        val project = if (requested != null) {
            ProjectId.parseOrNull(requested) ?: run {
                refuseTaskLink(
                    NoProjectException("'$requested' is not a project uuid — pass --project <uuid>"),
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
        } else {
            session.projectId ?: run {
                refuseTaskLink(
                    NoProjectException(
                        "session '${session.id.value}' resolves to no project — pass --project <uuid>",
                    ),
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
        }
        val record = routing.tasks.project(project)
        // Archived projects are not addressable as work sources, just like unknown projects.
        if (record == null || record.archived) {
            refuseTaskLink(UnknownProjectException(project), HttpStatusCode.NotFound)
            return@post
        }
        val taken = routing.service.linkNext(session.id, project)
        if (taken == null) {
            val current = routing.tasks.project(project)
            if (current == null || current.archived) {
                refuseTaskLink(UnknownProjectException(project), HttpStatusCode.NotFound)
                return@post
            }
        }
        call.respondText(
            routing.json.encodeToString(
                NextTaskResponse.serializer(),
                NextTaskResponse(taken?.let { taskLinkEntryDto(routing, it) }),
            ),
            ContentType.Application.Json,
        )
    }
}

private suspend fun RoutingContext.taskLinkRefParam(): TaskRef? {
    val raw = call.parameters["ref"].orEmpty()
    val ref = TaskRef.parseOrNull(raw)
    if (ref == null) refuseTaskLink(MalformedTaskRefException(raw), HttpStatusCode.BadRequest)
    return ref
}

private suspend fun <T> RoutingContext.taskLinkBody(
    routing: TaskRouting,
    serializer: DeserializationStrategy<T>,
    whenEmpty: T,
): T? {
    val raw = call.receiveText()
    if (raw.isBlank()) return whenEmpty
    return try {
        routing.json.decodeFromString(serializer, raw)
    } catch (_: SerializationException) {
        call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
        null
    }
}

private suspend fun RoutingContext.taskLinkCallerSession(
    routing: TaskRouting,
    explicitSessionId: String?,
): SessionMeta? {
    val id = resolveCallerSession(routing, explicitSessionId)
    val row = id?.let { routing.sessions.getSession(it) }
    if (row == null) {
        refuseTaskLink(
            NoSessionException(
                "no session for this request — the calling pane could not be resolved " +
                    "(header $TASK_PANE_HEADER) and no known session was named; pass --session <id>",
            ),
            HttpStatusCode.BadRequest,
        )
    }
    return row
}

private suspend fun taskLinkEntryDto(routing: TaskRouting, entry: BacklogEntry): BacklogEntryDto =
    // A fresh-rev response must carry real edges or newest-wins clients would erase their dependency view.
    entry.toDto(routing.tasks.get(entry.ref), routing.tasks.dependenciesOf(entry.ref))

private suspend fun RoutingContext.refuseTaskLink(failure: RuntimeException, status: HttpStatusCode) {
    call.respondText(failure.message ?: "request refused", status = status)
}
