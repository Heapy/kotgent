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

/**
 * The task layer's read surface: `GET /whoami`, `GET /tasks?project=`, `GET /tasks/{ref}`,
 * `GET /projects`. Mounted by [taskRoutes] inside the authenticated `route(API_PREFIX)` block, so every
 * endpoint here takes either credential (the CLI's master-token `Bearer` or the browser's cookie).
 *
 * What the implementation owes (task-backlog plan, Task 13):
 *  - `GET /whoami` resolves the calling PANE through the registry ([resolveCallerSession]) and answers
 *    `400` naming `--session` when it cannot. It is pane resolution, not a session lookup — a caller that
 *    already knows its id never comes here.
 *  - `GET /tasks?project=` lists in `position` order and carries the derived `blocked`.
 *  - `GET /tasks/{ref}` carries deps (both directions), every linked session, the activity feed and the
 *    project's last-seen path. An unknown ref is `404`; a malformed one is `400` (by the route
 *    convention `404` means "no such task `{ref}`", so a ref that cannot even be parsed is a bad
 *    request).
 *  - `GET /projects` is the board selector's only source.
 *
 * ## Two decisions this file makes, and why
 *
 * **A GET carries no body, so session identity here is the PANE HEADER alone.** Every write route reads
 * an explicit `sessionId` out of its request body; a read has none, and inventing a `?session=` query
 * would add a second spelling of the same identity that no shipped client sends —
 * [io.kotgent.cli.ApiClient.listTasks] takes only a project, and `/whoami` is *defined* as pane
 * resolution. A caller that already knows its session id reads the session row it already has an
 * endpoint for.
 *
 * **Every error message comes from the typed failures in `TaskErrors.kt`**, constructed here purely for
 * their wording rather than thrown across a seam: three route files owned by three agents have to agree
 * on what a `404` for an unknown ref says, and the only way to guarantee that without a shared helper
 * (which would be a package-level redeclaration hazard at merge) is to share the exception that already
 * spells it.
 */
fun Route.taskReadRoutes(routing: TaskRouting) {

    /**
     * `GET /whoami` — what the calling pane resolves to.
     *
     * The session row is looked up so the answer can carry the project and the current link; a pane that
     * resolves to an id with no row still answers `200` with that id and two nulls, because the pane
     * registry — narrowed by the `Reconciler` to the live-pane set — is the authority on "who is calling"
     * and a missing row is a fact about the store, not about the caller.
     */
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

    /**
     * `GET /tasks?project=<uuid>` — one project's backlog in rank order, each entry carrying the derived
     * `blocked` and its dependency refs.
     *
     * Three reads, never one per card: [io.kotgent.store.TaskStore.listBacklog] is already ordered by
     * `position`, [io.kotgent.task.TaskTracker.list] supplies the tracker fields for the whole project at
     * once, and [io.kotgent.store.TaskStore.dependencyEdges] supplies the whole edge set — which is
     * exactly why `BacklogEntry.toDto` takes `dependsOn` as a parameter instead of fetching it.
     *
     * A missing `project` falls back to the calling pane's session project, so an agent inside a kotgent
     * pane needs no argument at all. An unknown project is `404` rather than an empty array: every path
     * that reads or creates a `.kotgent.json` upserts the `projects` row, so a uuid the daemon has never
     * seen is a stale or mistyped argument, and answering "this project has no tasks" would hide that.
     */
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

    /**
     * `GET /tasks/{ref}` — the entry, its project's name and last-seen path, both directions of its
     * dependencies, every session holding it and its activity feed.
     *
     * The feed rides this response and deliberately not the events socket (see [TaskDetailDto]), and the
     * linked sessions come from [io.kotgent.store.EventStore.sessionsHoldingTask] — the `sessions` table
     * has exactly one reader-of-record here, the same store that writes it.
     */
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

    /** `GET /projects` — every project the daemon has ever read or created a `.kotgent.json` for. */
    get("/projects") {
        val dtos = routing.tasks.listProjects().map { it.toDto() }
        call.respondText(
            routing.json.encodeToString(ListSerializer(ProjectDto.serializer()), dtos),
            ContentType.Application.Json,
        )
    }
}

/**
 * The `project` query parameter, the calling pane's session project when it is absent, or `null` after
 * this has already answered the request.
 *
 * The two failures are different on purpose: a malformed uuid is the caller's argument being wrong,
 * while "nothing resolved" is the board-with-nothing-selected / outside-a-pane case the plan says must
 * name `--project`.
 */
private suspend fun RoutingContext.resolveProjectParameter(routing: TaskRouting): ProjectId? {
    val raw = call.request.queryParameters["project"]?.takeIf { it.isNotBlank() }
    if (raw != null) {
        val parsed = ProjectId.parseOrNull(raw)
        if (parsed == null) {
            respondNoProject(
                "malformed project id '$raw' — expected a canonical uuid, the `id` field of the " +
                    "project's .kotgent.json; pass --project <uuid>",
            )
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
