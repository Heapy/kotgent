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

class VapidSignerException(message: String) : IllegalStateException(message)

/**
 * ES256 signing through system OpenSSL. ProcessRunner exposes no writable stdin, so the public JWT
 * signing input uses an atomic temporary file that is always unlinked; the signature returns on stdout.
 */
@OptIn(ExperimentalForeignApi::class)
class OpensslVapidSigner(
    private val keyPath: String,
    private val opensslPath: String = VapidKey.DEFAULT_OPENSSL_PATH,
    private val runner: (List<String>) -> ProcessResult = { ProcessRunner.run(it) },
) {

    /** Returns the fixed-width raw `r || s` signature or throws [VapidSignerException]. */
    fun sign(input: String): ByteArray {
        if (input.isBlank()) {
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
                throw VapidSignerException(
                    "$opensslPath produced a signature that is not a P-256 ECDSA DER value: ${e.message}",
                )
            }
        } finally {
            unlink(inputPath)
        }
    }

    private fun runOpenssl(argv: List<String>): ProcessResult =
        try {
            runner(argv)
        } catch (e: Exception) {
            throw VapidSignerException("cannot run $opensslPath: ${e.message}")
        }

    /**
     * `mkstemp` prevents path collisions and symlink preplacement. Write through its descriptor rather
     * than reopening the now-visible path.
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
     * Partial input could produce a valid signature over the wrong claims, so short writes are completed
     * or fail explicitly.
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
        const val SIGNING_INPUT_PREFIX: String = "kotgent-vapidsign-"
    }
}
