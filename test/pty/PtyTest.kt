package io.kotgent.pty

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration tests for the PTY primitive (Task 2 cinterop spike).
 *
 * Every test that reads from the master fd is wrapped in a bounded [withTimeout] so a
 * broken round-trip fails fast instead of hanging the suite (anti-flaky requirement).
 */
class PtyTest {

    /** Receive chunks from the pty until [needle] appears in the accumulated output. */
    private suspend fun readUntil(pty: Pty, needle: String, timeoutMs: Long = 5_000): String {
        val sb = StringBuilder()
        withTimeout(timeoutMs) {
            while (needle !in sb) {
                val chunk = pty.output.receive()
                sb.append(chunk.decodeToString())
            }
        }
        return sb.toString()
    }

    @Test
    fun catEchoesRoundTrip() = runBlocking {
        val pty = Pty.open(listOf("/bin/cat"))
        try {
            pty.write("hello-kotgent\n".encodeToByteArray())
            val out = readUntil(pty, "hello-kotgent")
            assertTrue("hello-kotgent" in out, "expected the pty to echo our line, got: <$out>")
        } finally {
            pty.close()
        }
    }

    @Test
    fun resizeSucceeds() = runBlocking {
        val pty = Pty.open(listOf("/bin/cat"), cols = 80, rows = 24)
        try {
            // Must not throw; ioctl(TIOCSWINSZ) returns 0.
            pty.resize(cols = 120, rows = 40)
        } finally {
            pty.close()
        }
    }

    @Test
    fun exitCodeIsCaptured() = runBlocking {
        val pty = Pty.open(listOf("/bin/sh", "-c", "exit 7"))
        try {
            val code = withTimeout(5_000) {
                // The child exits on its own; that closes the slave, which EOFs the master
                // and closes the output channel. Draining it means the child is reapable.
                for (chunk in pty.output) { /* discard */ }
                pty.waitFor()
            }
            assertEquals(7, code, "child `sh -c 'exit 7'` should report exit code 7")
        } finally {
            pty.close()
        }
    }

    @Test
    fun spawnNonexistentCommandFails() {
        // On Darwin posix_spawn resolves an absolute path synchronously and returns
        // ENOENT, so open() throws rather than silently yielding a dead child.
        assertFailsWith<PtyException> {
            Pty.open(listOf("/nonexistent/kotgent-not-a-real-binary-xyz"))
        }
    }
}
