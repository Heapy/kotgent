package io.kotgent.cli

import io.kotgent.transport.SessionDto
import io.kotgent.transport.StartSessionRequest
import io.kotgent.transport.TRANSPORT_JSON
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

    /** `GET /sessions` — all sessions from the daemon's cache. */
    suspend fun listSessions(): List<SessionDto> {
        val resp = client.get("$baseUrl/sessions") { bearer() }
        ensureSuccess(resp)
        return json.decodeFromString(ListSerializer(SessionDto.serializer()), resp.bodyAsText())
    }

    /** `POST /sessions` — start a new [agent] session in [cwd]; returns the created session (with its id). */
    suspend fun startSession(
        agent: String,
        cwd: String,
        name: String? = null,
        tags: List<String> = emptyList(),
    ): SessionDto {
        val body = json.encodeToString(StartSessionRequest.serializer(), StartSessionRequest(agent, cwd, name, tags))
        val resp = client.post("$baseUrl/sessions") {
            bearer()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        ensureSuccess(resp)
        return json.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
    }

    /** `POST /sessions/{id}/stop`. Returns the updated session if the daemon echoed one. */
    suspend fun stop(id: String): SessionDto? = control(id, "stop")

    /** `POST /sessions/{id}/resume`. Throws [ApiException] (409) if the provider id is still pending. */
    suspend fun resume(id: String): SessionDto? = control(id, "resume")

    /** `POST /sessions/{id}/interrupt`. */
    suspend fun interrupt(id: String): SessionDto? = control(id, "interrupt")

    private suspend fun control(id: String, action: String): SessionDto? {
        val resp = client.post("$baseUrl/sessions/$id/$action") { bearer() }
        ensureSuccess(resp)
        val text = resp.bodyAsText()
        // The daemon returns the updated SessionDto for a live session, or a plain "ok" otherwise.
        return runCatching { json.decodeFromString(SessionDto.serializer(), text) }.getOrNull()
    }

    override fun close(): Unit = client.close()

    // --- internals -------------------------------------------------------------------------------

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
