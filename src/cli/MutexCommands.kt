package io.kotgent.cli

import io.kotgent.proc.ChildProcessException
import io.kotgent.proc.ForwardingChild
import io.kotgent.transport.MUTEX_WAIT_DEFAULT_SECONDS
import io.kotgent.transport.MutexAcquireResponse
import io.kotgent.transport.MutexListingDto
import io.kotgent.transport.MutexReleaseResponse
import io.kotgent.transport.TRANSPORT_JSON
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.utils.io.errors.PosixException
import kotlinx.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

data class MutexAcquire(
    val key: String,
    val ticket: String?,
    val wait: Int,
    val json: Boolean,
    val session: String?,
) : CliCommand

data class MutexRelease(val token: String, val json: Boolean, val session: String?) : CliCommand

data class MutexRun(val key: String, val command: List<String>, val session: String?) : CliCommand

data class MutexList(val json: Boolean) : CliCommand

/** Exit codes: 0 for any printed result (`pending` included), 1 daemon failure, 2 usage, 3 conflict. */
const val MUTEX_EXIT_DAEMON: Int = 1

const val MUTEX_EXIT_CONFLICT: Int = 3

/** A spawn failure reads like a shell's "command not found". */
const val MUTEX_EXIT_NOT_RUN: Int = 127

object MutexCommands {

