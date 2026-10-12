package io.kotgent.proc

import io.kotgent.cinterop.pty.kotgent_capture_exited
import io.kotgent.cinterop.pty.kotgent_spawn_capture
import kotlinx.cinterop.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import platform.posix.*

/** Complete output only. A limit or deadline failure never masquerades as a truncated success. */
data class CapturedProcessResult(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray)

enum class CapturedStream { STDOUT, STDERR }

class CapturedProcessLimitException(val stream: CapturedStream, val limitBytes: Int) :
    RuntimeException("$stream exceeded $limitBytes bytes")

class CapturedProcessTimeoutException : RuntimeException("Captured process deadline exceeded")

class CapturedProcessException(val operation: String, val errorCode: Int) :
    RuntimeException("$operation failed (errno=$errorCode)")

/**
 * Narrow, shell-free capture for short commands. The executable must be an absolute path; arguments
 * are literal and the supplied environment is the entire child environment. stdin is /dev/null; cwd is inherited
 * (Git callers can pass -C). No descriptor other than stdio is inherited.
 *
 * Both streams are drained concurrently with independent byte caps and a monotonic deadline. Coroutine
 * cancellation is checked at most one short poll apart. Every exit path closes the pipes, kills the
 * owned process group (including remaining descendants on success), and reaps the direct child.
 * Descendants that deliberately leave the process group are outside this contract.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
object CapturedProcess {
    suspend fun run(
        argv: List<String>,
        environment: Map<String, String> = emptyMap(),
        stdoutLimitBytes: Int = 8 * 1024 * 1024,
        stderrLimitBytes: Int = 256 * 1024,
        timeout: Duration = 10.seconds,
    ): CapturedProcessResult {
        require(argv.isNotEmpty() && argv[0].startsWith('/')) { "An absolute executable path is required" }
        require(argv.none { '\u0000' in it }) { "argv must not contain NUL" }
        require(environment.all { (key, value) ->
            key.isNotEmpty() && '=' !in key && '\u0000' !in key && '\u0000' !in value
        }) { "Invalid environment entry" }
        require(stdoutLimitBytes >= 0 && stderrLimitBytes >= 0) { "Byte caps must be nonnegative" }
        require(timeout.isPositive() && timeout.isFinite()) { "A finite positive timeout is required" }
        val started = TimeSource.Monotonic.markNow()
        // Native has no blocking-I/O dispatcher. A per-call thread keeps poll/reap off the caller's
        // dispatcher; its lifetime ends only after cleanup, including when withContext is cancelled.
        return newSingleThreadContext("kotgent-capture").use { dispatcher ->
            withContext(dispatcher) {
                val context = currentCoroutineContext()
                fun checkDeadline() {
                    context.ensureActive()
                    if (started.elapsedNow() >= timeout) throw CapturedProcessTimeoutException()
                }
                checkDeadline()
                memScoped {
                    fun strings(values: List<String>): CPointer<CPointerVar<ByteVar>> {
                        val result = allocArray<CPointerVar<ByteVar>>(values.size + 1)
                        values.forEachIndexed { index, value -> result[index] = value.cstr.ptr }
                        result[values.size] = null
                        return result
                    }
                    val args = strings(argv)
                    val env = strings(environment.map { (key, value) -> "$key=$value" })
                    val child = alloc<IntVar>()
                    val out = alloc<IntVar>()
                    val err = alloc<IntVar>()
                    val rc = kotgent_spawn_capture(child.ptr, out.ptr, err.ptr, args, env)
                    if (rc != 0) throw CapturedProcessException("posix_spawn", rc)
                    val pid = child.value
                    val fds = intArrayOf(out.value, err.value)
                    var reaped = false
                    try {
                        val buffers = arrayOf(
                            CaptureBuffer(CapturedStream.STDOUT, stdoutLimitBytes),
                            CaptureBuffer(CapturedStream.STDERR, stderrLimitBytes),
                        )
                        val polls = allocArray<pollfd>(2)
                        val chunk = allocArray<ByteVar>(8192)
                        while (true) {
                            checkDeadline()
                            if (fds.all { it < 0 }) {
                                val exited = kotgent_capture_exited(pid)
                                if (exited > 0) break
                                if (exited < 0 && errno != EINTR) throw CapturedProcessException("waitid", errno)
                            }
                            for (i in 0..1) {
                                polls[i].fd = fds[i]
                                polls[i].events = POLLIN.convert()
                                polls[i].revents = 0
                            }
                            val remaining = (timeout - started.elapsedNow()).inWholeMilliseconds
                            val ready = poll(polls, 2.convert(), remaining.coerceIn(0, 20).toInt())
                            if (ready < 0 && errno != EINTR) throw CapturedProcessException("poll", errno)
                            checkDeadline()
                            // One bounded read per stream keeps a flooding writer from starving its peer
                            // or the deadline/cancellation checks. The read ends are nonblocking.
                            for (i in 0..1) {
                                if (fds[i] < 0 || polls[i].revents.toInt() == 0) continue
                                val size = read(fds[i], chunk, 8192.convert())
                                when {
                                    size > 0 -> buffers[i].append(chunk, size.toInt())
                                    size == 0L -> { close(fds[i]); fds[i] = -1 }
                                    errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK ->
                                        throw CapturedProcessException("read", errno)
                                }
                            }
                        }
                        // The leader is still waitable here: kill before reap, never signal a reused pid.
                        kill(-pid, SIGKILL)
                        val exitCode = reap(pid)
                        reaped = true
                        CapturedProcessResult(exitCode, buffers[0].bytes(), buffers[1].bytes())
                    } finally {
                        if (!reaped) kill(-pid, SIGKILL)
                        fds.filter { it >= 0 }.forEach { close(it) }
                        if (!reaped) { val _ = reap(pid) }
                    }
                }
            }
        }
    }

    private fun reap(pid: Int): Int = memScoped {
        val status = alloc<IntVar>()
        while (waitpid(pid, status.ptr, 0) < 0) {
            if (errno != EINTR) throw CapturedProcessException("waitpid", errno)
        }
        val value = status.value
        if (value and 0x7f == 0) (value shr 8) and 0xff else 128 + (value and 0x7f)
    }

    private class CaptureBuffer(val stream: CapturedStream, val cap: Int) {
        private var storage = ByteArray(minOf(cap, 8192))
        private var size = 0

        fun append(source: CPointer<ByteVar>, count: Int) {
            if (count > cap - size) throw CapturedProcessLimitException(stream, cap)
            val needed = size + count
            if (needed > storage.size) {
                val grown = minOf(cap.toLong(), maxOf(needed.toLong(), storage.size.toLong() * 2)).toInt()
                storage = storage.copyOf(grown)
            }
            val _ = storage.usePinned { memcpy(it.addressOf(size), source, count.convert()) }
            size = needed
        }

        fun bytes(): ByteArray = storage.copyOf(size)
    }
}
