package io.kotgent.transport

import io.kotgent.adapter.claude.ClaudeHookConfig
import io.kotgent.adapter.claude.ClaudeHookNormalizer
import io.kotgent.adapter.codex.CodexHookConfig
import io.kotgent.adapter.codex.CodexHookNormalizer
import io.kotgent.adapter.junie.JunieHookConfig
import io.kotgent.adapter.junie.JunieHookNormalizer
import io.kotgent.cli.eprintln
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.SessionId
import io.kotgent.store.EventStore
import io.kotgent.tmux.TmuxHookConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

fun Route.claudeHookRoutes(
    token: () -> String,
    paneLookup: suspend (PaneId) -> SessionId?,
    store: EventStore,
    json: Json = HOOK_JSON,
    paneLookupGraceMillis: Long = PANE_LOOKUP_GRACE_MILLIS,
    modelCapture: ClaudeModelCapture = ClaudeModelCapture(store),
) = hookRoutes(
    path = ClaudeHookConfig.INGRESS_PATH,
    tokenHeader = ClaudeHookConfig.HOOK_TOKEN_HEADER,
    paneHeader = ClaudeHookConfig.TMUX_PANE_HEADER,
    eventHeader = ClaudeHookConfig.HOOK_EVENT_HEADER,
    normalize = ClaudeHookNormalizer::normalize,
    token = token,
    paneLookup = paneLookup,
    store = store,
    json = json,
    paneLookupGraceMillis = paneLookupGraceMillis,
    onHookPayload = { sessionId, payload -> modelCapture.maybeCapture(sessionId, payload) },
)

fun Route.codexHookRoutes(
    token: () -> String,
    paneLookup: suspend (PaneId) -> SessionId?,
    store: EventStore,
    json: Json = HOOK_JSON,
    paneLookupGraceMillis: Long = PANE_LOOKUP_GRACE_MILLIS,
    onProviderIdRebound: suspend (SessionId) -> Unit = {},
) = hookRoutes(
    path = CodexHookConfig.INGRESS_PATH,
    tokenHeader = CodexHookConfig.HOOK_TOKEN_HEADER,
    paneHeader = CodexHookConfig.TMUX_PANE_HEADER,
    eventHeader = CodexHookConfig.HOOK_EVENT_HEADER,
    normalize = CodexHookNormalizer::normalize,
    token = token,
    paneLookup = paneLookup,
    store = store,
    json = json,
    paneLookupGraceMillis = paneLookupGraceMillis,
    onProviderIdRebound = onProviderIdRebound,
)

fun Route.junieHookRoutes(
    token: () -> String,
    paneLookup: suspend (PaneId) -> SessionId?,
    store: EventStore,
    json: Json = HOOK_JSON,
    paneLookupGraceMillis: Long = PANE_LOOKUP_GRACE_MILLIS,
    onProviderIdRebound: suspend (SessionId) -> Unit = {},
) = hookRoutes(
    path = JunieHookConfig.INGRESS_PATH,
    tokenHeader = JunieHookConfig.HOOK_TOKEN_HEADER,
    paneHeader = JunieHookConfig.TMUX_PANE_HEADER,
    eventHeader = JunieHookConfig.HOOK_EVENT_HEADER,
    normalize = JunieHookNormalizer::normalize,
    token = token,
    paneLookup = paneLookup,
    store = store,
    json = json,
    paneLookupGraceMillis = paneLookupGraceMillis,
    onProviderIdRebound = onProviderIdRebound,
)

fun Route.tmuxHookRoutes(
    token: () -> String,
    onSessionClosed: suspend (SessionId) -> Unit,
) = loopbackOnly {
    post(TmuxHookConfig.INGRESS_PATH) {
        val presented = call.request.headers[TmuxHookConfig.HOOK_TOKEN_HEADER]
        if (presented == null || !constantTimeEquals(presented, token())) {
            call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
            return@post
        }

        val sessionId = call.request.headers[TmuxHookConfig.SESSION_HEADER]
            ?.takeIf { it.startsWith(TMUX_SESSION_PREFIX) }
            ?.removePrefix(TMUX_SESSION_PREFIX)
            ?.let { raw -> runCatching { SessionId(raw) }.getOrNull() }

        if (sessionId == null) {
            // The global tmux hook also observes sessions not owned by kotgent.
            call.respondText("ignored", status = HttpStatusCode.OK)
            return@post
        }

        runCatching { onSessionClosed(sessionId) }.onFailure { failure ->
            eprintln("tmux session-close handling failed for '${sessionId.value}': $failure")
        }
        call.respondText("ok", status = HttpStatusCode.OK)
    }
}

