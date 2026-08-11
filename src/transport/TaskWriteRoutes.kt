package io.kotgent.transport

import io.kotgent.core.ProjectId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.TaskRef
import io.kotgent.daemon.TaskService
import io.kotgent.task.BacklogEntry
import io.kotgent.task.DependencyRefusedException
import io.kotgent.task.MalformedTaskRefException
import io.kotgent.task.MoveTarget
import io.kotgent.task.NoProjectException
import io.kotgent.task.NoSessionException
import io.kotgent.task.PROJECT_NAME_MAX_LENGTH
import io.kotgent.task.ProjectPathException
import io.kotgent.task.ProjectRegistration
import io.kotgent.task.TaskState
import io.kotgent.task.UnknownProjectException
import io.kotgent.task.UnknownTaskException
import io.kotgent.task.mainCheckoutRoot
import io.kotgent.task.resolveProject
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException

fun Route.taskWriteRoutes(routing: TaskRouting) {

    post("/tasks") {
        val req = decodeBody(CreateTaskRequest.serializer(), routing) ?: return@post
        val title = req.title.trim()
        if (title.isEmpty()) {
            call.respondText("a task needs a title", status = HttpStatusCode.BadRequest)
            return@post
        }
        // Resolve/validate identity before project resolution, which may create a file or database row.
        val author = attributedAuthor(routing, req.sessionId) ?: return@post
        val project = resolveProjectForCreate(routing, req) ?: return@post
        val created = routing.tasks.create(project, title, req.body, author)
        val entry = routing.tasks.entry(created.ref)
        if (entry == null) {
            call.respondText(
                "task '${created.ref.value}' was created but has no backlog entry",
                status = HttpStatusCode.InternalServerError,
            )
            return@post
        }
        respondEntry(routing, entry, HttpStatusCode.Created)
    }

    patch("/tasks/{ref}") {
        val ref = taskRefParam() ?: return@patch
        val req = decodeBody(PatchTaskRequest.serializer(), routing) ?: return@patch
        val to = if (req.state != null) {
            taskStateOf(req.state) ?: run {
                call.respondText(
                    "unknown task state '${req.state}' — expected one of " +
                        TaskState.entries.joinToString(", ") { it.name },
                    status = HttpStatusCode.BadRequest,
                )
                return@patch
            }
        } else {
            null
        }
        if (req.title == null && req.body == null && to == null) {
            call.respondText(
                "nothing to change — a patch carries at least one of title, body or state",
                status = HttpStatusCode.BadRequest,
            )
            return@patch
        }
        if (req.message != null && to == null) {
            call.respondText(
                "a message is only meaningful with a state change — use /tasks/${ref.value}/comment " +
                    "for a standalone note",
                status = HttpStatusCode.BadRequest,
            )
            return@patch
        }
        val change = if (to == null) {
            null
        } else {
            // Resolve the author before tracker edits so an invalid identity cannot leave a partial patch.
            StateChange(to, attributedAuthor(routing, req.sessionId) ?: return@patch)
        }
        if (req.title != null || req.body != null) {
            if (req.title != null && req.title.isBlank()) {
                call.respondText("a task needs a title", status = HttpStatusCode.BadRequest)
                return@patch
            }
            if (routing.tasks.update(ref, req.title?.trim(), req.body) == null) {
                fail(HttpStatusCode.NotFound, UnknownTaskException(ref))
                return@patch
            }
        }
        if (change != null) {
            if (routing.service.transition(ref, change.to, change.author, req.message) == null) {
                fail(HttpStatusCode.NotFound, UnknownTaskException(ref))
                return@patch
            }
        }
        val entry = routing.tasks.entry(ref)
        if (entry == null) {
            fail(HttpStatusCode.NotFound, UnknownTaskException(ref))
            return@patch
        }
        respondEntry(routing, entry)
    }

    delete("/tasks/{ref}") {
        val ref = taskRefParam() ?: return@delete
        if (!routing.service.delete(ref)) {
            fail(HttpStatusCode.NotFound, UnknownTaskException(ref))
            return@delete
        }
        call.respondText("ok")
    }

    post("/tasks/{ref}/move") {
        val ref = taskRefParam() ?: return@post
        val req = decodeBody(MoveTaskRequest.serializer(), routing) ?: return@post
        val target = moveTargetOf(req)
        if (target == null) {
            call.respondText(
                "move requires exactly one of before, after, top or bottom, and a named neighbour must " +
                    "be a well-formed task ref",
                status = HttpStatusCode.BadRequest,
            )
            return@post
        }
        val moved = routing.tasks.move(ref, target)
        if (moved == null) {
            call.respondText(
                "no such task '${ref.value}', or the named neighbour is not in its project",
                status = HttpStatusCode.NotFound,
            )
            return@post
        }
        respondEntry(routing, moved)
    }

    post("/tasks/{ref}/deps") {
        val ref = taskRefParam() ?: return@post
        val req = decodeBody(DepsRequest.serializer(), routing) ?: return@post
        val on = TaskRef.parseOrNull(req.on)
        if (on == null) {
            fail(HttpStatusCode.BadRequest, MalformedTaskRefException(req.on))
            return@post
        }
        try {
            when (req.action) {
                "add" -> routing.tasks.addDependency(ref, on)
                "remove" -> routing.tasks.removeDependency(ref, on)
                else -> {
                    call.respondText(
                        "unknown dependency action '${req.action}' — expected 'add' or 'remove'",
                        status = HttpStatusCode.BadRequest,
                    )
                    return@post
                }
            }
        } catch (e: DependencyRefusedException) {
            fail(HttpStatusCode.BadRequest, e)
            return@post
        }
        val entry = routing.tasks.entry(ref)
        if (entry == null) {
            fail(HttpStatusCode.NotFound, UnknownTaskException(ref))
            return@post
        }
        respondEntry(routing, entry)
    }

    post("/tasks/{ref}/comment") {
        val ref = taskRefParam() ?: return@post
        val req = decodeBody(CommentRequest.serializer(), routing) ?: return@post
        val text = req.text.trim()
        if (text.isEmpty()) {
            call.respondText("a comment needs text", status = HttpStatusCode.BadRequest)
            return@post
        }
        val author = requireCallerSession(routing, req.sessionId) ?: return@post
        val row = routing.tasks.comment(ref, author, text)
        if (row == null) {
            fail(HttpStatusCode.NotFound, UnknownTaskException(ref))
            return@post
        }
        call.respondText(
            routing.json.encodeToString(ActivityEntryDto.serializer(), row.toDto()),
            ContentType.Application.Json,
            HttpStatusCode.Created,
        )
    }

    post("/projects") {
        val req = decodeBody(CreateProjectRequest.serializer(), routing) ?: return@post
        val requested = req.path.trim()
        // Check before realpath: resolving a relative path against the daemon cwd would accept the wrong target.
        if (!requested.startsWith('/')) {
            fail(HttpStatusCode.BadRequest, ProjectPathException(req.path, "project path must be absolute: '${req.path}'"))
            return@post
        }
        val fs = routing.service.projectFs
        val canonical = fs.canonicalize(requested)
        if (canonical == null) {
            fail(
                HttpStatusCode.BadRequest,
                ProjectPathException(requested, "no such directory: '$requested'"),
            )
            return@post
        }
        if (!fs.isDirectory(canonical)) {
            fail(
                HttpStatusCode.BadRequest,
                ProjectPathException(requested, "not a directory: '$requested'"),
            )
            return@post
        }
        val requestedName = if (req.name == null) {
            null
        } else {
            validProjectName(req.name) ?: run {
                call.respondText(
                    "a project name must be 1..$PROJECT_NAME_MAX_LENGTH characters and carry no control " +
                        "characters — otherwise the .kotgent.json this writes could not be read back",
                    status = HttpStatusCode.BadRequest,
                )
                return@post
            }
        }
        val owner = resolveProject(fs, canonical)
        // Adopt the nearest existing project before considering a new root-level project file.
        if (owner != null) {
            routing.tasks.upsertProject(owner.id, owner.name, owner.root)
            respondProject(routing, owner.id, owner.root)
            return@post
        }
        val dir = mainCheckoutRoot(fs, canonical) ?: canonical
        val name = requestedName ?: defaultProjectName(dir)
        val file = try {
            routing.service.projectFiles.ensureProjectFile(dir, name)
        } catch (e: ProjectPathException) {
            fail(HttpStatusCode.BadRequest, e)
            return@post
        }
        routing.tasks.upsertProject(file.id, file.name, dir)
        respondProject(routing, file.id, dir)
    }
}


