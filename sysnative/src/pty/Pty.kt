package io.kotgent.pty

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
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import platform.posix.EINTR
import platform.posix.O_RDWR
import platform.posix.SIGKILL
import platform.posix.SIGTERM
import platform.posix.WNOHANG
import platform.posix.errno
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.kill
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
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class Pty private constructor(
    /** The pty master file descriptor, owned by this process. */
    val masterFd: Int,
    /** The child process id. */
    val pid: Int,
) {
    /** Bytes read off the master fd, in arrival order. Closed when the child reaches EOF. */
    val output: Channel<ByteArray> = Channel(Channel.UNLIMITED)

    // Dedicated single OS thread doing blocking read() on the master fd.
    private val readerContext = newSingleThreadContext("kotgent-pty-reader-$pid")
    private val readerScope = CoroutineScope(readerContext)

    private var closed = false
    private var reaped = false
    private var exitCode = -1

    private fun startReader() {
        readerScope.launch {
            memScoped {
                val bufSize = 8192
                val buf = allocArray<ByteVar>(bufSize)
                while (isActive) {
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
            output.close()
        }
    }

    /** Write [bytes] to the master fd, looping over partial writes. */
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

    /** Set the terminal window size (ioctl TIOCSWINSZ on the master fd). */
    fun resize(cols: Int, rows: Int) {
        val rc = kotgent_set_winsize(masterFd, rows.toUShort(), cols.toUShort())
        if (rc != 0) throw PtyException("resize (TIOCSWINSZ) failed: ${errnoMessage(errno)}")
    }

    /** Block until the child exits and return its exit code (128 + signal if killed). */
    fun waitFor(): Int {
        if (reaped) return exitCode
        memScoped {
            val status = alloc<IntVar>()
            while (true) {
                val r = waitpid(pid, status.ptr, 0)
                if (r == -1) {
                    if (errno == EINTR) continue
                    // ECHILD or similar: nothing to reap.
                    reaped = true
                    exitCode = -1
                    return exitCode
                }
                break
            }
            exitCode = decodeStatus(status.value)
            reaped = true
        }
        return exitCode
    }

    /**
     * Poll-wait up to [micros] microseconds for the child to exit without blocking indefinitely
     * (`waitpid(WNOHANG)`). Returns `true` once the child is reaped (records its exit code), `false`
     * if it is still alive after the deadline. Used by [close] to bound its wait so it can never
     * deadlock a caller's lock (e.g. the Broadcaster's) on a child that ignores SIGTERM.
     */
    private fun reapBounded(micros: Long): Boolean {
        memScoped {
            val status = alloc<IntVar>()
            var waited = 0L
            val stepMicros = 5_000L // 5 ms
            while (waited < micros) {
                val r = waitpid(pid, status.ptr, WNOHANG)
                when {
                    r == -1 -> {
                        if (errno == EINTR) continue
                        reaped = true; exitCode = -1; return true // ECHILD: nothing to reap
                    }
                    r != 0 -> { exitCode = decodeStatus(status.value); reaped = true; return true }
                    else -> { usleep(stepMicros.convert()); waited += stepMicros }
                }
            }
        }
        return false
    }

    /**
     * Terminate the child (if still alive), reap it, close the master fd and stop the
     * reader thread. Returns the child's exit code. Idempotent.
     *
     * Termination is escalated and BOUNDED: SIGTERM first (so the slave closes and the reader sees
     * a clean EOF rather than us yanking the fd from under a blocked read), then a bounded poll-wait,
     * and finally SIGKILL if the child ignores SIGTERM — so this never blocks forever even though it
     * may run under a caller's lock.
     */
    fun close(): Int {
        if (closed) return exitCode
        closed = true
        if (!reaped) {
            kill(pid, SIGTERM)
            if (!reapBounded(CLOSE_GRACE_MICROS)) {
                kill(pid, SIGKILL)
                waitFor() // SIGKILL is uncatchable — this reaps promptly
            }
        }
        posixClose(masterFd)
        readerScope.cancel()
        readerContext.close()
        return exitCode
    }

    /**
     * Decode a macOS wait-status word: low 7 bits = terminating signal (0 if exited normally),
     * bits 8..15 = exit code when exited normally. A killed child reports `128 + signal`.
     */
    private fun decodeStatus(s: Int): Int =
        if (s and 0x7f == 0) (s shr 8) and 0xff else 128 + (s and 0x7f)

    companion object {
        /** Grace period (µs) after SIGTERM before [close] escalates to SIGKILL. */
        private const val CLOSE_GRACE_MICROS: Long = 2_000_000L

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

                // Resolve the slave's pts path so the CHILD can open it itself and thereby acquire
                // it as its controlling terminal (see the file actions below).
                val ptsBuf = allocArray<ByteVar>(PTS_PATH_CAP)
                if (kotgent_ptsname(master, ptsBuf, PTS_PATH_CAP.convert()) != 0) {
                    posixClose(master)
                    posixClose(slave)
                    throw PtyException("ptsname failed: ${errnoMessage(errno)}")
                }
                val ptsPath = ptsBuf.toKString()

                // File actions. CONTROLLING TERMINAL: under POSIX_SPAWN_SETSID the child is a fresh
                // session leader with no controlling tty, and the FIRST tty it opens (without
                // O_NOCTTY) becomes its controlling terminal. A dup2 of an *inherited* slave fd does
                // NOT do this — so we have the child OPEN the slave by its pts path as fd 0, then
                // wire that same tty to stdout/stderr. `tmux attach` requires a controlling tty; with
                // only dup2 it fails ("open terminal failed: not a terminal") and exits immediately.
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
                val setFlags = posix_spawnattr_setflags(attr.ptr, POSIX_SPAWN_SETSID.toShort())
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
                return Pty(master, pidVar.value).also { it.startReader() }
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

        private fun errnoMessage(code: Int): String =
            strerror(code)?.toKString() ?: "errno=$code"
    }
}
