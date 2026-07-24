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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.chmod
import platform.posix.stat

/**
 * The daemon's VAPID identity: one P-256 keypair in `~/.kotgent/vapid.pem`, and the uncompressed public
 * point the browser needs as `applicationServerKey`.
 *
 * ## Why openssl, and why `/usr/bin/openssl`
 * Kotlin/Native has no `BigInteger` and no bundled EC implementation, and CommonCrypto is out of reach
 * from a test binary (KT-78062 — the same reason the hash primitives in `src/crypto/` are common Kotlin).
 * macOS always ships
 * `/usr/bin/openssl` (LibreSSL), so the keypair and — later, in the signer — the ES256 signature are
 * delegated to it through the existing [ProcessRunner] (`popen` with the CLOEXEC sweep, the one spawn
 * path a test binary can use). The absolute path is PINNED rather than resolved on `PATH`: on a dev
 * machine `openssl` is usually Homebrew's build, while the launchd daemon runs on the PATH snapshotted
 * by `kotgent install` — a bare name would mean tests and production ran different binaries.
 *
 * ## Why WE write the file, not openssl
 * The generation command deliberately has **no `-out`**: openssl creates that file with the process
 * umask, which on macOS leaves the private key `0644` (verified). The PEM comes back on
 * [ProcessResult.stdoutBytes] and is persisted by [createPrivateFileExclusive] instead — `0600` from the
 * moment it exists, and first-writer-wins if two `GET /push/vapid-key` requests race, so a loser adopts
 * the winner's key rather than clobbering it. That matters more than usual here: replacing the key
 * silently invalidates **every** existing browser subscription, which cannot re-subscribe without a user
 * gesture. Hence the whole class is read-or-create and NEVER re-mints.
 *
 * ## Failure is not fatal to the daemon
 * Every problem — no openssl, an unwritable `~/.kotgent`, a corrupt PEM — surfaces as
 * [VapidKeyException] with the underlying stderr. `GET /push/vapid-key` turns that into a `503` ("push
 * unavailable"); the rest of the daemon is unaffected.
 *
 * [keyPath], [opensslPath] and [runner] are injected so tests drive a throwaway `$TMPDIR` key with the
 * real openssl (the key must be a genuine P-256 key) and a fake runner for the error paths.
 */
