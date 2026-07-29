package io.kotgent.daemon

import io.kotgent.core.ProviderSessionId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
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
import kotlin.test.assertNull

/**
 * Unit tests for [VendorSessionLocator] — the discovery half of `kotgent import`: finding the project
 * directory (`cwd`) a provider session was launched in, given only its provider session id.
 *
 * NEVER reads the real `~/.claude`: every filesystem test injects a throwaway `$TMPDIR` tree laid out
 * exactly like Claude's (`projects/<encoded-cwd>/<id>.jsonl`), so the scan runs for real against a fake
 * home. The pure part ([claudeTranscriptCwd]) is tested directly on strings, including the garbage and
 * truncation the real reader can produce. (The Codex side, [CodexRolloutScan.cwdOf], is tested in
 * [CodexRolloutScanTest] next to the rest of the rollout scan.)
 */
@OptIn(ExperimentalForeignApi::class)
class VendorSessionLocatorTest {

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

    // ---- pure: the cwd is scanned out of the transcript's head lines ----

    @Test
    fun cwdOnTheFirstLineIsFound() {
        val head = """{"parentUuid":null,"cwd":"/work/mine","sessionId":"x","type":"user"}"""
        assertEquals("/work/mine", claudeTranscriptCwd(head))
    }

    @Test
    fun cwdBeyondTheFirstLineIsStillFound() {
        // A real transcript often OPENS with a summary record that has no cwd — the scan must keep going.
        val head = """{"type":"summary","summary":"Fixing the build","leafUuid":"x"}""" + "\n" +
            """{"parentUuid":null,"cwd":"/work/mine","type":"user"}"""
        assertEquals("/work/mine", claudeTranscriptCwd(head))
    }

    @Test
    fun aHeadWithoutACwdFieldYieldsNothing() {
        val head = """{"type":"summary","summary":"no cwd anywhere"}""" + "\n" +
            """{"type":"user","sessionId":"x"}"""
        assertNull(claudeTranscriptCwd(head))
    }

    @Test
    fun garbageAndEmptyLinesAreSkippedNotFatal() {
        val head = "\n" + // empty line
            "not json at all\n" +
            """{"cwd":"/work/found"}"""
        assertEquals("/work/found", claudeTranscriptCwd(head))
    }

    @Test
    fun anEmptyHeadYieldsNothing() {
        assertNull(claudeTranscriptCwd(""))
    }

    @Test
    fun aTruncatedCwdValueYieldsNothing() {
        // What the bounded reader can hand over: the head window ended mid-value. No usable answer.
        assertNull(claudeTranscriptCwd("""{"cwd":"/work/cut-of"""))
    }

    @Test
    fun aCwdPastTheLineScanWindowIsNotFound() {
        // The scan is bounded to the head lines: a cwd that first appears past the window is not found
        // (a transcript whose first records carry no cwd is not one Claude wrote for a project dir).
        val filler = (1..CLAUDE_CWD_SCAN_LINES).joinToString("\n") { """{"type":"summary","n":$it}""" }
        val head = filler + "\n" + """{"cwd":"/work/late"}"""
        assertNull(claudeTranscriptCwd(head))
    }

    // ---- the scan: projects/*/ is probed for <id>.jsonl, then the head is read ----

    @Test
    fun theTranscriptIsFoundInOneOfTheProjectDirs() {
        val claudeDir = makeClaudeDir()
        val id = uuid('a')
        // Several project dirs; only one holds <id>.jsonl. Its RECORDED cwd is the answer — not a
        // re-decoding of the directory name (encodeClaudeProjectDir is irreversible).
        placeTranscript(claudeDir, project = "-work-other", id = uuid('b'), recordedCwd = "/work/other")
        placeTranscript(claudeDir, project = "-work-mine", id = id, recordedCwd = "/work/mine")

        assertEquals("/work/mine", claudeSessionLocator(claudeDir).cwdOf("claude", id))
    }

    @Test
    fun anUnknownIdYieldsNull() {
        val claudeDir = makeClaudeDir()
        placeTranscript(claudeDir, project = "-work-mine", id = uuid('c'), recordedCwd = "/work/mine")

        assertNull(claudeSessionLocator(claudeDir).cwdOf("claude", uuid('d')))
    }

    @Test
    fun aMissingProjectsDirYieldsNull() {
        val claudeDir = makeClaudeDir() // exists, but has no projects/ at all
        assertNull(claudeSessionLocator(claudeDir).cwdOf("claude", uuid('e')))
        assertNull(
            claudeSessionLocator("/nonexistent/kotgent-test-claude-home").cwdOf("claude", uuid('e')),
            "an absent home degrades to null, never an exception",
        )
    }

    // ---- dispatch: one locator per agent kind, unknown kinds answer null ----

    @Test
    fun dispatchSelectsTheLocatorForTheAgentKind() {
        val id = uuid('f')
        val locator = byAgentVendorSessionLocator(
            mapOf(
                "claude" to VendorSessionLocator { _, _ -> "/from/claude" },
                "codex" to VendorSessionLocator { _, _ -> "/from/codex" },
            ),
        )
        assertEquals("/from/claude", locator.cwdOf("claude", id))
        assertEquals("/from/codex", locator.cwdOf("codex", id))
        assertNull(locator.cwdOf("unknown-kind", id), "an unregistered kind answers null, not a crash")
    }

    // --- harness (throwaway $TMPDIR fake home; NEVER the real ~/.claude) ------------------------------

    private val mode0700: Int get() = S_IRUSR or S_IWUSR or S_IXUSR

    /** A fresh throwaway `<tmp>/…/.claude` base directory (created + tracked for teardown). */
    private fun makeClaudeDir(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val base = "$tmp/kotgent-locator-test-${getpid()}-${counter++}"
        mkdir(base, mode0700.convert()).also { dirs += base }
        val claude = "$base/.claude"
        mkdir(claude, mode0700.convert()).also { dirs += claude }
        return claude
    }

    /**
     * Lay a fake transcript `projects/<project>/<id>.jsonl` under [claudeDir], shaped like a real one:
     * a summary first line (no cwd), then a user record whose `"cwd"` field is [recordedCwd].
     */
    private fun placeTranscript(claudeDir: String, project: String, id: ProviderSessionId, recordedCwd: String) {
        val projects = "$claudeDir/projects"
        mkdir(projects, mode0700.convert()).also { if (!dirs.contains(projects)) dirs += projects }
        val projectDir = "$projects/$project"
        mkdir(projectDir, mode0700.convert()).also { if (!dirs.contains(projectDir)) dirs += projectDir }
        val path = "$projectDir/${id.value}.jsonl"
        writeFile(
            path,
            """{"type":"summary","summary":"a chat","leafUuid":"${id.value}"}""" + "\n" +
                """{"parentUuid":null,"isSidechain":false,"cwd":"$recordedCwd","sessionId":"${id.value}","type":"user"}""" + "\n",
        )
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
