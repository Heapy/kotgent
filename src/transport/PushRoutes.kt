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


const val PUSH_VAPID_KEY_PATH: String = "/push/vapid-key"

const val PUSH_SUBSCRIBE_PATH: String = "/push/subscribe"

const val PUSH_UNSUBSCRIBE_PATH: String = "/push/unsubscribe"

const val PUSH_ENDPOINT_MAX_LENGTH: Int = 2048

const val PUSH_KEY_MAX_LENGTH: Int = 512

@Serializable
data class VapidKeyResponse(val key: String)

@Serializable
data class SubscribeRequest(val endpoint: String, val p256dh: String, val auth: String)

@Serializable
data class UnsubscribeRequest(val endpoint: String)

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
        val endpoint = req.endpoint.trim()
        if (endpoint.isEmpty()) {
            call.respondText("endpoint must not be blank", status = HttpStatusCode.BadRequest)
            return@post
        }
        store.remove(endpoint)
        call.respondText("ok")
    }
}

fun validateSubscribeRequest(req: SubscribeRequest): String? {
    // The daemon later POSTs here unattended; reject non-HTTPS, userinfo, and header/path ambiguity now.
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

private fun pushEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
