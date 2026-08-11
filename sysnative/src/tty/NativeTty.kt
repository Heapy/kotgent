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

/** Main-binary-only cinterop (KT-78062); attach tests use a pure-Kotlin tty fake. */
@OptIn(ExperimentalForeignApi::class)
object NativeTty {
    fun enterRaw(fd: Int): Boolean = kotgent_tty_enter_raw(fd) == 0

    fun restore(fd: Int): Boolean = kotgent_tty_restore(fd) == 0

    fun windowSize(fd: Int): Pair<Int, Int>? = memScoped {
        val rows = alloc<UShortVar>()
        val cols = alloc<UShortVar>()
        if (kotgent_get_winsize(fd, rows.ptr, cols.ptr) != 0) return@memScoped null
        cols.value.toInt() to rows.value.toInt()
    }
}
