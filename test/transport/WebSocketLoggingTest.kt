package io.kotgent.transport

import io.ktor.util.logging.LogLevel
import io.ktor.util.logging.Logger
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.errors.PosixException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class WebSocketLoggingTest {

    @Test
    fun connectionResetFromKtorWebSocketHandlerIsDowngradedWithoutAStackTrace() {
        val delegate = RecordingLogger()
        val logger = websocketDisconnectAwareLogger(delegate)
        val reset = PosixException.ConnectionResetException("ECONNRESET: Connection reset by peer")
        val failure = ClosedByteChannelException(ClosedByteChannelException(reset))

        logger.error("Websocket handler failed", failure)

        assertEquals(
            listOf(Entry(LogLevel.DEBUG, "WebSocket peer reset the connection")),
            delegate.entries,
        )
    }

    @Test
    fun anotherWebSocketFailureRemainsAnError() {
        val delegate = RecordingLogger()
        val logger = websocketDisconnectAwareLogger(delegate)
        val failure = ClosedByteChannelException(IllegalStateException("broken handler"))

        logger.error("Websocket handler failed", failure)

        assertEquals(LogLevel.ERROR, delegate.entries.single().level)
        assertEquals("Websocket handler failed", delegate.entries.single().message)
        assertSame(failure, delegate.entries.single().cause)
    }

    @Test
    fun connectionResetOutsideKtorWebSocketLogRemainsAnError() {
        val delegate = RecordingLogger()
        val logger = websocketDisconnectAwareLogger(delegate)
        val failure = PosixException.ConnectionResetException("ECONNRESET")

        logger.error("Database connection failed", failure)

        assertEquals(LogLevel.ERROR, delegate.entries.single().level)
        assertEquals("Database connection failed", delegate.entries.single().message)
        assertSame(failure, delegate.entries.single().cause)
    }

    private data class Entry(
        val level: LogLevel,
        val message: String,
        val cause: Throwable? = null,
    )

    private class RecordingLogger : Logger {
        override val level: LogLevel = LogLevel.TRACE
        val entries = mutableListOf<Entry>()

        override fun error(message: String) = record(LogLevel.ERROR, message)
        override fun error(message: String, cause: Throwable) = record(LogLevel.ERROR, message, cause)
        override fun warn(message: String) = record(LogLevel.WARN, message)
        override fun warn(message: String, cause: Throwable) = record(LogLevel.WARN, message, cause)
        override fun info(message: String) = record(LogLevel.INFO, message)
        override fun info(message: String, cause: Throwable) = record(LogLevel.INFO, message, cause)
        override fun debug(message: String) = record(LogLevel.DEBUG, message)
        override fun debug(message: String, cause: Throwable) = record(LogLevel.DEBUG, message, cause)
        override fun trace(message: String) = record(LogLevel.TRACE, message)
        override fun trace(message: String, cause: Throwable) = record(LogLevel.TRACE, message, cause)

        private fun record(level: LogLevel, message: String, cause: Throwable? = null) {
            entries += Entry(level, message, cause)
        }
    }
}
