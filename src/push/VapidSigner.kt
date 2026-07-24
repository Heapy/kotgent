package io.kotgent.push

import io.kotgent.tmux.ProcessResult
import io.kotgent.tmux.ProcessRunner
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.close
import platform.posix.errno
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.mkstemp
import platform.posix.unlink
import platform.posix.write

/**
 * Produces the ES256 signature over a VAPID JWT's signing input: the 64-byte raw `r || s` RFC 7515 §3.4
 * requires, NOT the DER openssl emits.
 *
 * An interface with exactly one method so [VapidTokenCache] can be handed `signer::sign` in production and
 * a lambda in tests — the whole reason the token/claim logic in `VapidJwt.kt` is pure. Deliberately NOT
 * `suspend`: signing is a bounded, short subprocess call and [VapidTokenCache] invokes it under its lock.
 */
interface VapidSigner {
    /**
     * The raw `r || s` signature over [input] (`base64url(header).base64url(claims)`), exactly
     * [P256_RAW_SIGNATURE_LENGTH] bytes.
     *
     * @throws VapidSignerException if the signature cannot be produced for any reason. There is no
     *   "partial" outcome: a caller either gets 64 valid bytes or an explanation.
     */
    fun sign(input: String): ByteArray
}

/** Every signing failure: no openssl, an unreadable key, a temp file that cannot be written, junk DER. */
class VapidSignerException(message: String) : IllegalStateException(message)

/**
 * [VapidSigner] backed by the system openssl — the signing edge of the push feature, and the only place in
 * `src/push/` that spawns a process besides [VapidKey].
 *
 * ## Why a temp file for the input
 * The signing input has to reach openssl's stdin or a file, and [ProcessRunner] is `popen`-based
 * (`popen(cmd, "r")`), which gives a readable pipe and **no writable stdin** — by design, because `popen`
 * is the one spawn primitive usable from a test binary (KT-78062, see [ProcessRunner]). So the input goes
 * through a `mkstemp` file instead and openssl is pointed at it. Nothing secret is written: the input is
 * the JWT's already-public header and claims. The DER signature comes back on
 * [ProcessResult.stdoutBytes] — no output file at all — and the input temp is removed in a `finally`, so
 * neither a non-zero openssl nor a malformed signature can leave litter in `$TMPDIR`.
 *
 * ## Why `/usr/bin/openssl` again rather than a shared helper
 * The default is the same pinned absolute path [VapidKey] uses and for the same reason (a bare `openssl`
 * is Homebrew's build locally and possibly nothing at all under a stale launchd PATH). The two classes
 * stay separate because they fail differently: a key problem means "push cannot be enabled at all", a
 * signing problem means "this send failed" — [VapidTokenCache] caches nothing when [sign] throws, so the
 * next attempt retries from scratch.
 *
 * ## Failure is never fatal
 * Every path throws [VapidSignerException] with openssl's own stderr in the message. That is the
 * diagnostic the daemon prints once when it degrades to "push disabled"; nothing here takes the daemon
 * down and nothing here retries.
 *
 * @param keyPath the PEM private key — [VapidKey.ensureKeyFile]'s return value in production.
 * @param opensslPath the openssl binary; injected so a test can point at a nonexistent one.
 * @param runner the spawn seam, [ProcessRunner.run] in production.
 */
