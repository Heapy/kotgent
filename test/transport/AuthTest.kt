package io.kotgent.transport

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.UF_IMMUTABLE
import platform.posix.chflags
import platform.posix.chmod
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class AuthTest {

    private val path: String = run {
        val dir = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        "$dir/kotgent-authtest-${getpid()}.token"
    }

    @AfterTest
    fun cleanup() {
        chflags(path, 0.convert())
        unlink(path)
    }

    @Test
    fun readOrCreateTokenCreatesA0600FileAndIsIdempotent() {
        unlink(path)

        val token = readOrCreateToken(path)
        assertTrue(token.isNotBlank(), "a token is generated")
        assertTrue(token.length >= 32, "token carries real entropy (>= 16 bytes, hex-encoded)")

        assertEquals(0b110_000_000, fileMode(path) and 0b111_111_111, "token file permissions are 0600")

        assertEquals(token, readOrCreateToken(path), "readOrCreateToken is idempotent")
        assertEquals(token, readTokenOrNull(path), "readTokenOrNull reads the same value back")
    }

    @Test
    fun readOrCreateTokenRepairsAMisPermissionedExistingFile() {
        unlink(path)
        val token = readOrCreateToken(path)
        chmod(path, 0b110_100_100.convert())
        assertEquals(0b110_100_100, fileMode(path) and 0b111_111_111, "precondition: the file is 0644")

        val reread = readOrCreateToken(path)
        assertEquals(token, reread, "the same token is read back")
        assertEquals(0b110_000_000, fileMode(path) and 0b111_111_111, "a mis-permissioned token is re-hardened to 0600 on read")
    }

    @Test
    fun readOrCreateTokenRefusesATokenItCannotHarden() {
        unlink(path)
        readOrCreateToken(path)
        chmod(path, 0b110_100_100.convert())
        // Some filesystems cannot set UF_IMMUTABLE; without it the chmod-failure precondition is absent.
        if (chflags(path, UF_IMMUTABLE.convert()) != 0) return
        try {
            assertTrue(chmod(path, 0b110_000_000.convert()) != 0, "precondition: chmod fails on an immutable file")
            assertFailsWith<TokenPermissionException>("an un-hardenable token must not be handed out") {
                readOrCreateToken(path)
            }
            assertEquals(0b110_100_100, fileMode(path) and 0b111_111_111, "the token is still world-readable")
        } finally {
            chflags(path, 0.convert())
        }
    }

    @Test
    fun readTokenOrNullOnAMissingFileIsNull() {
        unlink(path)
        assertNull(readTokenOrNull(path), "a missing token file reads as null (the daemon owns creation)")
    }

    @Test
    fun readOrCreateTokenReturnsTheValueItActuallyPersisted() {
        unlink(path)
        val token = readOrCreateToken(path)
        assertEquals(token, readTokenOrNull(path), "the returned token is the persisted token")
    }

    @Test
    fun createPrivateFileExclusiveKeepsTheFirstWritersValue() {
        unlink(path)
        assertTrue(createPrivateFileExclusive(path, "first".encodeToByteArray()), "the first writer creates the file")
        assertFalse(createPrivateFileExclusive(path, "second".encodeToByteArray()), "a later writer reports it lost")
        assertEquals("first", readTokenOrNull(path), "the winner's value is untouched")
        assertEquals(0b110_000_000, fileMode(path) and 0b111_111_111, "an exclusively created secret is 0600")
    }

    @Test
    fun aConcurrentWritersTempFileIsNeverTouched() {
        unlink(path)
        val foreignTmp = "$path.tmp"
        writeText(foreignTmp, "another writer's in-flight token")
        try {
            val token = readOrCreateToken(path)
            assertEquals(token, readTokenOrNull(path), "the token file holds what was returned")
            assertEquals(
                "another writer's in-flight token",
                readTokenOrNull(foreignTmp),
                "a concurrent writer's temp file must not be unlinked or overwritten",
            )
        } finally {
            unlink(foreignTmp)
        }
    }

    @Test
    fun readOrCreateTokenReplacesABlankTokenFile() {
        unlink(path)
        writeText(path, "   \n")
        val token = readOrCreateToken(path)
        assertTrue(token.isNotBlank(), "a blank token file yields a freshly generated token")
        assertEquals(token, readTokenOrNull(path), "and that token is what was persisted")
        assertEquals(0b110_000_000, fileMode(path) and 0b111_111_111, "the replacement is 0600")
    }

    @Test
    fun requireTokenMode0600RefusesAModeItCouldNotVerify() {
        assertFailsWith<TokenPermissionException>("an unverifiable mode must not pass") {
            requireTokenMode0600("/tmp/whatever", null)
        }
        assertFailsWith<TokenPermissionException>("a group/world-readable mode must not pass") {
            requireTokenMode0600("/tmp/whatever", 0b110_100_100)
        }
        requireTokenMode0600("/tmp/whatever", 0b110_000_000)
    }

    @Test
    fun constantTimeEqualsMatchesExactlyEqualStrings() {
        assertTrue(constantTimeEquals("abc123", "abc123"))
        assertTrue(constantTimeEquals("", ""))
        assertFalse(constantTimeEquals("abc123", "abc124"), "a single differing byte is unequal")
        assertFalse(constantTimeEquals("abc", "abcd"), "different lengths are unequal")
        assertFalse(constantTimeEquals("abcd", "abc"))
    }

    private fun fileMode(p: String): Int = memScoped {
        val st = alloc<platform.posix.stat>()
        platform.posix.stat(p, st.ptr)
        st.st_mode.toInt()
    }

    private fun writeText(p: String, text: String) {
        val fp = fopen(p, "wb") ?: error("cannot open $p for write")
        fputs(text, fp)
        fclose(fp)
    }
}
