package io.kotgent.transport

import io.kotgent.core.PaneId
import io.kotgent.core.SessionId
import io.kotgent.mutex.Holding
import io.kotgent.mutex.MutexKey
import io.kotgent.mutex.MutexToken
import io.kotgent.mutex.WaitTicket
import io.kotgent.store.EventStore
import io.kotgent.store.MutexAcquireResult
import io.kotgent.store.MutexListing
import io.kotgent.store.MutexReleaseResult
import io.kotgent.store.MutexStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.http.HttpRequestCloseHandlerKey
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ConnectionClosedException
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

const val MUTEX_BODY_MAX_BYTES: Int = 4 * 1024

const val MUTEX_WAIT_DEFAULT_SECONDS: Int = 90

const val MUTEX_WAIT_MAX_SECONDS: Int = 540

class MutexRouting(
    val mutexes: MutexStore,
    val sessions: EventStore,
    val paneLookup: suspend (PaneId) -> SessionId?,
    val json: Json = TRANSPORT_JSON,
    val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
)

@Serializable
data class AcquireMutexRequest(val sessionId: String? = null)

@Serializable
data class ReleaseMutexRequest(val token: String, val sessionId: String? = null)

@Serializable
data class MutexAcquireResponse(
    val status: String,
    val key: String,
    val token: String? = null,
    val acquiredAt: Long? = null,
    val ticket: String? = null,
    val position: Int? = null,
)

@Serializable
data class MutexReleaseResponse(val released: Boolean, val key: String? = null)

@Serializable
data class MutexHolderDto(val sessionId: String, val acquiredAt: Long)

@Serializable
data class MutexWaiterDto(val sessionId: String, val since: Long, val granted: Boolean)

@Serializable
data class MutexDto(val key: String, val holder: MutexHolderDto?, val waiters: List<MutexWaiterDto>)

@Serializable
data class MutexListingDto(val rev: Long, val mutexes: List<MutexDto>, val serverNow: Long)

/** Tokens and tickets stay out of listings: only the acquiring call learns them. */
fun MutexListing.toDto(serverNow: Long): MutexListingDto = MutexListingDto(
    rev = rev,
    mutexes = entries.map { entry ->
        MutexDto(
            key = entry.key.value,
            holder = entry.holding?.let { MutexHolderDto(it.sessionId.value, it.acquiredAt) },
            waiters = entry.waiters.map { MutexWaiterDto(it.sessionId.value, it.enqueuedAt, it.granted) },
        )
    },
    serverNow = serverNow,
)

/**
 * CIO keeps a finished request's handler running after its peer hangs up. A long-poll must see the hang-up
 * instead: a grant claimed for a caller that is already gone would hold the mutex until its session ends.
 * Ktor's HttpRequestLifecycle plugin would also cancel `Connection: close` requests, whose pipeline reports
 * the close as soon as their body is read, so this installs the same close handler for keep-alive calls only.
 */
@OptIn(InternalAPI::class)
private val CancelOnDisconnect = createRouteScopedPlugin("KotgentCancelOnDisconnect") {
    on(CallSetup) { call ->
        val closing = call.request.headers.getAll(HttpHeaders.Connection).orEmpty()
            .any { header -> header.split(',').any { it.trim().equals("close", ignoreCase = true) } }
        if (closing || call.request.local.version == "HTTP/1.0") return@on
        if (call.attributes.contains(HttpRequestCloseHandlerKey)) return@on
        call.attributes.put(HttpRequestCloseHandlerKey) {
            call.coroutineContext.cancel(CancellationException("the client disconnected", ConnectionClosedException()))
        }
    }
}

