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
    // Linking deliberately stays open for a deleted project's card. The line this feature draws is
    // around a project used as a SOURCE of new work — `POST /tasks` files a new card into it and
    // `POST /tasks/next` picks one OUT of it — and what puts a route on the far side of that line is
    // SELECTING work, not writing. This route can only reach the card its caller already named by ref,
    // exactly as `PATCH`, `move`, `deps`, `comment` and `done` do, none of which stopped answering; an
    // agent that was already working when the operator deleted the project must be able to re-link and
    // close what it holds.
    //
    // It writes, and on a `todo` card it writes exactly what `POST /tasks/next` writes: TaskService.link
    // runs `startIfTodo` (`todo` → `in_progress`), stamps the session's `task_ref` and appends a `linked`
    // activity row. So `kotgent task claim <ref>` starts a deleted project's card, deliberately — the
    // writes are the same family as the `PATCH`/`move`/`deps` ones the contract leaves open, and refusing
    // them for a named ref would strand an agent mid-task. That is why this path passes
    // `requireLiveProject = false`: the tombstone clause `POST /tasks/next` writes into the very same
    // update must not reach here, or a named ref would start refusing along with a selected one.
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

    // Handing out the next card SELECTS work out of the project: the caller names no ref, the daemon
    // picks one and starts it (`linkNext` moves a `todo` to `in_progress`, links the session and appends
    // an activity row). Selection is what a deleted project stops offering, so this refuses for the same
    // reason `POST /tasks` does — an operator who deleted a project must not keep finding it handing work
    // out, on a board that no longer lists it. The ANSWER is not the same, though: both ways in here name
    // the project outright (an explicit `--project`, or the session's own stamp), so both get the bare
    // `404` an unknown uuid gets. `POST /tasks`' three-exit `400` belongs to the one arrival this route
    // does not have — a directory whose `.kotgent.json` still resolves to the deleted project.
    // `/tasks/{ref}/link` makes those same three writes for a card the caller NAMES and deliberately
    // stays open; the ref, not the writing, is the difference.
    //
    // The check below decides the MESSAGE, not the outcome. It reads the project row and this route then
    // holds no lock while `linkNext` runs, so a `DELETE /projects/{id}` landing in that gap would defeat
    // a route-only guard. The guarantee therefore lives in the store, in BOTH statements `linkNext`
    // reaches: `Backlog.sq:nextCandidate` excludes an archived project in the same statement that picks
    // the card, and `startIfTodoInLiveProject` repeats the clause in the update that starts it — the two
    // are separate store calls, so guarding only the first would still let a delete landing between them
    // start work out of a deleted project. Whichever one the delete overtakes, `linkNext` ends with
    // nothing selected: a start that matches zero rows sends its loop back to a candidate query that now
    // answers null. So the answer degrades to the ordinary `{"task":null}` — `task next`'s documented
    // exit `3` — instead of the `404` a caller would have got a moment earlier. Nothing is handed out
    // either way, which is the contract; re-reading the row afterwards would be the same race with more
    // steps.
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
        // Null task means "known project, nothing eligible" to clients; an unknown project must be an
        // error — and a deleted one is not addressable as a work source, so it answers the same way.
        if (record == null || record.archived) {
            refuseTaskLink(UnknownProjectException(project), HttpStatusCode.NotFound)
            return@post
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
