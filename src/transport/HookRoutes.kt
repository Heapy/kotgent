package io.kotgent.transport

import io.kotgent.adapter.claude.ClaudeHookConfig
import io.kotgent.adapter.claude.ClaudeHookNormalizer
import io.kotgent.adapter.codex.CodexHookConfig
import io.kotgent.adapter.codex.CodexHookNormalizer
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.SessionId
import io.kotgent.store.EventStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.delay
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
    /**
     * How long to keep retrying an unresolved pane→session lookup before answering `404`. A hook can
     * fire in the brief window between `tmux new-session` launching the agent and the daemon registering
     * the pane (see [io.kotgent.daemon.SessionManager.start]); without a grace window an early
     * `SessionStart` would 404 and the provider id would stay permanently pending. Small (a few ms) in
     * practice; tests pass `0` to keep the genuine-unknown-pane case fast.
     */
    paneLookupGraceMillis: Long = PANE_LOOKUP_GRACE_MILLIS,
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
)

/**
 * The Codex hook ingress: `POST /hooks/codex` ([CodexHookConfig.INGRESS_PATH]), the counterpart of
 * [claudeHookRoutes] for the hooks the generated `codex-hook.sh` posts. Same contract in every respect
 * (auth → event name → pane → normalize → append); only the path and the normalizer differ.
 *
 * It is a SEPARATE path rather than one ingress with a `?provider=` parameter so the two providers' hook
 * vocabularies cannot be confused: `Stop` means "turn finished" to both, but `SessionEnd` exists only in
 * Codex and `Notification` only in Claude, and routing by path makes the mapping unambiguous.
 */
fun Route.codexHookRoutes(
    token: String,
    paneLookup: suspend (PaneId) -> SessionId?,
    store: EventStore,
    json: Json = HOOK_JSON,
    /** See [claudeHookRoutes]. */
    paneLookupGraceMillis: Long = PANE_LOOKUP_GRACE_MILLIS,
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
)

/**
 * The provider-neutral hook ingress both [claudeHookRoutes] and [codexHookRoutes] are built from: the
 * authenticate → identify → resolve → normalize → append pipeline described in [claudeHookRoutes], with
 * the provider-specific pieces ([path], the header names, [normalize]) passed in.
 */
private fun Route.hookRoutes(
    path: String,
    tokenHeader: String,
    paneHeader: String,
    eventHeader: String,
    normalize: (String, JsonElement, PaneId) -> AgentEvent?,
    token: String,
    paneLookup: suspend (PaneId) -> SessionId?,
    store: EventStore,
    json: Json,
    paneLookupGraceMillis: Long,
) {
    post(path) {
        // 1. Authenticate the shared hook token before anything else (constant-time — see Auth).
        val presented = call.request.headers[tokenHeader]
        if (presented == null || !constantTimeEquals(presented, token)) {
            call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
            return@post
        }

        // 2. Hook event name (query param preferred, header as fallback) + $TMUX_PANE.
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

        // 3. Resolve pane → session, tolerating the brief post-launch/pre-register window with a bounded
        // retry (see paneLookupGraceMillis). A still-unknown pane after the grace is a clean 404.
        val sessionId = resolvePane(paneLookup, paneId, paneLookupGraceMillis)
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

        val normalized = normalize(event, payload, paneId)
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

/** Default grace window for the pane→session lookup retry (see [claudeHookRoutes]). */
const val PANE_LOOKUP_GRACE_MILLIS: Long = 2_000

/** Poll interval while waiting for a not-yet-registered pane to appear. */
private const val PANE_LOOKUP_POLL_MILLIS: Long = 25

/**
 * Resolve [paneId] → session, retrying for up to [graceMillis] so a hook that arrives in the tiny window
 * between the agent launching and its pane being registered is not dropped with a hard 404. Returns as
 * soon as the pane resolves; `null` only if it never does within the grace.
 */
private suspend fun resolvePane(
    paneLookup: suspend (PaneId) -> SessionId?,
    paneId: PaneId,
    graceMillis: Long,
): SessionId? {
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