fun Route.mutexRoutes(routing: MutexRouting) {
    route("/mutexes") {
        val _ = install(CancelOnDisconnect)

        get {
            if (callerOrAbsent(routing, null) == null) return@get
            respondJson(routing, MutexListingDto.serializer(), routing.mutexes.listing.value.toDto(routing.now()))
        }

        post("/release") {
            val req = decodeBoundedBody(routing, ReleaseMutexRequest.serializer()) ?: return@post
            val token = MutexToken.parseOrNull(req.token) ?: run {
                call.respondText("malformed mutex token '${req.token}'", status = HttpStatusCode.BadRequest)
                return@post
            }
            val session = requireLiveCaller(routing, req.sessionId) ?: return@post
            when (val result = routing.mutexes.release(token, session)) {
                is MutexReleaseResult.Released ->
                    respondRelease(routing, MutexReleaseResponse(true, result.holding.key.value))
                MutexReleaseResult.NotHeld -> respondRelease(routing, MutexReleaseResponse(false))
                is MutexReleaseResult.HeldByAnotherSession -> call.respondText(
                    "mutex '${result.holding.key.value}' is held by session '${result.holding.sessionId.value}', " +
                        "not '${session.value}'",
                    status = HttpStatusCode.Conflict,
                )
            }
        }

        post("/{key}/acquire") {
            val key = keyParam() ?: return@post
            val wait = waitParam() ?: return@post
            val ticketRaw = call.request.queryParameters["ticket"]
            val ticket = ticketRaw?.let {
                WaitTicket.parseOrNull(it) ?: run {
                    call.respondText("malformed wait ticket '$it'", status = HttpStatusCode.BadRequest)
                    return@post
                }
            }
            val req = decodeBoundedBody(routing, AcquireMutexRequest.serializer()) ?: return@post
            val session = requireLiveCaller(routing, req.sessionId) ?: return@post
            val response = when (val result = routing.mutexes.acquire(key, session, ticket, wait.seconds)) {
                is MutexAcquireResult.Acquired -> result.holding.toAcquired()
                is MutexAcquireResult.Pending ->
                    MutexAcquireResponse("pending", key.value, ticket = result.ticket.value, position = result.position)
                MutexAcquireResult.UnknownTicket -> {
                    call.respondText(
                        "wait ticket '${ticketRaw.orEmpty()}' is no longer queued for '${key.value}' — its lease " +
                            "ran out or its session ended; acquire again without --ticket",
                        status = HttpStatusCode.Gone,
                    )
                    return@post
                }
                MutexAcquireResult.ForeignTicket -> {
                    call.respondText(
                        "wait ticket '${ticketRaw.orEmpty()}' belongs to another session or mutex",
                        status = HttpStatusCode.Conflict,
                    )
                    return@post
                }
            }
            respondJson(routing, MutexAcquireResponse.serializer(), response)
        }

        post("/{key}/force-release") {
            val key = keyParam() ?: return@post
            val _ = receiveBoundedText(MUTEX_BODY_MAX_BYTES) ?: return@post
            when (val caller = resolveCallerIdentity(routing.paneLookup, null)) {
                CallerIdentity.Absent -> Unit
                is CallerIdentity.Rejected -> {
                    call.respondText(caller.reason, status = HttpStatusCode.BadRequest)
                    return@post
                }
                is CallerIdentity.Resolved -> {
                    call.respondText(
                        "force-release is an operator action; session '${caller.id.value}' releases its own " +
                            "holdings with its token",
                        status = HttpStatusCode.Forbidden,
                    )
                    return@post
                }
            }
            val released = routing.mutexes.forceRelease(key) ?: run {
                call.respondText("mutex '${key.value}' is not held", status = HttpStatusCode.NotFound)
                return@post
            }
            respondRelease(routing, MutexReleaseResponse(true, released.key.value))
        }
    }
}

private fun Holding.toAcquired() =
    MutexAcquireResponse("acquired", key.value, token = token.value, acquiredAt = acquiredAt)

private suspend fun RoutingContext.keyParam(): MutexKey? {
    val raw = call.parameters["key"].orEmpty()
    return MutexKey.parseOrNull(raw) ?: run {
        call.respondText(
            "malformed mutex key '$raw' — 1-${MutexKey.MAX_LENGTH} characters: a letter or digit, then " +
                "letters, digits, '.', '_' or '-'",
            status = HttpStatusCode.BadRequest,
        )
        null
    }
}

private suspend fun RoutingContext.waitParam(): Int? {
    val raw = call.request.queryParameters["wait"] ?: return MUTEX_WAIT_DEFAULT_SECONDS
    return raw.toIntOrNull()?.takeIf { it in 0..MUTEX_WAIT_MAX_SECONDS } ?: run {
        call.respondText(
            "wait must be a whole number of seconds from 0 to $MUTEX_WAIT_MAX_SECONDS, got '$raw'",
            status = HttpStatusCode.BadRequest,
        )
        null
    }
}

/** Only a live kotgent session may hold or wait: its end is what releases the mutex. */
private suspend fun RoutingContext.requireLiveCaller(routing: MutexRouting, explicitSessionId: String?): SessionId? {
    val id = when (val caller = resolveCallerIdentity(routing.paneLookup, explicitSessionId)) {
        CallerIdentity.Absent -> {
            call.respondText(
                "no calling session: mutexes belong to kotgent sessions — run inside a kotgent pane or name " +
                    "one with --session",
                status = HttpStatusCode.BadRequest,
            )
            return null
        }
        is CallerIdentity.Rejected -> {
            call.respondText(caller.reason, status = HttpStatusCode.BadRequest)
            return null
        }
        is CallerIdentity.Resolved -> caller.id
    }
    val meta = routing.sessions.getSession(id)
    if (meta == null || !meta.state.isAlive) {
        call.respondText("session '${id.value}' is not running", status = HttpStatusCode.BadRequest)
        return null
    }
    return id
}

private suspend fun RoutingContext.callerOrAbsent(routing: MutexRouting, explicitSessionId: String?): CallerIdentity? =
    when (val caller = resolveCallerIdentity(routing.paneLookup, explicitSessionId)) {
        is CallerIdentity.Rejected -> {
            call.respondText(caller.reason, status = HttpStatusCode.BadRequest)
            null
        }
        else -> caller
    }

private suspend fun <T> RoutingContext.decodeBoundedBody(routing: MutexRouting, serializer: KSerializer<T>): T? {
    val text = receiveBoundedText(MUTEX_BODY_MAX_BYTES) ?: return null
    return try {
        routing.json.decodeFromString(serializer, text.ifBlank { "{}" })
    } catch (_: SerializationException) {
        call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
        null
    } catch (_: IllegalArgumentException) {
        call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
        null
    }
}

private suspend fun RoutingContext.respondRelease(routing: MutexRouting, response: MutexReleaseResponse) =
    respondJson(routing, MutexReleaseResponse.serializer(), response)

private suspend fun <T> RoutingContext.respondJson(routing: MutexRouting, serializer: KSerializer<T>, value: T) {
    call.respondText(routing.json.encodeToString(serializer, value), ContentType.Application.Json)
}
