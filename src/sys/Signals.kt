package io.kotgent.sys

import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.signal

/**
 * Signal handlers may only store an async-signal-safe scalar; the daemon coroutine performs teardown.
 */
@Volatile
private var shutdownSignalFlag: Int = 0

/**
 * Ktor Native replaces SIGINT/SIGTERM with handlers that stop its engine but leave kotgent suspended.
 * Install this after the server starts so it replaces Ktor's handler. The first signal requests orderly
 * teardown and restores the default disposition, allowing a second signal to terminate a wedged process.
 */
@OptIn(ExperimentalForeignApi::class)
fun installShutdownSignals() {
    shutdownSignalFlag = 0
    val handler = staticCFunction<Int, Unit> { signo ->
        shutdownSignalFlag = signo
        // `signal` is async-signal-safe; null is SIG_DFL in this binding.
        signal(signo, null)
    }
    signal(SIGINT, handler)
    signal(SIGTERM, handler)
}

fun pendingShutdownSignal(): Int = shutdownSignalFlag

fun shutdownSignalName(signo: Int): String = when (signo) {
    SIGINT -> "SIGINT"
    SIGTERM -> "SIGTERM"
    else -> "signal $signo"
}

@OptIn(ExperimentalForeignApi::class)
fun restoreDefaultShutdownSignals() {
    signal(SIGINT, null)
    signal(SIGTERM, null)
    shutdownSignalFlag = 0
}
