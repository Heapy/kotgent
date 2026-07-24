package io.kotgent.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post

/**
 * [PushTransport] over NSURLSession — the daemon's only outbound HTTPS client.
 *
 * ## Why Darwin and not CIO
 * Every other client in this codebase (`ApiClient`, `AttachClient`) is `ktor-client-cio`, because it only
 * ever talks to `http://127.0.0.1`. A push endpoint is always `https://`, and Ktor's CIO client **cannot do
 * TLS on Kotlin/Native at all**: its non-JVM `openTLSSession` is a hard
 * `error("TLS sessions are not supported on Native platform.")`. This is the client-side twin of the
 * server-side fact CLAUDE.md already records (`ktor-server-cio` has no `sslConnector` on native, hence the
 * cloudflared tunnel) — with the same conclusion for opposite reasons: inbound TLS is delegated to
 * Cloudflare, outbound TLS is delegated to NSURLSession. The bonus is the **system trust store**: Apple's
 * and Google's certificate chains validate against the OS roots with no bundled CA list to go stale.
 *
 * ## Timeouts are mandatory
 * The CLAUDE.md rule — never issue an untimed request — applies with extra force here: this runs on the
 * daemon's background scope while collecting session updates, so a push service that accepts a connection
 * and then goes silent would wedge the notifier, not just one request. The budget is deliberately short:
 * a notification that takes more than a few seconds to hand off has already lost its race with the
 * operator noticing on their own.
 *
 * @param client injected so a test can supply a `MockEngine`; production builds [defaultPushHttpClient].
 */
class DarwinPushTransport(
    private val client: HttpClient = defaultPushHttpClient(),
) : PushTransport, AutoCloseable {

    override suspend fun post(url: String, headers: Map<String, String>): Int {
        val response = client.post(url) {
            for ((name, value) in headers) header(name, value)
            // RFC 8030 allows a bodyless push message, and the whole payload-less design rests on it: an
            // absent body means no RFC 8291 encryption and no misleading application/octet-stream
            // Content-Type. Ktor's default NoContent still emits the zero-length request correctly.
        }
        return response.status.value
    }

    /**
     * Release the NSURLSession. Only meaningful for a caller that owns the client; the daemon holds one
     * for its whole life, which is the point of a session that pools connections to Apple and Google.
     */
    override fun close(): Unit = client.close()
}

/**
 * The push client: NSURLSession plus finite timeouts (see [DarwinPushTransport] on why both halves are
 * load-bearing).
 */
fun defaultPushHttpClient(): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        connectTimeoutMillis = PUSH_CONNECT_TIMEOUT_MS
        requestTimeoutMillis = PUSH_REQUEST_TIMEOUT_MS
        socketTimeoutMillis = PUSH_REQUEST_TIMEOUT_MS
    }
}

/** TCP + TLS budget for reaching a push service. */
private const val PUSH_CONNECT_TIMEOUT_MS: Long = 10_000

/** End-to-end budget for handing one message to a push service — a header-only POST, so this is generous. */
private const val PUSH_REQUEST_TIMEOUT_MS: Long = 20_000
