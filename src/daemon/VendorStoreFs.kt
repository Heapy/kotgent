package io.kotgent.daemon

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_SET
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.opendir
import platform.posix.readdir

/*
 * Thin POSIX filesystem helpers shared by the vendor-store scans — Codex's rollout walking
 * ([CodexRolloutScan]) and Claude's `projects` scan ([claudeSessionLocator]). Vendor-agnostic on
 * purpose: nothing here knows a file format, only how to list a directory and read a bounded head.
 * Like the rest of the vendor-store edge, everything degrades to `null`/empty on filesystem trouble —
 * never an exception into the daemon. Public rather than `internal` because toolchain 0.11 gives tests
 * no friend-module visibility.
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
