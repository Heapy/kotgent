package io.kotgent.host

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.X_OK
import platform.posix.access
import platform.posix.errno
import platform.posix.stat
import platform.posix.strerror

/** Preflight a child working directory without changing the daemon's process-wide cwd. */
@OptIn(ExperimentalForeignApi::class)
fun workingDirectoryError(path: String): String? {
    if ('\u0000' in path) return "working directory must not contain a NUL character"
    if (!path.startsWith('/')) return "working directory must be an absolute path: $path"
    return memScoped {
        val metadata = alloc<stat>()
        when {
            stat(path, metadata.ptr) != 0 ->
                "cannot access working directory '$path': ${strerror(errno)?.toKString()}"
            (metadata.st_mode.toInt() and S_IFMT) != S_IFDIR ->
                "working directory is not a directory: $path"
            access(path, X_OK) != 0 ->
                "cannot enter working directory '$path': ${strerror(errno)?.toKString()}"
            else -> null
        }
    }
}
