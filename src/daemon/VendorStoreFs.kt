package io.kotgent.daemon

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.opendir
import platform.posix.readdir

/*
 * Thin POSIX filesystem helpers shared by the vendor-store scans — Codex's rollout walking
 * ([CodexRolloutScan]), Claude's `projects` scan ([claudeSessionLocator]) and Junie's session tree
 * ([JunieSessionScan]). Vendor-agnostic on purpose: nothing here knows a file FORMAT, only how to list a
 * directory, read a bounded head/tail, and pull one field out of a JSONL record. Like the rest of the
 * vendor-store edge, everything degrades to `null`/empty on filesystem trouble — never an exception into
 * the daemon. Public rather than `internal` because toolchain 0.11 gives tests no friend-module
 * visibility.
 */

/** Entry names in [path] excluding `.`/`..`; empty if it cannot be opened. */
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

/**
 * The first [bytes] of [path] as text, or `null` if it cannot be read. What it returns is usually a
 * TRUNCATED head, which is why the pure parsers over it ([rolloutCwd], [claudeTranscriptCwd]) tolerate
 * cut-off lines. Deliberately no default window: every caller names its own bound (e.g.
 * [CodexRolloutScan.HEAD_BYTES], [CLAUDE_CWD_SCAN_BYTES]) instead of inheriting one vendor's.
 */
@OptIn(ExperimentalForeignApi::class)
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

/**
 * The LAST [bytes] of [path] as text (the whole file when it is smaller), or `null` if it cannot be read.
 *
 * The counterpart of [readHead], for a record file whose INTERESTING end is the newest one:
 * [JunieSessionScan]'s `index.jsonl` grows/rewrites by session, so a bounded head could answer with
 * nothing but long-dead sessions. A bounded tail cuts the FIRST line instead of the last, which the
 * per-line parsers already tolerate the same way (an unparseable line is skipped, never fatal).
 */
@OptIn(ExperimentalForeignApi::class)
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

/**
 * The value of the first `"<name>":"…"` field in [text], or `null` when it is absent or its value never
 * closes (a truncated read). Pure and host-free — the ONE escape-aware string-field scanner behind
 * [rolloutCwd], [claudeTranscriptCwd] and [JunieSessionScan]'s index parsing.
 *
 * Deliberately a scan rather than a JSON parse: callers read a BOUNDED window of a JSONL file, so what
 * arrives here is usually a truncated line no JSON parser would accept (a codex `session_meta` line
 * embeds the full base instructions and runs to tens of KB). JSON string escapes are unescaped, so a
 * path containing a quote or a backslash round-trips.
 */
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
                if (i + 1 >= text.length) return null // truncated mid-escape: no usable value
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
                    else -> sb.append(esc) // covers \" \\ \/ and anything else, literally
                }
                i++
            }
            else -> sb.append(c)
        }
        i++
    }
    return null // the closing quote never arrived (truncated text)
}

/**
 * The value of the first `"<name>":<digits>` field in [text], or `null` when it is absent, not an
 * integer, or does not fit a [Long]. Pure and host-free; the numeric sibling of [jsonStringField] (JSON
 * numbers carry no quotes, so the string scanner cannot read them).
 */
fun jsonLongField(text: String, name: String): Long? {
    val marker = "\"$name\":"
    val start = text.indexOf(marker)
    if (start < 0) return null
    var i = start + marker.length
    val digits = StringBuilder()
    if (i < text.length && text[i] == '-') digits.append(text[i++])
    while (i < text.length && text[i].isDigit()) digits.append(text[i++])
    // A run that ends at the very end of a bounded read may be cut mid-number, but the callers' windows
    // always contain whole records past the first line, so a partial value is not worth a special case.
    return digits.toString().toLongOrNull()
}
