package io.kotgent.push

import io.kotgent.cli.eprintln
import io.kotgent.core.SessionId
import io.kotgent.crypto.base64Url
import io.kotgent.crypto.sha256
import kotlinx.coroutines.CancellationException

/**
 * Sends payload-less RFC 8030 messages. The service worker fetches current session state, keeping session
 * data out of Apple/Google; per-session topics collapse queued wakes. Failures do not stop other devices
 * and are never retried as stale approvals may already be resolved. Permanent 404/410 endpoints are
 * pruned, while transient failures retain their subscription.
 */
class PushSender(
    private val store: PushStore,
    private val publicKey: suspend () -> String,
    private val vapidToken: suspend (String) -> String,
    private val transport: PushTransport,
    private val onError: (String) -> Unit = ::eprintln,
) {

    /** Resolves the VAPID key only when at least one subscription exists. */
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
                // Transient failures retain the subscription and drop only this stale-prone message.
                onError("push: ${subscription.endpoint} returned HTTP $status for session ${sessionId.value}")
            }
        }
    }

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

/** Non-empty one-line failure text shared by the package's swallowed-error paths. */
internal fun Throwable.describe(): String = message ?: this::class.simpleName ?: "unknown failure"

interface PushTransport {
    /**
     * Transport failures must throw rather than masquerade as HTTP statuses.
     */
    suspend fun post(url: String, headers: Map<String, String>): Int
}

/** Bounds delivery delay so an old approval does not arrive after it was resolved elsewhere. */
const val PUSH_TTL_SECONDS: Int = 1800

val PUSH_SUCCESS_STATUSES: IntRange = 200..299

val PUSH_GONE_STATUSES: Set<Int> = setOf(404, 410)

/** 96 digest bits fit RFC 8030's 32-character topic bound. */
const val PUSH_TOPIC_LENGTH: Int = 16

/** Hashing hides the session id and satisfies Topic's URL-safe alphabet. */
fun pushTopic(sessionId: SessionId): String =
    base64Url(sha256(sessionId.value.encodeToByteArray())).take(PUSH_TOPIC_LENGTH)

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

const val PUSH_AUTHORIZATION_HEADER: String = "Authorization"

const val PUSH_TTL_HEADER: String = "TTL"

const val PUSH_TOPIC_HEADER: String = "Topic"
