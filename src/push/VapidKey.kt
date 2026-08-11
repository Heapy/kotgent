package io.kotgent.push

import io.kotgent.cli.kotgentHome
import io.kotgent.cli.mkdir0700
import io.kotgent.crypto.base64Url
import io.kotgent.tmux.ProcessResult
import io.kotgent.tmux.ProcessRunner
import io.kotgent.transport.createPrivateFileExclusive
import io.kotgent.transport.readFileBytesOrNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.chmod
import platform.posix.errno
import platform.posix.stat
import platform.posix.strerror

/**
 * Persistent VAPID P-256 identity. System OpenSSL is pinned absolutely because Kotlin/Native lacks the
 * needed EC primitives and launchd PATH may differ. OpenSSL writes the PEM to stdout; kotgent creates it
 * mode 0600 with first-writer-wins semantics. The key is never replaced automatically because rotation
 * invalidates every browser subscription and re-subscription requires a user gesture.
 */
class VapidKey(
    val keyPath: String = defaultVapidKeyPath(),
    private val opensslPath: String = DEFAULT_OPENSSL_PATH,
    private val runner: (List<String>) -> ProcessResult = { ProcessRunner.run(it) },
) {

    private val mutex = Mutex()

    private var cachedPoint: ByteArray? = null

    suspend fun ensureKeyFile(): String = mutex.withLock { ensureKeyFileLocked() }

    suspend fun publicPoint(): ByteArray = mutex.withLock { pointLocked() }.copyOf()

    suspend fun publicKeyBase64Url(): String = base64Url(publicPoint())

    private fun ensureKeyFileLocked(): String {
        if (existingPem() != null) return keyPath

        val pem = generatePem()
        val dir = keyPath.substringBeforeLast('/', missingDelimiterValue = "")
        if (dir.isNotEmpty()) mkdir0700(dir)
        // A concurrent winner's persisted key becomes authoritative; never replace it.
        createPrivateFileExclusive(keyPath, pem)
        existingPem() ?: throw VapidKeyException(
            "VAPID key file $keyPath is missing immediately after being written",
        )
        return keyPath
    }

    private fun pointLocked(): ByteArray {
        cachedPoint?.let { return it }
        val path = ensureKeyFileLocked()
        val result = runOpenssl(listOf(opensslPath, "ec", "-in", path, "-pubout", "-outform", "DER"))
        if (!result.isSuccess) {
            throw VapidKeyException(
                "cannot read the VAPID public key from $path " +
                    "($opensslPath ec exited ${result.exitCode}): ${result.stderr.trim()}",
            )
        }
        return publicPointFromSpki(result.stdoutBytes).also { cachedPoint = it }
    }

    /**
     * Existing keys are forced and verified as 0600. Empty or invalid files fail rather than being
     * overwritten because their identity may still back live subscriptions.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun existingPem(): ByteArray? {
        val bytes = readFileBytesOrNull(keyPath) ?: return null
        if (chmod(keyPath, VAPID_KEY_MODE_0600.convert()) != 0) {
            val chmodError = errno
            throw VapidKeyException(
                "cannot secure VAPID key file $keyPath (chmod 0600 failed: ${errnoText(chmodError)}) — " +
                    "refusing to use a private key that may be readable by other users",
            )
        }
        val mode = memScoped {
            val metadata = alloc<stat>()
            if (stat(keyPath, metadata.ptr) != 0) {
                val statError = errno
                throw VapidKeyException(
                    "cannot verify VAPID key file $keyPath permissions " +
                        "(stat failed: ${errnoText(statError)}) — refusing to use a private key whose " +
                        "mode could not be confirmed 0600",
                )
            }
            metadata.st_mode.toInt() and PERMISSION_MASK
        }
        if (mode != VAPID_KEY_MODE_0600) {
            throw VapidKeyException(
                "VAPID key file $keyPath is still mode ${mode.toString(8).padStart(3, '0')} after chmod " +
                    "0600 — refusing to use a private key that may be readable by other users",
            )
        }
        val text = bytes.decodeToString()
        if (text.isBlank()) {
            throw VapidKeyException(
                "VAPID key file $keyPath is empty — delete it to have a new keypair generated " +
                    "(every existing push subscription will have to be re-enabled)",
            )
        }
        if (!text.contains(PEM_BEGIN_MARKER) || !text.contains(PEM_PRIVATE_KEY_MARKER)) {
            throw VapidKeyException(
                "VAPID key file $keyPath is not a PEM private key — refusing to use it; " +
                    "delete it to have a new keypair generated",
            )
        }
        return bytes
    }

    private fun generatePem(): ByteArray {
        val argv = listOf(opensslPath, "ecparam", "-name", "prime256v1", "-genkey", "-noout")
        val result = runOpenssl(argv)
        if (!result.isSuccess) {
            throw VapidKeyException(
                "cannot generate the VAPID keypair ($opensslPath exited ${result.exitCode}): " +
                    result.stderr.trim(),
            )
        }
        if (result.stdoutBytes.decodeToString().isBlank()) {
            throw VapidKeyException(
                "$opensslPath produced no VAPID key on stdout: ${result.stderr.trim()}",
            )
        }
        return result.stdoutBytes
    }

    private fun runOpenssl(argv: List<String>): ProcessResult =
        try {
            runner(argv)
        } catch (e: Exception) {
            throw VapidKeyException("cannot run $opensslPath: ${e.message}")
        }

    companion object {
        const val DEFAULT_OPENSSL_PATH: String = "/usr/bin/openssl"
    }
}

class VapidKeyException(message: String) : IllegalStateException(message)

const val VAPID_KEY_FILE_NAME: String = "vapid.pem"

fun defaultVapidKeyPath(): String = "${kotgentHome()}/$VAPID_KEY_FILE_NAME"

const val P256_SPKI_HEADER_LENGTH: Int = 26

const val P256_POINT_LENGTH: Int = 65

const val P256_SPKI_LENGTH: Int = P256_SPKI_HEADER_LENGTH + P256_POINT_LENGTH

const val UNCOMPRESSED_POINT_TAG: Byte = 0x04

private const val PEM_BEGIN_MARKER: String = "-----BEGIN"
private const val PEM_PRIVATE_KEY_MARKER: String = "PRIVATE KEY-----"
private const val VAPID_KEY_MODE_0600: Int = S_IRUSR or S_IWUSR
private const val PERMISSION_MASK: Int = 0b111_111_111

@OptIn(ExperimentalForeignApi::class)
private fun errnoText(code: Int): String = strerror(code)?.toKString() ?: "errno=$code"

/**
 * Prime256v1 SPKI is fixed-width, so the public point is its final 65 bytes. Length and uncompressed tag
 * are verified to avoid a delayed opaque rejection from the push service.
 */
fun publicPointFromSpki(der: ByteArray): ByteArray {
    if (der.size != P256_SPKI_LENGTH) {
        throw VapidKeyException(
            "expected a $P256_SPKI_LENGTH-byte P-256 SubjectPublicKeyInfo, got ${der.size} bytes",
        )
    }
    val point = der.copyOfRange(P256_SPKI_HEADER_LENGTH, der.size)
    if (point[0] != UNCOMPRESSED_POINT_TAG) {
        throw VapidKeyException(
            "VAPID public point does not start with the uncompressed-point tag 0x04 " +
                "(got 0x${(point[0].toInt() and 0xff).toString(16).padStart(2, '0')})",
        )
    }
    return point
}
