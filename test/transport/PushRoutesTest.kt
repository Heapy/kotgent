package io.kotgent.transport

import io.kotgent.push.PushStore
import io.kotgent.push.PushSubscription
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO

/**
 * The `/push` registration routes (plan Task 3): who may reach them, what a valid subscription looks like,
 * and that the row actually lands in (and leaves) the store.
 *
 * A bare server mounting [authRoutes] — so a real session cookie can be minted the way a browser gets one —
 * plus [pushRoutes] inside the same [authenticated] gate [KotgentServer] uses. That is the whole point of
 * the auth cases here: not to re-test [authorize] (which [AuthorizeWiringTest] pins), but to prove these
 * three routes are mounted INSIDE the gate and therefore inherit it.
 *
 * The store is an in-memory [Mutex]-guarded fake, because the CIO server runs handlers on its own engine
 * threads: the test thread reads state the handler wrote, so it needs the happens-before a coroutine lock
 * gives. Every body is bounded by [withTimeout] (anti-hang).
 */
class PushRoutesTest {

    private val token = "push-routes-master-token-0123456789ab"
    private val publicUrl = "https://kotgent.example.com"
    private val vapidKey = "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U"
    private val fixedNow = 1_753_280_000_000L

    // --- vapid key ---------------------------------------------------------------------------------

    @Test
    fun theVapidKeyRouteReturnsTheInjectedKey() = withPushServer { env ->
        val resp = env.client.req(env.port, PUSH_VAPID_KEY_PATH, bearer = token)
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(
            vapidKey,
            TRANSPORT_JSON.decodeFromString(VapidKeyResponse.serializer(), resp.bodyAsText()).key,
            "the route hands the provider's key through verbatim — it is the applicationServerKey",
        )
    }

    @Test
    fun aFailingKeyProviderIsA503AndNotACrash() = withPushServer(
        vapidPublicKey = { error("/usr/bin/openssl: not found") },
    ) { env ->
        val resp = env.client.req(env.port, PUSH_VAPID_KEY_PATH, bearer = token)
        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            resp.status,
            "no key means no push — a per-route 503 the operator can read, not a 500",
        )
        assertTrue(resp.bodyAsText().contains("openssl"), "the diagnostic names the real cause")

