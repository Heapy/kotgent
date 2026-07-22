package io.kotgent.tmux

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
 * The outcome of running a subprocess: its [exitCode] plus the fully captured [stdoutBytes] and
 * [stderrBytes]. A non-zero exit is a normal, non-throwing outcome (`tmux` reports "can't find
 * session" etc. that way) — callers inspect [isSuccess] / [exitCode] and decide.
 *
 * Not a `data class` on purpose: structural equality over [ByteArray] would be by-reference and
 * misleading. Consumers use the decoded [stdout] / [stderr] text (tmux speaks UTF-8) or the raw
 * bytes directly.
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
 * Runs child processes and captures their output, built entirely on the stock Kotlin/Native
 * `platform.posix` platform library (no custom cinterop).
 *
 * ## Why `popen`/`pclose` and not `posix_spawn`
 * `posix_spawn` and friends live in `<spawn.h>`, which is **not** in the macosArm64 `platform.posix`
 * header set (that is exactly why [io.kotgent.pty.Pty] had to add its own `pty.def` cinterop for
 * them). Custom cinterop klibs do not link into TEST binaries on Kotlin Toolchain 0.11.0 (KT-78062,
 * see the Task 2 blocker), and these tmux commands MUST be spawnable from the test binary. That
 * rules out `posix_spawn`. A hand-rolled `fork()`+`execvp()` in Kotlin/Native is unsafe: only
 * async-signal-safe work may run between fork and exec, and any Kotlin allocation or GC safepoint
 * in the forked child risks a deadlock (the reason [io.kotgent.pty.Pty] chose `posix_spawn` over
 * `forkpty`). `popen` sidesteps both problems: the `fork`+`exec` happens **inside libc** — no
 * Kotlin/Native code ever runs in the child — and `popen`/`pclose` are stock `platform.posix`
 * (`stdio.h`), so they link and run in the test binary fine (like Task 3's `fopen`/`fread`).
 *
 * ## Deadlock-free capture
 * `popen` gives a single pipe (the child's stdout). We redirect the child's **stderr to a
 * per-call temp file** (`… 2> <tmpfile>`) and fully drain the one stdout pipe with a blocking
 * `fread` loop. With only one pipe, and it always drained to EOF, a chatty process can never fill
 * an unread pipe buffer and deadlock — and stdout stays uncontaminated by stderr (so e.g.
 * `capture-pane` content is exactly the pane content).
 *
 * ## No shell-injection despite the `/bin/sh` layer
 * `popen` runs `/bin/sh -c "<string>"`, so each argv element is wrapped with strict POSIX
 * single-quote quoting ([shQuote]) before being joined. Single quotes make every byte literal
 * (including tmux format specifiers like `#{pane_id}` and embedded tabs), so arguments cannot be
 * re-split or expanded by the shell.
 */
object ProcessRunner {

    /**
     * Run [argv] (argv[0] is the program; PATH is honored via `/bin/sh`) and return its result.
     * [env] entries are exported to the child as `KEY='value'` assignments; the child otherwise
     * inherits the current environment. Never throws on a non-zero child exit — that is reported
     * in [ProcessResult.exitCode]. Throws [ProcessException] only on a runner-level failure
     * (e.g. `popen` itself failing).
     */
    @OptIn(ExperimentalForeignApi::class)
    fun run(argv: List<String>, env: Map<String, String> = emptyMap()): ProcessResult {
        require(argv.isNotEmpty()) { "argv must not be empty" }

        val errPath = makeTempPath()
        val envPrefix = env.entries.joinToString(separator = "") { (k, v) -> "$k=${shQuote(v)} " }
        val commandLine = envPrefix + argv.joinToString(" ") { shQuote(it) } + " 2> " + shQuote(errPath)

        try {
            val fp = popen(commandLine, "r")
                ?: throw ProcessException("popen failed for '${argv[0]}' (errno=$errno)")

            val stdoutBytes = readStreamToEof(fp)
            // pclose returns the child's termination status in wait(2) format, or -1 on error.
            val status = pclose(fp)
            val exitCode = decodeExitCode(status)
            val stderrBytes = readFileBytes(errPath)
            return ProcessResult(exitCode, stdoutBytes, stderrBytes)
        } finally {
            unlink(errPath)
        }
    }

    /** Fully drain a `FILE*` stream into a ByteArray (streamed 8 KiB reads, no size cap). */
    @OptIn(ExperimentalForeignApi::class)
    private fun readStreamToEof(fp: kotlinx.cinterop.CPointer<platform.posix.FILE>): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        memScoped {
            val bufSize = 8192
            val buf = allocArray<ByteVar>(bufSize)
            while (true) {
                val n = fread(buf, 1.convert(), bufSize.convert(), fp).toInt()
                if (n <= 0) break // 0 == EOF (or a read error, which we also treat as end)
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

    /**
     * Decode a wait(2)-format status (as returned by `pclose`) into a conventional exit code:
     * the low 7 bits hold the terminating signal (0 when the child exited normally), bits 8..15
     * hold the exit code. A killed child reports `128 + signal`; a `-1` status (pclose error) is
     * surfaced as `-1`.
     */
    private fun decodeExitCode(status: Int): Int = when {
        status == -1 -> -1
        status and 0x7f == 0 -> (status shr 8) and 0xff
        else -> 128 + (status and 0x7f)
    }

    /**
     * Reserve a unique temp path via `mkstemp` (atomic, race-free) and hand back its name; the fd
     * is closed immediately because the child re-opens the file through the shell's `2>` redirect.
     */
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

    /**
     * POSIX single-quote quoting: wrap in single quotes and rewrite every embedded `'` as the
     * classic `'\''` (close-quote, escaped literal quote, reopen-quote). Makes [s] a single,
     * fully literal shell word — no expansion, no re-splitting.
     */
    internal fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

/** Thrown only for runner-level failures (not for a child's non-zero exit, which is a result). */
class ProcessException(message: String) : RuntimeException(message)
