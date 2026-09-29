package io.kotgent.transport

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
        closePinnedCioConnection(reason)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun ApplicationCall.closePinnedCioConnection(reason: String) {
    // Ktor CIO's grandparent owns raw-body parsing and closes the accepted socket.
    val callJob = coroutineContext[Job] ?: return
    val requestHandlerJob = callJob.parent ?: return
    val connectionPipelineJob = requestHandlerJob.parent ?: return
    // Cancelling it also cancels the sibling response writer mid-response, so wait outside the pipeline for a
    // peer that reads `Connection: close` to hang up, and cut off only a peer still connected at the deadline.
    application.launch {
        withTimeoutOrNull(PINNED_CIO_CONNECTION_DEADLINE_MILLIS.milliseconds) { connectionPipelineJob.join() }
            ?: connectionPipelineJob.cancel(CancellationException(reason))
    }
}

private const val PINNED_CIO_CONNECTION_DEADLINE_MILLIS: Long = 1_000L
