package io.kotgent.exe

import io.kotgent.cinterop.pty.kotgent_executable_path
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString

/**
 * The absolute path of the currently-running executable, backed by the `sysnative` cinterop helper
 * `kotgent_executable_path` (macOS `_NSGetExecutablePath` + `realpath`). `kotgent install` (plan Task 16)
 * uses it to put the running binary's real path into the LaunchAgent plist's `ProgramArguments`.
 *
 * ⚠️ Like [io.kotgent.pty.Pty] / [io.kotgent.pty.NativeTty], this touches our own raw cinterop, which the
 * toolchain links into MAIN binaries only — never a test binary (KT-78062). It is called from the real
 * `install` command; the launchd unit tests inject the binary path directly and never call this.
 */
@OptIn(ExperimentalForeignApi::class)
object NativeExe {
    /** The running binary's absolute, canonical path, or `null` if it could not be resolved. */
    fun path(): String? = memScoped {
        val cap = 4096
        val buf = allocArray<ByteVar>(cap)
        if (kotgent_executable_path(buf, cap.convert()) != 0) return@memScoped null
        buf.toKString().ifEmpty { null }
    }
}
