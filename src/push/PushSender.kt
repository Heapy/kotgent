package io.kotgent.push

import io.kotgent.cli.eprintln
import io.kotgent.core.SessionId
import io.kotgent.crypto.base64Url
import io.kotgent.crypto.sha256
import kotlinx.coroutines.CancellationException

/**
 * Delivery of one payload-less Web Push message (RFC 8030) to every registered device.
 *
 * ## The whole request
 * A push here carries **no body**. RFC 8030 allows that, and it is what lets the daemon skip RFC 8291
 * payload encryption (ECDH P-256 + HKDF + AES-128-GCM, which on Kotlin/Native would mean hand-writing
 * 256-bit modular arithmetic — there is no `BigInteger`). So the entire message is a `POST` to the
 * subscription's endpoint with three headers:
 *
 * ```
 *   Authorization: vapid t=<ES256 JWT>, k=<base64url public point>
 *   TTL: 1800
 *   Topic: <short hash of the session id>
 * ```
 *
 * The service worker learns *which* session woke it by fetching `/sessions` with its cookie; nothing
 * about the session travels through Apple or Google. `Topic` is what lets the push service collapse
 * queued messages: a phone that was offline for an hour gets one banner per session, not one per update.
 *
 * ## Failure never propagates
 * This runs on the daemon's background scope, driven by [PushNotifier] collecting session updates. A push
 * that fails is a *lost notification*, never a reason to break the collector or the request that triggered
 * it — so every per-subscription failure is caught, reported once through [onError], and the remaining
 * subscriptions are still attempted. There is no retry queue on purpose: by the time a retry would fire the
 * session has usually moved on, and a queue would mean ringing a phone about an approval the operator has
 * already answered. [CancellationException] is the one exception that is re-thrown — swallowing it would
 * detach this work from the scope that owns it.
 *
 * ## Pruning
 * `404`/`410` from a push service is the browser telling us the subscription is permanently gone (the app
 * was uninstalled, the user revoked permission, the endpoint rotated). That row is deleted immediately —
 * it is the only garbage collection the subscription table has, since the browser cannot tell the daemon
 * it is gone. `429` and `5xx` are transient and keep the row.
 *
 * @param store the subscriptions to fan out to; also where a dead endpoint is pruned from.
 * @param publicKey the base64url VAPID application server key (`VapidKey::publicKeyBase64Url`), resolved
 *   per send because the first call may have to generate the keypair. Throwing degrades to "no push".
 * @param vapidToken the JWT for an endpoint — `VapidTokenCache::tokenFor` in production, so the per-origin
 *   cache means one openssl run per push service per 11 hours rather than one per message. A function
 *   reference rather than the cache itself, matching how every other seam here is injected
 *   (`VapidTokenCache(sign = signer::sign)`, `authenticated(tokens::current)`): it keeps the JWT format out
 *   of this class's tests entirely, so they assert which token went to which service, not how one is built.
 * @param transport the HTTP edge ([DarwinPushTransport] in production, a fake in tests). Nothing in the
 *   suite may make a real outbound call.
 * @param onError where a swallowed failure is reported; stderr in production, a collector in tests.
 */
