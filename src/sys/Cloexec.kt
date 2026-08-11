package io.kotgent.sys

import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.F_SETFD
import platform.posix._SC_OPEN_MAX
import platform.posix.fcntl
import platform.posix.sysconf

/**
 * Ktor-created sockets are inheritable on macOS, and a daemonized tmux server would otherwise retain
 * the listener after kotgent exits. Ktor exposes no descriptor hook, so each spawn sweeps library-owned
 * descriptors. An accepted client socket can still race between the sweep and `popen`; the listener is
 * already flagged, so that residual cannot block a daemon rebind.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
fun markOpenFdsCloexec(from: Int = FIRST_INHERITABLE_FD): Int {
    var flagged = 0
    for (fd in from until fdScanLimit()) {
        val flags = fcntl(fd, F_GETFD)
        if (flags == -1) continue
        if (flags and FD_CLOEXEC != 0) continue
        if (fcntl(fd, F_SETFD, flags or FD_CLOEXEC) == 0) flagged++
    }
    return flagged
}

const val FIRST_INHERITABLE_FD: Int = 3

/**
 * Caps a per-spawn `fcntl` sweep when `_SC_OPEN_MAX` is huge. A descriptor above the cap would require
 * thousands of concurrent open descriptors.
 */
private fun fdScanLimit(): Int {
    val max = sysconf(_SC_OPEN_MAX)
    if (max <= 0) return MAX_FD_SCAN
    return minOf(max, MAX_FD_SCAN.toLong()).toInt()
}

private const val MAX_FD_SCAN: Int = 4096
