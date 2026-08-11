package io.kotgent.daemon

import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
// Filesystem fixtures use unique TMPDIR trees and never read the developer's ~/.claude.
class ClaudeVendorStoreProbeTest {

    private val files = mutableListOf<String>()
    private val dirs = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        for (f in files) unlink(f)
        for (d in dirs.asReversed()) rmdir(d)
    }

    private fun uuid(c: Char): ProviderSessionId =
        ProviderSessionId("$c$c$c$c$c$c$c$c-$c$c$c$c-4$c$c$c-8$c$c$c-$c$c$c$c$c$c$c$c$c$c$c$c")


    @Test
    fun encodeMatchesTheRealClaudeProjectDirConvention() {
        assertEquals("-Users-yoda-dev-pet", encodeClaudeProjectDir("/Users/yoda/dev/pet"))
        assertEquals(
            "-Users-yoda-dev-os-kotlinx-serialization",
            encodeClaudeProjectDir("/Users/yoda/dev/os/kotlinx.serialization"),
            "a '.' in a path segment becomes '-'",
        )
        assertEquals(
            "-Users-yoda-dev-bond-bond-customer-app-backend--claude-worktrees-perf-nplusone-flights",
            encodeClaudeProjectDir("/Users/yoda/dev/bond/bond-customer-app-backend/.claude-worktrees/perf-nplusone-flights"),
            "'/.' yields a DOUBLE dash (no collapsing), existing '-' preserved",
        )
        assertEquals("-a-b-c", encodeClaudeProjectDir("/a/b_c"), "'_' also becomes '-'")
    }

    @Test
    fun transcriptPathComposesBaseProjectsEncodedCwdAndSessionJsonl() {
        val id = uuid('a')
        assertEquals(
            "/home/.claude/projects/-work-proj/${id.value}.jsonl",
            claudeTranscriptPath("/home/.claude/", "/work/proj", id),
        )
    }


    @Test
    fun transcriptPresentIsResumableEligible() = runBlocking {
        withTimeout(10_000) {
            val claudeDir = makeClaudeDir()
            val cwd = "/work/my.proj"
            val id = uuid('a')
            placeTranscript(claudeDir, cwd, id)

            val probe = claudeVendorStoreProbe(claudeDir)
            assertTrue(probe.hasTranscript("claude", cwd, id), "a present transcript is detected")

            assertEquals(
                SessionState.resumable,
                Reconciler.classify(paneAlive = false, currentState = SessionState.running, stopIntent = false, transcriptExists = probe.hasTranscript("claude", cwd, id)),
            )
        }
    }

    @Test
    fun transcriptAbsentIsNotResumable() = runBlocking {
        withTimeout(10_000) {
            val claudeDir = makeClaudeDir()
            val cwd = "/work/my.proj"
            val id = uuid('b')

            val probe = claudeVendorStoreProbe(claudeDir)
            assertFalse(probe.hasTranscript("claude", cwd, id), "no transcript on disk -> not resumable")

            assertEquals(
                SessionState.crashed,
                Reconciler.classify(paneAlive = false, currentState = SessionState.running, stopIntent = false, transcriptExists = probe.hasTranscript("claude", cwd, id)),
            )
        }
    }

    @Test
    fun probeIsKeyedOnCwd_transcriptUnderADifferentCwdDoesNotMatch() = runBlocking {
        withTimeout(10_000) {
            val claudeDir = makeClaudeDir()
            val id = uuid('c')
            placeTranscript(claudeDir, "/work/other", id)

            val probe = claudeVendorStoreProbe(claudeDir)
            assertTrue(probe.hasTranscript("claude", "/work/other", id), "found under its own cwd")
            assertFalse(probe.hasTranscript("claude", "/work/mine", id), "same id, wrong cwd -> not found (probe keys on cwd)")
        }
    }


    private val mode0700: Int get() = S_IRUSR or S_IWUSR or S_IXUSR

    private fun makeClaudeDir(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val base = "$tmp/kotgent-probe-test-${getpid()}-${counter++}"
        mkdir(base, mode0700.convert()).also { dirs += base }
        val claude = "$base/.claude"
        mkdir(claude, mode0700.convert()).also { dirs += claude }
        return claude
    }

    private fun placeTranscript(claudeDir: String, cwd: String, id: ProviderSessionId) {
        val projects = "$claudeDir/projects".also { mkdir(it, mode0700.convert()); dirs += it }
        val projectDir = "$projects/${encodeClaudeProjectDir(cwd)}".also { mkdir(it, mode0700.convert()); dirs += it }
        val path = "$projectDir/${id.value}.jsonl"
        writeFile(path, "{\"type\":\"summary\",\"leafUuid\":\"${id.value}\"}\n")
        files += path
    }

    private fun writeFile(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        val fp = fopen(path, "wb") ?: error("cannot write $path")
        try {
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp) }
        } finally {
            fclose(fp)
        }
    }

    private companion object {
        var counter = 0
    }
}
