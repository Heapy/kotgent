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

/**
 * Unit tests for the real Claude [VendorStoreProbe] (plan Task 18) — the probe that closes the Task-15
 * `{ false }` stub so `resumable` classification actually fires in production.
 *
 * NEVER reads the real `~/.claude`: every test injects a throwaway `$TMPDIR` directory as the base and
 * lays a fake `projects/<encoded-cwd>/<id>.jsonl` transcript under it, so the probe is exercised entirely
 * against a fake home (host-free by injection). The pure encoder is also pinned against the real Claude
 * naming convention verified on disk (2026-07).
 */
@OptIn(ExperimentalForeignApi::class)
class ClaudeVendorStoreProbeTest {

    private val files = mutableListOf<String>()
    private val dirs = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        // Best-effort teardown of the throwaway tree (deepest dir first); ignore every error.
        for (f in files) unlink(f)
        for (d in dirs.asReversed()) rmdir(d)
    }

    private fun uuid(c: Char): ProviderSessionId =
        ProviderSessionId("$c$c$c$c$c$c$c$c-$c$c$c$c-4$c$c$c-8$c$c$c-$c$c$c$c$c$c$c$c$c$c$c$c")

    // ---- the encoder is a pure function pinned to the real Claude naming convention ----

    @Test
    fun encodeMatchesTheRealClaudeProjectDirConvention() {
        // Verified against a real ~/.claude/projects on 2026-07: every non-[A-Za-z0-9] char -> '-',
        // 1:1, NO collapsing (existing '-' preserved; '/' and '.' each contribute one dash).
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
            claudeTranscriptPath("/home/.claude/", "/work/proj", id), // trailing slash on base is trimmed
        )
    }

    // ---- the probe stats the exact per-(cwd, id) path under an injected fake ~/.claude ----

    @Test
    fun transcriptPresentIsResumableEligible() = runBlocking {
        withTimeout(10_000) {
            val claudeDir = makeClaudeDir()
            val cwd = "/work/my.proj"
            val id = uuid('a')
            placeTranscript(claudeDir, cwd, id) // Claude wrote a transcript here

            val probe = claudeVendorStoreProbe(claudeDir)
            assertTrue(probe.hasTranscript(cwd, id), "a present transcript is detected")

            // ...which is exactly what turns a dead session into `resumable` rather than `crashed`.
            assertEquals(
                SessionState.resumable,
                Reconciler.classify(paneAlive = false, projectionState = SessionState.running, stopIntent = false, transcriptExists = probe.hasTranscript(cwd, id)),
            )
        }
    }

    @Test
    fun transcriptAbsentIsNotResumable() = runBlocking {
        withTimeout(10_000) {
            val claudeDir = makeClaudeDir() // exists, but no transcript laid down
            val cwd = "/work/my.proj"
            val id = uuid('b')

            val probe = claudeVendorStoreProbe(claudeDir)
            assertFalse(probe.hasTranscript(cwd, id), "no transcript on disk -> not resumable")

            assertEquals(
                SessionState.crashed,
                Reconciler.classify(paneAlive = false, projectionState = SessionState.running, stopIntent = false, transcriptExists = probe.hasTranscript(cwd, id)),
            )
        }
    }

    @Test
    fun probeIsKeyedOnCwd_transcriptUnderADifferentCwdDoesNotMatch() = runBlocking {
        withTimeout(10_000) {
            val claudeDir = makeClaudeDir()
            val id = uuid('c')
            // The transcript exists, but under a DIFFERENT project dir than the cwd we ask about.
            placeTranscript(claudeDir, "/work/other", id)

            val probe = claudeVendorStoreProbe(claudeDir)
            assertTrue(probe.hasTranscript("/work/other", id), "found under its own cwd")
            assertFalse(probe.hasTranscript("/work/mine", id), "same id, wrong cwd -> not found (probe keys on cwd)")
        }
    }

    // --- harness (throwaway $TMPDIR fake home; NEVER the real ~/.claude) ------------------------------

    // 0700 (user-only); Int here, `.convert()` narrows to mode_t (UShort) at each mkdir call site.
    private val mode0700: Int get() = S_IRUSR or S_IWUSR or S_IXUSR

    /** A fresh throwaway `<tmp>/…/.claude` base directory (created + tracked for teardown). */
    private fun makeClaudeDir(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val base = "$tmp/kotgent-probe-test-${getpid()}-${counter++}"
        mkdir(base, mode0700.convert()).also { dirs += base }
        val claude = "$base/.claude"
        mkdir(claude, mode0700.convert()).also { dirs += claude }
        return claude
    }

    /** Lay a fake transcript JSONL at exactly the path Claude would use for [cwd] + [id] under [claudeDir]. */
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
