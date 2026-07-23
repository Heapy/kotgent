package io.kotgent.transport

import io.kotgent.adapter.claude.ClaudeHookConfig
import io.kotgent.adapter.claude.ClaudeHookNormalizer
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.SessionId
import io.kotgent.store.EventStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The Claude hook ingress (plan Task 12) — the HTTP entry point the hooks configured by
 * [ClaudeHookConfig] POST to. Installs `POST /hooks/claude` ([ClaudeHookConfig.INGRESS_PATH]) on the
 * receiver [Route].
 *
 * Per request:
 *  1. **Authenticate** the shared hook token (header [ClaudeHookConfig.HOOK_TOKEN_HEADER]); wrong or
 *     missing → `401`, done FIRST so nothing about a session leaks to an unauthenticated caller.
 *  2. Read the **hook event name** (`?event=` query, or the [ClaudeHookConfig.HOOK_EVENT_HEADER]
 *     header) and **`$TMUX_PANE`** (header [ClaudeHookConfig.TMUX_PANE_HEADER]). Missing/malformed → `400`.
 *  3. **Resolve pane → session** via the injected [paneLookup]. An unknown pane → `404` — a clean error,
 *     never a crash. In production the lookup is the SessionManager pane registry (Task 13); tests
 *     inject a seeded map.
 *  4. **Normalize** the payload with [ClaudeHookNormalizer]; if it maps to an event, [EventStore.append]
 *     it with source [EventSource.hook]. A wired-but-unmapped hook (normalizes to `null`) still `200`s.
 *
 * The route is a plain function of `(token, paneLookup, store)` so it is testable in isolation with an
 * embedded server + a fake lookup + an in-memory store — no daemon, no real tmux (Task 3 proved Ktor
 * CIO server + client run in the test binary).
 *
 * ## [decision] How Claude events reach the runtime event stream
 * The ingress appends directly to the [EventStore] — the single source of truth — rather than pushing
 * into a separate per-adapter channel. Downstream (the events-WS in Task 14, the daemon in Task 13)
 * READS the store, so `ClaudeAdapter.events` for Claude is intended to be backed by
 * `store.subscribe(sessionId, fromSeq).map { it.event }`, not a distinct push path. This keeps ONE
 * ordering authority (the store's per-session `seq`) and makes the stream restart-safe for free. The
 * full daemon wiring (constructing the adapter over the store subscription) is Task 13 — NOT built here;
 * this task only makes the ingress append to the store and records the intended wiring.
 */
fun Route.claudeHookRoutes(
    token: String,
    paneLookup: suspend (PaneId) -> SessionId?,
    store: EventStore,
    json: Json = HOOK_JSON,
) {
    post(ClaudeHookConfig.INGRESS_PATH) {
        // 1. Authenticate the shared hook token before anything else (constant-time — see Auth).
        val presented = call.request.headers[ClaudeHookConfig.HOOK_TOKEN_HEADER]
        if (presented == null || !constantTimeEquals(presented, token)) {
            call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
            return@post
        }

        // 2. Hook event name (query param preferred, header as fallback) + $TMUX_PANE.
        val event = call.request.queryParameters["event"]
            ?: call.request.headers[ClaudeHookConfig.HOOK_EVENT_HEADER]
        if (event.isNullOrBlank()) {
            call.respondText("missing hook event name", status = HttpStatusCode.BadRequest)
            return@post
        }
        val paneRaw = call.request.headers[ClaudeHookConfig.TMUX_PANE_HEADER]
        if (paneRaw.isNullOrBlank()) {
            call.respondText("missing tmux pane header", status = HttpStatusCode.BadRequest)
            return@post
        }
        val paneId = runCatching { PaneId(paneRaw) }.getOrNull()
        if (paneId == null) {
            call.respondText("malformed pane id '$paneRaw'", status = HttpStatusCode.BadRequest)
            return@post
        }

        // 3. Resolve pane → session. An unknown pane is a clean 404, not a crash.
        val sessionId = paneLookup(paneId)
        if (sessionId == null) {
            call.respondText("unknown pane ${paneId.value}", status = HttpStatusCode.NotFound)
            return@post
        }

        // 4. Parse the payload, normalize, append. An empty body is a valid empty object (hooks that
        // read no field still map); a non-empty, non-JSON body is a 400.
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

        val normalized = ClaudeHookNormalizer.normalize(event, payload, paneId)
        if (normalized != null) {
            store.append(sessionId, normalized, EventSource.hook)
            call.respondText("ok", status = HttpStatusCode.OK)
        } else {
            // A wired-but-unmapped hook (or a SessionStart with no usable id): accepted, nothing stored.
            call.respondText("ignored", status = HttpStatusCode.OK)
        }
    }
}

/** Lenient JSON for untrusted hook payloads (Claude sends fields we do not model). */
val HOOK_JSON: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val EMPTY_OBJECT: JsonObject = JsonObject(emptyMap())
