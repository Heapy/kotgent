package io.kotgent.sys

import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.signal

/**
 * The signal that asked the daemon to shut down, or 0 while none has arrived.
 *
 * Written from a C signal handler, so it is a plain `@Volatile` int and nothing else: storing an int is
 * async-signal-safe, while anything allocating (a Kotlin object, a channel send, a `println`) is not.
 * The daemon's main coroutine polls it and does the actual — suspending — teardown, exactly like
 * [io.kotgent.cli.AttachClient]'s SIGWINCH flag.
 */
@Volatile
private var shutdownSignalFlag: Int = 0

/**
 * Take SIGINT/SIGTERM back from Ktor and turn them into a shutdown request the daemon can observe.
 *
 * ## Why this exists (Ctrl+C used to do nothing)
 * `EmbeddedServer.start()` installs a shutdown hook, and on Kotlin/Native that hook is literally
 * `signal(SIGINT, …)` + `signal(SIGTERM, …)` (`ShutdownHookNative.kt` in `ktor-server-core`; confirmed in
 * our own linked binary — the two bridge calls pass `2` and `15`). Its handler only calls
 * `EmbeddedServer.stop()`. So Ctrl+C on a foreground `kotgent daemon`:
 *  - **no longer terminates the process** — the kernel's default disposition is gone, replaced by Ktor's
 *    handler;
 *  - stops the HTTP engine, which closes the listening socket and every WS;
 *  - leaves the process alive forever in [kotlinx.coroutines.awaitCancellation], holding the SQLite
 *    database, the terminal bridges and the tty, serving nothing.
 * That state is observable and was observed: a daemon with no `LISTEN` descriptor, only CLOSED accepted
 * sockets, still running hours after the operator pressed Ctrl+C — and a later daemon happily taking the
 * now-free port. From the outside it reads as "the daemon ignores Ctrl+C"; the truth is worse (it ignored
 * the *exit* and obeyed the *stop*).
 *
 * Because `signal(2)` keeps only the last handler, calling this **after** [io.ktor.server.engine.EmbeddedServer.start]
 * (i.e. after [io.kotgent.transport.KotgentServer.start] returns) replaces Ktor's — the order is
 * load-bearing.
 *
 * The handler also restores the default disposition for the signal it just took, so a **second** Ctrl+C
 * kills the process outright if the graceful teardown ever wedges.
 *
 * Stock `platform.posix` only (no custom cinterop), so it links into the test binary too (KT-78062) and
 * is covered by `SignalsTest`.
 */
@OptIn(ExperimentalForeignApi::class)
fun installShutdownSignals() {
    shutdownSignalFlag = 0
    val handler = staticCFunction<Int, Unit> { signo ->
        shutdownSignalFlag = signo
        // `signal` is on POSIX's async-signal-safe list; SIG_DFL is (void(*)(int))0, i.e. null here.
        signal(signo, null)
    }
    signal(SIGINT, handler)
    signal(SIGTERM, handler)
}

/** The signal that requested shutdown, or 0 if none has arrived yet. */
fun pendingShutdownSignal(): Int = shutdownSignalFlag

/** Human name for the shutdown signals, for the daemon's goodbye line. */
fun shutdownSignalName(signo: Int): String = when (signo) {
    SIGINT -> "SIGINT"
    SIGTERM -> "SIGTERM"
    else -> "signal $signo"
}

/**
 * Put SIGINT/SIGTERM back on their default disposition and clear the flag. Exists for tests: a handler
 * installed by one test would otherwise outlive it and swallow a real signal for the rest of the binary.
 */
@OptIn(ExperimentalForeignApi::class)
fun restoreDefaultShutdownSignals() {
    signal(SIGINT, null)
    signal(SIGTERM, null)
    shutdownSignalFlag = 0
}
