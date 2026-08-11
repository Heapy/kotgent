package io.kotgent.daemon

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.stat

// Vendor-store filesystem failures are absence, not daemon failures.
@OptIn(ExperimentalForeignApi::class)
fun isDirectory(path: String): Boolean = memScoped {
    val st = alloc<stat>()
    if (stat(path, st.ptr) != 0) return@memScoped false
    (st.st_mode.toInt() and S_IFMT) == S_IFDIR
}

@OptIn(ExperimentalForeignApi::class)
fun listDir(path: String): List<String> {
    val dir = opendir(path) ?: return emptyList()
    try {
        val names = ArrayList<String>()
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name != "." && name != "..") names.add(name)
        }
        return names
    } finally {
        closedir(dir)
    }
}

@OptIn(ExperimentalForeignApi::class)
// Callers choose a byte bound; the returned text may end mid-record.
fun readHead(path: String, bytes: Int): String? {
    val fp = fopen(path, "rb") ?: return null
    try {
        fseek(fp, 0, SEEK_SET)
        val buffer = ByteArray(bytes)
        val read = buffer.usePinned { fread(it.addressOf(0), 1.convert(), bytes.convert(), fp) }
        val n = read.toInt()
        if (n <= 0) return null
        return buffer.decodeToString(0, n)
    } finally {
        fclose(fp)
    }
}

@OptIn(ExperimentalForeignApi::class)
// The returned tail may begin mid-record; per-line parsers must tolerate it.
fun readTail(path: String, bytes: Int): String? {
    val fp = fopen(path, "rb") ?: return null
    try {
        if (fseek(fp, 0, SEEK_END) != 0) return null
        val size = ftell(fp)
        if (size <= 0L) return null
        val take = if (size < bytes.toLong()) size.toInt() else bytes
        if (fseek(fp, size - take.toLong(), SEEK_SET) != 0) return null
        val buffer = ByteArray(take)
        val read = buffer.usePinned { fread(it.addressOf(0), 1.convert(), take.convert(), fp) }
        val n = read.toInt()
        if (n <= 0) return null
        return buffer.decodeToString(0, n)
    } finally {
        fclose(fp)
    }
}

// A scanner is used because bounded JSONL windows are commonly not valid JSON documents.
fun jsonStringField(text: String, name: String): String? {
    val marker = "\"$name\":\""
    val start = text.indexOf(marker)
    if (start < 0) return null
    val from = start + marker.length
    val sb = StringBuilder()
    var i = from
    while (i < text.length) {
        when (val c = text[i]) {
            '"' -> return sb.toString()
            '\\' -> {
                if (i + 1 >= text.length) return null
                when (val esc = text[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 5 >= text.length) return null
                        val code = text.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> sb.append(esc)
                }
                i++
            }
            else -> sb.append(c)
        }
        i++
    }
    return null
}

fun jsonLongField(text: String, name: String): Long? {
    val marker = "\"$name\":"
    val start = text.indexOf(marker)
    if (start < 0) return null
    var i = start + marker.length
    val digits = StringBuilder()
    if (i < text.length && text[i] == '-') digits.append(text[i++])
    while (i < text.length && text[i].isDigit()) digits.append(text[i++])
    return digits.toString().toLongOrNull()
}
