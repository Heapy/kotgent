package io.kotgent.transport

import io.kotgent.core.ProjectId
import io.kotgent.core.SessionMeta
import io.kotgent.core.TaskRef
import io.kotgent.task.BacklogEntry
import io.kotgent.task.MalformedTaskRefException
import io.kotgent.task.NoProjectException
import io.kotgent.task.NoSessionException
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

/**
 * The three endpoints that tie a session to a task: `POST /tasks/{ref}/link`, `POST /tasks/{ref}/unlink`
 * and `POST /tasks/next`.
 *
 * What the implementation owes (task-backlog plan, Task 15):
 *  - **All three REQUIRE session identity** ([resolveCallerSession]) — each writes `sessions.task_ref`
 *    or attributes an activity row, and none means anything without a session. A pane the registry does
 *    not know is REFUSED (`400` naming `--session`) rather than silently attributed to something else.
 *  - `link` is unconditional and may target a task already `in_progress`: kotgent enforces no
 *    exclusivity, so a second session simply appears on the card.
 *  - `unlink` leaves the task's state alone. Whether the work is finished is not inferable from a
 *    session detaching, and other sessions may still be linked.
 *  - `next` answers **"nothing eligible" distinguishably from every error** — a `200` with a null task
 *    (see [NextTaskResponse]) — so the CLI can map it to exit `3` without guessing.
 *
 * `ControlRoutes.kt`'s optional `taskRef` on `POST /sessions` belongs to the same task: the session row
 * and its link are written by ONE request, so `start --task` has nothing to roll back if the launch
 * fails. The link itself cannot fail (it is unconditional), which is a direct dividend of dropping
 * exclusivity.
 *
 * ## The four decisions this file makes that the plan left open
 *
 *  1. **A resolved session must EXIST.** [resolveCallerSession] deliberately does not check (its KDoc
 *     says so: "a route that writes on the caller's behalf checks it, because a silent no-op on a
 *     missing row is exactly what `link` must not do"), so [taskLinkCallerSession] re-reads the row and
 *     refuses `400` when it is not there. An explicit `--session <id>` naming nothing is the same client
 *     error as an unresolvable pane, and gets the same answer.
 *  2. **`link` 404s an unknown ref, `unlink` does not.** A link to a task that does not exist would
 *     write a `sessions.task_ref` pointing at nothing — the dangling badge the "reference, not a foreign
 *     key" rule tolerates as a race but must not CREATE deliberately. `unlink` is the opposite case: a
 *     session left holding a ref whose task was deleted is exactly who needs to clear it, so the task's
 *     existence is never consulted there.
 *  3. **`unlink` refuses a MISMATCHED ref out loud (`409`), and a ref-less session quietly (`200`).**
 *     [io.kotgent.daemon.TaskService.unlink] takes no ref — it clears whatever the session holds — so
 *     the path segment would otherwise be decorative and `task unlink local:5` would silently clear a
 *     link to `local:7`. A session that holds nothing is already in the requested state, so that is an
 *     idempotent `ok`; a session that holds a DIFFERENT task is a conflicting action, answered the way
 *     the control plane answers those, with a `409` that names what it actually holds and writes nothing.
 *  4. **`next` does not verify that the project exists.** Its answer space is exactly what the CLI maps:
 *     a task, "nothing eligible" (`200` + null), or a refusal. An unknown project has nothing eligible
 *     in it, and adding a `404` would cost a read on every pickup and misfire on a project whose
 *     `projects` row is missing while its backlog is not.
 */
