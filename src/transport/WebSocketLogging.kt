package io.kotgent.transport

import io.ktor.util.logging.Logger
import io.ktor.utils.io.errors.PosixException

fun websocketDisconnectAwareLogger(delegate: Logger): Logger = object : Logger by delegate {
    override fun error(message: String, cause: Throwable) {
        // Ktor/CIO Native reports ordinary peer resets as handler errors. Narrow on both its exact
        // message and typed POSIX cause so unrelated channel/handler failures stay errors.
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
