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
        chflags(path, 0.convert()) // drop any immutable flag a test set, so the unlink succeeds
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
    fun readOrCreateTokenRefusesATokenItCannotHarden() {
        unlink(path)
        readOrCreateToken(path) // creates it 0600
        // Leave the token group/world-readable AND make it un-chmod-able (the macOS user-immutable flag
        // makes chmod fail with EPERM even for the owner). The token gates the whole local control plane,
        // so silently proceeding with a secret every local user can read would downgrade auth to "anyone
        // on this machine" — the read path must fail loudly instead.
        chmod(path, 0b110_100_100.convert()) // 0644
        if (chflags(path, UF_IMMUTABLE.convert()) != 0) return // flag unsupported here: nothing to assert
        try {
            // Precondition, so this test can never pass vacuously: the flag really does make chmod fail.
            assertTrue(chmod(path, 0b110_000_000.convert()) != 0, "precondition: chmod fails on an immutable file")
            assertFailsWith<TokenPermissionException>("an un-hardenable token must not be handed out") {
                readOrCreateToken(path)
            }
            assertEquals(0b110_100_100, fileMode(path) and 0b111_111_111, "the token is still world-readable")
        } finally {
            chflags(path, 0.convert()) // clear the flag so cleanup can unlink it
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
        // The contract every other process depends on: what this call returned IS what is on disk. A
        // creator that returned its own generated value while another writer's value landed on disk would
        // authenticate with a secret the daemon never sees — every request 401s.
        assertEquals(token, readTokenOrNull(path), "the returned token is the persisted token")
    }

    @Test
    fun createPrivateFileExclusiveKeepsTheFirstWritersValue() {
        unlink(path)
        // The primitive behind concurrent token creation: first writer wins, everyone else must adopt its
        // value rather than clobber it (a rename-based write would silently replace the winner's secret).
        assertTrue(createPrivateFileExclusive(path, "first".encodeToByteArray()), "the first writer creates the file")
        assertFalse(createPrivateFileExclusive(path, "second".encodeToByteArray()), "a later writer reports it lost")
        assertEquals("first", readTokenOrNull(path), "the winner's value is untouched")
        assertEquals(0b110_000_000, fileMode(path) and 0b111_111_111, "an exclusively created secret is 0600")
    }

    @Test
    fun aConcurrentWritersTempFileIsNeverTouched() {
        unlink(path)
        // Every writer used to stage into the SAME "$path.tmp", so concurrent writers unlinked and
        // overwrote each other's in-flight temp. A writer's temp is now unique to it: a foreign temp
        // sitting next to the target must survive a full write untouched.
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
        // A truncated leftover from a crashed writer carries no secret to preserve: exclusive creation
        // must not deadlock on it — it is replaced, and the fresh value is what gets returned.
        writeText(path, "   \n")
        val token = readOrCreateToken(path)
        assertTrue(token.isNotBlank(), "a blank token file yields a freshly generated token")
        assertEquals(token, readTokenOrNull(path), "and that token is what was persisted")
        assertEquals(0b110_000_000, fileMode(path) and 0b111_111_111, "the replacement is 0600")
    }

    @Test
    fun requireTokenMode0600RefusesAModeItCouldNotVerify() {
        // `stat` failing means the mode is UNKNOWN — not "fine". Proceeding there would hand out an
        // unverified secret exactly when the filesystem is misbehaving. (The branch is unreachable
        // through the file API — the file was just read — so the decision is asserted directly.)
        assertFailsWith<TokenPermissionException>("an unverifiable mode must not pass") {
            requireTokenMode0600("/tmp/whatever", null)
        }
        assertFailsWith<TokenPermissionException>("a group/world-readable mode must not pass") {
            requireTokenMode0600("/tmp/whatever", 0b110_100_100) // 0644
        }
        requireTokenMode0600("/tmp/whatever", 0b110_000_000) // 0600 — the only accepted mode
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

    /** Write [text] to [p] verbatim — seeds the leftover/foreign files the creation paths must handle. */
    private fun writeText(p: String, text: String) {
        val fp = fopen(p, "wb") ?: error("cannot open $p for write")
        fputs(text, fp)
        fclose(fp)
    }
}
