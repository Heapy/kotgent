package io.kotgent.pty

import io.kotgent.cinterop.pty.kotgent_get_winsize
import io.kotgent.cinterop.pty.kotgent_tty_enter_raw
import io.kotgent.cinterop.pty.kotgent_tty_restore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value

/**
 * Low-level controlling-tty operations for `kotgent attach` raw passthrough (plan Task 15), backed by
 * the `sysnative` cinterop C-helpers (`kotgent_tty_enter_raw` / `kotgent_tty_restore` /
 * `kotgent_get_winsize`). This lives in `sysnative` for the same reason [Pty] does: it touches our own
 * raw cinterop, which the toolchain links into MAIN binaries only. The app consumes it as a normal
 * module dependency (same package as [Pty]).
 *
 * ⚠️ Like [Pty], this MUST NOT be called from a unit test — our cinterop is not linked into test
 * binaries (KT-78062), so a call throws `IrLinkageError`. `kotgent attach`'s tests inject a pure-Kotlin
 * fake tty instead; the real path is exercised by the Task 18 manual acceptance run.
 */
@OptIn(ExperimentalForeignApi::class)
object NativeTty {
    /** Put [fd] into raw mode, saving the previous settings for [restore]. `true` on success. */
    fun enterRaw(fd: Int): Boolean = kotgent_tty_enter_raw(fd) == 0

    /** Restore [fd] to the settings saved by [enterRaw]. `true` on success (or if none were saved). */
    fun restore(fd: Int): Boolean = kotgent_tty_restore(fd) == 0

    /** The current window size of [fd] as `cols to rows`, or `null` if the ioctl failed. */
    fun windowSize(fd: Int): Pair<Int, Int>? = memScoped {
        val rows = alloc<UShortVar>()
        val cols = alloc<UShortVar>()
        if (kotgent_get_winsize(fd, rows.ptr, cols.ptr) != 0) return@memScoped null
        cols.value.toInt() to rows.value.toInt()
    }
}