private data class StateChange(val to: TaskState, val author: String)

private suspend fun <T> RoutingContext.decodeBody(
    serializer: kotlinx.serialization.KSerializer<T>,
    routing: TaskRouting,
): T? = try {
    routing.json.decodeFromString(serializer, call.receiveText())
} catch (_: SerializationException) {
    call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
    null
}

private suspend fun RoutingContext.taskRefParam(): TaskRef? {
    val raw = call.parameters["ref"].orEmpty()
    val ref = TaskRef.parseOrNull(raw)
    if (ref == null) fail(HttpStatusCode.BadRequest, MalformedTaskRefException(raw))
    return ref
}

private suspend fun RoutingContext.fail(status: HttpStatusCode, e: RuntimeException) {
    call.respondText(e.message ?: status.description, status = status)
}

private suspend fun RoutingContext.requireCallerSession(routing: TaskRouting, explicitSessionId: String?): String? =
    when (val caller = resolveCallerIdentity(routing, explicitSessionId)) {
        CallerIdentity.Absent -> {
            fail(
                HttpStatusCode.BadRequest,
                NoSessionException(
                    "no calling session: send the $TASK_PANE_HEADER header from inside a kotgent pane, " +
                        "or name one with --session",
                ),
            )
            null
        }

        is CallerIdentity.Rejected -> {
            fail(HttpStatusCode.BadRequest, NoSessionException(caller.reason))
            null
        }

        is CallerIdentity.Resolved -> existingCallerSession(routing, caller.id)
    }

