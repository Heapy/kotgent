package io.kotgent.pty

import io.kotgent.cinterop.pty.POSIX_SPAWN_CLOEXEC_DEFAULT
import io.kotgent.cinterop.pty.POSIX_SPAWN_SETSID
import io.kotgent.cinterop.pty.kotgent_openpty
import io.kotgent.cinterop.pty.kotgent_ptsname
import io.kotgent.cinterop.pty.kotgent_set_winsize
import io.kotgent.cinterop.pty.posix_spawn
import io.kotgent.cinterop.pty.posix_spawn_file_actions_addclose
import io.kotgent.cinterop.pty.posix_spawn_file_actions_adddup2
import io.kotgent.cinterop.pty.posix_spawn_file_actions_addopen
import io.kotgent.cinterop.pty.posix_spawn_file_actions_destroy
import io.kotgent.cinterop.pty.posix_spawn_file_actions_init
import io.kotgent.cinterop.pty.posix_spawn_file_actions_tVar
import io.kotgent.cinterop.pty.posix_spawnattr_destroy
import io.kotgent.cinterop.pty.posix_spawnattr_init
import io.kotgent.cinterop.pty.posix_spawnattr_setflags
import io.kotgent.cinterop.pty.posix_spawnattr_tVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.TimeSource
import platform.posix.EINTR
import platform.posix.O_RDWR
import platform.posix.POLLIN
import platform.posix.SIGKILL
import platform.posix.SIGTERM
import platform.posix.SIGWINCH
import platform.posix.WNOHANG
import platform.posix.errno
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.kill
import platform.posix.pipe
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.stderr
import platform.posix.strerror
import platform.posix.usleep
import platform.posix.waitpid
import platform.posix.close as posixClose
import platform.posix.read as posixRead
import platform.posix.write as posixWrite

class PtyException(message: String) : RuntimeException(message)

/**
 * Uses `posix_spawn`, never fork-without-exec, which is unsafe for the Kotlin/Native runtime.
 * A dedicated OS thread is required because Kotlin/Native has no blocking-I/O dispatcher.
 */
