package io.kotgent.host

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.open
import platform.posix.read
import platform.posix.stat

/** Read only regular files, checking the opened descriptor so replacing the path cannot bypass the check. */
@OptIn(ExperimentalForeignApi::class)
fun readRegularFileBytesOrNull(path: String): ByteArray? {
    // A FIFO must not block in open before we can discover that it is not a regular file.
    val fd = open(path, O_RDONLY or O_NONBLOCK or O_CLOEXEC)
    if (fd < 0) return null
    try {
        val size = memScoped {
            val metadata = alloc<stat>()
            if (fstat(fd, metadata.ptr) != 0 ||
                (metadata.st_mode.toInt() and S_IFMT.toInt()) != S_IFREG.toInt()
            ) return null
            metadata.st_size
        }
        if (size < 0 || size > Int.MAX_VALUE.toLong()) return null
        val bytes = ByteArray(size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = bytes.usePinned { read(fd, it.addressOf(offset), (bytes.size - offset).convert()) }
            when {
                count < 0 -> if (errno != EINTR) return null
                count == 0L -> return null
                else -> offset += count.toInt()
            }
        }
        return bytes
    } finally {
        close(fd)
    }
}