@OptIn(ExperimentalForeignApi::class)
class OpensslVapidSigner(
    private val keyPath: String,
    private val opensslPath: String = VapidKey.DEFAULT_OPENSSL_PATH,
    private val runner: (List<String>) -> ProcessResult = { ProcessRunner.run(it) },
) : VapidSigner {

    override fun sign(input: String): ByteArray {
        if (input.isBlank()) {
            // Only reachable by bypassing vapidSigningInput; signing nothing would yield a JWT whose
            // signature covers no claims, which a push service rejects with an opaque 401.
            throw VapidSignerException("refusing to sign an empty VAPID signing input")
        }

        val inputPath = writeSigningInput(input)
        try {
            val argv = listOf(opensslPath, "dgst", "-sha256", "-sign", keyPath, inputPath)
            val result = runOpenssl(argv)
            if (!result.isSuccess) {
                throw VapidSignerException(
                    "cannot sign the VAPID token with $keyPath " +
                        "($opensslPath dgst exited ${result.exitCode}): ${result.stderr.trim()}",
                )
            }
            if (result.stdoutBytes.isEmpty()) {
                throw VapidSignerException(
                    "$opensslPath produced no VAPID signature on stdout: ${result.stderr.trim()}",
                )
            }
            return try {
                derToRawSignature(result.stdoutBytes)
            } catch (e: EcdsaDerException) {
                // The bytes came from openssl, so this is "openssl gave us something unexpected", not a
                // caller error — say so, or the operator goes looking in the wrong place.
                throw VapidSignerException(
                    "$opensslPath produced a signature that is not a P-256 ECDSA DER value: ${e.message}",
                )
            }
        } finally {
            unlink(inputPath)
        }
    }

    /**
     * [runner] with runner-level failures (a `popen` that never got off the ground) folded into
     * [VapidSignerException], so a caller has exactly one exception type to degrade on — the same shape
     * [VapidKey.runOpenssl] uses.
     */
    private fun runOpenssl(argv: List<String>): ProcessResult =
        try {
            runner(argv)
        } catch (e: Exception) {
            throw VapidSignerException("cannot run $opensslPath: ${e.message}")
        }

    /**
     * [input] in a fresh `$TMPDIR` file, whose path is returned; the caller unlinks it.
     *
     * `mkstemp` (not a name we compose ourselves) because it creates the file atomically and `0600`, so
     * two concurrent sends cannot collide on a path and no one can pre-place a symlink at it. The bytes go
     * through the returned descriptor rather than a second `fopen` by name, which would reopen a path that
     * is no longer guaranteed to be the file we just made.
     */
    private fun writeSigningInput(input: String): String {
        val dir = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val template = "$dir/$SIGNING_INPUT_PREFIX${getpid()}-XXXXXX"
        memScoped {
            val templateBytes = template.encodeToByteArray()
            val buf = allocArray<ByteVar>(templateBytes.size + 1)
            templateBytes.forEachIndexed { i, b -> buf[i] = b }
            buf[templateBytes.size] = 0

            val fd = mkstemp(buf)
            if (fd < 0) {
                throw VapidSignerException(
                    "cannot create a temporary file for the VAPID signing input in $dir (errno=$errno)",
                )
            }
            val path = buf.toKString()
            try {
                writeAll(fd, input.encodeToByteArray(), path)
            } catch (e: Throwable) {
                close(fd)
                unlink(path)
                throw e
            }
            if (close(fd) != 0) {
                unlink(path)
                throw VapidSignerException("cannot close the VAPID signing input file $path (errno=$errno)")
            }
            return path
        }
    }

    /**
     * All of [bytes] to [fd], looping because a single `write` may be short. A partial signing input would
     * produce a perfectly valid signature over the WRONG bytes — silently unverifiable at the push
     * service — so a short or failed write throws instead. [path] only names the file in the error.
     */
    private fun writeAll(fd: Int, bytes: ByteArray, path: String) {
        if (bytes.isEmpty()) return
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val written = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
                if (written <= 0) {
                    throw VapidSignerException(
                        "cannot write the VAPID signing input to $path " +
                            "(wrote $offset of ${bytes.size} bytes, errno=$errno)",
                    )
                }
                offset += written
            }
        }
    }

    companion object {
        /**
         * Prefix of the per-signature temp file. Distinctive on purpose: anything matching it in `$TMPDIR`
         * is leaked litter from this class, which is exactly what the tests assert never happens.
         */
        const val SIGNING_INPUT_PREFIX: String = "kotgent-vapidsign-"
    }
}
