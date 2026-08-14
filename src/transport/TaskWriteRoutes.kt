package io.kotgent.transport

import io.kotgent.core.ProjectId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.TaskRef
import io.kotgent.daemon.TaskService
import io.kotgent.task.ArchivedProjectException
import io.kotgent.task.BacklogEntry
import io.kotgent.task.DependencyRefusedException
import io.kotgent.task.MalformedTaskRefException
import io.kotgent.task.MoveTarget
import io.kotgent.task.NoProjectException
import io.kotgent.task.NoSessionException
import io.kotgent.task.PROJECT_FILE_NAME
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
        val target = resolveProjectForCreate(routing, req) ?: return@post
        // Resolution chooses contextual guidance; the store's second check closes the delete/create race.
        val created = try {
            routing.tasks.create(target.id, title, req.body, author)
        } catch (_: ArchivedProjectException) {
            refuseDeletedProject(target.name, target.id, target.arrival)
            return@post
        }
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
            // Adoption explicitly restores before registering; the response re-reads any racing delete.
            routing.tasks.setProjectArchived(owner.id, false)
            routing.tasks.upsertProject(owner.id, owner.name, owner.root)
            respondProject(routing, owner.id)
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
        // resolveProject already proved the writer minted a new, unarchived identity.
        routing.tasks.upsertProject(file.id, file.name, dir)
        respondProject(routing, file.id)
    }

    // Tombstone only: preserve filesystem identity, backlog, and links. Project changes are not broadcast.
    delete("/projects/{id}") {
        val id = projectIdParam() ?: return@delete
        setProjectArchivedAndRespond(routing, id, archived = true)
    }

    // Restore by UUID also works when the last-seen directory no longer exists.
    post("/projects/{id}/restore") {
        val id = projectIdParam() ?: return@post
        setProjectArchivedAndRespond(routing, id, archived = false)
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

private suspend fun RoutingContext.projectIdParam(): ProjectId? {
    val raw = call.parameters["id"].orEmpty()
    val id = ProjectId.parseOrNull(raw)
    if (id == null) {
        call.respondText(malformedProjectIdMessage(raw), status = HttpStatusCode.BadRequest)
    }
    return id
}

private suspend fun RoutingContext.setProjectArchivedAndRespond(
    routing: TaskRouting,
    id: ProjectId,
    archived: Boolean,
) {
    if (!routing.tasks.setProjectArchived(id, archived)) {
        fail(HttpStatusCode.NotFound, UnknownProjectException(id))
        return
    }
    respondProject(routing, id)
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
): CreateTarget? {
    val explicit = req.project?.takeIf { it.isNotBlank() }
    if (explicit != null) {
        val id = ProjectId.parseOrNull(explicit)
        if (id == null) {
            call.respondText(
                malformedProjectIdMessage(explicit) + "; pass --project <uuid>",
                status = HttpStatusCode.BadRequest,
            )
            return null
        }
        val record = routing.tasks.project(id)
        if (record == null || record.archived) {
            fail(HttpStatusCode.NotFound, UnknownProjectException(id))
            return null
        }
        return CreateTarget(id, record.name, DeletedProjectArrival.explicitUuid)
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

    // Recheck stale session stamps so they cannot file into an archived backlog.
    session.projectId?.let { stamped ->
        val record = routing.tasks.project(stamped)
        if (record == null || !record.archived) {
            return CreateTarget(stamped, record?.name ?: stamped.value, DeletedProjectArrival.sessionStamp)
        }
        refuseDeletedProject(record.name, stamped, DeletedProjectArrival.sessionStamp)
        return null
    }

    val fs = routing.service.projectFs
    val resolved = resolveProject(fs, session.cwd)
    if (resolved != null) {
        return when (routing.tasks.upsertProject(resolved.id, resolved.name, resolved.root)) {
            ProjectRegistration.registered -> {
                bindSessionProject(routing, session, resolved.id)
                CreateTarget(resolved.id, resolved.name, DeletedProjectArrival.projectFile)
            }
            ProjectRegistration.refusedArchived -> {
                refuseDeletedProject(resolved.name, resolved.id, DeletedProjectArrival.projectFile)
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
    // The fallback may adopt an existing project file, so registration can still meet a tombstone.
    if (routing.tasks.upsertProject(file.id, file.name, root) == ProjectRegistration.refusedArchived) {
        refuseDeletedProject(file.name, file.id, DeletedProjectArrival.projectFile)
        return null
    }
    bindSessionProject(routing, session, file.id)
    return CreateTarget(file.id, file.name, DeletedProjectArrival.projectFile)
}

/** Retains resolution context for a later atomic archive refusal. */
private data class CreateTarget(
    val id: ProjectId,
    val name: String,
    val arrival: DeletedProjectArrival,
)

private enum class DeletedProjectArrival { explicitUuid, sessionStamp, projectFile }

/** Reports recovery paths appropriate to how the archived project was resolved. */
private suspend fun RoutingContext.refuseDeletedProject(
    name: String,
    id: ProjectId,
    arrival: DeletedProjectArrival,
) {
    val fileExit = when (arrival) {
        DeletedProjectArrival.explicitUuid -> {
            fail(HttpStatusCode.NotFound, UnknownProjectException(id))
            return
        }

        DeletedProjectArrival.projectFile ->
            ", or delete or move this directory's $PROJECT_FILE_NAME so it stops resolving to that project"

        DeletedProjectArrival.sessionStamp ->
            ", or delete or move that directory's $PROJECT_FILE_NAME and file from a session started " +
                "after that — this one is stamped with the project and no longer asks its directory"
    }
    fail(
        HttpStatusCode.BadRequest,
        NoProjectException(
            "project '$name' (${id.value}) was deleted — bring it back with " +
                "`kotgent project restore ${id.value}`, file this task in another project with " +
                "--project$fileExit",
        ),
    )
}

private suspend fun bindSessionProject(routing: TaskRouting, session: SessionMeta, project: ProjectId) {
    // Derived binding is not activity; the store preserves the row's committed ordering stamp atomically.
    routing.sessions.setProjectId(session.id, project)
}

/** Re-reads the row so clients see committed state, including a racing tombstone change. */
private suspend fun RoutingContext.respondProject(routing: TaskRouting, project: ProjectId) {
    val record = routing.tasks.project(project)
    if (record == null) {
        call.respondText(
            "project '${project.value}' was written but cannot be read back",
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
