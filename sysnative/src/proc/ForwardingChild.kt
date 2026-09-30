package io.kotgent.proc

import io.kotgent.cinterop.pty.kotgent_forward_install
import io.kotgent.cinterop.pty.kotgent_forward_restore
import io.kotgent.cinterop.pty.kotgent_forward_to
import io.kotgent.cinterop.pty.kotgent_spawn_inheriting
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.EINTR
import platform.posix.errno
import platform.posix.fflush
import platform.posix.strerror
import platform.posix.waitpid

class ChildProcessException(message: String) : RuntimeException(message)

/**
 * Runs a command in the foreground on behalf of the caller: it shares the caller's stdio, cwd, environment
 * and process group, inherits no other descriptor, and receives every SIGINT, SIGTERM and SIGHUP the
 * caller receives while it runs. `posix_spawn` only, never fork-without-exec, which is unsafe for the
 * Kotlin/Native runtime.
 */
@OptIn(ExperimentalForeignApi::class)
object ForwardingChild {

    /** `argv[0]` is resolved through `PATH`. Returns the exit status, or `128 + signal` for a killed child. */
    fun run(argv: List<String>): Int {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        // Buffered output written before the spawn must not appear after the child's.
        val _ = fflush(null)
        try {
            if (kotgent_forward_install() != 0) {
                throw ChildProcessException("cannot install signal forwarding: ${errnoMessage(errno)}")
            }
            val pid = spawn(argv)
            kotgent_forward_to(pid)
            return waitFor(pid)
        } finally {
            kotgent_forward_restore()
        }
    }

    private fun spawn(argv: List<String>): Int = memScoped {
        val scope = this
        val cArgv = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
        argv.forEachIndexed { i, arg -> cArgv[i] = arg.cstr.getPointer(scope) }
        cArgv[argv.size] = null
        val pid = alloc<IntVar>()
        val actionsCleanup = alloc<IntVar>()
        val attrCleanup = alloc<IntVar>()
        val rc = kotgent_spawn_inheriting(pid.ptr, cArgv, actionsCleanup.ptr, attrCleanup.ptr)
        if (rc != 0) throw ChildProcessException("cannot run '${argv[0]}': ${errnoMessage(rc)}")
        pid.value
    }

    private fun waitFor(pid: Int): Int = memScoped {
        val status = alloc<IntVar>()
        while (waitpid(pid, status.ptr, 0) == -1) {
            val code = errno
            if (code != EINTR) throw ChildProcessException("waitpid failed: ${errnoMessage(code)}")
        }
        val s = status.value
        if (s and 0x7f == 0) (s shr 8) and 0xff else 128 + (s and 0x7f)
    }

    private fun errnoMessage(code: Int): String = strerror(code)?.toKString() ?: "errno=$code"
}
