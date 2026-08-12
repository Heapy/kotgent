package io.kotgent.transport

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.task.MalformedTaskRefException
import io.kotgent.task.NoProjectException
import io.kotgent.task.NoSessionException
import io.kotgent.task.UnknownProjectException
import io.kotgent.task.UnknownTaskException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import kotlinx.serialization.builtins.ListSerializer

fun Route.taskReadRoutes(routing: TaskRouting) {

    get("/whoami") {
        val sessionId = resolveCallerSession(routing, explicitSessionId = null) ?: run {
            respondNoSession(
                "cannot resolve the calling session: no $TASK_PANE_HEADER header, or the pane it names " +
                    "is not a kotgent session — pass --session <id> from outside a kotgent pane",
            )
            return@get
        }
        val row = routing.sessions.getSession(sessionId)
        call.respondText(
            routing.json.encodeToString(
                WhoamiDto.serializer(),
                WhoamiDto(
                    sessionId = sessionId.value,
                    projectId = row?.projectId?.value,
                    taskRef = row?.taskRef?.value,
                ),
            ),
            ContentType.Application.Json,
        )
    }

    get("/tasks") {
        val project = resolveProjectParameter(routing) ?: return@get
        if (routing.tasks.project(project) == null) {
            respondUnknownProject(project)
            return@get
        }
        val entries = routing.tasks.listBacklog(project)
        val tracked = routing.tasks.list(project).associateBy { it.ref }
        val edges = routing.tasks.dependencyEdges(project)
        val dtos = entries.map { it.toDto(tracked[it.ref], edges[it.ref].orEmpty()) }
        call.respondText(
            routing.json.encodeToString(ListSerializer(BacklogEntryDto.serializer()), dtos),
            ContentType.Application.Json,
        )
    }

    get("/tasks/{ref}") {
        val raw = call.parameters["ref"].orEmpty()
        val ref = TaskRef.parseOrNull(raw) ?: run {
            respondMalformedRef(raw)
            return@get
        }
        val entry = routing.tasks.entry(ref) ?: run {
            respondUnknownTask(ref)
            return@get
        }
        val dependsOn = routing.tasks.dependenciesOf(ref)
        val project = routing.tasks.project(entry.project)
        val detail = TaskDetailDto(
            task = entry.toDto(routing.tasks.get(ref), dependsOn),
            projectName = project?.name,
            projectPath = project?.path,
            dependsOn = dependsOn.map { it.value },
            dependents = routing.tasks.dependentsOf(ref).map { it.value },
            sessions = routing.sessions.sessionsHoldingTask(ref).map { it.toLinkedSessionDto() },
            activity = routing.tasks.activity(ref).map { it.toDto() },
        )
        call.respondText(
            routing.json.encodeToString(TaskDetailDto.serializer(), detail),
            ContentType.Application.Json,
        )
    }

    get("/projects") {
        val archived = call.request.queryParameters["archived"] == "true"
        val dtos = routing.tasks.listProjects(archived).map { it.toDto() }
        call.respondText(
            routing.json.encodeToString(ListSerializer(ProjectDto.serializer()), dtos),
            ContentType.Application.Json,
        )
    }
}

private suspend fun RoutingContext.resolveProjectParameter(routing: TaskRouting): ProjectId? {
    val raw = call.request.queryParameters["project"]?.takeIf { it.isNotBlank() }
    if (raw != null) {
        val parsed = ProjectId.parseOrNull(raw)
        if (parsed == null) {
            respondNoProject(malformedProjectIdMessage(raw) + "; pass --project <uuid>")
        }
        return parsed
    }
    val sessionId = resolveCallerSession(routing, explicitSessionId = null)
    val project = sessionId?.let { routing.sessions.getSession(it) }?.projectId
    if (project == null) {
        respondNoProject(
            "no project: the request named none and the calling session resolves to none — " +
                "pass --project <uuid>",
        )
    }
    return project
}

private suspend fun RoutingContext.respondNoSession(message: String) =
    call.respondText(NoSessionException(message).message.orEmpty(), status = HttpStatusCode.BadRequest)

private suspend fun RoutingContext.respondNoProject(message: String) =
    call.respondText(NoProjectException(message).message.orEmpty(), status = HttpStatusCode.BadRequest)

private suspend fun RoutingContext.respondMalformedRef(raw: String) =
    call.respondText(MalformedTaskRefException(raw).message.orEmpty(), status = HttpStatusCode.BadRequest)

private suspend fun RoutingContext.respondUnknownTask(ref: TaskRef) =
    call.respondText(UnknownTaskException(ref).message.orEmpty(), status = HttpStatusCode.NotFound)

private suspend fun RoutingContext.respondUnknownProject(id: ProjectId) =
    call.respondText(UnknownProjectException(id).message.orEmpty(), status = HttpStatusCode.NotFound)