private suspend fun RoutingContext.attributedAuthor(routing: TaskRouting, explicitSessionId: String?): String? =
    when (val caller = resolveCallerIdentity(routing, explicitSessionId)) {
        CallerIdentity.Absent -> TaskService.BOARD_AUTHOR
        is CallerIdentity.Rejected -> {
            fail(HttpStatusCode.BadRequest, NoSessionException(caller.reason))
            null
        }

        is CallerIdentity.Resolved -> existingCallerSession(routing, caller.id)
    }

private suspend fun RoutingContext.existingCallerSession(routing: TaskRouting, id: SessionId): String? {
    if (routing.sessions.getSession(id) == null) {
        fail(HttpStatusCode.BadRequest, NoSessionException("no such session '${id.value}' — name a live one with --session"))
        return null
    }
    return id.value
}

private suspend fun RoutingContext.resolveProjectForCreate(
    routing: TaskRouting,
    req: CreateTaskRequest,
): ProjectId? {
    val explicit = req.project?.takeIf { it.isNotBlank() }
    if (explicit != null) {
        val id = ProjectId.parseOrNull(explicit)
        if (id == null) {
            call.respondText(
                "malformed project id '$explicit' — expected a canonical uuid",
                status = HttpStatusCode.BadRequest,
            )
            return null
        }
        // A tombstoned project is not addressable: the row survives so a restore can return the backlog,
        // but nothing may file INTO it, so a caller naming one gets what an unknown uuid gets.
        val record = routing.tasks.project(id)
        if (record == null || record.archived) {
            fail(HttpStatusCode.NotFound, UnknownProjectException(id))
            return null
        }
        return id
    }

    val sessionId = resolveCallerSession(routing, req.sessionId)
    val session = sessionId?.let { routing.sessions.getSession(it) }
    if (session == null) {
        fail(
            HttpStatusCode.BadRequest,
            NoProjectException(
                "no project: name one with --project, or run this from inside a kotgent session " +
                    "(the $TASK_PANE_HEADER header, or --session)",
            ),
        )
        return null
    }

    session.projectId?.let { return it }

    val fs = routing.service.projectFs
    val resolved = resolveProject(fs, session.cwd)
    if (resolved != null) {
        return when (routing.tasks.upsertProject(resolved.id, resolved.name, resolved.root)) {
            ProjectRegistration.registered -> {
                bindSessionProject(routing, session, resolved.id)
                resolved.id
            }
            // Refusal has to answer HERE, and it is not the same as the projectless case the fallback
            // below serves: the .kotgent.json that named this project is still on disk, so
            // ensureProjectFile would adopt it and hand back the very uuid the operator deleted.
            ProjectRegistration.refusedArchived -> {
                fail(
                    HttpStatusCode.BadRequest,
                    NoProjectException(
                        "project '${resolved.name}' (${resolved.id.value}) was deleted — bring it back with " +
                            "`kotgent project restore ${resolved.id.value}`, or file this task in another " +
                            "project with --project",
                    ),
                )
                null
            }
        }
    }

    val canonical = fs.canonicalize(session.cwd) ?: session.cwd
    val root = mainCheckoutRoot(fs, canonical) ?: canonical
    val file = try {
        routing.service.projectFiles.ensureProjectFile(root, defaultProjectName(root))
    } catch (e: ProjectPathException) {
        fail(HttpStatusCode.BadRequest, e)
        return null
    }
    routing.tasks.upsertProject(file.id, file.name, root)
    bindSessionProject(routing, session, file.id)
    return file.id
}

