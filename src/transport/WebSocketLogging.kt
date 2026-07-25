package io.kotgent.transport

import io.ktor.util.logging.Logger
import io.ktor.utils.io.errors.PosixException

/**
 * Keeps Ktor's application logger intact except for an expected WebSocket peer reset.
 *
 * Ktor 3.4.3 avoids logging [io.ktor.util.cio.ChannelIOException] from a WebSocket handler, but CIO on
 * Kotlin/Native surfaces `ECONNRESET` as a `ClosedByteChannelException` chain whose root cause is
 * [PosixException.ConnectionResetException]. Ktor consequently logs a full ERROR stack trace whenever
 * a browser, proxy, or CLI drops a socket without completing the close handshake.
 *
 * Match both Ktor's exact log message and the typed POSIX cause. This keeps unrelated channel closures,
 * handler bugs, and resets logged from non-WebSocket code at their original severity.
 */
fun websocketDisconnectAwareLogger(delegate: Logger): Logger = object : Logger by delegate {
    override fun error(message: String, cause: Throwable) {
        if (message == KTOR_WEBSOCKET_HANDLER_FAILED && cause.hasConnectionResetCause()) {
            delegate.debug("WebSocket peer reset the connection")
        } else {
            delegate.error(message, cause)
        }
    }
}

private fun Throwable.hasConnectionResetCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is PosixException.ConnectionResetException) return true
        val next = current.cause
        if (next === current) return false
        current = next
    }
    return false
}

private const val KTOR_WEBSOCKET_HANDLER_FAILED: String = "Websocket handler failed"