private fun Route.hookRoutes(
    path: String,
    tokenHeader: String,
    paneHeader: String,
    eventHeader: String,
    normalize: (String, JsonElement, PaneId) -> AgentEvent?,
    token: () -> String,
    paneLookup: suspend (PaneId) -> SessionId?,
    store: EventStore,
    json: Json,
    paneLookupGraceMillis: Long,
    onHookPayload: suspend (SessionId, JsonElement) -> Unit = { _, _ -> },
    onProviderIdRebound: suspend (SessionId) -> Unit = {},
) = loopbackOnly {
    // Provider hooks originate locally; keeping this wrapper here prevents accidental tunnel exposure.
    post(path) {
        val presented = call.request.headers[tokenHeader]
        if (presented == null || !constantTimeEquals(presented, token())) {
            call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
            return@post
        }

        val event = call.request.queryParameters["event"]
            ?: call.request.headers[eventHeader]
        if (event.isNullOrBlank()) {
            call.respondText("missing hook event name", status = HttpStatusCode.BadRequest)
            return@post
        }
        val paneRaw = call.request.headers[paneHeader]
        if (paneRaw.isNullOrBlank()) {
            call.respondText("missing tmux pane header", status = HttpStatusCode.BadRequest)
            return@post
        }
        val paneId = runCatching { PaneId(paneRaw) }.getOrNull()
        if (paneId == null) {
            call.respondText("malformed pane id '$paneRaw'", status = HttpStatusCode.BadRequest)
            return@post
        }

        val sessionId = resolvePane(paneLookup, paneId, paneLookupGraceMillis)
        if (sessionId == null) {
            call.respondText("unknown pane ${paneId.value}", status = HttpStatusCode.NotFound)
            return@post
        }

        val body = call.receiveText()
        val payload: JsonElement = if (body.isBlank()) {
            EMPTY_OBJECT
        } else {
            try {
                json.parseToJsonElement(body)
            } catch (_: SerializationException) {
                call.respondText("invalid JSON payload", status = HttpStatusCode.BadRequest)
                return@post
            }
        }

        onHookPayload(sessionId, payload)

        val normalized = normalize(event, payload, paneId)
        if (normalized != null) {
            val priorProviderId =
                if (normalized is AgentEvent.SessionBound) store.getSession(sessionId)?.providerSessionId
                else null
            val displacing = normalized is AgentEvent.SessionBound &&
                priorProviderId != null &&
                priorProviderId != normalized.providerSessionId
            if (displacing) {
                // Once the authoritative id commits, a retry no longer looks like a displacement. Keep
                // append plus correction non-cancellable so a dropped hook connection cannot split them.
                withContext(NonCancellable) {
                    store.append(sessionId, normalized, EventSource.hook)
                    runCatching { onProviderIdRebound(sessionId) }.onFailure { failure ->
                        eprintln("provider-id rebind correction failed for '${sessionId.value}': $failure")
                    }
                }
            } else {
                store.append(sessionId, normalized, EventSource.hook)
            }
            call.respondText("ok", status = HttpStatusCode.OK)
        } else {
            call.respondText("ignored", status = HttpStatusCode.OK)
        }
    }
}

val HOOK_JSON: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

const val PANE_LOOKUP_GRACE_MILLIS: Long = 2_000

private const val PANE_LOOKUP_POLL_MILLIS: Long = 25

private suspend fun resolvePane(
    paneLookup: suspend (PaneId) -> SessionId?,
    paneId: PaneId,
    graceMillis: Long,
): SessionId? {
    // Hooks can fire after tmux launch but before SessionManager publishes the pane registry entry.
    paneLookup(paneId)?.let { return it }
    var waited = 0L
    while (waited < graceMillis) {
        delay(PANE_LOOKUP_POLL_MILLIS)
        paneLookup(paneId)?.let { return it }
        waited += PANE_LOOKUP_POLL_MILLIS
    }
    return null
}

private val EMPTY_OBJECT: JsonObject = JsonObject(emptyMap())

private const val TMUX_SESSION_PREFIX: String = "kt-"
