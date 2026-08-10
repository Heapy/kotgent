package io.kotgent.transport

import io.kotgent.adapter.claude.ClaudeHookConfig
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
 * The header a caller inside a kotgent pane identifies itself with — literally the SAME header the
 * provider hooks send, so an agent's shell has one thing to know.
 *
 * It is **defined as** [ClaudeHookConfig.TMUX_PANE_HEADER] rather than re-spelling the string, and that
 * is the whole point: the hook ingress reads the header through those adapter constants
 * (`HookRoutes.kt:83,120,157`), so a fourth independent literal here could drift from the value the
 * scripts on disk actually send, and the only symptom would be `/whoami` quietly answering "no session".
 * Claude's is the one chosen because this package already sits downstream of the adapters — `HookRoutes`
 * imports all three. The three ADAPTER constants remain three copies: each adapter owns its own
 * generated script and its own tests assert the literal inside it. That duplication predates the task
 * layer and is deliberately not extended by a fourth.
 *
 * The CLI sends it only when `$TMUX`'s socket path is kotgent's (see `io.kotgent.cli.TmuxSelf`): pane ids
 * are unique per tmux SERVER, so a `%2` from the operator's own tmux would otherwise resolve to an
 * unrelated kotgent pane and attribute the link to the wrong session.
 */
const val TASK_PANE_HEADER: String = ClaudeHookConfig.TMUX_PANE_HEADER

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
 * Who is calling — **three** answers, not two, and the distinction between the last two is the whole
 * reason this type exists.
 *
 * Collapsing "nobody identified themselves" and "somebody did, and it names nobody" into one `null` is
 * safe only for a route that refuses both. It is NOT safe for the two routes that may legitimately run
 * with no caller at all (`POST /tasks` and `PATCH /tasks/{ref}` — the board files and drags cards), and
 * that is where it did damage: they map "no caller" to [io.kotgent.daemon.TaskService.BOARD_AUTHOR], so
 * an unresolvable pane header or a malformed `sessionId` silently recorded an agent's write as the human
 * board actor. The activity feed is the only coordination signal the no-exclusivity design has; a feed
 * that attributes writes to whoever it could not identify is worse than one that refuses them.
 *
 * Existence of a [Resolved] session is deliberately NOT checked here — that needs the store, and the
 * routes that write on a caller's behalf check it themselves, because a silent no-op on a missing row is
 * exactly what `link` must not do.
 */
sealed interface CallerIdentity {

    /** Nothing was supplied: no pane header, no `sessionId`. The board's shape, and the ONLY one. */
    data object Absent : CallerIdentity

    /** The session a pane header or an explicit id named. Its row may or may not exist. */
    data class Resolved(val id: SessionId) : CallerIdentity

    /**
     * An identity WAS supplied and it names nobody — a header that is not a pane id, a pane the registry
     * does not know, or a `sessionId` [SessionId] refuses. [reason] is the whole `400` body, so the
     * caller learns which of the three it was.
     */
    data class Rejected(val reason: String) : CallerIdentity
}

/**
 * Resolve the caller from the `X-Kotgent-Tmux-Pane` header or from an explicit id in the request body.
 *
 * The explicit id **wins and is not re-resolved**: `--session <id>` is the escape hatch for a caller
 * outside any kotgent pane, and consulting the pane registry for it would just be a second way to fail.
 * A present-but-blank one is [CallerIdentity.Rejected] rather than a fall-through to the header: the
 * value was supplied, [SessionId] refuses it, and reading the header instead would attribute the write
 * to a session the caller never named.
 *
 * A pane the registry does not know is [CallerIdentity.Rejected] too, never some other session and never
 * the board — the registry is narrowed to the authoritative live-pane set by the `Reconciler`, so a stale
 * pane fails closed. Only a caller that sent NOTHING is [CallerIdentity.Absent]; the browser is the one
 * that does that, since `io.kotgent.cli.TmuxSelf` withholds the header outside a kotgent pane.
 */
suspend fun RoutingContext.resolveCallerIdentity(
    routing: TaskRouting,
    explicitSessionId: String?,
): CallerIdentity {
    if (explicitSessionId != null) {
        val id = runCatching { SessionId(explicitSessionId) }.getOrNull()
            ?: return CallerIdentity.Rejected(
                "'$explicitSessionId' is not a session id — name a live one with --session",
            )
        return CallerIdentity.Resolved(id)
    }
    val header = call.request.headers[TASK_PANE_HEADER] ?: return CallerIdentity.Absent
    val pane = runCatching { PaneId(header) }.getOrNull()
        ?: return CallerIdentity.Rejected(
            "the $TASK_PANE_HEADER header carried '$header', which is not a tmux pane id (%<n>)",
        )
    val session = routing.paneLookup(pane)
        ?: return CallerIdentity.Rejected(
            "no kotgent session is running in pane '$header' — name one with --session",
        )
    return CallerIdentity.Resolved(session)
}

/**
 * [resolveCallerIdentity] for the routes that refuse an absent and a rejected caller alike — `link`,
 * `unlink`, `next` and `GET /whoami`, all of which write `sessions.task_ref` or answer about one session,
 * so neither shape means anything to them and one `null` is the honest join.
 *
 * A route that may run for the BOARD must not use this: it cannot tell the board apart from a caller it
 * failed to identify. Use [resolveCallerIdentity] and answer [CallerIdentity.Rejected] with a `400`.
 */
suspend fun RoutingContext.resolveCallerSession(routing: TaskRouting, explicitSessionId: String?): SessionId? =
    (resolveCallerIdentity(routing, explicitSessionId) as? CallerIdentity.Resolved)?.id
