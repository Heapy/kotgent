package io.kotgent.pty

import io.kotgent.cinterop.pty.POSIX_SPAWN_SETSID
import io.kotgent.cinterop.pty.kotgent_openpty
import io.kotgent.cinterop.pty.kotgent_set_winsize
import io.kotgent.cinterop.pty.posix_spawn
import io.kotgent.cinterop.pty.posix_spawn_file_actions_addclose
import io.kotgent.cinterop.pty.posix_spawn_file_actions_adddup2
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
import platform.posix.SIGTERM
import platform.posix.errno
import platform.posix.kill
import platform.posix.strerror
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
            val s = status.value
            // macOS wait-status layout: low 7 bits = terminating signal (0 if exited
            // normally), bits 8..15 = exit code when exited normally.
            exitCode = if (s and 0x7f == 0) (s shr 8) and 0xff else 128 + (s and 0x7f)
            reaped = true
        }
        return exitCode
    }

    /**
     * Terminate the child (if still alive), reap it, close the master fd and stop the
     * reader thread. Returns the child's exit code. Idempotent.
     */
    fun close(): Int {
        if (closed) return exitCode
        closed = true
        // Ask the child to exit so the slave side closes; the reader then sees a clean
        // EOF on the master instead of us yanking the fd out from under a blocked read().
        if (!reaped) kill(pid, SIGTERM)
        val code = waitFor()
        posixClose(masterFd)
        readerScope.cancel()
        readerContext.close()
        return code
    }

    companion object {
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

                // File actions: wire the slave as the child's stdin/stdout/stderr, then
                // drop both the extra slave fd and the master fd in the child so that
                // closing our master later produces a clean EOF.
                val fileActions = alloc<posix_spawn_file_actions_tVar>()
                posix_spawn_file_actions_init(fileActions.ptr)
                posix_spawn_file_actions_adddup2(fileActions.ptr, slave, 0)
                posix_spawn_file_actions_adddup2(fileActions.ptr, slave, 1)
                posix_spawn_file_actions_adddup2(fileActions.ptr, slave, 2)
                posix_spawn_file_actions_addclose(fileActions.ptr, slave)
                posix_spawn_file_actions_addclose(fileActions.ptr, master)
                // NOTE for Task 9 (`tmux attach`): the child there must acquire a
                // CONTROLLING terminal. The robust pattern is, under POSIX_SPAWN_SETSID
                // (child becomes a session leader), to have the child *open the slave by
                // its ptsname() path* via posix_spawn_file_actions_addopen() -- the first
                // tty a session leader opens becomes its controlling terminal -- instead
                // of only dup2'ing an inherited slave fd. /bin/cat needs no controlling
                // tty, so plain dup2 is sufficient for this spike.

                val attr = alloc<posix_spawnattr_tVar>()
                posix_spawnattr_init(attr.ptr)
                // POSIX_SPAWN_SETSID: child calls setsid(), detaching from our session.
                posix_spawnattr_setflags(attr.ptr, POSIX_SPAWN_SETSID.toShort())

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

                posix_spawn_file_actions_destroy(fileActions.ptr)
                posix_spawnattr_destroy(attr.ptr)
                // Parent has no use for the slave; the child holds its own dup'd copies.
                posixClose(slave)

                if (rc != 0) {
                    posixClose(master)
                    // posix_spawn returns the errno value directly (it does not set errno).
                    throw PtyException("posix_spawn failed for '${command[0]}': ${errnoMessage(rc)} (code=$rc)")
                }

                return Pty(master, pidVar.value).also { it.startReader() }
            }
        }

        private fun errnoMessage(code: Int): String =
            strerror(code)?.toKString() ?: "errno=$code"
    }
}
