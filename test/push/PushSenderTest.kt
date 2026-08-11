package io.kotgent.push

import io.kotgent.core.SessionId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PushSenderTest {

    private val apple = "https://web.push.apple.com/device-a"
    private val google = "https://fcm.googleapis.com/fcm/send/device-b"
    private val publicKey = "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U"
    private val session = SessionId("sess-alpha")

    private val sessionTopic = "Cc0mtfSec0nSHnMY"

    private fun sub(endpoint: String, createdAt: Long = 1_000L) = PushSubscription(
        endpoint = endpoint,
        p256dh = "p256dh-$endpoint",
        auth = "auth-$endpoint",
        createdAt = createdAt,
    )


    @Test
    fun aSuccessfulSendReachesEverySubscriptionAndKeepsEveryRow() = runBlocking {
        withTimeout(20_000) {
            val env = Env(statuses = mapOf(apple to 201, google to 200))
            env.store.seed(sub(apple), sub(google))

            env.sender.send(session)
            assertEquals(listOf(apple, google), env.transport.urls(), "one POST per subscription, in store order")
            assertEquals(listOf(apple, google), env.store.endpoints(), "a 2xx is not a reason to touch the table")
            assertTrue(env.errors.isEmpty(), "a clean fan-out reports nothing: ${env.errors}")
        }
    }

    @Test
    fun noSubscriptionsMeansNoRequestAndNoVapidKeyResolution() = runBlocking {
        withTimeout(20_000) {
            var keyCalls = 0
            val env = Env(publicKey = { keyCalls++; publicKey })

            env.sender.send(session)
            assertTrue(env.transport.calls.isEmpty(), "an empty table must not produce a request")
            assertEquals(0, keyCalls, "the VAPID key is resolved only when there is somewhere to send")
            assertTrue(env.errors.isEmpty(), "silence, not an error: ${env.errors}")
        }
    }


    @Test
    fun theOutgoingHeadersAreExactlyWhatRfc8030AndRfc8292Need() = runBlocking {
        withTimeout(20_000) {
            val env = Env(statuses = mapOf(apple to 201))
            env.store.seed(sub(apple))
            env.sender.send(session)

            val headers = env.transport.calls.single().headers
            assertEquals(
                setOf("Authorization", "TTL", "Topic"),
                headers.keys,
                "nothing else goes on a payload-less push (Content-Length is the transport's job)",
            )
            assertEquals(
                "vapid t=jwt-for-$apple, k=$publicKey",
                headers["Authorization"],
                "the RFC 8292 credential: this endpoint's token plus the application server key",
            )
            assertEquals("1800", headers["TTL"], "TTL is required by RFC 8030 §5.2 and is 30 minutes")
            assertEquals(sessionTopic, headers["Topic"], "Topic is the short digest of the session id")
        }
    }

    @Test
    fun theTopicIsAShortUrlSafeDigestThatDiffersPerSession() = runBlocking {
        withTimeout(20_000) {
            assertEquals(sessionTopic, pushTopic(session), "pinned against an independent sha256/base64url")
            assertEquals(PUSH_TOPIC_LENGTH, pushTopic(session).length, "far under the RFC 8030 §5.4 cap of 32")
            assertNotEquals(
                pushTopic(session),
                pushTopic(SessionId("sess-beta")),
                "two sessions must not collapse onto one queued message",
            )
            assertTrue(
                pushTopic(SessionId("a session id/with?characters a topic may not carry"))
                    .all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' },
                "a Topic must be URL-safe base64 whatever the session id looks like",
            )
        }
    }

    @Test
    fun everyEndpointGetsItsOwnTokenAndTheKeyIsResolvedOnce() = runBlocking {
        withTimeout(20_000) {
            var keyCalls = 0
            val env = Env(
                statuses = mapOf(apple to 201, google to 201),
                publicKey = { keyCalls++; publicKey },
            )
            env.store.seed(sub(apple), sub(google))
            env.sender.send(session)

            assertEquals(
                listOf(apple, google),
                env.tokenRequests,
                "the token is resolved per endpoint — a token names its audience, and Apple's is " +
                    "worthless at Google (the production cache collapses same-origin endpoints itself)",
            )
            assertEquals(
                listOf("vapid t=jwt-for-$apple, k=$publicKey", "vapid t=jwt-for-$google, k=$publicKey"),
                env.transport.calls.map { it.headers.getValue("Authorization") },
                "each request carries its own service's token",
            )
            assertEquals(1, keyCalls, "the application server key is the same for every service")
        }
    }


    @Test
    fun aGoneSubscriptionIsPrunedAndTheRestStillGetTheirMessage() = runBlocking {
        withTimeout(20_000) {
            val env = Env(statuses = mapOf(apple to 410, google to 201))
            env.store.seed(sub(apple), sub(google))

            env.sender.send(session)
            assertEquals(listOf(apple, google), env.transport.urls(), "the dead one is discovered by trying it")
            assertEquals(
                listOf(google),
                env.store.endpoints(),
                "410 Gone is the browser saying the subscription is permanently over — drop the row",
            )
            assertTrue(env.errors.any { apple in it }, "the drop is reported once: ${env.errors}")
        }
    }

    @Test
    fun aNotFoundSubscriptionIsPrunedToo() = runBlocking {
        withTimeout(20_000) {
            val env = Env(statuses = mapOf(apple to 404))
            env.store.seed(sub(apple))

            env.sender.send(session)
            assertTrue(env.store.endpoints().isEmpty(), "404 is the other permanent answer (RFC 8030 §7.3)")
        }
    }

    @Test
    fun aFailedPruneIsReportedAndDoesNotAbortTheFanOut() = runBlocking {
        withTimeout(20_000) {
            val store = FakePushStore(failRemoveFor = setOf(apple))
            val env = Env(statuses = mapOf(apple to 410, google to 201), store = store)
            env.store.seed(sub(apple), sub(google))

            env.sender.send(session)

            assertEquals(
                listOf(apple, google),
                env.transport.urls(),
                "a database failure pruning the first endpoint does not deprive the healthy device",
            )
            assertEquals(
                listOf(apple, google),
                env.store.endpoints(),
                "the failed removal leaves the dead row in place for a later retry",
            )
            assertTrue(
                env.errors.any { "cannot remove the dead subscription $apple" in it },
                "the failed cleanup is diagnosed: ${env.errors}",
            )
        }
    }

    @Test
    fun aRateLimitOrServerErrorKeepsTheRowAndIsNotRetried() = runBlocking {
        withTimeout(20_000) {
            val env = Env(statuses = mapOf(apple to 429, google to 503))
            env.store.seed(sub(apple), sub(google))

            env.sender.send(session)
            assertEquals(
                listOf(apple, google),
                env.store.endpoints(),
                "429/5xx are transient — the device is fine, the message is lost",
            )
            assertEquals(2, env.transport.calls.size, "exactly one attempt each: there is no retry queue")
            assertEquals(2, env.errors.size, "each transient failure is reported once: ${env.errors}")
        }
    }


    @Test
    fun aThrowingTransportIsSwallowedAndTheOtherSubscriptionsAreStillAttempted() = runBlocking {
        withTimeout(20_000) {
            val env = Env(statuses = mapOf(google to 201), throwFor = setOf(apple))
            env.store.seed(sub(apple), sub(google))

            env.sender.send(session)
            assertEquals(listOf(apple, google), env.transport.urls(), "the failure did not abort the fan-out")
            assertEquals(listOf(apple, google), env.store.endpoints(), "a transport failure proves nothing")
            assertTrue(env.errors.any { apple in it }, "the failure is reported, not hidden: ${env.errors}")
        }
    }

    @Test
    fun aFailingVapidKeyDisablesThisSendWithoutTouchingAnything() = runBlocking {
        withTimeout(20_000) {
            val env = Env(publicKey = { throw VapidKeyException("no openssl at /usr/bin/openssl") })
            env.store.seed(sub(apple))

            env.sender.send(session)
            assertTrue(env.transport.calls.isEmpty(), "nothing is sent unsigned")
            assertEquals(listOf(apple), env.store.endpoints(), "the device is fine — the daemon is not")
            assertTrue(
                env.errors.any { "no openssl" in it },
                "the openssl diagnostic reaches the operator: ${env.errors}",
            )
        }
    }

    @Test
    fun aFailingTokenSkipsThatServiceAndKeepsGoing() = runBlocking {
        withTimeout(20_000) {
            val env = Env(
                statuses = mapOf(apple to 201, google to 201),
                failTokenFor = setOf(google),
            )
            env.store.seed(sub(apple), sub(google))

            env.sender.send(session)
            assertEquals(listOf(apple), env.transport.urls(), "the unsignable service is skipped, not faked")
            assertEquals(listOf(apple, google), env.store.endpoints(), "a signing fault is ours, not the device's")
            assertTrue(env.errors.any { google in it }, "the signing failure is reported: ${env.errors}")
        }
    }

    @Test
    fun anUnreadableStoreDegradesToSilence() = runBlocking {
        withTimeout(20_000) {
            val env = Env(store = FakePushStore(failList = true))

            env.sender.send(session)
            assertTrue(env.transport.calls.isEmpty())
            assertTrue(env.errors.single().contains("subscription list"), env.errors.toString())
        }
    }


    private data class Call(val url: String, val headers: Map<String, String>)

    private class FakeTransport(
        private val statuses: Map<String, Int>,
        private val throwFor: Set<String>,
    ) : PushTransport {
        val calls = mutableListOf<Call>()

        override suspend fun post(url: String, headers: Map<String, String>): Int {
            calls += Call(url, headers)
            if (url in throwFor) throw RuntimeException("connection reset by peer")
            return statuses[url] ?: error("FakeTransport has no status configured for $url")
        }

        fun urls(): List<String> = calls.map { it.url }
    }

    private class FakePushStore(
        private val failList: Boolean = false,
        private val failRemoveFor: Set<String> = emptySet(),
    ) : PushStore {
        private val rows = mutableMapOf<String, PushSubscription>()

        fun seed(vararg subscriptions: PushSubscription) {
            for (s in subscriptions) rows[s.endpoint] = s
        }

        fun endpoints(): List<String> = rows.keys.toList()

        override suspend fun list(): List<PushSubscription> {
            if (failList) throw IllegalStateException("the subscription list cannot be read: database is locked")
            return rows.values.toList()
        }

        override suspend fun save(subscription: PushSubscription) {
            rows[subscription.endpoint] = subscription
        }

        override suspend fun remove(endpoint: String) {
            if (endpoint in failRemoveFor) {
                throw IllegalStateException("the subscription table is locked")
            }
            rows.remove(endpoint)
        }
    }

    private inner class Env(
        statuses: Map<String, Int> = emptyMap(),
        throwFor: Set<String> = emptySet(),
        val store: FakePushStore = FakePushStore(),
        publicKey: suspend () -> String = { this@PushSenderTest.publicKey },
        failTokenFor: Set<String> = emptySet(),
    ) {
        val transport = FakeTransport(statuses, throwFor)
        val errors = mutableListOf<String>()

        val tokenRequests = mutableListOf<String>()

        val sender = PushSender(
            store = store,
            publicKey = publicKey,
            vapidToken = { endpoint ->
                tokenRequests += endpoint
                if (endpoint in failTokenFor) {
                    throw VapidSignerException("$endpoint: /usr/bin/openssl dgst exited 1")
                }
                "jwt-for-$endpoint"
            },
            transport = transport,
            onError = { errors += it },
        )
    }
}