private suspend fun bindSessionProject(routing: TaskRouting, session: SessionMeta, project: ProjectId) {
    // Derived binding is not activity. Re-read after filesystem work so it neither advances nor rewinds sorting.
    val sortKey = routing.sessions.getSession(session.id)?.updatedAt ?: session.updatedAt
    routing.sessions.setProjectId(session.id, project, sortKey)
}

private suspend fun RoutingContext.respondProject(routing: TaskRouting, project: ProjectId, dir: String) {
    val record = routing.tasks.project(project)
    if (record == null) {
        call.respondText(
            "project '${project.value}' at $dir is not registered",
            status = HttpStatusCode.InternalServerError,
        )
        return
    }
    call.respondText(
        routing.json.encodeToString(ProjectDto.serializer(), record.toDto()),
        ContentType.Application.Json,
    )
}

private suspend fun RoutingContext.respondEntry(
    routing: TaskRouting,
    entry: BacklogEntry,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    // Every fresh-rev row must carry current edges for newest-wins clients.
    val dto = entry.toDto(routing.tasks.get(entry.ref), routing.tasks.dependenciesOf(entry.ref))
    call.respondText(
        routing.json.encodeToString(BacklogEntryDto.serializer(), dto),
        ContentType.Application.Json,
        status,
    )
}

private fun taskStateOf(raw: String): TaskState? = TaskState.entries.firstOrNull { it.name == raw }

private fun moveTargetOf(req: MoveTaskRequest): MoveTarget? {
    val targets = mutableListOf<MoveTarget>()
    val before = req.before
    if (before != null) targets += MoveTarget.Before(TaskRef.parseOrNull(before) ?: return null)
    val after = req.after
    if (after != null) targets += MoveTarget.After(TaskRef.parseOrNull(after) ?: return null)
    if (req.top) targets += MoveTarget.Top
    if (req.bottom) targets += MoveTarget.Bottom
    return targets.singleOrNull()
}

private fun defaultProjectName(dir: String): String {
    val base = dir.trimEnd('/').substringAfterLast('/')
        .filter { it.code >= 0x20 && it.code != 0x7f }
        .trim()
        .take(PROJECT_NAME_MAX_LENGTH)
    return base.ifEmpty { "project" }
}

private fun validProjectName(raw: String): String? {
    val name = raw.trim()
    if (name.isEmpty() || name.length > PROJECT_NAME_MAX_LENGTH) return null
    if (name.any { it.code < 0x20 || it.code == 0x7f }) return null
    return name
}
