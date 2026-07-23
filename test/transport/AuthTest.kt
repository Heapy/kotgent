package io.kotgent.transport

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.chmod
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the token/auth helpers (plan Task 14 / Technical Details): the shared token is created
 * `0600` (never briefly world-readable), read back idempotently, and secrets are compared in constant
 * time. Driven against a throwaway `$TMPDIR` path so they never touch the real `~/.kotgent/token`.
 */
@OptIn(ExperimentalForeignApi::class)
class AuthTest {

    private val path: String = run {
        val dir = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        "$dir/kotgent-authtest-${getpid()}.token"
    }

    @AfterTest
    fun cleanup() {
        unlink(path)
    }

    @Test
    fun readOrCreateTokenCreatesA0600FileAndIsIdempotent() {
        unlink(path) // ensure a clean slate

        val token = readOrCreateToken(path)
        assertTrue(token.isNotBlank(), "a token is generated")
        assertTrue(token.length >= 32, "token carries real entropy (>= 16 bytes, hex-encoded)")

        // The freshly created secret file must be 0600 — never a brief 0644 window.
        assertEquals(0b110_000_000, fileMode(path) and 0b111_111_111, "token file permissions are 0600")

        // Idempotent: a second call reads the SAME token back rather than minting a new one, so the
        // daemon, CLI and hooks all resolve one value.
        assertEquals(token, readOrCreateToken(path), "readOrCreateToken is idempotent")
        assertEquals(token, readTokenOrNull(path), "readTokenOrNull reads the same value back")
    }

    @Test
    fun readOrCreateTokenRepairsAMisPermissionedExistingFile() {
        unlink(path)
        val token = readOrCreateToken(path) // creates it 0600
        // Simulate a token file left group/other-readable by an older build.
        chmod(path, 0b110_100_100.convert()) // 0644
        assertEquals(0b110_100_100, fileMode(path) and 0b111_111_111, "precondition: the file is 0644")

        val reread = readOrCreateToken(path) // the read path must re-harden the permissions
        assertEquals(token, reread, "the same token is read back")
        assertEquals(0b110_000_000, fileMode(path) and 0b111_111_111, "a mis-permissioned token is re-hardened to 0600 on read")
    }

    @Test
    fun readTokenOrNullOnAMissingFileIsNull() {
        unlink(path)
        assertNull(readTokenOrNull(path), "a missing token file reads as null (the daemon owns creation)")
    }

    @Test
    fun constantTimeEqualsMatchesExactlyEqualStrings() {
        assertTrue(constantTimeEquals("abc123", "abc123"))
        assertTrue(constantTimeEquals("", ""))
        assertFalse(constantTimeEquals("abc123", "abc124"), "a single differing byte is unequal")
        assertFalse(constantTimeEquals("abc", "abcd"), "different lengths are unequal")
        assertFalse(constantTimeEquals("abcd", "abc"))
    }

    /** The file's permission bits (via `stat`), for the 0600 assertion. */
    private fun fileMode(p: String): Int = memScoped {
        val st = alloc<platform.posix.stat>()
        platform.posix.stat(p, st.ptr)
        st.st_mode.toInt()
    }
}