    fun acquire(command: MutexAcquire): Int = withMutexApi { api ->
        runMutexAcquireCommand(
            command,
            acquire = { api.acquireMutex(command.key, command.ticket, command.wait, command.session) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    fun release(command: MutexRelease): Int = withMutexApi { api ->
        runMutexReleaseCommand(
            command,
            release = { api.releaseMutex(command.token, command.session) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    fun list(command: MutexList): Int = withMutexApi { api ->
        runMutexListCommand(command, list = { api.listMutexes() }, stdout = ::println, stderr = ::eprintln)
    }

    fun run(command: MutexRun): Int = withMutexApi { api ->
        runMutexRunCommand(
            command,
            acquire = { ticket -> api.acquireMutex(command.key, ticket, MUTEX_WAIT_DEFAULT_SECONDS, command.session) },
            release = { token -> api.releaseMutex(token, command.session) },
            execute = ForwardingChild::run,
            stderr = ::eprintln,
        )
    }

    private fun withMutexApi(block: suspend (ApiClient) -> Int): Int = runBlocking {
        ApiClient(paneId = TmuxSelf.currentPane()).use { block(it) }
    }
}

suspend fun runMutexAcquireCommand(
    command: MutexAcquire,
    acquire: suspend () -> MutexAcquireResponse,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int {
    val response = try {
        acquire()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (!isInterruptedWait(e)) return reportMutexFailure(e, stderr)
        // The wait may have ended because its connection dropped; the ticket, if any, is still queued.
        MutexAcquireResponse("pending", command.key, ticket = command.ticket)
    }
    stdout(
        if (command.json) {
            TRANSPORT_JSON.encodeToString(MutexAcquireResponse.serializer(), response)
        } else {
            response.describe()
        },
    )
    return 0
}

suspend fun runMutexReleaseCommand(
    command: MutexRelease,
    release: suspend () -> MutexReleaseResponse,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int {
    val response = try {
        release()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return reportMutexFailure(e, stderr)
    }
    stdout(
        when {
            command.json -> TRANSPORT_JSON.encodeToString(MutexReleaseResponse.serializer(), response)
            response.released -> "released: ${response.key}"
            else -> "not-held: ${command.token}"
        },
    )
    return 0
}

suspend fun runMutexListCommand(
    command: MutexList,
    list: suspend () -> MutexListingDto,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int {
    val listing = try {
        list()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return reportMutexFailure(e, stderr)
    }
    if (command.json) {
        stdout(TRANSPORT_JSON.encodeToString(MutexListingDto.serializer(), listing))
        return 0
    }
    if (listing.mutexes.isEmpty()) stdout("no mutexes held or awaited")
    for (mutex in listing.mutexes) {
        val holder = mutex.holder
        val held = if (holder == null) {
            "granted, awaiting its claim"
        } else {
            "held by ${holder.sessionId} for ${formatElapsed(listing.serverNow - holder.acquiredAt)}"
        }
        val waiting = mutex.waiters.count { !it.granted }
        stdout("${mutex.key}  $held" + if (waiting > 0) "  · $waiting waiting" else "")
    }
    return 0
}

/**
 * Waits for the mutex, runs [MutexRun.command] in the foreground, and releases after it exits, whatever the
 * exit — including one caused by a forwarded SIGINT, SIGTERM or SIGHUP. Returns the command's own status.
 */
suspend fun runMutexRunCommand(
    command: MutexRun,
    acquire: suspend (ticket: String?) -> MutexAcquireResponse,
    release: suspend (token: String) -> MutexReleaseResponse,
    execute: (List<String>) -> Int,
    stderr: (String) -> Unit,
    retryDelayMillis: Long = RUN_RETRY_DELAY_MILLIS,
): Int {
    val token = when (val acquisition = waitForMutex(command.key, acquire, stderr, retryDelayMillis)) {
        is Acquisition.Held -> acquisition.token
        is Acquisition.Failed -> return acquisition.exitCode
    }
    try {
        return execute(command.command)
    } catch (e: ChildProcessException) {
        stderr("kotgent: ${e.message}")
        return MUTEX_EXIT_NOT_RUN
    } finally {
        withContext(NonCancellable) {
            try {
                val _ = release(token)
            } catch (e: Throwable) {
                stderr(
                    "kotgent: mutex '${command.key}' could not be released (${e.message}); it stays held until " +
                        "this session ends or it is force-released in the Web UI",
                )
            }
        }
    }
}

private sealed interface Acquisition {
    data class Held(val token: String) : Acquisition

    data class Failed(val exitCode: Int) : Acquisition
}

private suspend fun waitForMutex(
    key: String,
    acquire: suspend (ticket: String?) -> MutexAcquireResponse,
    stderr: (String) -> Unit,
    retryDelayMillis: Long,
): Acquisition {
    var ticket: String? = null
    var announced = false
    var interruptions = 0
    while (true) {
        val response = try {
            acquire(ticket)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // A lapsed ticket only loses the place in line; a fresh request queues again.
            if (e.status != HTTP_GONE || ticket == null) return Acquisition.Failed(reportMutexFailure(e, stderr))
            ticket = null
            continue
        } catch (e: Throwable) {
            if (!isInterruptedWait(e) || ++interruptions > RUN_MAX_INTERRUPTED_WAITS) {
                return Acquisition.Failed(reportMutexFailure(e, stderr))
            }
            delay(retryDelayMillis.milliseconds)
            continue
        }
        interruptions = 0
        if (response.status == "acquired") {
            val token = response.token ?: return Acquisition.Failed(
                reportMutexFailure(IllegalStateException("the daemon granted '$key' without a token"), stderr),
            )
            return Acquisition.Held(token)
        }
        ticket = response.ticket
        if (!announced) {
            stderr("kotgent: waiting for mutex '$key' (position ${response.position ?: "?"})")
            announced = true
        }
    }
}

private fun MutexAcquireResponse.describe(): String = buildString {
    if (status == "acquired") {
        appendLine("acquired: $token")
        appendLine("key: $key")
        append("release: kotgent mutex release $token")
    } else {
        appendLine("pending: ${ticket.orEmpty()}")
        appendLine("key: $key")
        position?.let { appendLine("position: $it") }
        append("next: kotgent mutex acquire $key" + if (ticket == null) "" else " --ticket $ticket")
    }
}

private fun reportMutexFailure(e: Throwable, stderr: (String) -> Unit): Int {
    if (e is ApiException) {
        stderr("kotgent: ${e.body.trim().ifEmpty { "HTTP ${e.status}" }}")
        return if (e.status == HTTP_CONFLICT || e.status == HTTP_GONE) MUTEX_EXIT_CONFLICT else MUTEX_EXIT_DAEMON
    }
    stderr("kotgent: ${e.message ?: e::class.simpleName}")
    return MUTEX_EXIT_DAEMON
}

/** A timed-out or dropped wait: the request may still be queued, unlike a daemon that cannot be reached. */
fun isInterruptedWait(e: Throwable): Boolean {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is ConnectTimeoutException || cause is PosixException.ConnectionRefusedException) return false
        cause = cause.cause
    }
    return e is IOException || e is PosixException
}

private fun formatElapsed(millis: Long): String {
    val seconds = (millis / 1_000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m${seconds % 60}s"
        else -> "${seconds / 3_600}h${seconds % 3_600 / 60}m"
    }
}

private const val HTTP_CONFLICT: Int = 409

private const val HTTP_GONE: Int = 410

private const val RUN_RETRY_DELAY_MILLIS: Long = 1_000

private const val RUN_MAX_INTERRUPTED_WAITS: Int = 10
