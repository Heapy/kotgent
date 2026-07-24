package io.kotgent.push

import io.kotgent.tmux.ProcessResult
import io.kotgent.transport.readFileBytesOrNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.fclose
import platform.posix.chmod
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.stat
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [VapidKey], driven against a throwaway `$TMPDIR` PEM so the real `~/.kotgent/vapid.pem` is
 * never touched, and against the REAL `/usr/bin/openssl` — the key has to be a genuine P-256 key, so a
 * fake runner would only prove that our own string plumbing agrees with itself. The error paths (a
 * non-zero openssl, an empty stdout) use a fake runner, which is the only way to provoke them
 * deterministically.
 *
 * If the system openssl is missing the openssl-backed tests skip-guard and return (the [TmuxTest] idiom),
 * so the suite stays green on a host without it; the pure [publicPointFromSpki] cases always run.
 */
@OptIn(ExperimentalForeignApi::class)
class VapidKeyTest {

    private val keyPath: String = run {
        val dir = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        "$dir/kotgent-vapidtest-${getpid()}.pem"
    }

    /** True when the pinned system openssl exists — the tests that actually generate a key need it. */
    private fun opensslAvailable(): Boolean =
        readFileBytesOrNull(VapidKey.DEFAULT_OPENSSL_PATH, limit = 1) != null

    @BeforeTest
    fun setUp() {
        unlink(keyPath)
    }

    @AfterTest
    fun cleanup() {
        unlink(keyPath)
    }

    private fun writeFile(path: String, text: String) {
        val fp = fopen(path, "wb") ?: error("cannot open $path for write")
        fputs(text, fp)
        fclose(fp)
    }

