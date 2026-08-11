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

class CloexecTest {

    @Test
    fun `marks an inherited descriptor close-on-exec`() = withHighFdPipe { fd ->
        // F_DUPFD deliberately clears CLOEXEC, providing an inheritable precondition.
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

        assertTrue("1" in childFds, "child should report its stdout; got $childFds")
        assertTrue("$fd" !in childFds, "fd $fd leaked into the child; got $childFds")
    }

    @OptIn(ExperimentalForeignApi::class)
    // A high duplicate cannot collide with descriptors the child opens while reporting `/dev/fd`.
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
