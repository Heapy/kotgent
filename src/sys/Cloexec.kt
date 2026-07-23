package io.kotgent.sys

import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.F_SETFD
import platform.posix._SC_OPEN_MAX
import platform.posix.fcntl
import platform.posix.sysconf

/**
 * Marks every open file descriptor from [from] upwards close-on-exec, so a child process spawned
 * afterwards does not inherit it.
 *
 * ## Why this exists (the orphaned-`tmux`-holds-the-port bug)
 * The daemon's listening socket is created inside Ktor CIO, and on macOS `socket(2)` has no
 * `SOCK_CLOEXEC` — so every descriptor the daemon owns is inheritable by default. `tmux` is spawned
 * through [io.kotgent.tmux.ProcessRunner]'s `popen`, which forks with the whole descriptor table
 * intact; the spawned `tmux` **server** then daemonizes (re-parents to `init`) and outlives the
 * daemon while still holding the listening socket open. Consequences, all observed:
 *  - restarting the daemon fails with `EADDRINUSE` for as long as any such `tmux` server lives;
 *  - clients still complete a TCP handshake against that orphaned listener (the kernel queues the
 *    connection on a socket nobody will ever `accept()`), so they hang forever instead of getting an
 *    honest `connection refused`;
 *  - every agent running under that `tmux` inherits the daemon's sockets, listening one included.
 *
 * ## Why a sweep rather than flagging our own sockets
 * We never create the sockets — Ktor does, and it exposes no hook to set `FD_CLOEXEC` on them. A
 * table sweep is the one mechanism that covers descriptors opened by libraries. It runs right before
 * each spawn ([io.kotgent.tmux.ProcessRunner.run]) and once after the server binds
 * ([io.kotgent.transport.KotgentServer.start]).
 *
 * ## Residual race (deliberately accepted)
 * A connection accepted by Ktor's I/O thread *between* the sweep and the `fork` inside `popen` is
 * still inherited. That leaks a client socket, never the listening one (which is created once, at
 * bind, and flagged from then on) — so it can neither block a rebind nor swallow connections. Closing
 * that window would need an `FD_CLOEXEC`-aware socket factory inside the engine.
 *
 * Uses only stock `platform.posix` (`fcntl`), never custom cinterop, so it links into test binaries
 * too (KT-78062) and is covered by `CloexecTest`.
 *
 * @param from first descriptor to touch; defaults to [FIRST_INHERITABLE_FD], leaving stdin/stdout/stderr
 *   alone — those are meant to be inherited.
 * @return how many descriptors this call newly flagged (0 when everything was already marked).
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
fun markOpenFdsCloexec(from: Int = FIRST_INHERITABLE_FD): Int {
    var flagged = 0
    for (fd in from until fdScanLimit()) {
        val flags = fcntl(fd, F_GETFD)
        if (flags == -1) continue // not open (EBADF) — the table is sparse, so keep scanning
        if (flags and FD_CLOEXEC != 0) continue
        if (fcntl(fd, F_SETFD, flags or FD_CLOEXEC) == 0) flagged++
    }
    return flagged
}

/** stdin/stdout/stderr are inherited on purpose; the sweep starts above them. */
const val FIRST_INHERITABLE_FD: Int = 3

/**
 * Upper bound of the descriptor scan: the process's `RLIMIT_NOFILE`-derived `_SC_OPEN_MAX`, capped at
 * [MAX_FD_SCAN]. The cap keeps the sweep cheap when the limit is huge (macOS reports 10240+, and an
 * `unlimited` limit reports `Long.MAX_VALUE`) — a daemon holding a descriptor above the cap would need
 * thousands of concurrent connections, and the sweep costs one `fcntl` per slot on every `tmux` call.
 */
private fun fdScanLimit(): Int {
    val max = sysconf(_SC_OPEN_MAX)
    if (max <= 0) return MAX_FD_SCAN
    return minOf(max, MAX_FD_SCAN.toLong()).toInt()
}

/** Hard cap on descriptors scanned per sweep — see [fdScanLimit]. */
private const val MAX_FD_SCAN: Int = 4096
