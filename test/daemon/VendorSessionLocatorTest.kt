package io.kotgent.daemon

import io.kotgent.core.ProviderSessionId
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
import kotlin.test.assertNull

@OptIn(ExperimentalForeignApi::class)
// Filesystem fixtures use unique TMPDIR trees and never read the developer's ~/.claude.
class VendorSessionLocatorTest {

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
    fun cwdOnTheFirstLineIsFound() {
        val head = """{"parentUuid":null,"cwd":"/work/mine","sessionId":"x","type":"user"}"""
        assertEquals("/work/mine", claudeTranscriptCwd(head))
    }

    @Test
    fun cwdBeyondTheFirstLineIsStillFound() {
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
        val head = "\n" +
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
        assertNull(claudeTranscriptCwd("""{"cwd":"/work/cut-of"""))
    }

    @Test
    fun aCwdOnALateLineWithinTheByteWindowIsFound() {
        val filler = (1..200).joinToString("\n") { """{"type":"summary","n":$it}""" }
        assertEquals("/work/late", claudeTranscriptCwd(filler + "\n" + """{"cwd":"/work/late"}"""))
    }


    @Test
    fun theTranscriptIsFoundInOneOfTheProjectDirs() = runBlocking {
        withTimeout(20_000) {
            val claudeDir = makeClaudeDir()
            val id = uuid('a')
            placeTranscript(claudeDir, project = "-work-other", id = uuid('b'), recordedCwd = "/work/other")
            placeTranscript(claudeDir, project = "-work-mine", id = id, recordedCwd = "/work/mine")

            assertEquals("/work/mine", claudeSessionLocator(claudeDir).cwdOf("claude", id))
        }
    }

    @Test
    fun anUnknownIdYieldsNull() = runBlocking {
        withTimeout(20_000) {
            val claudeDir = makeClaudeDir()
            placeTranscript(claudeDir, project = "-work-mine", id = uuid('c'), recordedCwd = "/work/mine")

            assertNull(claudeSessionLocator(claudeDir).cwdOf("claude", uuid('d')))
        }
    }

    @Test
    fun aMissingProjectsDirYieldsNull() = runBlocking {
        withTimeout(20_000) {
            val claudeDir = makeClaudeDir()
            assertNull(claudeSessionLocator(claudeDir).cwdOf("claude", uuid('e')))
            assertNull(
                claudeSessionLocator("/nonexistent/kotgent-test-claude-home").cwdOf("claude", uuid('e')),
                "an absent home degrades to null, never an exception",
            )
        }
    }

    @Test
    fun theScanReadsTheFullByteWindowNotTheDefaultHead() = runBlocking {
        withTimeout(20_000) {
            val claudeDir = makeClaudeDir()
            val id = uuid('a')
            val pad = "x".repeat(180)
            val filler = (1..200).joinToString("\n") { """{"type":"summary","pad":"$pad","n":$it}""" }
            placeTranscriptRaw(claudeDir, "-work-big", id, filler + "\n" + """{"cwd":"/work/big"}""" + "\n")

            assertEquals("/work/big", claudeSessionLocator(claudeDir).cwdOf("claude", id))
        }
    }

    @Test
    fun aCwdPastTheByteWindowIsNotFound() = runBlocking {
        withTimeout(20_000) {
            val claudeDir = makeClaudeDir()
            val id = uuid('b')
            val pad = "x".repeat(180)
            val lines = (CLAUDE_CWD_SCAN_BYTES / 190) + 40
            val filler = (1..lines).joinToString("\n") { """{"type":"summary","pad":"$pad","n":$it}""" }
            placeTranscriptRaw(claudeDir, "-work-huge", id, filler + "\n" + """{"cwd":"/work/beyond"}""" + "\n")

            assertNull(claudeSessionLocator(claudeDir).cwdOf("claude", id))
        }
    }


    @Test
    fun dispatchSelectsTheLocatorForTheAgentKind() = runBlocking {
        withTimeout(20_000) {
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
    }


    private val mode0700: Int get() = S_IRUSR or S_IWUSR or S_IXUSR

    private fun makeClaudeDir(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val base = "$tmp/kotgent-locator-test-${getpid()}-${counter++}"
        mkdir(base, mode0700.convert()).also { dirs += base }
        val claude = "$base/.claude"
        mkdir(claude, mode0700.convert()).also { dirs += claude }
        return claude
    }

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

    private fun placeTranscriptRaw(claudeDir: String, project: String, id: ProviderSessionId, content: String) {
        val projects = "$claudeDir/projects"
        mkdir(projects, mode0700.convert()).also { if (!dirs.contains(projects)) dirs += projects }
        val projectDir = "$projects/$project"
        mkdir(projectDir, mode0700.convert()).also { if (!dirs.contains(projectDir)) dirs += projectDir }
        val path = "$projectDir/${id.value}.jsonl"
        writeFile(path, content)
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
