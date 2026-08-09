package io.kotgent.transport

import io.kotgent.core.PaneId
import io.kotgent.core.SessionId
import io.kotgent.daemon.TaskService
import io.kotgent.store.EventStore
import io.kotgent.store.TaskStore
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.json.Json

/**
 * Everything the task routes need, in one object.
 *
 * A bundle rather than six parameters repeated three times: the three route files are owned by three
 * different agents and may not change a signature declared here, so the contract deliberately hands each
 * of them MORE than it needs rather than exactly enough. [TaskService] also carries the project
 * filesystem and the project-file writer as public properties, which is why [KotgentServer] needs only
 * the two nullable task parameters.
 */
class TaskRouting(
    val tasks: TaskStore,
    val service: TaskService,
    val sessions: EventStore,
    /** Pane → session, the registry the hook ingress uses (`SessionManager.paneLookup`). */
    val paneLookup: suspend (PaneId) -> SessionId?,
    val json: Json = TRANSPORT_JSON,
)

/**
 * The header a caller inside a kotgent pane identifies itself with — the SAME header the provider hooks
 * send, so an agent's shell has one thing to know.
 *
 * The CLI sends it only when `$TMUX`'s socket path is kotgent's (see `io.kotgent.cli.TmuxSelf`): pane ids
 * are unique per tmux SERVER, so a `%2` from the operator's own tmux would otherwise resolve to an
 * unrelated kotgent pane and attribute the link to the wrong session.
 */
const val TASK_PANE_HEADER: String = "X-Kotgent-Tmux-Pane"

/**
 * The task/backlog REST surface, mounted inside the `route(API_PREFIX)` block (so it is cookie/`Bearer`
 * gated like the rest of the client-facing API, and so the SPA keeps the bare `/tasks` path).
 *
 * ```
 * GET    /whoami              { sessionId, projectId, taskRef } for the calling pane
 * GET    /tasks?project=<u>   backlog entries joined with tracker fields
 * POST   /tasks               create — { project?, title, body }
 * GET    /tasks/{ref}         entry + tracker fields + project path + deps + sessions + activity
 * PATCH  /tasks/{ref}         title / body / state — state may carry an optional message
 * DELETE /tasks/{ref}         unlink every session, then remove the task, its deps and its feed
 * POST   /tasks/{ref}/move    { before | after | top | bottom }
 * POST   /tasks/{ref}/deps    { action: add|remove, on }
 * POST   /tasks/{ref}/comment { text }
 * POST   /tasks/{ref}/link    link the calling session to this task
 * POST   /tasks/{ref}/unlink  drop this session's link; the task's state is untouched
 * POST   /tasks/next          { project? } → link the next eligible task to the calling session
 * GET    /projects            known projects (the board selector)
 * POST   /projects            create/init a project at a path
 * ```
 *
 * `POST /tasks/next` and `POST /tasks/claim`-shaped literals can never be shadowed by
 * `POST /tasks/{ref}/…`, because a [io.kotgent.core.TaskRef] must contain a `:` and a bare word cannot
 * parse as one. That is load-bearing, not incidental — pin it with a test.
 *
 * Split into three files purely so three agents can implement them in parallel; the split is by verb
 * (read / write / link), and nothing but this aggregator knows about it.
 */
fun Route.taskRoutes(routing: TaskRouting) {
    taskReadRoutes(routing)
    taskWriteRoutes(routing)
    taskLinkRoutes(routing)
}

/**
 * Who is calling, from the `X-Kotgent-Tmux-Pane` header or from an explicit id in the request body, or
 * `null` when neither answers.
 *
 * The explicit id **wins and is not re-resolved**: `--session <id>` is the escape hatch for a caller
 * outside any kotgent pane, and consulting the pane registry for it would just be a second way to fail.
 * A pane the registry does not know resolves to `null` rather than to some other session — the registry
 * is narrowed to the authoritative live-pane set by the `Reconciler`, so a stale pane fails closed.
 *
 * Existence of the named session is NOT checked here; a route that writes on the caller's behalf checks
 * it, because a silent no-op on a missing row is exactly what `link` must not do.
 *
 * `link`, `unlink`, `comment` and `next` REQUIRE this (all four write `sessions.task_ref` or attribute an
 * activity row); `POST /tasks` deliberately does not, because the board has neither a pane nor a session.
 */
suspend fun RoutingContext.resolveCallerSession(routing: TaskRouting, explicitSessionId: String?): SessionId? {
    val explicit = explicitSessionId?.takeIf { it.isNotBlank() }
    if (explicit != null) return runCatching { SessionId(explicit) }.getOrNull()
    val header = call.request.headers[TASK_PANE_HEADER]?.takeIf { it.isNotBlank() } ?: return null
    val pane = runCatching { PaneId(header) }.getOrNull() ?: return null
    return routing.paneLookup(pane)
}
