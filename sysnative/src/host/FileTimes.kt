package io.kotgent.host

import io.kotgent.cinterop.pty.kotgent_file_times
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value

data class FileTimes(val modifiedMillis: Long, val birthMillis: Long?) {
    val birthOrModifiedMillis: Long get() = birthMillis ?: modifiedMillis
}

@OptIn(ExperimentalForeignApi::class)
fun fileTimes(path: String): FileTimes? = memScoped {
    val modified = alloc<LongVar>()
    val birth = alloc<LongVar>()
    if (kotgent_file_times(path, modified.ptr, birth.ptr) != 0) return@memScoped null
    FileTimes(modified.value, birth.value.takeIf { it > 0 })
}
