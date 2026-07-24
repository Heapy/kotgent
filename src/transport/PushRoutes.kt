package io.kotgent.transport

import io.kotgent.push.PushStore
import io.kotgent.push.PushSubscription
import io.kotgent.push.VapidJwtException
import io.kotgent.push.pushServiceOrigin
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * The Web Push registration surface: the three routes a browser needs to become — and stop being — a
 * push target.
 *
 * ```
 *   GET  /push/vapid-key    → {"key": "<base64url 65-byte P-256 point>"}   (applicationServerKey)
 *   POST /push/subscribe    ← PushSubscription.toJSON()'s endpoint + keys  → the row is stored
 *   POST /push/unsubscribe  ← {"endpoint": …}                              → the row is dropped
 * ```
 *
 * ## Why the KEY route is the generation trigger
 * `pushManager.subscribe()` needs `applicationServerKey` BEFORE it can produce a subscription, so the
 * browser always fetches the key first. That makes `GET /push/vapid-key` — not the subscribe — the point
 * at which the daemon must have a VAPID keypair, which is why [vapidPublicKey] is a `suspend` provider
 * rather than a value: it may have to shell out to `openssl` and persist `~/.kotgent/vapid.pem` on the
 * very first call. A provider that fails (no `openssl`, an unwritable `~/.kotgent`) answers `503` and the
 * page simply cannot enable push — the daemon itself stays healthy.
 *
 * ## Auth
 * Mounted by [KotgentServer] INSIDE [authenticated], so every route here takes the one authorization rule:
 * a `Bearer` master token or the browser's session cookie, plus the `Host` allowlist and the `Origin`
 * requirement on the two POSTs. Nothing extra is added locally — a push subscription is ordinary
 * browser-scoped state, not a credential-minting surface like `/auth/ticket`, so it is deliberately NOT
 * [loopbackOnly]: subscribing from the phone through the tunnel is the entire point of the feature.
 *
 * Validation is strict on the way in ([validateSubscribeRequest]) because an `endpoint` is a URL the
 * daemon will later POST to unattended: anything that is not an absolute `https://` URL is refused with a
 * `400` rather than stored and discovered at send time.
 */

/** Where the page reads the VAPID public key it must pass as `applicationServerKey`. */
const val PUSH_VAPID_KEY_PATH: String = "/push/vapid-key"

/** Where the page registers a `PushSubscription` it just obtained from the browser's push service. */
const val PUSH_SUBSCRIBE_PATH: String = "/push/subscribe"

/** Where the page drops a subscription (the notifications toggle going off, or a stale endpoint). */
const val PUSH_UNSUBSCRIBE_PATH: String = "/push/unsubscribe"

/**
 * Longest `endpoint` accepted. Real push-service URLs are ~200 characters (Apple, FCM, Mozilla); the cap
 * exists so an authenticated-but-buggy client cannot write unbounded rows into the daemon's database.
 */
const val PUSH_ENDPOINT_MAX_LENGTH: Int = 2048

/** Longest client key accepted — `p256dh` is 65 bytes base64url (88 chars), `auth` 16 bytes (22). */
const val PUSH_KEY_MAX_LENGTH: Int = 512

/** The body of `GET /push/vapid-key`: the base64url uncompressed P-256 point, verbatim. */
@Serializable
data class VapidKeyResponse(val key: String)

/**
 * The body of `POST /push/subscribe` — the three fields of the browser's `PushSubscription.toJSON()`
 * flattened ([p256dh] and [auth] come out of its `keys` object). They are stored but unread today; see
 * [PushSubscription].
 */
@Serializable
data class SubscribeRequest(val endpoint: String, val p256dh: String, val auth: String)

/** The body of `POST /push/unsubscribe` — the endpoint identifying the row to drop. */
@Serializable
data class UnsubscribeRequest(val endpoint: String)

/**
 * Mount the three `/push` routes on [this] route.
 *
 * @param store where subscriptions live; `endpoint` is their identity, so a re-subscribe replaces a row.
 * @param vapidPublicKey the base64url application server key, resolved per request (it may be generated
 *   on the first call). Throwing is a supported outcome — it becomes a `503`, never a daemon failure.
 * @param now epoch millis stamped onto a stored subscription, injected so tests are deterministic.
 */