class PushSender(
    private val store: PushStore,
    private val publicKey: suspend () -> String,
    private val vapidToken: suspend (String) -> String,
    private val transport: PushTransport,
    private val onError: (String) -> Unit = ::eprintln,
) {

    /**
     * Push "[sessionId] needs your attention" to every registered device.
     *
     * The VAPID key is resolved only when there is at least one subscription, so a daemon without push
     * never shells out to openssl at all.
     */
    suspend fun send(sessionId: SessionId) {
        val subscriptions = try {
            store.list()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError("push: cannot read the subscription list: ${e.describe()}")
            return
        }
        if (subscriptions.isEmpty()) return

        val key = try {
            publicKey()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError("push: no VAPID key, notifications are disabled: ${e.describe()}")
            return
        }

        val topic = pushTopic(sessionId)
        for (subscription in subscriptions) {
            deliver(subscription, key, topic, sessionId)
        }
    }

    /** One subscription: mint/reuse its token, POST, and act on the status. */
    private suspend fun deliver(
        subscription: PushSubscription,
        key: String,
        topic: String,
        sessionId: SessionId,
    ) {
        val jwt = try {
            vapidToken(subscription.endpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A bad endpoint or a missing openssl. Nothing is cached on failure, so the next send retries.
            onError("push: cannot build a VAPID token for ${subscription.endpoint}: ${e.describe()}")
            return
        }

        val status = try {
            transport.post(subscription.endpoint, pushRequestHeaders(jwt, key, topic))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError("push: ${subscription.endpoint} did not answer: ${e.describe()}")
            return
        }

        when {
            status in PUSH_SUCCESS_STATUSES -> Unit
            status in PUSH_GONE_STATUSES -> {
                prune(subscription.endpoint, status)
            }
            else -> {
                // 429 (rate limited) and 5xx (the service is having a bad day) are transient: keep the row
                // and drop this one message. The session id names what was lost, not what is wrong.
                onError("push: ${subscription.endpoint} returned HTTP $status for session ${sessionId.value}")
            }
        }
    }

    /** Drop a permanently dead endpoint; a failure to drop it is itself only worth a diagnostic. */
    private suspend fun prune(endpoint: String, status: Int) {
        try {
            store.remove(endpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError("push: cannot remove the dead subscription $endpoint: ${e.describe()}")
            return
        }
        onError("push: dropped subscription $endpoint (push service returned HTTP $status)")
    }
}

/**
 * The one-line form of [this] for a diagnostic — never empty, so a message is never left dangling.
 * `internal` rather than file-private because [PushNotifier] reports its own swallowed failures the same
 * way; one rule for the whole package beats two copies that drift.
 */
internal fun Throwable.describe(): String = message ?: this::class.simpleName ?: "unknown failure"

/**
 * The HTTP edge of the push sender: `POST <url>` with [headers] and an empty body, answering with the
 * status code.
 *
 * An interface for the usual reason — [DarwinPushTransport] is the only thing in `src/push/` that opens a
 * socket, and the suite must never make a real outbound call, so [PushSender]'s whole policy (which status
 * prunes a row, which is transient, what the header set looks like) is tested against a fake. The status
 * code is the entire return value on purpose: a push service's response body carries nothing the daemon
 * acts on.
 */
interface PushTransport {
    /**
     * POST an empty body to [url] with [headers], returning the HTTP status.
     *
     * @throws Exception for any transport-level failure (DNS, TLS, timeout). [PushSender] catches it —
     *   implementations must NOT translate a failure into a fake status code.
     */
    suspend fun post(url: String, headers: Map<String, String>): Int
}

/**
 * How long a push service may hold an undelivered message: 30 minutes.
 *
 * Long enough to reach a phone that is briefly out of signal, short enough that a banner about an approval
 * cannot arrive so late that it is about something the operator already answered from the desktop.
 */
const val PUSH_TTL_SECONDS: Int = 1800

/** Statuses that mean the push service took the message (`201` in practice; `200` from some services). */
val PUSH_SUCCESS_STATUSES: IntRange = 200..299

/** Statuses that mean the subscription is permanently gone and its row must be deleted (RFC 8030 §7.3). */
val PUSH_GONE_STATUSES: Set<Int> = setOf(404, 410)

/**
 * Characters of the SHA-256 digest that make up a `Topic`. RFC 8030 §5.4 caps a topic at 32 characters
 * from the URL-safe base64 alphabet; 16 is 96 bits of digest, which is far past any collision that would
 * matter between a handful of sessions.
 */
const val PUSH_TOPIC_LENGTH: Int = 16

/**
 * The `Topic` header value for [sessionId]: a short base64url SHA-256 digest.
 *
 * Hashed rather than sent verbatim for two reasons. A session id is kotgent's own identifier and there is
 * no reason for Apple or Google to learn it — the point of a payload-less push is that the push service
 * learns nothing but "this daemon wants to wake that device". And a `Topic` is syntactically restricted to
 * the URL-safe base64 alphabet, which an arbitrary id is not guaranteed to satisfy.
 */
fun pushTopic(sessionId: SessionId): String =
    base64Url(sha256(sessionId.value.encodeToByteArray())).take(PUSH_TOPIC_LENGTH)

/**
 * The exact header set of a payload-less push, as a pure function so the wire format is testable without a
 * transport.
 *
 * Nothing else is added: `Content-Length: 0` is the transport's job (Ktor refuses to let a caller set it),
 * and an `Urgency` would only override the push service's default for a notification that is already
 * user-visible by definition.
 */
fun pushRequestHeaders(
    jwt: String,
    publicKey: String,
    topic: String,
    ttlSeconds: Int = PUSH_TTL_SECONDS,
): Map<String, String> =
    mapOf(
        PUSH_AUTHORIZATION_HEADER to vapidAuthorizationHeader(jwt, publicKey),
        PUSH_TTL_HEADER to ttlSeconds.toString(),
        PUSH_TOPIC_HEADER to topic,
    )

/** Carries the VAPID credential (RFC 8292 §3). */
const val PUSH_AUTHORIZATION_HEADER: String = "Authorization"

/** How long the push service may store an undelivered message (RFC 8030 §5.2). Required — not optional. */
const val PUSH_TTL_HEADER: String = "TTL"

/** Lets the push service replace a queued message with the same topic (RFC 8030 §5.4). */
const val PUSH_TOPIC_HEADER: String = "Topic"
