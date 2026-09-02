package io.kotgent.pty

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import platform.posix.fread
import platform.posix.pclose
import platform.posix.popen
import platform.posix.setenv
import platform.posix.unsetenv

internal const val TEST_SOCKET = "kotgent-test" // Never use the developer's real kotgent tmux socket.

internal inline fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}

/** Puts Pty's teardown-stage trace on stderr so a failing close ordering is diagnosable from CI logs. */
@OptIn(ExperimentalForeignApi::class)
internal fun withCloseTrace(body: () -> Unit) {
    setenv(Pty.CLOSE_TRACE_ENV, "1", 1)
    try {
        body()
    } finally {
        unsetenv(Pty.CLOSE_TRACE_ENV)
    }
}

internal fun readUntil(pty: Pty, needle: String, timeoutMs: Long = 5_000): String = runBlocking {
    val sb = StringBuilder()
    withTimeout(timeoutMs.milliseconds) {
        while (needle !in sb) sb.append(pty.output.receive().decodeToString())
    }
    sb.toString()
}

internal suspend fun receiveUntil(sub: Subscriber, needle: String, timeoutMs: Long = 10_000): String {
    val sb = StringBuilder()
    withTimeout(timeoutMs.milliseconds) {
        while (needle !in sb) sb.append(sub.output.receive().decodeToString())
    }
    return sb.toString()
}

/** POSIX single-quote escaping for the fixture's `/bin/sh -c` snippets. */
internal fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

@OptIn(ExperimentalForeignApi::class)
internal fun sh(command: String): Int {
    val fp = popen("$command >/dev/null 2>&1", "r") ?: return -1
    return decodeExitCode(pclose(fp))
}

@OptIn(ExperimentalForeignApi::class)
internal fun capture(command: String): String {
    val fp = popen("$command 2>/dev/null", "r") ?: return ""
    val out = StringBuilder()
    memScoped {
        val bufSize = 4096
        val buf = allocArray<ByteVar>(bufSize)
        while (true) {
            val n = fread(buf, 1UL, bufSize.toULong(), fp).toInt()
            if (n <= 0) break
            out.append(buf.readBytes(n).decodeToString())
        }
    }
    pclose(fp)
    return out.toString().trim()
}

internal fun which(program: String): String? =
    capture("command -v ${q(program)}").ifEmpty { null }

// tmux state settles asynchronously after the client/server IPC round trip.
internal fun waitUntil(attempts: Int = 50, delayMs: Long = 100, condition: () -> Boolean): Boolean =
    runBlocking {
        repeat(attempts) {
            if (condition()) return@runBlocking true
            delay(delayMs.milliseconds)
        }
        condition()
    }

private fun decodeExitCode(status: Int): Int = when {
    status == -1 -> -1
    status and 0x7f == 0 -> (status shr 8) and 0xff
    else -> 128 + (status and 0x7f)
}