fun Route.pushRoutes(
    store: PushStore,
    vapidPublicKey: suspend () -> String,
    json: Json = TRANSPORT_JSON,
    now: () -> Long = ::pushEpochMillis,
) {
    get(PUSH_VAPID_KEY_PATH) {
        val key = try {
            vapidPublicKey()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // No key means no push, and that is a configuration/environment fault the operator can read
            // off this response (a missing `openssl`, an unwritable ~/.kotgent). The rest of the daemon is
            // unaffected, so this is a 503 on one route rather than a 500 that looks like a crash.
            call.respondText(
                "push is unavailable: ${e.message ?: e::class.simpleName ?: "the VAPID key could not be resolved"}",
                status = HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }
        call.respondText(
            json.encodeToString(VapidKeyResponse.serializer(), VapidKeyResponse(key)),
            ContentType.Application.Json,
        )
    }

    post(PUSH_SUBSCRIBE_PATH) {
        val req = try {
            json.decodeFromString(SubscribeRequest.serializer(), call.receiveText())
        } catch (_: SerializationException) {
            call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }
        val problem = validateSubscribeRequest(req)
        if (problem != null) {
            call.respondText(problem, status = HttpStatusCode.BadRequest)
            return@post
        }
        store.save(
            PushSubscription(
                endpoint = req.endpoint.trim(),
                p256dh = req.p256dh.trim(),
                auth = req.auth.trim(),
                createdAt = now(),
            ),
        )
        call.respondText("ok")
    }

    post(PUSH_UNSUBSCRIBE_PATH) {
        val req = try {
            json.decodeFromString(UnsubscribeRequest.serializer(), call.receiveText())
        } catch (_: SerializationException) {
            call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }
        // Deliberately NOT validated the way subscribe is, and deliberately not a 404 when the endpoint is
        // unknown: unsubscribe is the cleanup path, and both callers (a toggle going off, a browser that
        // rotated its endpoint) can legitimately name a row that is already gone. [PushStore.remove] is
        // idempotent, so the honest answer is always "ok".
        val endpoint = req.endpoint.trim()
        if (endpoint.isEmpty()) {
            call.respondText("endpoint must not be blank", status = HttpStatusCode.BadRequest)
            return@post
        }
        store.remove(endpoint)
        call.respondText("ok")
    }
}

/**
 * Why [req] may not be stored, or `null` if it may — the pure half of `POST /push/subscribe`, public so the
 * rule is unit-testable without a server.
 *
 * The endpoint must be an ABSOLUTE `https://` URL with a non-empty host and no whitespace or control
 * characters: the daemon later POSTs to this value unattended from a background coroutine, so a relative
 * path, an `http://` URL (no push service uses one) or a header-splitting payload has to be refused at the
 * door rather than discovered by the sender. The keys are only length-checked — they are opaque base64url
 * blobs that nothing in the payload-less path decodes.
 */
fun validateSubscribeRequest(req: SubscribeRequest): String? {
    val endpoint = req.endpoint.trim()
    if (endpoint.isEmpty()) return "endpoint must not be blank"
    if (endpoint.length > PUSH_ENDPOINT_MAX_LENGTH) return "endpoint is too long"
    if (endpoint.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
        return "endpoint must not contain whitespace or control characters"
    }
    try {
        pushServiceOrigin(endpoint)
    } catch (_: VapidJwtException) {
        return "endpoint must be an absolute https:// URL with a valid host, port, and no userinfo"
    }
    if (req.p256dh.isBlank() || req.auth.isBlank()) return "p256dh and auth must not be blank"
    if (req.p256dh.length > PUSH_KEY_MAX_LENGTH || req.auth.length > PUSH_KEY_MAX_LENGTH) {
        return "p256dh and auth are too long"
    }
    return null
}

/** Wall clock in epoch millis — the production [pushRoutes] `now` (`getTimeMillis` is a hard error). */
private fun pushEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
