package io.kotgent.push

import io.kotgent.tmux.ProcessResult
import io.kotgent.tmux.ProcessRunner
import io.kotgent.transport.readFileBytesOrNull
import io.kotgent.transport.writePrivateFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.closedir
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class OpensslVapidSignerTest {

    private val tmpDir: String = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')

    private val keyPath = "$tmpDir/kotgent-signtest-${getpid()}.pem"
    private val publicKeyPath = "$tmpDir/kotgent-signtest-${getpid()}.pub.pem"
    private val messagePath = "$tmpDir/kotgent-signtest-${getpid()}.msg"
    private val signaturePath = "$tmpDir/kotgent-signtest-${getpid()}.der"

    private val signingInput: String =
        vapidSigningInput(aud = "https://web.push.apple.com", exp = 1_800_000_000, sub = "mailto:a@b.c")

    private lateinit var litterBaseline: Set<String>

    @BeforeTest
    fun setUp() {
        listOf(keyPath, publicKeyPath, messagePath, signaturePath).forEach { unlink(it) }
        litterBaseline = signingInputLitter()
    }

    @AfterTest
    fun cleanup() {
        listOf(keyPath, publicKeyPath, messagePath, signaturePath).forEach { unlink(it) }
    }

    private fun opensslAvailable(): Boolean =
        readFileBytesOrNull(VapidKey.DEFAULT_OPENSSL_PATH, limit = 1) != null

    private fun signingInputLitter(): Set<String> {
        val dir = opendir(tmpDir) ?: return emptySet()
        try {
            val names = mutableSetOf<String>()
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name.startsWith(OpensslVapidSigner.SIGNING_INPUT_PREFIX)) names.add(name)
            }
            return names
        } finally {
            closedir(dir)
        }
    }

    private fun assertNoLitter(what: String) {
        assertEquals(litterBaseline, signingInputLitter(), "$what leaves no signing-input file behind")
    }

    private fun runnerReturning(result: ProcessResult): (List<String>) -> ProcessResult = { result }

    private fun rawToDer(raw: ByteArray): ByteArray {
        fun integer(value: ByteArray): ByteArray {
            var start = 0
            while (start < value.size - 1 && value[start] == ZERO) start++
            var magnitude = value.copyOfRange(start, value.size)
            if (magnitude[0].toInt() and 0x80 != 0) magnitude = byteArrayOf(0) + magnitude
            return byteArrayOf(DER_INTEGER, magnitude.size.toByte()) + magnitude
        }

        val body = integer(raw.copyOfRange(0, P256_COORDINATE_LENGTH)) +
            integer(raw.copyOfRange(P256_COORDINATE_LENGTH, P256_RAW_SIGNATURE_LENGTH))
        return byteArrayOf(DER_SEQUENCE, body.size.toByte()) + body
    }

    private fun opensslVerifies(raw: ByteArray): Boolean {
        val pub = ProcessRunner.run(listOf(VapidKey.DEFAULT_OPENSSL_PATH, "ec", "-in", keyPath, "-pubout"))
        assertTrue(pub.isSuccess, "extracting the public key failed: ${pub.stderr}")
        writePrivateFile(publicKeyPath, pub.stdoutBytes)
        writePrivateFile(messagePath, signingInput.encodeToByteArray())
        writePrivateFile(signaturePath, rawToDer(raw))

        val verify = ProcessRunner.run(
            listOf(
                VapidKey.DEFAULT_OPENSSL_PATH, "dgst", "-sha256",
                "-verify", publicKeyPath,
                "-signature", signaturePath,
                messagePath,
            ),
        )
        return verify.isSuccess && verify.stdout.contains("Verified OK")
    }

    @Test
    fun signsAnInputOpensslItselfThenVerifies() = runBlocking {
        withTimeout(30_000) {
            if (!opensslAvailable()) return@withTimeout
            VapidKey(keyPath = keyPath).ensureKeyFile()
            val signer = OpensslVapidSigner(keyPath = keyPath)

            val raw = signer.sign(signingInput)

            assertEquals(P256_RAW_SIGNATURE_LENGTH, raw.size, "ES256 wants exactly 64 bytes of r||s")
            assertTrue(opensslVerifies(raw), "an outside verifier accepts the signature we produced")
            assertNoLitter("a successful signature")
        }
    }

    @Test
    fun everySignatureIsFreshAndStillVerifies() = runBlocking {
        withTimeout(30_000) {
            if (!opensslAvailable()) return@withTimeout
            VapidKey(keyPath = keyPath).ensureKeyFile()
            val signer = OpensslVapidSigner(keyPath = keyPath)

            val first = signer.sign(signingInput)
            val second = signer.sign(signingInput)

            assertTrue(!first.contentEquals(second), "two ECDSA signatures over the same input differ")
            assertTrue(opensslVerifies(first), "the first signature verifies")
            assertTrue(opensslVerifies(second), "the second signature verifies")
            assertNoLitter("repeated signing")
        }
    }

    @Test
    fun bindsAsTheTokenCachesSignLambda() = runBlocking {
        withTimeout(30_000) {
            if (!opensslAvailable()) return@withTimeout
            VapidKey(keyPath = keyPath).ensureKeyFile()
            val cache = VapidTokenCache(subject = "mailto:a@b.c", sign = OpensslVapidSigner(keyPath)::sign)

            val jwt = cache.tokenFor("https://web.push.apple.com/12345")

            val segments = jwt.split(".")
            assertEquals(3, segments.size, "a JWT is three dot-separated segments: $jwt")
            assertEquals(86, segments[2].length, "the signature segment encodes 64 bytes")
            assertTrue(
                segments[2].none { it == '+' || it == '/' || it == '=' },
                "the JWT signature is URL-safe and unpadded",
            )
            assertNoLitter("signing through the token cache")
        }
    }

    @Test
    fun passesTheSigningInputToOpensslInATempFileAndRemovesIt() {
        var seenArgv: List<String>? = null
        var inputSeenByOpenssl: ByteArray? = null
        val signer = OpensslVapidSigner(
            keyPath = keyPath,
            opensslPath = "/usr/bin/openssl",
            runner = { argv ->
                seenArgv = argv
                inputSeenByOpenssl = readFileBytesOrNull(argv.last())
                ProcessResult(0, VALID_DER_SIGNATURE, ByteArray(0))
            },
        )

        val raw = signer.sign(signingInput)

        assertEquals(P256_RAW_SIGNATURE_LENGTH, raw.size, "the DER on stdout is transcoded to raw r||s")
        val argv = seenArgv ?: error("openssl was never invoked")
        assertEquals(
            listOf("/usr/bin/openssl", "dgst", "-sha256", "-sign", keyPath),
            argv.dropLast(1),
            "the command is a SHA-256 ECDSA signature over the key",
        )
        assertTrue(
            argv.last().substringAfterLast('/').startsWith(OpensslVapidSigner.SIGNING_INPUT_PREFIX),
            "the last argument is our temp file, not an option: ${argv.last()}",
        )
        assertContentEquals(
            signingInput.encodeToByteArray(),
            inputSeenByOpenssl,
            "openssl sees exactly the signing input, byte for byte",
        )
        assertNull(readFileBytesOrNull(argv.last()), "the temp file is gone once sign() returns")
        assertNoLitter("a successful signature")
    }

    @Test
    fun aMissingKeyFileFailsWithOpensslsOwnDiagnostic() {
        if (!opensslAvailable()) return
        assertNull(readFileBytesOrNull(keyPath), "precondition: no key file")
        val argvSeen = mutableListOf<List<String>>()
        val signer = OpensslVapidSigner(
            keyPath = keyPath,
            runner = { argv -> argvSeen.add(argv); ProcessRunner.run(argv) },
        )

        val failure = assertFailsWith<VapidSignerException> { signer.sign(signingInput) }

        assertTrue(failure.message!!.contains(keyPath), "the message names the key: ${failure.message}")
        assertTrue(
            failure.message!!.contains("cannot sign the VAPID token"),
            "the failure is attributed to signing: ${failure.message}",
        )
        assertNull(readFileBytesOrNull(argvSeen.last().last()), "the temp file is removed on failure too")
        assertNoLitter("a failed signature")
    }

    @Test
    fun aBogusOpensslPathFails() {
        val argvSeen = mutableListOf<List<String>>()
        val signer = OpensslVapidSigner(
            keyPath = keyPath,
            opensslPath = "/nonexistent/bin/openssl",
            runner = { argv -> argvSeen.add(argv); ProcessRunner.run(argv) },
        )

        val failure = assertFailsWith<VapidSignerException> { signer.sign(signingInput) }

        assertTrue(
            failure.message!!.contains("/nonexistent/bin/openssl"),
            "the message names the binary it could not run: ${failure.message}",
        )
        assertNull(readFileBytesOrNull(argvSeen.last().last()), "the temp file is removed on failure too")
        assertNoLitter("an unusable openssl")
    }

    @Test
    fun aNonZeroOpensslIsReportedWithItsStderr() {
        val signer = OpensslVapidSigner(
            keyPath = keyPath,
            runner = runnerReturning(ProcessResult(1, ByteArray(0), "unable to load key file\n".encodeToByteArray())),
        )

        val failure = assertFailsWith<VapidSignerException> { signer.sign(signingInput) }

        assertTrue(
            failure.message!!.contains("unable to load key file"),
            "openssl's own diagnostic is what the operator needs: ${failure.message}",
        )
        assertNoLitter("a non-zero openssl")
    }

    @Test
    fun anOpensslThatPrintsNothingFails() {
        val signer = OpensslVapidSigner(
            keyPath = keyPath,
            runner = runnerReturning(ProcessResult(0, ByteArray(0), ByteArray(0))),
        )

        val failure = assertFailsWith<VapidSignerException> { signer.sign(signingInput) }

        assertTrue(
            failure.message!!.contains("no VAPID signature"),
            "the message says openssl produced nothing: ${failure.message}",
        )
        assertNoLitter("an empty openssl")
    }

    @Test
    fun junkOnStdoutIsAttributedToOpenssl() {
        val signer = OpensslVapidSigner(
            keyPath = keyPath,
            runner = runnerReturning(ProcessResult(0, "not DER at all".encodeToByteArray(), ByteArray(0))),
        )

        val failure = assertFailsWith<VapidSignerException> { signer.sign(signingInput) }

        assertTrue(
            failure.message!!.contains("not a P-256 ECDSA DER value"),
            "the transcoding failure is attributed to openssl: ${failure.message}",
        )
        assertNoLitter("a malformed signature")
    }

    @Test
    fun aRunnerLevelFailureBecomesAVapidSignerException() {
        val signer = OpensslVapidSigner(keyPath = keyPath, runner = { error("popen failed") })

        val failure = assertFailsWith<VapidSignerException> { signer.sign(signingInput) }

        assertTrue(failure.message!!.contains("popen failed"), "the cause is preserved: ${failure.message}")
        assertNoLitter("a runner-level failure")
    }

    @Test
    fun anEmptySigningInputIsRefusedBeforeAnythingIsSpawned() {
        var invoked = false
        val signer = OpensslVapidSigner(
            keyPath = keyPath,
            runner = { invoked = true; ProcessResult(0, VALID_DER_SIGNATURE, ByteArray(0)) },
        )

        assertFailsWith<VapidSignerException> { signer.sign("") }
        assertFailsWith<VapidSignerException> { signer.sign("   ") }

        assertTrue(!invoked, "nothing is spawned for an input there is no point signing")
        assertNoLitter("a refused input")
    }

    private companion object {
        const val ZERO: Byte = 0
        const val DER_INTEGER: Byte = 0x02
        const val DER_SEQUENCE: Byte = 0x30

        val VALID_DER_SIGNATURE: ByteArray = byteArrayOf(
            0x30, 0x44,
            0x02, 0x20,
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x11.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x11.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x11.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x11.toByte(),
            0x02, 0x20,
            0x7f, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x11.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x11.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x11.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x11.toByte(),
        )
    }
}