@OptIn(
    ExperimentalForeignApi::class,
    DelicateCoroutinesApi::class,
    ExperimentalCoroutinesApi::class,
    ExperimentalAtomicApi::class,
)
class Pty private constructor(
    val masterFd: Int,
    val pid: Int,
    private val readerWakeReadFd: Int,
    private val readerWakeWriteFd: Int,
    private val closeTraceEnabled: Boolean,
) {
    val output: Channel<ByteArray> = Channel(Channel.UNLIMITED)

    private val readerContext = newSingleThreadContext("kotgent-pty-reader-$pid")
    private val readerScope = CoroutineScope(readerContext)
    private lateinit var readerJob: Job

    private val closeClaimed = AtomicInt(0)
    private val closeCompletion = CompletableDeferred<Int>()
    private val closeTraceOrigin = TimeSource.Monotonic.markNow()
    private var reaped = false
    private var exitCode = -1

    // Integration seam for KT-78062: production never branches on this teardown-order observation.
    public var readerCompletedBeforeMasterFdRelease: Boolean = false
        private set

    private fun startReader() {
        readerJob = readerScope.launch {
            try {
                memScoped {
                    val bufSize = 8192
                    val buf = allocArray<ByteVar>(bufSize)
                    val pollFds = allocArray<pollfd>(2)
                    pollFds[0].fd = masterFd
                    pollFds[0].events = POLLIN.convert()
                    pollFds[1].fd = readerWakeReadFd
                    pollFds[1].events = POLLIN.convert()

                    while (isActive) {
                        pollFds[0].revents = 0
                        pollFds[1].revents = 0
                        val ready = poll(pollFds, 2.convert(), -1)
                        if (ready < 0) {
                            if (errno == EINTR) continue
                            break
                        }
                        // Teardown wins when output and the wake pipe become readable together.
                        if (pollFds[1].revents.toInt() != 0) break
                        if (!isActive || pollFds[0].revents.toInt() == 0) continue

                        val n = posixRead(masterFd, buf, bufSize.convert())
                        when {
                            n < 0 -> {
                                if (errno == EINTR) continue
                                break
                            }
                            n == 0L -> break
                            else -> output.trySend(buf.readBytes(n.toInt()))
                        }
                    }
                }
            } finally {
                traceClose("reader-finished")
                output.close()
            }
        }
    }

    private fun wakeReader() {
        traceClose("reader-wake-start", "completed=${readerJob.isCompleted}")
        memScoped {
            val byte = alloc<ByteVar>()
            byte.value = 0
            while (true) {
                val written = posixWrite(readerWakeWriteFd, byte.ptr, 1.convert())
                if (written == 1L) {
                    traceClose("reader-wake-complete")
                    return@memScoped
                }
                if (written < 0 && errno == EINTR) continue
                if (readerJob.isCompleted) {
                    traceClose("reader-wake-skipped", "reader already completed")
                    return@memScoped
                }
                val code = errno
                traceClose("reader-wake-failed", "errno=$code (${errnoMessage(code)})")
                throw PtyException("wake pty reader failed: ${errnoMessage(code)}")
            }
        }
    }

    // Keep the reader-state snapshot at the exact descriptor-release operation for ptycheck.
    private fun releaseMasterFd() {
        readerCompletedBeforeMasterFdRelease = readerJob.isCompleted
        val rc = posixClose(masterFd)
        val code = if (rc == 0) 0 else errno
        traceClose(
            "master-fd-released",
            "readerCompleted=$readerCompletedBeforeMasterFdRelease rc=$rc${errnoDetail(rc, code)}",
        )
    }

    // Opt-in, flush-on-write diagnostics let a timed-out ptycheck identify the last completed syscall.
    private fun traceClose(stage: String, detail: String = "") {
        if (!closeTraceEnabled) return
        val suffix = if (detail.isEmpty()) "" else " $detail"
        fputs(
            "kotgent: pty-close pid=$pid fd=$masterFd " +
                "elapsedMs=${closeTraceOrigin.elapsedNow().inWholeMilliseconds} stage=$stage$suffix\n",
            stderr,
        )
        fflush(stderr)
    }

    /** A failure after a partial write means the written prefix was delivered and cannot be rolled back. */
    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val n = posixWrite(masterFd, pinned.addressOf(offset), (bytes.size - offset).convert())
                if (n < 0) {
                    if (errno == EINTR) continue
                    throw PtyException("write to pty failed: ${errnoMessage(errno)}")
                }
                offset += n.toInt()
            }
        }
    }

    // The spawned child has no controlling tty/foreground pgrp, so TIOCSWINSZ cannot deliver SIGWINCH.
    fun resize(cols: Int, rows: Int) {
        val rc = kotgent_set_winsize(masterFd, rows.toUShort(), cols.toUShort())
        if (rc != 0) throw PtyException("resize (TIOCSWINSZ) failed: ${errnoMessage(errno)}")
        if (!reaped && pid > 1) kill(-pid, SIGWINCH) // A reaped pid may already have been reused.
    }

    fun waitFor(): Int {
        if (reaped) {
            traceClose("wait-reused", "exitCode=$exitCode")
            return exitCode
        }
        traceClose("wait-start")
        memScoped {
            val status = alloc<IntVar>()
            while (true) {
                val r = waitpid(pid, status.ptr, 0)
                if (r == -1) {
                    if (errno == EINTR) continue
                    val code = errno
                    reaped = true
                    exitCode = -1
                    traceClose("wait-failed", "errno=$code (${errnoMessage(code)})")
                    return exitCode
                }
                break
            }
            exitCode = decodeStatus(status.value)
            reaped = true
        }
        traceClose("wait-complete", "exitCode=$exitCode")
        return exitCode
    }

    private fun reapBounded(micros: Long): Boolean {
        val timeout = micros.microseconds
        val started = TimeSource.Monotonic.markNow()
        var polls = 0
        traceClose("reap-grace-start", "timeoutMicros=$micros")
        memScoped {
            val status = alloc<IntVar>()
            while (true) {
                polls++
                val r = waitpid(pid, status.ptr, WNOHANG)
                when {
                    r == -1 -> {
                        val code = errno
                        if (code != EINTR) {
                            reaped = true
                            exitCode = -1
                            traceClose(
                                "reap-grace-no-child",
                                "polls=$polls errno=$code (${errnoMessage(code)})",
                            )
                            return true
                        }
                    }
                    r != 0 -> {
                        exitCode = decodeStatus(status.value)
                        reaped = true
                        traceClose("reap-grace-complete", "polls=$polls exitCode=$exitCode")
                        return true
                    }
                }

                // usleep may overshoot badly under load; elapsed monotonic time defines the grace bound.
                val remainingMicros = (timeout - started.elapsedNow()).inWholeMicroseconds
                if (remainingMicros <= 0L) break
                if (r == 0) usleep(minOf(REAP_POLL_MICROS, remainingMicros).convert())
            }
        }
        traceClose(
            "reap-grace-timeout",
            "polls=$polls elapsedMs=${started.elapsedNow().inWholeMilliseconds}",
        )
        return false
    }

    /** Reaps the child without releasing the master, unblocking writers before their fd ownership ends. */
    fun prepareClose() {
        if (closeClaimed.load() != 0 || reaped) return
        terminateAndReapChild()
    }

    private fun terminateAndReapChild() {
        if (!reaped) {
            val termRc = kill(pid, SIGTERM)
            val termErrno = if (termRc == 0) 0 else errno
            traceClose("signal-term", "rc=$termRc${errnoDetail(termRc, termErrno)}")
            if (!reapBounded(CLOSE_GRACE_MICROS)) {
                val killRc = kill(pid, SIGKILL)
                val killErrno = if (killRc == 0) 0 else errno
                traceClose("signal-kill", "rc=$killRc${errnoDetail(killRc, killErrno)}")
                waitFor()
            }
        } else {
            traceClose("child-already-reaped", "exitCode=$exitCode")
        }
    }

    // Wake and join the reader before freeing the descriptor number; a stale reader could otherwise
    // consume bytes from a new session that reused it. Concurrent callers await the single CAS winner.
    fun close(): Int {
        if (!closeClaimed.compareAndSet(0, 1)) {
            traceClose("close-waiter-start", "completion=${closeCompletion.isCompleted}")
            val result = runBlocking { closeCompletion.await() }
            traceClose("close-waiter-complete", "exitCode=$result")
            return result
        }

        traceClose("close-owner-claimed")
        try {
            terminateAndReapChild()
            traceClose("reader-stop-start")
            wakeReader()
            readerScope.cancel()
            traceClose("reader-cancelled")
            runBlocking { readerJob.join() }
            traceClose("reader-joined")
            readerContext.close()
            traceClose("reader-context-closed")
            releaseMasterFd()
            val wakeReadRc = posixClose(readerWakeReadFd)
            val wakeReadErrno = if (wakeReadRc == 0) 0 else errno
            traceClose("reader-wake-read-released", "rc=$wakeReadRc${errnoDetail(wakeReadRc, wakeReadErrno)}")
            val wakeWriteRc = posixClose(readerWakeWriteFd)
            val wakeWriteErrno = if (wakeWriteRc == 0) 0 else errno
            traceClose("reader-wake-write-released", "rc=$wakeWriteRc${errnoDetail(wakeWriteRc, wakeWriteErrno)}")
            val result = exitCode
            closeCompletion.complete(result)
            traceClose("close-owner-complete", "exitCode=$result")
            return result
        } catch (t: Throwable) {
            closeCompletion.completeExceptionally(t)
            traceClose("close-owner-failed", "${t::class.simpleName}: ${t.message}")
            throw t
        }
    }

    private fun decodeStatus(s: Int): Int =
        if (s and 0x7f == 0) (s shr 8) and 0xff else 128 + (s and 0x7f)

    companion object {
        const val CLOSE_TRACE_ENV: String = "KOTGENT_PTY_CLOSE_TRACE"

        private const val CLOSE_GRACE_MICROS: Long = 2_000_000L

        private const val REAP_POLL_MICROS: Long = 5_000L

        private const val PTS_PATH_CAP: Int = 1024

        /** `command[0]` is used as an executable path; `env` defaults to a deliberately empty environment. */
        fun open(
            command: List<String>,
            env: Map<String, String> = emptyMap(),
            cols: Int = 80,
            rows: Int = 24,
        ): Pty {
            require(command.isNotEmpty()) { "command must not be empty" }

            memScoped {
                val scope = this
                val masterVar = alloc<IntVar>()
                val slaveVar = alloc<IntVar>()

                if (kotgent_openpty(masterVar.ptr, slaveVar.ptr) != 0) {
                    throw PtyException("openpty failed: ${errnoMessage(errno)}")
                }
                val master = masterVar.value
                val slave = slaveVar.value

                kotgent_set_winsize(master, rows.toUShort(), cols.toUShort())

                val ptsBuf = allocArray<ByteVar>(PTS_PATH_CAP)
                if (kotgent_ptsname(master, ptsBuf, PTS_PATH_CAP.convert()) != 0) {
                    posixClose(master)
                    posixClose(slave)
                    throw PtyException("ptsname failed: ${errnoMessage(errno)}")
                }
                val ptsPath = ptsBuf.toKString()

                // The child must open the pts path itself for tmux attach; merely duping the inherited
                // slave fails on macOS. The file-action open still does not acquire a controlling tty.
                val fileActions = alloc<posix_spawn_file_actions_tVar>()
                val faInit = posix_spawn_file_actions_init(fileActions.ptr)
                if (faInit != 0) {
                    posixClose(master); posixClose(slave)
                    throw PtyException("posix_spawn_file_actions_init failed: ${errnoMessage(faInit)} (code=$faInit)")
                }
                var faRc = posix_spawn_file_actions_addopen(fileActions.ptr, 0, ptsPath, O_RDWR, 0.convert())
                if (faRc == 0) faRc = posix_spawn_file_actions_adddup2(fileActions.ptr, 0, 1)
                if (faRc == 0) faRc = posix_spawn_file_actions_adddup2(fileActions.ptr, 0, 2)
                if (faRc == 0) faRc = posix_spawn_file_actions_addclose(fileActions.ptr, slave)
                if (faRc == 0) faRc = posix_spawn_file_actions_addclose(fileActions.ptr, master)
                if (faRc != 0) {
                    val cleanup = cleanupNote(FILE_ACTIONS_DESTROY, posix_spawn_file_actions_destroy(fileActions.ptr))
                    posixClose(master); posixClose(slave)
                    throw PtyException(
                        "posix_spawn_file_actions setup failed: ${errnoMessage(faRc)} (code=$faRc)$cleanup",
                    )
                }

                val attr = alloc<posix_spawnattr_tVar>()
                val attrInit = posix_spawnattr_init(attr.ptr)
                if (attrInit != 0) {
                    val cleanup = cleanupNote(FILE_ACTIONS_DESTROY, posix_spawn_file_actions_destroy(fileActions.ptr))
                    posixClose(master); posixClose(slave)
                    throw PtyException("posix_spawnattr_init failed: ${errnoMessage(attrInit)} (code=$attrInit)$cleanup")
                }
                // CLOEXEC_DEFAULT atomically prevents daemon sockets and all unnamed fds leaking to agents.
                val spawnFlags = POSIX_SPAWN_SETSID or POSIX_SPAWN_CLOEXEC_DEFAULT
                val setFlags = posix_spawnattr_setflags(attr.ptr, spawnFlags.toShort())
                if (setFlags != 0) {
                    val cleanup = cleanupNote(FILE_ACTIONS_DESTROY, posix_spawn_file_actions_destroy(fileActions.ptr)) +
                        cleanupNote(ATTR_DESTROY, posix_spawnattr_destroy(attr.ptr))
                    posixClose(master); posixClose(slave)
                    throw PtyException(
                        "posix_spawnattr_setflags failed: ${errnoMessage(setFlags)} (code=$setFlags)$cleanup",
                    )
                }

                val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
                command.forEachIndexed { i, arg -> argv[i] = arg.cstr.getPointer(scope) }
                argv[command.size] = null

                val envEntries = env.map { (k, v) -> "$k=$v" }
                val envp = allocArray<CPointerVar<ByteVar>>(envEntries.size + 1)
                envEntries.forEachIndexed { i, e -> envp[i] = e.cstr.getPointer(scope) }
                envp[envEntries.size] = null

                val pidVar = alloc<IntVar>()
                val rc = posix_spawn(
                    pidVar.ptr,
                    command[0],
                    fileActions.ptr,
                    attr.ptr,
                    argv,
                    envp,
                )

                // Preserve destroy failures without masking an earlier setup/spawn error.
                val faDestroy = posix_spawn_file_actions_destroy(fileActions.ptr)
                val attrDestroy = posix_spawnattr_destroy(attr.ptr)
                posixClose(slave)

                if (rc != 0) {
                    posixClose(master)
                    val cleanup = cleanupNote(FILE_ACTIONS_DESTROY, faDestroy) + cleanupNote(ATTR_DESTROY, attrDestroy)
                    throw PtyException(
                        "posix_spawn failed for '${command[0]}': ${errnoMessage(rc)} (code=$rc)$cleanup",
                    )
                }

                warnCleanupFailure(FILE_ACTIONS_DESTROY, faDestroy)
                warnCleanupFailure(ATTR_DESTROY, attrDestroy)

                // Created after spawn so the child cannot inherit this private teardown pipe.
                val readerWakeFds = allocArray<IntVar>(2)
                if (pipe(readerWakeFds) != 0) {
                    val pipeErrno = errno
                    terminateSpawnedChild(pidVar.value)
                    posixClose(master)
                    throw PtyException("pipe for pty reader wakeup failed: ${errnoMessage(pipeErrno)}")
                }
                return Pty(
                    masterFd = master,
                    pid = pidVar.value,
                    readerWakeReadFd = readerWakeFds[0],
                    readerWakeWriteFd = readerWakeFds[1],
                    closeTraceEnabled = getenv(CLOSE_TRACE_ENV)?.toKString() == "1",
                ).also { it.startReader() }
            }
        }

        private const val FILE_ACTIONS_DESTROY: String = "posix_spawn_file_actions_destroy"
        private const val ATTR_DESTROY: String = "posix_spawnattr_destroy"

        private fun cleanupNote(what: String, rc: Int): String =
            if (rc == 0) "" else " [cleanup: $what failed: ${errnoMessage(rc)} (code=$rc)]"

        private fun warnCleanupFailure(what: String, rc: Int) {
            if (rc == 0) return
            fputs("kotgent: $what failed: ${errnoMessage(rc)} (code=$rc)\n", stderr)
            fflush(stderr)
        }

        private fun terminateSpawnedChild(pid: Int) {
            kill(pid, SIGKILL)
            memScoped {
                val status = alloc<IntVar>()
                while (true) {
                    val result = waitpid(pid, status.ptr, 0)
                    if (result >= 0 || errno != EINTR) return@memScoped
                }
            }
        }

        private fun errnoMessage(code: Int): String =
            strerror(code)?.toKString() ?: "errno=$code"

        private fun errnoDetail(rc: Int, code: Int): String =
            if (rc == 0) "" else " errno=$code (${errnoMessage(code)})"
    }
}