        // And the daemon is fine: the rest of the surface still answers.
        assertEquals(
            HttpStatusCode.OK,
            env.client.req(
                env.port, PUSH_UNSUBSCRIBE_PATH, HttpMethod.Post, bearer = token,
                jsonBody = """{"endpoint":"https://web.push.apple.com/x"}""",
            ).status,
            "a key failure does not take the other push routes down",
        )
    }

    // --- subscribe / unsubscribe ------------------------------------------------------------------

    @Test
    fun subscribeWithABearerStoresTheRowAndUnsubscribeRemovesIt() = withPushServer { env ->
        val endpoint = "https://web.push.apple.com/12345"
        val resp = env.client.req(
            env.port, PUSH_SUBSCRIBE_PATH, HttpMethod.Post, bearer = token,
            jsonBody = """{"endpoint":"$endpoint","p256dh":"BPubKey","auth":"AuthSecret"}""",
        )
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(
            listOf(PushSubscription(endpoint, "BPubKey", "AuthSecret", fixedNow)),
            env.store.list(),
            "every field lands in the store, stamped with the injected clock",
        )

        val gone = env.client.req(
            env.port, PUSH_UNSUBSCRIBE_PATH, HttpMethod.Post, bearer = token,
            jsonBody = """{"endpoint":"$endpoint"}""",
        )
        assertEquals(HttpStatusCode.OK, gone.status)
        assertEquals(emptyList(), env.store.list(), "unsubscribe drops the row")
    }

    @Test
    fun subscribeWithASessionCookieAlsoSucceeds() = withPushServer { env ->
        // The path that actually matters: the browser has no Bearer, only the cookie the login flow set.
        val cookie = env.signIn()
        val origin = "http://127.0.0.1:${env.port}"
        val resp = env.client.req(
            env.port, PUSH_SUBSCRIBE_PATH, HttpMethod.Post, cookie = cookie, origin = origin,
            jsonBody = """{"endpoint":"https://fcm.googleapis.com/fcm/send/abc","p256dh":"k","auth":"a"}""",
        )
        assertEquals(HttpStatusCode.OK, resp.status, "the browser's cookie reaches the push routes")
        assertEquals(1, env.store.list().size)

        val gone = env.client.req(
            env.port, PUSH_UNSUBSCRIBE_PATH, HttpMethod.Post, cookie = cookie, origin = origin,
            jsonBody = """{"endpoint":"https://fcm.googleapis.com/fcm/send/abc"}""",
        )
        assertEquals(HttpStatusCode.OK, gone.status)
        assertEquals(emptyList(), env.store.list())
    }

    @Test
    fun unsubscribingAnUnknownEndpointIsStillOk() = withPushServer { env ->
        // Both callers (a toggle going off, a browser whose endpoint rotated) can name a row that is
        // already gone; the store's remove is idempotent, so the route must not invent a 404.
        val resp = env.client.req(
            env.port, PUSH_UNSUBSCRIBE_PATH, HttpMethod.Post, bearer = token,
            jsonBody = """{"endpoint":"https://web.push.apple.com/never-stored"}""",
        )
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(emptyList(), env.store.list())
    }

    @Test
    fun reSubscribingTheSameEndpointReplacesTheRow() = withPushServer { env ->
        val endpoint = "https://web.push.apple.com/same"
        repeat(2) { i ->
            env.client.req(
                env.port, PUSH_SUBSCRIBE_PATH, HttpMethod.Post, bearer = token,
                jsonBody = """{"endpoint":"$endpoint","p256dh":"k$i","auth":"a$i"}""",
            )
        }
        assertEquals(1, env.store.list().size, "endpoint is the identity — no duplicate device rows")
        assertEquals("k1", env.store.list().single().p256dh, "the latest keys win")
    }

    // --- refusals ----------------------------------------------------------------------------------

    @Test
    fun noCredentialIs401OnEveryPushRoute() = withPushServer { env ->
        assertEquals(
            HttpStatusCode.Unauthorized,
            env.client.req(env.port, PUSH_VAPID_KEY_PATH).status,
            "the key route is inside the gate",
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            env.client.req(
                env.port, PUSH_SUBSCRIBE_PATH, HttpMethod.Post, origin = "http://127.0.0.1:${env.port}",
                jsonBody = """{"endpoint":"https://web.push.apple.com/x","p256dh":"k","auth":"a"}""",
            ).status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            env.client.req(
                env.port, PUSH_UNSUBSCRIBE_PATH, HttpMethod.Post, origin = "http://127.0.0.1:${env.port}",
                jsonBody = """{"endpoint":"https://web.push.apple.com/x"}""",
            ).status,
        )
        assertEquals(emptyList(), env.store.list(), "a refused request wrote nothing")
    }

    @Test
    fun aPostWithoutAnOriginIsRefusedByTheExistingRule() = withPushServer { env ->
        // The cookie is ambient, so the Origin requirement on non-GET is what stops a third-party page
        // from registering ITS push endpoint against this daemon. No Origin → the gate refuses.
        val cookie = env.signIn()
        val resp = env.client.req(
            env.port, PUSH_SUBSCRIBE_PATH, HttpMethod.Post, cookie = cookie,
            jsonBody = """{"endpoint":"https://web.push.apple.com/x","p256dh":"k","auth":"a"}""",
        )
        assertTrue(resp.status.value in 400..499, "an Origin-less POST is refused; got ${resp.status}")
        assertEquals(emptyList(), env.store.list())
    }

    @Test
    fun aForeignOriginIsRefused() = withPushServer(publicUrl = publicUrl) { env ->
        val cookie = env.signIn()
        val resp = env.client.req(
            env.port, PUSH_SUBSCRIBE_PATH, HttpMethod.Post, cookie = cookie, origin = "https://evil.example.com",
            jsonBody = """{"endpoint":"https://web.push.apple.com/x","p256dh":"k","auth":"a"}""",
        )
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertEquals(emptyList(), env.store.list())
    }

    @Test
    fun aMalformedEndpointIs400AndStoresNothing() = withPushServer { env ->
        val bad = listOf(
            """{"endpoint":"","p256dh":"k","auth":"a"}""" to "blank endpoint",
            """{"endpoint":"/push/relative","p256dh":"k","auth":"a"}""" to "a relative path",
            """{"endpoint":"http://web.push.apple.com/x","p256dh":"k","auth":"a"}""" to "plain http",
            """{"endpoint":"https://","p256dh":"k","auth":"a"}""" to "no host",
            """{"endpoint":"https://#fragment","p256dh":"k","auth":"a"}""" to "a fragment without a host",
            """{"endpoint":"https://:443/x","p256dh":"k","auth":"a"}""" to "a port without a host",
            """{"endpoint":"https://host:/x","p256dh":"k","auth":"a"}""" to "an empty port",
            """{"endpoint":"https://host:abc/x","p256dh":"k","auth":"a"}""" to "a nonnumeric port",
            """{"endpoint":"https://host:65536/x","p256dh":"k","auth":"a"}""" to "an out-of-range port",
            """{"endpoint":"https://[2001:db8::1/x","p256dh":"k","auth":"a"}""" to "an unclosed IPv6 host",
            """{"endpoint":"https://2001:db8::1/x","p256dh":"k","auth":"a"}""" to "an unbracketed IPv6 host",
            """{"endpoint":"https://user@host/x","p256dh":"k","auth":"a"}""" to "userinfo",
            """{"endpoint":"https://a.example/x","p256dh":"","auth":"a"}""" to "blank p256dh",
            """{"endpoint":"https://a.example/x","p256dh":"k","auth":""}""" to "blank auth",
        )
        for ((body, why) in bad) {
            assertEquals(
                HttpStatusCode.BadRequest,
                env.client.req(env.port, PUSH_SUBSCRIBE_PATH, HttpMethod.Post, bearer = token, jsonBody = body).status,
                "$why is refused",
            )
        }
        assertEquals(emptyList(), env.store.list(), "no refused body reached the store")
    }

    @Test
    fun anUnparseableBodyIs400() = withPushServer { env ->
        assertEquals(
            HttpStatusCode.BadRequest,
            env.client.req(env.port, PUSH_SUBSCRIBE_PATH, HttpMethod.Post, bearer = token, jsonBody = "not json").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            env.client.req(env.port, PUSH_UNSUBSCRIBE_PATH, HttpMethod.Post, bearer = token, jsonBody = "{}").status,
            "unsubscribe needs its endpoint field",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            env.client.req(
                env.port, PUSH_UNSUBSCRIBE_PATH, HttpMethod.Post, bearer = token, jsonBody = """{"endpoint":"  "}""",
            ).status,
            "a blank endpoint names no row",
        )
        assertEquals(emptyList(), env.store.list())
    }

    // --- the pure validator -----------------------------------------------------------------------

    @Test
    fun theValidatorAcceptsRealPushServiceEndpointsAndRejectsInjection() {
        assertNull(
            validateSubscribeRequest(
                SubscribeRequest("https://web.push.apple.com/QK9k...long/path?x=1", "p", "a"),
            ),
            "a real Apple endpoint passes",
        )
        assertNull(
            validateSubscribeRequest(SubscribeRequest("https://fcm.googleapis.com/fcm/send/cXYZ", "p", "a")),
            "a real FCM endpoint passes",
        )
        assertTrue(
            validateSubscribeRequest(SubscribeRequest("https://a.example/x\r\nHost: evil", "p", "a")) != null,
            "a CRLF payload cannot be stored — the sender would put this on the wire unattended",
        )
        for (endpoint in listOf(
            "https://#fragment",
            "https://:443/x",
            "https://host:/x",
            "https://host:nonnumeric/x",
            "https://host:65536/x",
            "https://[2001:db8::1/x",
            "https://2001:db8::1/x",
            "https://user@host/x",
        )) {
            assertTrue(
                validateSubscribeRequest(SubscribeRequest(endpoint, "p", "a")) != null,
                "the route rejects the same malformed authority the sender would reject: $endpoint",
            )
        }
        assertTrue(
            validateSubscribeRequest(SubscribeRequest("https://a.example/" + "x".repeat(4000), "p", "a")) != null,
            "an absurdly long endpoint is refused rather than written to the database",
        )
        assertTrue(
            validateSubscribeRequest(
                SubscribeRequest("https://a.example/x", "k".repeat(600), "a"),
            ) != null,
            "an absurdly long key is refused too",
        )
    }

    // --- harness ----------------------------------------------------------------------------------

    /** Thread-safe in-memory [PushStore] — the handler thread writes, the test thread reads. */
    private class FakePushStore : PushStore {
        private val mutex = Mutex()
        private val rows = mutableMapOf<String, PushSubscription>()

        override suspend fun list(): List<PushSubscription> = mutex.withLock { rows.values.toList() }

        override suspend fun save(subscription: PushSubscription) = mutex.withLock {
            rows[subscription.endpoint] = subscription
        }

        override suspend fun remove(endpoint: String) = mutex.withLock { rows.remove(endpoint); Unit }
    }

    private inner class Env(val port: Int, val client: HttpClient, val store: FakePushStore) {
        /** Get a session cookie the way a browser does: mint a ticket on loopback, then exchange it. */
        suspend fun signIn(): String {
            val ticket = TRANSPORT_JSON.decodeFromString(
                TicketResponse.serializer(),
                client.req(port, AUTH_TICKET_PATH, HttpMethod.Post, bearer = token).bodyAsText(),
            ).ticket
            val exchanged = client.req(
                port, AUTH_EXCHANGE_PATH, HttpMethod.Post,
                origin = "http://127.0.0.1:$port", jsonBody = """{"ticket":"$ticket"}""",
            )
            return parseServerSetCookieHeader(exchanged.headers[HttpHeaders.SetCookie]!!).value
        }
    }

    private fun withPushServer(
        publicUrl: String? = null,
        vapidPublicKey: suspend () -> String = { vapidKey },
        block: suspend (Env) -> Unit,
    ) = runBlocking {
        withTimeout(30_000) {
            val tokens = TokenHolder(token)
            val store = FakePushStore()
            val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
                routing {
                    authRoutes(tokens, TicketStore(now = { fixedNow }), publicUrl, TRANSPORT_JSON, now = { fixedNow })
                    authenticated(tokens::current, publicUrl) {
                        pushRoutes(store, vapidPublicKey, TRANSPORT_JSON, now = { fixedNow })
                    }
                }
            }
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                block(Env(port, client, store))
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            }
        }
    }

    private suspend fun HttpClient.req(
        port: Int,
        path: String,
        method: HttpMethod = HttpMethod.Get,
        bearer: String? = null,
        cookie: String? = null,
        origin: String? = null,
        host: String? = null,
        jsonBody: String? = null,
    ): HttpResponse = request("http://127.0.0.1:$port$path") {
        this.method = method
        applyIfPresent(HttpHeaders.Authorization, bearer?.let { "Bearer $it" })
        applyIfPresent(HttpHeaders.Cookie, cookie?.let { "$SESSION_COOKIE_NAME=$it" })
        applyIfPresent(HttpHeaders.Origin, origin)
        applyIfPresent(HttpHeaders.Host, host)
        if (jsonBody != null) {
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
    }

    private fun HttpRequestBuilder.applyIfPresent(name: String, value: String?) {
        if (value != null) header(name, value)
    }
}
