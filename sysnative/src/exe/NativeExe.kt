package io.kotgent.exe

import io.kotgent.cinterop.pty.kotgent_executable_path
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString

/** Main-binary-only cinterop (KT-78062); tests must inject the executable path instead. */
@OptIn(ExperimentalForeignApi::class)
object NativeExe {
    fun path(): String? = memScoped {
        val cap = 4096
        val buf = allocArray<ByteVar>(cap)
        if (kotgent_executable_path(buf, cap.convert()) != 0) return@memScoped null
        buf.toKString().ifEmpty { null }
    }
}
