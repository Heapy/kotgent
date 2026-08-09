package io.kotgent.cli

import io.kotgent.transport.API_PREFIX
import io.kotgent.transport.AUTH_PAGE_PATH
import io.kotgent.transport.AUTH_ROTATE_PATH
import io.kotgent.transport.AUTH_TICKET_PATH
import io.kotgent.transport.ImportSessionRequest
import io.kotgent.transport.RotateResponse
import io.kotgent.transport.SessionDto
import io.kotgent.transport.StartSessionRequest
import io.kotgent.transport.TRANSPORT_JSON
import io.kotgent.transport.TicketResponse
import io.kotgent.transport.defaultTokenPath
import io.kotgent.transport.readTokenOrNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Thrown when a control call is attempted but no token is available (the daemon owns token creation). */
class MissingTokenException(val tokenPath: String) : RuntimeException(
    "no kotgent token found at $tokenPath — is the daemon running? start it with: kotgent daemon",
)

/** Thrown when the daemon answers a control call with a non-2xx status (e.g. 409 resume-blocked). */
class ApiException(val status: Int, val body: String) :
    RuntimeException("kotgent daemon returned HTTP $status: ${body.trim().ifEmpty { "(no body)" }}")

/**
 * Where [path] actually lives on the daemon: under [API_PREFIX], **except** for the `/auth*` bootstrap
 * surface, which deliberately did not move (see [API_PREFIX]'s KDoc).
 *
 * The exemption is not cosmetic. This one client mixes both kinds — `"/sessions"` moved,
 * [AUTH_TICKET_PATH] and [AUTH_ROTATE_PATH] did not — so a blanket `"$API_PREFIX$path"` helper would
 * silently break `kotgent web` (which mints a login ticket) and `kotgent token rotate`. It is the exact
 * counterpart of the `/auth` exemption inside `resources/webui/lib/api.js`'s `apiRequest`/`wsUrl`; both
 * sides need it, and they must agree.
 */
fun daemonPath(path: String): String = if (path.startsWith(AUTH_PAGE_PATH)) path else "$API_PREFIX$path"

/**
 * A thin Ktor CIO **client** for the kotgent daemon's control REST (plan Task 15). It talks to the same
 * surface [io.kotgent.transport.controlRoutes] serves, reusing the transport's wire types
 * ([SessionDto] / [StartSessionRequest] / [TRANSPORT_JSON]) so the CLI and the server can never drift.
 *
 * Auth: the shared token (read from `~/.kotgent/token` by default via [readTokenOrNull]) is sent as
 * `Authorization: Bearer <token>` on every call. A `null` token makes each call fail fast with
 * [MissingTokenException] before any network I/O, so the CLI can print a clear setup hint.
 *
 * Testability: point [baseUrl] at an embedded stub Ktor server on an ephemeral port and pass an explicit
 * [token] — every method issues exactly one REST call against it (see `CliTest`).
 */