fun Route.taskLinkRoutes(routing: TaskRouting) {
    /**
     * Link the calling session to this task. Unconditional: a task already `in_progress` simply gains
     * another session, and a session already linked elsewhere is re-pointed (it works one task at a
     * time). The two writes behind this are independent and neither is conditional on the other — see
     * [io.kotgent.daemon.TaskService.link].
     */
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

    /**
     * Drop this session's link to this task. The task's STATE is untouched — several sessions may still
     * hold it, and a session detaching says nothing about whether the work is done.
     */
    post("/tasks/{ref}/unlink") {
        val ref = taskLinkRefParam() ?: return@post
        val req = taskLinkBody(routing, LinkRequest.serializer(), LinkRequest()) ?: return@post
        val session = taskLinkCallerSession(routing, req.sessionId) ?: return@post
        val held = session.taskRef
        when {
            // Already in the requested state: idempotent, and deliberately not a 404 — the caller asked
            // for "not linked to this", which is true.
            held == null -> call.respondText("ok")
            held == ref -> {
                routing.service.unlink(session.id)
                call.respondText("ok")
            }
            // A conflicting action, refused out loud rather than clearing a link nobody asked about.
            else -> call.respondText(
                "session '${session.id.value}' is linked to '${held.value}', not '${ref.value}' — " +
                    "unlink that one, or pass its ref",
                status = HttpStatusCode.Conflict,
            )
        }
    }

    /**
     * Take the next eligible task in a project and link it to the calling session.
     *
     * The project comes from the body or, failing that, from the calling session's own `project_id`.
     * A null `task` in the answer is **"nothing eligible"**, not a failure: it is a `200`, precisely so
     * the CLI can tell it apart from an error and map it to exit `3`.
     *
     * This literal can never be shadowed by `/tasks/{ref}/…`: a [TaskRef] must contain a `:`, so a bare
     * word cannot parse as one — and the two patterns do not even have the same segment count.
     */
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
        val taken = routing.service.linkNext(session.id, project)
        call.respondText(
            routing.json.encodeToString(
                NextTaskResponse.serializer(),
                NextTaskResponse(taken?.let { taskLinkEntryDto(routing, it) }),
            ),
            ContentType.Application.Json,
        )
    }
}

/**
 * The `{ref}` path segment as a [TaskRef], or `null` after answering `400` — the malformed-ref mapping
 * `TaskErrors.kt` records. Parsed rather than constructed: [TaskRef]'s constructor throws
 * [IllegalArgumentException], which no route catch is looking for.
 */
private suspend fun RoutingContext.taskLinkRefParam(): TaskRef? {
    val raw = call.parameters["ref"].orEmpty()
    val ref = TaskRef.parseOrNull(raw)
    if (ref == null) refuseTaskLink(MalformedTaskRefException(raw), HttpStatusCode.BadRequest)
    return ref
}

/**
 * Decode a request body, treating an ABSENT one as [whenEmpty]. Returns `null` after answering `400`
 * for a body that is present and unparseable.
 *
 * All three routes here carry only optional fields, and the CLI sends no body at all when it has none
 * to send, so an empty body must not be an error — but a body that IS there and is malformed is a
 * client bug worth naming, exactly as `POST /sessions` treats one.
 */
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

/**
 * The calling session's row, or `null` after answering `400` naming `--session`.
 *
 * Two failures collapse into one answer on purpose: a pane the registry does not know and an explicit
 * `sessionId` naming no row are the same thing from the caller's side — kotgent has nobody to attribute
 * this write to — and both must be refused rather than written somewhere else or silently dropped. The
 * row itself is returned because every caller needs a field off it (`taskRef` for `unlink`, `projectId`
 * for `next`), so this read is not an extra one.
 */
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

/**
 * One backlog entry as the wire shape, joined with its tracker row and its dependency edges.
 *
 * The edges are read even though nothing in `next`'s own output needs a count: every observation of a
 * row is merged newest-rev-wins, so answering with an empty `dependsOn` at a FRESH rev would make a
 * connected board drop a dependency list it already had until the next update touched that task.
 */
private suspend fun taskLinkEntryDto(routing: TaskRouting, entry: BacklogEntry): BacklogEntryDto =
    entry.toDto(routing.tasks.get(entry.ref), routing.tasks.dependenciesOf(entry.ref))

/**
 * Answer one of `TaskErrors.kt`'s typed failures with the status that file's table assigns it. The
 * exception carries the wording so a message is written once; nothing here throws, because this
 * transport has no `StatusPages` and every other route file answers by hand too.
 */
private suspend fun RoutingContext.refuseTaskLink(failure: RuntimeException, status: HttpStatusCode) {
    call.respondText(failure.message ?: "request refused", status = status)
}
