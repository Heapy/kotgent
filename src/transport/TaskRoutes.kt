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

class TaskRouting(
    val tasks: TaskStore,
    val service: TaskService,
    val sessions: EventStore,
    val paneLookup: suspend (PaneId) -> SessionId?,
    val json: Json = TRANSPORT_JSON,
)

// Reuse the generated-hook header constant so pane identity cannot drift between ingress and task APIs.
const val TASK_PANE_HEADER: String = ClaudeHookConfig.TMUX_PANE_HEADER

fun Route.taskRoutes(routing: TaskRouting) {
    taskReadRoutes(routing)
    taskWriteRoutes(routing)
    taskLinkRoutes(routing)
}

// Missing identity is the board; supplied-but-invalid identity must fail instead of being attributed to it.
sealed interface CallerIdentity {

    data object Absent : CallerIdentity

    data class Resolved(val id: SessionId) : CallerIdentity

    data class Rejected(val reason: String) : CallerIdentity
}

suspend fun RoutingContext.resolveCallerIdentity(
    routing: TaskRouting,
    explicitSessionId: String?,
): CallerIdentity {
    // Explicit --session is an escape hatch outside tmux and intentionally wins over a pane header.
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

suspend fun RoutingContext.resolveCallerSession(routing: TaskRouting, explicitSessionId: String?): SessionId? =
    (resolveCallerIdentity(routing, explicitSessionId) as? CallerIdentity.Resolved)?.id