class VapidKey(
    val keyPath: String = defaultVapidKeyPath(),
    private val opensslPath: String = DEFAULT_OPENSSL_PATH,
    private val runner: (List<String>) -> ProcessResult = { ProcessRunner.run(it) },
) {

    /**
     * Serializes generation AND guards [cachedPoint]. Both `GET /push/vapid-key` and the sender read
     * through here from arbitrary coroutines; without it two first requests could each shell out to
     * openssl (harmless but wasteful) while racing on the cache field (a data race).
     */
    private val mutex = Mutex()

    /** The extracted 65-byte point, cached after the first extraction — openssl runs once per daemon. */
    private var cachedPoint: ByteArray? = null

    /**
     * The path of the PEM, generating it first if it does not exist yet. This is what the signer
     * (`openssl dgst -sign`) needs, and what makes the key exist before the first push is ever sent.
     */
    suspend fun ensureKeyFile(): String = mutex.withLock { ensureKeyFileLocked() }

    /**
     * The uncompressed P-256 public point: 65 bytes starting with `0x04`. A defensive copy, so a caller
     * cannot mutate the cached value.
     */
    suspend fun publicPoint(): ByteArray = mutex.withLock { pointLocked() }.copyOf()

    /** The public point as base64url — verbatim what the page passes as `applicationServerKey`. */
    suspend fun publicKeyBase64Url(): String = base64Url(publicPoint())

    /** @see ensureKeyFile — the body, assuming [mutex] is already held (Kotlin's Mutex is not reentrant). */
    private fun ensureKeyFileLocked(): String {
        if (existingPem() != null) return keyPath

        val pem = generatePem()
        val dir = keyPath.substringBeforeLast('/', missingDelimiterValue = "")
        if (dir.isNotEmpty()) mkdir0700(dir)
        // A `false` return means another writer created the file first; its key is the one on disk and
        // therefore the one every other process will use, so we adopt it instead of replacing it.
        createPrivateFileExclusive(keyPath, pem)
        existingPem() ?: throw VapidKeyException(
            "VAPID key file $keyPath is missing immediately after being written",
        )
        return keyPath
    }

    /** @see publicPoint — the body, assuming [mutex] is already held. */
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
     * The persisted PEM, or `null` if the file does not exist yet (the generate case).
     *
     * A file that exists but is EMPTY or is not a PEM at all throws instead of being treated as absent:
     * overwriting it might destroy a key whose subscriptions are still live, and using it would produce a
     * half-broken push path that only fails at send time. The operator is told to delete it explicitly.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun existingPem(): ByteArray? {
        val bytes = readFileBytesOrNull(keyPath) ?: return null
        if (chmod(keyPath, VAPID_KEY_MODE_0600.convert()) != 0) {
            throw VapidKeyException(
                "cannot secure VAPID key file $keyPath with mode 0600 — refusing to use a private key " +
                    "that may be readable by other users",
            )
        }
        val mode = memScoped {
            val metadata = alloc<stat>()
            if (stat(keyPath, metadata.ptr) != 0) null else metadata.st_mode.toInt() and PERMISSION_MASK
        }
        if (mode != VAPID_KEY_MODE_0600) {
            val shown = mode?.toString(8)?.padStart(3, '0') ?: "unknown"
            throw VapidKeyException(
                "VAPID key file $keyPath is still mode $shown after chmod 0600 — refusing to use a " +
                    "private key that may be readable by other users",
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

    /** A fresh P-256 private key in PEM form, on stdout (see the class KDoc on why not `-out`). */
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

    /**
     * [runner] with runner-level failures (a `popen` that never got off the ground) folded into
     * [VapidKeyException], so every caller of this class has exactly one exception type to degrade on.
     */
    private fun runOpenssl(argv: List<String>): ProcessResult =
        try {
            runner(argv)
        } catch (e: Exception) {
            throw VapidKeyException("cannot run $opensslPath: ${e.message}")
        }

    companion object {
        /** The macOS system openssl (LibreSSL). Pinned absolute — see the class KDoc. */
        const val DEFAULT_OPENSSL_PATH: String = "/usr/bin/openssl"
    }
}

/** Every VAPID-key failure: no openssl, an unwritable directory, a corrupt or non-PEM key file. */
class VapidKeyException(message: String) : IllegalStateException(message)

/** File name of the VAPID keypair inside `~/.kotgent` (next to the token and the database). */
const val VAPID_KEY_FILE_NAME: String = "vapid.pem"

/** `~/.kotgent/vapid.pem` — the same directory as the token (`.kotgent/…` if `$HOME` is unset). */
fun defaultVapidKeyPath(): String = "${kotgentHome()}/$VAPID_KEY_FILE_NAME"

/** The fixed DER prologue (SEQUENCE + `id-ecPublicKey` + `prime256v1` + BIT STRING header) before the point. */
const val P256_SPKI_HEADER_LENGTH: Int = 26

/** An uncompressed P-256 point: the `0x04` tag plus the 32-byte X and Y coordinates. */
const val P256_POINT_LENGTH: Int = 65

/** Length of a P-256 `SubjectPublicKeyInfo` in DER: the 26-byte header plus the 65-byte point. */
const val P256_SPKI_LENGTH: Int = P256_SPKI_HEADER_LENGTH + P256_POINT_LENGTH

/** SEC 1 tag introducing an uncompressed point. Web Push accepts nothing else as an application key. */
const val UNCOMPRESSED_POINT_TAG: Byte = 0x04

private const val PEM_BEGIN_MARKER: String = "-----BEGIN"
private const val PEM_PRIVATE_KEY_MARKER: String = "PRIVATE KEY-----"
private const val VAPID_KEY_MODE_0600: Int = S_IRUSR or S_IWUSR
private const val PERMISSION_MASK: Int = 0b111_111_111

/**
 * The 65-byte uncompressed point inside a P-256 `SubjectPublicKeyInfo` ([der], as
 * `openssl ec -pubout -outform DER` emits it).
 *
 * For prime256v1 the SPKI is a FIXED 91 bytes — the algorithm identifier and both OIDs have constant
 * lengths — so the point is simply the last 65 bytes, and a full ASN.1 parser would be ceremony around a
 * constant. The length and the `0x04` tag are both asserted rather than assumed: a silently mis-sliced
 * key would be accepted by `pushManager.subscribe` and only fail much later, as an opaque rejection from
 * Apple. Pure and public so the slicing rule is unit-testable without openssl.
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
