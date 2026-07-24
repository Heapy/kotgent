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

/** Thrown when a pty cannot be opened or its child process cannot be spawned. */
class PtyException(message: String) : RuntimeException(message)

/**
 * A pseudo-terminal with a child process attached to its slave side.
 *
 * The child is started with `openpty()` + `posix_spawn(POSIX_SPAWN_SETSID)` rather than
 * `forkpty()`: fork-without-exec is unsafe for the Kotlin/Native runtime (only
 * async-signal-safe work is allowed between fork and exec), and posix_spawn performs the
 * whole fork/exec atomically in one call. All argv/envp C strings are marshalled into
 * native memory *before* the spawn.
 *
 * The parent keeps the master fd. Output is pumped off the blocking master fd by a
 * dedicated reader thread (there is no `Dispatchers.IO` on Kotlin/Native) into an
 * unlimited [output] channel that coroutine consumers read from.
 */
@OptIn(
    ExperimentalForeignApi::class,
    DelicateCoroutinesApi::class,
    ExperimentalCoroutinesApi::class,
    ExperimentalAtomicApi::class,
)
class Pty private constructor(
    /** The pty master file descriptor, owned by this process. */
    val masterFd: Int,
    /** The child process id. */
    val pid: Int,
    /** Read side of the private pipe that wakes the reader without releasing [masterFd]. */
    private val readerWakeReadFd: Int,
    /** Write side of the private pipe that wakes the reader without releasing [masterFd]. */
    private val readerWakeWriteFd: Int,
    /** Whether close-stage diagnostics should be written to stderr. */
    private val closeTraceEnabled: Boolean,
) {
    /** Bytes read off the master fd, in arrival order. Closed when the child reaches EOF. */
    val output: Channel<ByteArray> = Channel(Channel.UNLIMITED)

    // Dedicated single OS thread polling the master fd and a private teardown wake pipe.
    private val readerContext = newSingleThreadContext("kotgent-pty-reader-$pid")
    private val readerScope = CoroutineScope(readerContext)
    private lateinit var readerJob: Job

    /** Exactly one [close] caller owns teardown; every later caller awaits [closeCompletion]. */
    private val closeClaimed = AtomicInt(0)
    private val closeCompletion = CompletableDeferred<Int>()
    private val closeTraceOrigin = TimeSource.Monotonic.markNow()
    private var reaped = false
    private var exitCode = -1

    /**
     * Whether the independent reader job had completed when [close] began releasing [masterFd].
     *
     * Public as a real-PTY integration seam because KT-78062 prevents the test binary from linking
     * this cinterop-backed class. Unlike checking the descriptor immediately before closing it (which
     * is tautologically true), [releaseMasterFd] snapshots the reader's actual completion state at the
     * release operation: moving that operation above the wake/cancel/join protocol makes this false.
     * Production teardown does not branch on this observation.
     */
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
                        // Teardown wins over simultaneously readable output: close means no consumer
                        // should receive bytes after the reader's stop/join protocol has begun.
                        if (pollFds[1].revents.toInt() != 0) break
                        if (!isActive || pollFds[0].revents.toInt() == 0) continue

                        // This is the only reader of masterFd, so readiness cannot be consumed between
                        // poll and read. The read therefore returns data, EOF, or an error without
                        // stranding teardown in a fresh blocking syscall.
                        val n = posixRead(masterFd, buf, bufSize.convert())
                        when {
                            n < 0 -> {
                                if (errno == EINTR) continue
                                break // EIO (slave closed) or a real error -> treat as EOF
                            }
                            n == 0L -> break // clean EOF: the last slave fd was closed
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

    /** Wake the reader's blocking [poll] without closing or replacing [masterFd]. */
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

    /**
     * Release the master descriptor while recording the reader state at that exact operation.
     *
     * Keeping the observation and [posixClose] in one helper is load-bearing for the integration
     * check: reordering the release necessarily reorders its snapshot too.
     */
    private fun releaseMasterFd() {
        readerCompletedBeforeMasterFdRelease = readerJob.isCompleted
        val rc = posixClose(masterFd)
        val code = if (rc == 0) 0 else errno
        traceClose(
            "master-fd-released",
            "readerCompleted=$readerCompletedBeforeMasterFdRelease rc=$rc${errnoDetail(rc, code)}",
        )
    }

    /**
     * Emit one flush-on-write teardown marker when [CLOSE_TRACE_ENV] was set when this pty was opened.
     *
     * The trace is deliberately opt-in: normal daemon operation stays quiet, while `ptycheck` enables
     * it so a CI timeout reports the last completed syscall/lifecycle stage after the helper exits.
     */
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

    /**
     * Write [bytes] to the master fd, looping over partial writes.
     *
     * Normal return means every byte was written. If a later syscall fails after one or more successful
     * partial writes, this throws even though that prefix has already reached the pty; POSIX cannot roll
     * it back, and the exception therefore must not be interpreted as proof of zero delivery.
     */
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

    /**
     * Set the terminal window size (`ioctl(TIOCSWINSZ)` on the master fd) and then hand the child the
     * `SIGWINCH` that the kernel will NOT send for this pty.
     *
     * That signal is load-bearing, not belt-and-braces. `TIOCSWINSZ` raises `SIGWINCH` on the tty's
     * **foreground process group**, and this pty has none: under `POSIX_SPAWN_SETSID` the child is a
     * fresh session leader, but it acquires the pts through a posix_spawn **file action**, and the
     * kernel's file-action open does not run `open(2)`'s implicit `TIOCSCTTY` — so the child never
     * takes the pts as its controlling terminal (`ps` reports `TT ??`, and no process owns the pts)
     * and there is no pgrp to signal. Verified on macOS 15: without this `kill`, a resize applied
     * while `tmux attach` is already RUNNING is silently lost — only a size set *before* the child
     * reads `TIOCGWINSZ` at startup ever took effect, which is why a freshly attached terminal used
     * to sit at the pty's birth size until it was detached and re-attached.
     *
     * The signal goes to the child's process **group** (== its pid under `POSIX_SPAWN_SETSID`), which
     * is what the kernel's foreground-pgrp delivery would have done. Failures are ignored: a child
     * that already exited is `ESRCH`, and a duplicate `SIGWINCH` would be harmless anyway (readers
     * respond by re-reading `TIOCGWINSZ`).
     */
    fun resize(cols: Int, rows: Int) {
        val rc = kotgent_set_winsize(masterFd, rows.toUShort(), cols.toUShort())
        if (rc != 0) throw PtyException("resize (TIOCSWINSZ) failed: ${errnoMessage(errno)}")
        // Guarded on the child still being ours to signal: once reaped, the pid (hence the pgid) can be
        // recycled by an unrelated process group.
        if (!reaped && pid > 1) kill(-pid, SIGWINCH)
    }

    /** Block until the child exits and return its exit code (128 + signal if killed). */
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
                    // ECHILD or similar: nothing to reap.
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

    /**
     * Poll-wait up to [micros] microseconds for the child to exit without blocking indefinitely
     * (`waitpid(WNOHANG)`). Returns `true` once the child is reaped (records its exit code), `false`
     * if it is still alive after the deadline. Used by [prepareClose] to bound its wait so it can never
     * deadlock a caller's lock (e.g. the Broadcaster's) on a child that ignores SIGTERM.
     */
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
                            return true // ECHILD or similar: nothing to reap
                        }
                    }
                    r != 0 -> {
                        exitCode = decodeStatus(status.value)
                        reaped = true
                        traceClose("reap-grace-complete", "polls=$polls exitCode=$exitCode")
                        return true
                    }
                }

                // `usleep(n)` may return much later than n on a loaded/virtualized macOS host.
                // Compare against the monotonic wall clock after every poll; summing the requested
                // sleeps made this nominal two-second grace take 7–10+ seconds and race CI's tripwire.
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

    /**
     * Terminate and reap the child without closing [masterFd]. This is the first half of [close], split
     * out so a caller can make a blocking master write return before waiting for exclusive ownership of
     * the fd. Termination is escalated and BOUNDED: SIGTERM first, then [reapBounded], then SIGKILL.
     * The child closing its slave makes a blocked master write fail/finish, while the raw fd remains
     * valid and cannot be reused until [close]. Idempotent.
     */
    fun prepareClose() {
        if (closeClaimed.load() != 0 || reaped) return
        terminateAndReapChild()
    }

    /** The child-teardown half shared by [prepareClose] and the winning [close] caller. */
    private fun terminateAndReapChild() {
        if (!reaped) {
            val termRc = kill(pid, SIGTERM)
            val termErrno = if (termRc == 0) 0 else errno
            traceClose("signal-term", "rc=$termRc${errnoDetail(termRc, termErrno)}")
            if (!reapBounded(CLOSE_GRACE_MICROS)) {
                val killRc = kill(pid, SIGKILL)
                val killErrno = if (killRc == 0) 0 else errno
                traceClose("signal-kill", "rc=$killRc${errnoDetail(killRc, killErrno)}")
                waitFor() // SIGKILL is uncatchable — this reaps promptly
            }
        } else {
            traceClose("child-already-reaped", "exitCode=$exitCode")
        }
    }

    /**
     * Terminate/reap the child, stop and JOIN the reader, then release [masterFd]. Returns the child's
     * exit code. Idempotent.
     *
     * The order is load-bearing. Closing the master first does wake Darwin's blocked `read`, but it
     * also frees the descriptor NUMBER before that reader exits; another session can reuse the number
     * and the stale reader can consume its bytes. Moving `readerScope.cancel()` before that close is
     * not a fix: coroutine cancellation cannot interrupt a thread parked in a C syscall, so teardown
     * hangs. Atomically replacing the master with `dup2(/dev/null, masterFd)` looks tempting too, but
     * on Darwin `dup2` itself blocks while another thread is reading the target pty (measured here).
     *
     * The reader therefore waits on the master plus a private wake pipe. Teardown signals that pipe,
     * cancels and explicitly joins [readerJob] while [masterFd]'s number is still reserved, then closes
     * the master. [prepareClose] remains separate for Broadcaster's close-vs-write gate; calling
     * [close] directly still performs both phases for every other owner.
     *
     * A compare-and-set claims this whole sequence before child teardown starts. Concurrent and later
     * callers await the winner's [closeCompletion], so they return the same post-reap exit code and can
     * never repeat any descriptor close after the numbers have been recycled.
     */
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

    /**
     * Decode a macOS wait-status word: low 7 bits = terminating signal (0 if exited normally),
     * bits 8..15 = exit code when exited normally. A killed child reports `128 + signal`.
     */
    private fun decodeStatus(s: Int): Int =
        if (s and 0x7f == 0) (s shr 8) and 0xff else 128 + (s and 0x7f)

    companion object {
        /** Set to `1` before [open] to emit close-stage diagnostics to stderr for that pty. */
        const val CLOSE_TRACE_ENV: String = "KOTGENT_PTY_CLOSE_TRACE"

        /** Grace period (µs) after SIGTERM before [close] escalates to SIGKILL. */
        private const val CLOSE_GRACE_MICROS: Long = 2_000_000L

        /** Maximum sleep between non-blocking child-state polls. */
        private const val REAP_POLL_MICROS: Long = 5_000L

        /** Buffer size for the resolved pts path (well above macOS `PATH_MAX`/pts names). */
        private const val PTS_PATH_CAP: Int = 1024

        /**
         * Open a pty and spawn [command] on its slave side.
         *
         * @param command argv; command[0] is the executable path (resolved as-is, not via PATH).
         * @param env    child environment (empty = an empty environment).
         * @param cols   initial terminal columns.
         * @param rows   initial terminal rows.
         */
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

                // Initial window size on the master; the slave inherits it.
                kotgent_set_winsize(master, rows.toUShort(), cols.toUShort())

                // Resolve the slave's pts path so the CHILD opens the tty itself rather than inheriting
                // a dup of our slave fd (see the file actions below).
                val ptsBuf = allocArray<ByteVar>(PTS_PATH_CAP)
                if (kotgent_ptsname(master, ptsBuf, PTS_PATH_CAP.convert()) != 0) {
                    posixClose(master)
                    posixClose(slave)
                    throw PtyException("ptsname failed: ${errnoMessage(errno)}")
                }
                val ptsPath = ptsBuf.toKString()

                // File actions. The child OPENS the slave by its pts path as fd 0 and wires that same
                // tty to stdout/stderr, rather than getting a dup2 of our *inherited* slave fd: with
                // only dup2, `tmux attach` fails ("open terminal failed: not a terminal") and exits
                // immediately. NOTE this does NOT give the child a controlling terminal — the kernel
                // runs the file-action open without open(2)'s implicit TIOCSCTTY, so the pts ends up
                // with no session and no foreground pgrp (`ps` shows `TT ??` for the child). Nothing
                // here needs one, but it means TIOCSWINSZ raises no SIGWINCH, so [resize] sends the
                // signal itself — see its KDoc.
                // Finally drop the inherited master/slave fds so closing our master yields a clean EOF.
                // Each posix_spawn_file_actions_* / posix_spawnattr_* call returns an errno on failure;
                // ignoring them would feed a half-built structure to posix_spawn (fd leaks / a child with
                // no controlling tty). Check each, and on failure destroy what was inited + close the fds
                // + surface the error.
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
                // POSIX_SPAWN_SETSID: child calls setsid(), detaching from our session.
                // POSIX_SPAWN_CLOEXEC_DEFAULT (Apple extension, <sys/spawn.h>): close EVERY inherited
                // descriptor at exec except the ones named in the file actions above — i.e. the child
                // gets exactly the pts wired to 0/1/2 and nothing else. Without it a `tmux attach`
                // spawned here inherits the daemon's whole descriptor table, listening socket included;
                // an agent living on inside tmux then keeps that socket open after the daemon dies,
                // blocking rebinds and swallowing client connections (see io.kotgent.sys.markOpenFdsCloexec).
                // Unlike the popen path's sweep this is race-free: the closing happens atomically at exec.
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

                // Marshal argv into native memory BEFORE the spawn.
                val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
                command.forEachIndexed { i, arg -> argv[i] = arg.cstr.getPointer(scope) }
                argv[command.size] = null

                // Marshal envp likewise.
                val envEntries = env.map { (k, v) -> "$k=$v" }
                val envp = allocArray<CPointerVar<ByteVar>>(envEntries.size + 1)
                envEntries.forEachIndexed { i, e -> envp[i] = e.cstr.getPointer(scope) }
                envp[envEntries.size] = null

                val pidVar = alloc<IntVar>()
                val rc = posix_spawn(
                    pidVar.ptr,
                    command[0], // cinterop maps the const char* path param to String?
                    fileActions.ptr,
                    attr.ptr,
                    argv,
                    envp,
                )

                // The spawn is decided; release both spawn objects, keeping their results — a destroy
                // failure means a spawn object leaked in THIS process and must not vanish.
                val faDestroy = posix_spawn_file_actions_destroy(fileActions.ptr)
                val attrDestroy = posix_spawnattr_destroy(attr.ptr)
                // Parent has no use for the slave; the child holds its own dup'd copies.
                posixClose(slave)

                if (rc != 0) {
                    posixClose(master)
                    // The spawn FAILED, so there is no child to protect: this path throws anyway, and the
                    // cleanup notes ride along on the primary error exactly like the setup paths above.
                    val cleanup = cleanupNote(FILE_ACTIONS_DESTROY, faDestroy) + cleanupNote(ATTR_DESTROY, attrDestroy)
                    // posix_spawn returns the errno value directly (it does not set errno).
                    throw PtyException(
                        "posix_spawn failed for '${command[0]}': ${errnoMessage(rc)} (code=$rc)$cleanup",
                    )
                }

                // Success: the child is running and must not be leaked by an exception, so a destroy
                // failure can only be reported — stderr, never swallowed.
                warnCleanupFailure(FILE_ACTIONS_DESTROY, faDestroy)
                warnCleanupFailure(ATTR_DESTROY, attrDestroy)

                // The child is already spawned, so it cannot inherit this private wake pipe. Future
                // kotgent spawn paths close all unnamed descriptors (CLOEXEC_DEFAULT / fd sweep).
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

        /** Names of the spawn-object destructors, used in both the appended note and the stderr warning. */
        private const val FILE_ACTIONS_DESTROY: String = "posix_spawn_file_actions_destroy"
        private const val ATTR_DESTROY: String = "posix_spawnattr_destroy"

        /**
         * A suffix describing a failed cleanup call ([rc] != 0), or `""`. Appended to the PRIMARY error's
         * message on the throwing paths: a destroy failure must not mask why the spawn setup failed, but
         * it must not vanish either (it means a spawn object leaked).
         */
        private fun cleanupNote(what: String, rc: Int): String =
            if (rc == 0) "" else " [cleanup: $what failed: ${errnoMessage(rc)} (code=$rc)]"

        /** Report a failed cleanup call on stderr — the success path, where throwing is not an option. */
        private fun warnCleanupFailure(what: String, rc: Int) {
            if (rc == 0) return
            fputs("kotgent: $what failed: ${errnoMessage(rc)} (code=$rc)\n", stderr)
            fflush(stderr)
        }

        /** Best-effort cleanup after a post-spawn reader setup failure; never leak the live child. */
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

        /** Add errno only for a failed POSIX call; successful calls must not report stale thread errno. */
        private fun errnoDetail(rc: Int, code: Int): String =
            if (rc == 0) "" else " errno=$code (${errnoMessage(code)})"
    }
}
