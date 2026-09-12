package io.kotgent.transport

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** Refuse an unread request body without retaining CIO's raw parser or dropping the response. */
internal suspend fun ApplicationCall.respondToUnconsumedBodyAndClose(
    text: String,
    status: HttpStatusCode,
    requestBody: ByteReadChannel? = null,
    reason: String = "closing unconsumed request body",
) {
    response.header(HttpHeaders.Connection, "close")
    try {
        respondText(text, status = status)
    } finally {
        requestBody?.cancel(null)
        withContext(NonCancellable) { closePinnedCioConnectionAfterFlush(reason) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun ApplicationCall.closePinnedCioConnectionAfterFlush(reason: String) {
    // Ktor CIO's grandparent owns raw-body parsing and closes the accepted socket.
    val callJob = coroutineContext[Job] ?: return
    val requestHandlerJob = callJob.parent ?: return
    val connectionPipelineJob = requestHandlerJob.parent ?: return
    // Its response writer is a sibling, so allow the early 4xx bytes a bounded flush window.
    delay(CIO_RESPONSE_FLUSH_GRACE_MILLIS.milliseconds)
    connectionPipelineJob.cancel(CancellationException(reason))
}

private const val CIO_RESPONSE_FLUSH_GRACE_MILLIS: Long = 100L
