@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.kotgent.host

import io.kotgent.cinterop.pty.kotgent_openat_read
import io.kotgent.cinterop.pty.kotgent_at_file_kind
import io.kotgent.cinterop.pty.kotgent_readlinkat
import kotlinx.cinterop.*
import platform.posix.*

enum class WorktreeFileKind { regular, symlink, missing, unreadable, special, limited, unsafePath }

data class WorktreeFileRead(
    val kind: WorktreeFileKind,
    val bytes: ByteArray = byteArrayOf(),
    val executable: Boolean = false,
)

/** Canonicalize without replacement-decoding unsupported native path bytes. */
fun canonicalWorktreePath(path: String): String? {
    if ('\u0000' in path) return null
    val resolved = realpath(path, null) ?: return null
    return try {
        try { resolved.readBytes(strlen(resolved).toInt()).decodeToString(throwOnInvalidSequence = true) }
        catch (_: CharacterCodingException) { null }
    } finally { free(resolved) }
}

/** Whitelist HOME only; Git callers must not inherit arbitrary GIT_* variables. */
fun worktreeUserHome(): String? = getenv("HOME")?.toKString()

/**
 * Read at most maxBytes under an opened root. Every relative component is opened with O_NOFOLLOW;
 * descriptors pin traversed directories, so renaming/replacing a path cannot redirect later opens.
 * Symlinks return their target bytes as metadata, never target content. Special files never block.
 * This is a bounded observation, not an atomic snapshot against concurrent writers.
 */
fun readWorktreeFile(root: String, relativePath: String, maxBytes: Int): WorktreeFileRead {
    require(maxBytes in 0..<Int.MAX_VALUE)
    val parts = relativePath.split('/')
    if (!root.startsWith('/') || '\u0000' in root || '\u0000' in relativePath ||
        parts.any { it.isEmpty() || it == "." || it == ".." }) return WorktreeFileRead(WorktreeFileKind.unsafePath)
    var directory = open(root, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC or O_NONBLOCK)
    if (directory < 0) return WorktreeFileRead(if (errno == ENOENT) WorktreeFileKind.missing else WorktreeFileKind.unreadable)
    try {
        for (part in parts.dropLast(1)) {
            val next = kotgent_openat_read(directory, part, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC or O_NONBLOCK)
            if (next < 0) return WorktreeFileRead(when (errno) {
                ENOENT -> WorktreeFileKind.missing
                ELOOP, ENOTDIR -> WorktreeFileKind.unsafePath
                else -> WorktreeFileKind.unreadable
            })
            close(directory)
            directory = next
        }
        val name = parts.last()
        return memScoped {
            val metadata = alloc<stat>()
            val kind = kotgent_at_file_kind(directory, name)
            if (kind < 0) {
                return@memScoped WorktreeFileRead(if (errno == ENOENT) WorktreeFileKind.missing else WorktreeFileKind.unreadable)
            }
            if (kind == 2) {
                val bytes = ByteArray(minOf(maxBytes + 1, 64 * 1024))
                val count = bytes.usePinned { kotgent_readlinkat(directory, name, it.addressOf(0), bytes.size.convert()) }
                return@memScoped when {
                    count < 0 -> WorktreeFileRead(WorktreeFileKind.unreadable)
                    count > maxBytes || count == bytes.size.toLong() -> WorktreeFileRead(WorktreeFileKind.limited)
                    else -> WorktreeFileRead(WorktreeFileKind.symlink, bytes.copyOf(count.toInt()))
                }
            }
            if (kind != 1) return@memScoped WorktreeFileRead(WorktreeFileKind.special)
            val fd = kotgent_openat_read(directory, name, O_RDONLY or O_NOFOLLOW or O_CLOEXEC or O_NONBLOCK)
            if (fd < 0) return@memScoped WorktreeFileRead(WorktreeFileKind.unreadable)
            try {
                if (fstat(fd, metadata.ptr) != 0) return@memScoped WorktreeFileRead(WorktreeFileKind.unreadable)
                if ((metadata.st_mode.toInt() and S_IFMT.toInt()) != S_IFREG.toInt())
                    return@memScoped WorktreeFileRead(WorktreeFileKind.special)
                if (metadata.st_size > maxBytes) return@memScoped WorktreeFileRead(WorktreeFileKind.limited)
                val executable = (metadata.st_mode.toInt() and S_IXUSR.toInt()) != 0
                val bytes = ByteArray(minOf(maxBytes.toLong(), metadata.st_size.coerceAtLeast(0)).toInt() + 1)
                var size = 0
                while (size < bytes.size) {
                    val count = bytes.usePinned { read(fd, it.addressOf(size), (bytes.size - size).convert()) }
                    when {
                        count < 0 -> if (errno != EINTR) return@memScoped WorktreeFileRead(WorktreeFileKind.unreadable)
                        count == 0L -> return@memScoped WorktreeFileRead(WorktreeFileKind.regular, bytes.copyOf(size), executable)
                        else -> size += count.toInt()
                    }
                }
                WorktreeFileRead(WorktreeFileKind.limited)
            } finally { close(fd) }
        }
    } finally { close(directory) }
}