class ApiClient(
    private val baseUrl: String = defaultBaseUrl(),
    private val token: String? = readTokenOrNull(),
    private val client: HttpClient = defaultHttpClient(),
    private val json: Json = TRANSPORT_JSON,
    private val tokenPath: String = defaultTokenPath(),
) : AutoCloseable {

    /** `GET /api/v1/sessions` — all sessions from the daemon's cache. */
    suspend fun listSessions(): List<SessionDto> {
        val resp = client.get(url("/sessions")) { bearer() }
        ensureSuccess(resp)
        return json.decodeFromString(ListSerializer(SessionDto.serializer()), resp.bodyAsText())
    }

    /** `POST /api/v1/sessions` — start a new [agent] session in [cwd]; returns the created session (with its id). */
    suspend fun startSession(
        agent: String,
        cwd: String,
        name: String? = null,
        tags: List<String> = emptyList(),
    ): SessionDto {
        val body = json.encodeToString(StartSessionRequest.serializer(), StartSessionRequest(agent, cwd, name, tags))
        val resp = client.post(url("/sessions")) {
            bearer()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        ensureSuccess(resp)
        return json.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
    }

    /**
     * `POST /api/v1/sessions/import` — register a provider session started OUTSIDE kotgent as a `resumable`
     * row (no launch, no tmux side effect); returns the created session. A null [cwd] lets the daemon
     * discover the project directory from the provider's on-disk store. Import failures surface as
     * [ApiException]: 409 = the provider id is already held by an existing kotgent session (the body
     * names it), 400 = unknown agent / malformed id / cwd or transcript problems.
     */
    suspend fun importSession(
        agent: String,
        providerSessionId: String,
        cwd: String? = null,
        name: String? = null,
        tags: List<String> = emptyList(),
    ): SessionDto {
        val body = json.encodeToString(
            ImportSessionRequest.serializer(),
            ImportSessionRequest(agent, providerSessionId, cwd, name, tags),
        )
        val resp = client.post(url("/sessions/import")) {
            bearer()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        ensureSuccess(resp)
        return json.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
    }

    /** `POST /api/v1/sessions/{id}/stop`. Returns the updated session if the daemon echoed one. */
    suspend fun stop(id: String): SessionDto? = control(id, "stop")

    /** `POST /api/v1/sessions/{id}/resume`. Throws [ApiException] (409) if the provider id is still pending. */
    suspend fun resume(id: String): SessionDto? = control(id, "resume")

    /** `POST /api/v1/sessions/{id}/interrupt`. */
    suspend fun interrupt(id: String): SessionDto? = control(id, "interrupt")

    private suspend fun control(id: String, action: String): SessionDto? {
        val resp = client.post(url("/sessions/$id/$action")) { bearer() }
        ensureSuccess(resp)
        val text = resp.bodyAsText()
        // The daemon returns the updated SessionDto for a live session, or a plain "ok" otherwise.
        return runCatching { json.decodeFromString(SessionDto.serializer(), text) }.getOrNull()
    }

    /**
     * `POST /auth/ticket` — mint a one-shot login ticket plus the URLs (local + optional public) that carry
     * it in their fragment. `Bearer` + loopback only on the daemon side, so this is the CLI's job, not a
     * browser's. Backs `kotgent web` and the Task-11 QR dialog.
     */
    suspend fun issueTicket(): TicketResponse {
        val resp = client.post(url(AUTH_TICKET_PATH)) { bearer() }
        ensureSuccess(resp)
        return json.decodeFromString(TicketResponse.serializer(), resp.bodyAsText())
    }

    /**
     * `POST /auth/rotate` — re-mint the master token, returning the new value for `kotgent token rotate` to
     * print. The daemon persists it (token file + hook headers) and publishes it before answering, so the
     * old key stops authenticating new requests the moment this returns.
     */
    suspend fun rotateToken(): String {
        val resp = client.post(url(AUTH_ROTATE_PATH)) { bearer() }
        ensureSuccess(resp)
        return json.decodeFromString(RotateResponse.serializer(), resp.bodyAsText()).token
    }

    override fun close(): Unit = client.close()

    // --- internals -------------------------------------------------------------------------------

    /** [baseUrl] plus [daemonPath] of [path] — the one place a CLI call learns where a route lives. */
    private fun url(path: String): String = "$baseUrl${daemonPath(path)}"

    private fun HttpRequestBuilder.bearer() {
        val t = token ?: throw MissingTokenException(tokenPath)
        header(HttpHeaders.Authorization, "Bearer $t")
    }

    private suspend fun ensureSuccess(resp: HttpResponse) {
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, resp.bodyAsText())
    }
}

/**
 * The CLI's HTTP client: plain CIO plus finite timeouts. Every control call is a short request/response,
 * so an answer that never arrives is always a failure, never patience — and it *can* never arrive: if
 * the daemon died while an orphaned process still holds its listening socket (see
 * [io.kotgent.sys.markOpenFdsCloexec]), the kernel completes the TCP handshake against a socket nobody
 * accepts, and an untimed client waits forever. Timeouts turn that into a reportable error.
 */
fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        socketTimeoutMillis = REQUEST_TIMEOUT_MS
    }
}

/** TCP connect budget — loopback, so anything slower than this is not a live daemon. */
private const val CONNECT_TIMEOUT_MS: Long = 3_000

/** End-to-end budget for one control call (`start` shells out to tmux + claude, so not too tight). */
private const val REQUEST_TIMEOUT_MS: Long = 20_000