    /** The nine `rwxrwxrwx` permission bits of [path], or `null` if it cannot be `stat`ed. */
    private fun fileMode(path: String): Int? = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) null else st.st_mode.toInt() and 0b111_111_111
    }

    private fun runnerReturning(result: ProcessResult): (List<String>) -> ProcessResult = { result }

    @Test
    fun generatesA0600KeyOnceAndReusesIt() = runBlocking {
        withTimeout(20_000) {
            if (!opensslAvailable()) return@withTimeout
            val key = VapidKey(keyPath = keyPath)

            val point = key.publicPoint()
            assertEquals(P256_POINT_LENGTH, point.size, "the VAPID public key is a 65-byte P-256 point")
            assertEquals(UNCOMPRESSED_POINT_TAG, point[0], "the point is uncompressed (0x04)")

            // The private key must never be world-readable: openssl's own -out would leave it 0644,
            // which is exactly why the PEM comes back on stdout and WE persist it.
            assertEquals(0b110_000_000, fileMode(keyPath), "vapid.pem is 0600")

            val pem = readFileBytesOrNull(keyPath) ?: error("no PEM was persisted")
            assertTrue(
                pem.decodeToString().contains("-----BEGIN"),
                "what we persisted is the PEM openssl printed",
            )

            // A re-mint would silently kill every existing browser subscription (which cannot
            // re-subscribe without a user gesture), so a second read must return the SAME key…
            assertContentEquals(point, key.publicPoint(), "the cached point is stable")
            // …including from a fresh instance, i.e. across daemon restarts.
            assertContentEquals(
                point,
                VapidKey(keyPath = keyPath).publicPoint(),
                "an existing vapid.pem is adopted, never regenerated",
            )
            assertContentEquals(pem, readFileBytesOrNull(keyPath), "the PEM on disk is untouched")
        }
    }

    @Test
    fun publicKeyBase64UrlEncodesThePoint() = runBlocking {
        withTimeout(20_000) {
            if (!opensslAvailable()) return@withTimeout
            val key = VapidKey(keyPath = keyPath)

            val encoded = key.publicKeyBase64Url()
            // 65 bytes → 87 unpadded base64url characters; the leading 0x04 always encodes as 'B'.
            assertEquals(87, encoded.length, "the application server key is 87 base64url characters")
            assertTrue(encoded.startsWith("B"), "the encoded point starts with the 0x04 tag")
            assertTrue(
                encoded.none { it == '+' || it == '/' || it == '=' },
                "the key is URL-safe and unpadded — a push service rejects anything else",
            )
            assertEquals(encoded, key.publicKeyBase64Url(), "encoding is stable across calls")
        }
    }

    @Test
    fun ensureKeyFileCreatesThePemAndIsIdempotent() = runBlocking {
        withTimeout(20_000) {
            if (!opensslAvailable()) return@withTimeout
            assertNull(readFileBytesOrNull(keyPath), "precondition: no key file yet")

            val key = VapidKey(keyPath = keyPath)
            assertEquals(keyPath, key.ensureKeyFile(), "ensureKeyFile hands back the path it guarantees")
            val pem = readFileBytesOrNull(keyPath) ?: error("no PEM was persisted")

            assertEquals(keyPath, key.ensureKeyFile(), "a second call is a no-op")
            assertContentEquals(pem, readFileBytesOrNull(keyPath), "the existing key is left alone")
        }
    }

    @Test
    fun anExistingPemIsReHardenedTo0600BeforeUse() = runBlocking {
        withTimeout(20_000) {
            writeFile(
                keyPath,
                "-----BEGIN EC PRIVATE KEY-----\nexisting\n-----END EC PRIVATE KEY-----\n",
            )
            assertEquals(0, chmod(keyPath, 0b110_100_100.convert()), "precondition: chmod existing PEM to 0644")
            assertEquals(0b110_100_100, fileMode(keyPath), "precondition: the PEM is group/world-readable")

            assertEquals(keyPath, VapidKey(keyPath = keyPath).ensureKeyFile())

            assertEquals(0b110_000_000, fileMode(keyPath), "adopting an old PEM restores the 0600 invariant")
        }
    }

    @Test
    fun anEmptyPemFailsLoudlyInsteadOfBeingSilentlyReplaced() = runBlocking {
        withTimeout(20_000) {
            writeFile(keyPath, "")
            // Truncated by a crashed writer or a full disk. Overwriting it could destroy a key whose
            // subscriptions are still live, and using it would fail only at send time — so it throws.
            val failure = assertFailsWith<VapidKeyException> {
                VapidKey(keyPath = keyPath).publicPoint()
            }
            assertTrue(failure.message!!.contains(keyPath), "the message names the offending file")
            assertTrue(failure.message!!.contains("empty"), "the message says what is wrong: ${failure.message}")
        }
    }

    @Test
    fun aNonPemFileFailsLoudly() = runBlocking {
        withTimeout(20_000) {
            writeFile(keyPath, "not a key at all\n")
            val failure = assertFailsWith<VapidKeyException> {
                VapidKey(keyPath = keyPath).ensureKeyFile()
            }
            assertTrue(
                failure.message!!.contains("not a PEM private key"),
                "the message says what is wrong: ${failure.message}",
            )
        }
    }

    @Test
    fun aCorruptPemSurfacesOpensslsOwnDiagnostic() = runBlocking {
        withTimeout(20_000) {
            if (!opensslAvailable()) return@withTimeout
            // Shaped like a PEM (so the file check passes) but not a key: only openssl can tell.
            writeFile(keyPath, "-----BEGIN EC PRIVATE KEY-----\nnot base64 at all\n-----END EC PRIVATE KEY-----\n")

            val failure = assertFailsWith<VapidKeyException> {
                VapidKey(keyPath = keyPath).publicPoint()
            }
            assertTrue(
                failure.message!!.contains("cannot read the VAPID public key"),
                "the failure is attributed to reading the key: ${failure.message}",
            )
            assertTrue(failure.message!!.contains(keyPath), "the message names the offending file")
        }
    }

    @Test
    fun aBogusOpensslPathFailsWithoutLeavingAKeyBehind() = runBlocking {
        withTimeout(20_000) {
            val key = VapidKey(keyPath = keyPath, opensslPath = "/nonexistent/bin/openssl")

            val failure = assertFailsWith<VapidKeyException> { key.ensureKeyFile() }
            assertTrue(
                failure.message!!.contains("cannot generate the VAPID keypair"),
                "the message points at generation: ${failure.message}",
            )
            // Push is simply unavailable; nothing half-written is left for the next start to adopt.
            assertNull(readFileBytesOrNull(keyPath), "a failed generation persists no key file")
        }
    }

    @Test
    fun aNonZeroOpensslIsReportedWithItsStderr() = runBlocking {
        withTimeout(20_000) {
            val key = VapidKey(
                keyPath = keyPath,
                runner = runnerReturning(
                    ProcessResult(1, ByteArray(0), "unknown curve name\n".encodeToByteArray()),
                ),
            )
            val failure = assertFailsWith<VapidKeyException> { key.ensureKeyFile() }
            assertTrue(
                failure.message!!.contains("unknown curve name"),
                "openssl's own diagnostic is what the operator needs to see: ${failure.message}",
            )
        }
    }

    @Test
    fun anOpensslThatPrintsNothingIsNotPersistedAsAKey() = runBlocking {
        withTimeout(20_000) {
            val key = VapidKey(
                keyPath = keyPath,
                runner = runnerReturning(ProcessResult(0, ByteArray(0), ByteArray(0))),
            )
            // Exit 0 with no stdout would otherwise persist an EMPTY vapid.pem that every later start
            // refuses to use — a permanently broken push path from one flaky run.
            assertFailsWith<VapidKeyException> { key.ensureKeyFile() }
            assertNull(readFileBytesOrNull(keyPath), "an empty key is never written")
        }
    }

    @Test
    fun aRunnerLevelFailureBecomesAVapidKeyException() = runBlocking {
        withTimeout(20_000) {
            // popen itself failing (fd exhaustion) must degrade like every other push problem — one
            // exception type for the route's 503, never an exception the daemon does not expect.
            val key = VapidKey(keyPath = keyPath, runner = { error("popen failed") })
            val failure = assertFailsWith<VapidKeyException> { key.ensureKeyFile() }
            assertTrue(failure.message!!.contains("popen failed"), "the cause is preserved: ${failure.message}")
        }
    }

    @Test
    fun publicPointFromSpkiTakesTheLast65Bytes() {
        val point = ByteArray(P256_POINT_LENGTH) { if (it == 0) UNCOMPRESSED_POINT_TAG else (it and 0xff).toByte() }
        val der = ByteArray(P256_SPKI_HEADER_LENGTH) { 0x2a } + point

        assertContentEquals(point, publicPointFromSpki(der), "the point is the tail of the SPKI")
    }

    @Test
    fun publicPointFromSpkiRejectsAWrongLength() {
        assertFailsWith<VapidKeyException>("a truncated SPKI must not be sliced into garbage") {
            publicPointFromSpki(ByteArray(P256_SPKI_LENGTH - 1))
        }
        assertFailsWith<VapidKeyException>("an over-long SPKI is not a P-256 key") {
            publicPointFromSpki(ByteArray(P256_SPKI_LENGTH + 1))
        }
        assertFailsWith<VapidKeyException>("empty input is not a key") { publicPointFromSpki(ByteArray(0)) }
    }

    @Test
    fun publicPointFromSpkiRejectsACompressedPoint() {
        // 0x02/0x03 is a COMPRESSED point: accepted by nothing in the Web Push stack, and a silent
        // pass here would surface as an opaque rejection from Apple much later.
        val der = ByteArray(P256_SPKI_HEADER_LENGTH) { 0x2a } + ByteArray(P256_POINT_LENGTH).also { it[0] = 0x03 }

        val failure = assertFailsWith<VapidKeyException> { publicPointFromSpki(der) }
        assertTrue(failure.message!!.contains("0x03"), "the message reports the tag it saw: ${failure.message}")
    }
}
