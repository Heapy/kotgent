package io.kotgent.tmux

import io.kotgent.sys.markOpenFdsCloexec
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.mkstemp
import platform.posix.pclose
import platform.posix.popen
import platform.posix.unlink

/**
 * Non-zero child exits are results, not runner failures. This is not a data class because ByteArray
 * structural equality would be misleadingly reference-based.
 */
@OptIn(ExperimentalForeignApi::class)
class ProcessResult(
    val exitCode: Int,
    val stdoutBytes: ByteArray,
    val stderrBytes: ByteArray,
) {
    val stdout: String get() = stdoutBytes.decodeToString()
    val stderr: String get() = stderrBytes.decodeToString()
    val isSuccess: Boolean get() = exitCode == 0

    override fun toString(): String =
        "ProcessResult(exit=$exitCode, stdout=${stdout.trim().take(200)}, stderr=${stderr.trim().take(200)})"
}

/**
 * Uses libc `popen`, avoiding Kotlin work between fork and exec and custom spawn cinterop. Stderr goes
 * to a per-call temp file so stdout's single pipe can always be drained without a two-pipe deadlock.
 * Although popen invokes a shell, [shQuote] makes every argv element one literal word.
 */
object ProcessRunner {

    /**
     * Children inherit stdio but not other daemon descriptors. The popen pipe is created after the
     * close-on-exec sweep and is therefore unaffected.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun run(argv: List<String>): ProcessResult {
        require(argv.isNotEmpty()) { "argv must not be empty" }

        markOpenFdsCloexec()
        val errPath = makeTempPath()
        val commandLine = argv.joinToString(" ") { shQuote(it) } + " 2> " + shQuote(errPath)

        try {
            val fp = popen(commandLine, "r")
                ?: throw ProcessException("popen failed for '${argv[0]}' (errno=$errno)")

            val stdoutBytes = readStreamToEof(fp)
            val status = pclose(fp)
            val exitCode = decodeExitCode(status)
            val stderrBytes = readFileBytes(errPath)
            return ProcessResult(exitCode, stdoutBytes, stderrBytes)
        } finally {
            unlink(errPath)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readStreamToEof(fp: kotlinx.cinterop.CPointer<platform.posix.FILE>): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        memScoped {
            val bufSize = 8192
            val buf = allocArray<ByteVar>(bufSize)
            while (true) {
                val n = fread(buf, 1.convert(), bufSize.convert(), fp).toInt()
                if (n <= 0) break
                chunks.add(buf.readBytes(n))
                total += n
            }
        }
        val out = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    /** Decodes wait(2) status; signalled children use the conventional `128 + signal`. */
    private fun decodeExitCode(status: Int): Int = when {
        status == -1 -> -1
        status and 0x7f == 0 -> (status shr 8) and 0xff
        else -> 128 + (status and 0x7f)
    }

    /** Atomically reserves a path; the child reopens it through the stderr redirect. */
    @OptIn(ExperimentalForeignApi::class)
    private fun makeTempPath(): String {
        val dir = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val template = "$dir/kotgent-proc-${getpid()}-XXXXXX"
        memScoped {
            val bytes = template.encodeToByteArray()
            val buf = allocArray<ByteVar>(bytes.size + 1)
            bytes.forEachIndexed { i, b -> buf[i] = b }
            buf[bytes.size] = 0
            val fd = mkstemp(buf)
            if (fd < 0) throw ProcessException("mkstemp failed for '$template' (errno=$errno)")
            close(fd)
            return buf.toKString()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readFileBytes(path: String): ByteArray {
        val fp = fopen(path, "rb") ?: return ByteArray(0)
        try {
            fseek(fp, 0, SEEK_END)
            val size = ftell(fp)
            fseek(fp, 0, SEEK_SET)
            if (size <= 0L) return ByteArray(0)
            val buffer = ByteArray(size.toInt())
            buffer.usePinned { pinned ->
                fread(pinned.addressOf(0), 1.convert(), size.convert(), fp)
            }
            return buffer
        } finally {
            fclose(fp)
        }
    }

    internal fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

class ProcessException(message: String) : RuntimeException(message)
