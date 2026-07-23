package io.kotgent.sys

import io.kotgent.tmux.ProcessRunner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.FD_CLOEXEC
import platform.posix.F_DUPFD
import platform.posix.F_GETFD
import platform.posix.STDOUT_FILENO
import platform.posix.close
import platform.posix.fcntl
import platform.posix.pipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression cover for the orphaned-listener bug: a `tmux` server spawned by the daemon inherited its
 * whole descriptor table — listening socket included — daemonized, and then kept the port bound after
 * the daemon was gone (full mechanism on [markOpenFdsCloexec]).
 *
 * These run in the plain test binary: [markOpenFdsCloexec] and [ProcessRunner] deliberately use only
 * stock `platform.posix`, no custom cinterop (KT-78062). The child's view of its descriptor table is
 * read back through `ls /dev/fd`, which is the same information `lsof` would report.
 */
class CloexecTest {

    @Test
    fun `marks an inherited descriptor close-on-exec`() = withHighFdPipe { fd ->
        // F_DUPFD clears FD_CLOEXEC by definition, so the duplicate starts out inheritable.
        assertEquals(0, fcntl(fd, F_GETFD) and FD_CLOEXEC, "precondition: fd $fd starts inheritable")

        markOpenFdsCloexec()

        assertTrue(fcntl(fd, F_GETFD) and FD_CLOEXEC != 0, "fd $fd should be close-on-exec after the sweep")
    }

    @Test
    fun `leaves the standard streams inheritable`() {
        markOpenFdsCloexec()

        assertEquals(
            0,
            fcntl(STDOUT_FILENO, F_GETFD) and FD_CLOEXEC,
            "stdout must stay inheritable — children are meant to get it",
        )
    }

    @Test
    fun `a spawned child does not inherit our descriptors`() = withHighFdPipe { fd ->
        val childFds = ProcessRunner.run(listOf("sh", "-c", "ls /dev/fd")).stdout
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toSet()

        // Sanity: the child really does report inherited descriptors, so an empty-ish result cannot
        // masquerade as "nothing leaked".
        assertTrue("1" in childFds, "child should report its stdout; got $childFds")
        assertTrue("$fd" !in childFds, "fd $fd leaked into the child; got $childFds")
    }

    /**
     * Run [body] with a pipe whose read end is duplicated to a descriptor at or above [HIGH_FD] — high
     * enough that neither the child's own `ls` descriptors nor anything else in the test process can
     * collide with the number we assert on. `F_DUPFD` picks the lowest free slot at or above the
     * request, so unlike `dup2` it can never close an unrelated descriptor.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun withHighFdPipe(body: (Int) -> Unit) = memScoped {
        val fds = allocArray<IntVar>(2)
        check(pipe(fds) == 0) { "pipe() failed" }
        val readEnd = fds[0]
        val writeEnd = fds[1]
        val high = fcntl(readEnd, F_DUPFD, HIGH_FD)
        check(high >= HIGH_FD) { "F_DUPFD failed (got $high)" }
        try {
            body(high)
        } finally {
            close(high)
            close(readEnd)
            close(writeEnd)
        }
    }

    private companion object {
        const val HIGH_FD: Int = 30
    }
}
