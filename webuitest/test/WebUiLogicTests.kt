package io.kotgent.webuitest

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Gate for the browser-independent Web UI tier in `webuitest/js/`, which runs under Node's built-in test
 * runner. The tier stays outside `resources/webui/` because `webUiRevision` digests and serves every file
 * under that tree; this class is the only thing that wires it into the aggregate suite.
 */
class WebUiLogicTests {

    @Test
    fun theBrowserIndependentWebLogicSuitePasses() {
        val root = locateRepoRoot()
        val jsDir = root.resolve(JS_RELATIVE)
        val testFiles = listTestFiles(jsDir)
        assertTrue(
            testFiles.isNotEmpty(),
            "$jsDir holds no *$TEST_SUFFIX file, so the runner below would prove nothing.",
        )

        val result = runNode(root)
        val report = "\n--- node --test stdout ---\n${result.stdout}\n--- node --test stderr ---\n${result.stderr}"

        // A missing or renamed pattern makes node exit 0 with an empty plan, so the count is load-bearing.
        val ran = tapCount(result.stdout, TESTS_SUMMARY)
            ?: fail(
                "the node runner printed no TAP summary line ('$TESTS_SUMMARY <n>'), so it never reached " +
                    "the tests. Its own output is the diagnosis$report",
            )
        assertTrue(
            ran > 0,
            "node --test matched no test at all under $jsDir (it exits 0 when its pattern " +
                "'$TEST_PATTERN' matches nothing). Files seen there: ${testFiles.joinToString()}$report",
        )
        assertTrue(
            ran >= testFiles.size,
            "node --test ran $ran tests but $jsDir holds ${testFiles.size} test files " +
                "(${testFiles.joinToString()}), so at least one file contributed nothing$report",
        )
        assertEquals(0, tapCount(result.stdout, FAIL_SUMMARY) ?: -1, "the JavaScript tier reported failures$report")
        assertEquals(0, result.exitCode, "node --test exited non-zero$report")
    }

    private fun runNode(root: Path): NodeResult {
        val command = listOf(NODE, "--test", "--test-reporter=tap", TEST_PATTERN)
        val process = try {
            ProcessBuilder(command).directory(root.toFile()).start()
        } catch (e: IOException) {
            fail(
                "`$NODE` is not on PATH, so the browser-independent Web UI tier cannot run " +
                    "(${e.message}). Node is a system prerequisite of this module, recorded in " +
                    "webuitest/module.yaml; install Node (v24 or newer) and re-run. This check never skips.",
            )
        }

        // Drain both pipes concurrently: a full stderr buffer would otherwise stall the child forever.
        val stderrLines = Collections.synchronizedList(mutableListOf<String>())
        val drain = Thread {
            process.errorStream.bufferedReader().forEachLine { stderrLines.add(it) }
        }
        drain.isDaemon = true
        drain.name = "node-test-stderr"
        drain.start()

        val stdout = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("`${command.joinToString(" ")}` did not finish within ${TIMEOUT_SECONDS}s\n$stdout")
        }
        drain.join(DRAIN_JOIN_MILLIS)
        return NodeResult(process.exitValue(), stdout, stderrLines.joinToString("\n"))
    }

    private fun listTestFiles(jsDir: Path): List<String> {
        if (!Files.isDirectory(jsDir)) {
            fail("the browser-independent Web UI tier is missing: $jsDir is not a directory")
        }
        return jsDir.toFile().listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(TEST_SUFFIX) }
            .map { it.name }
            .sorted()
    }

    /** The summary sits at the end of the stream; a child's own output is re-emitted as a nested comment. */
    private fun tapCount(stdout: String, prefix: String): Int? =
        stdout.lineSequence()
            .lastOrNull { it.startsWith("$prefix ") }
            ?.removePrefix("$prefix ")
            ?.trim()
            ?.toIntOrNull()

    // JVM test working directories are not fixed by the toolchain, so walk up as WebUiServingTest does.
    private fun locateRepoRoot(): Path {
        val starts = buildList {
            System.getProperty("user.dir")?.let { add(Path.of(it)) }
            codeSourceDirectory()?.let { add(it) }
        }.map { it.toAbsolutePath().normalize() }
        for (start in starts) {
            var dir: Path? = start
            while (dir != null) {
                val hasManifest = Files.isRegularFile(dir.resolve(PROJECT_MANIFEST))
                if (hasManifest && Files.isDirectory(dir.resolve(JS_RELATIVE))) return dir
                dir = dir.parent
            }
        }
        fail(
            "could not locate the kotgent checkout (looked for a directory holding both $PROJECT_MANIFEST " +
                "and $JS_RELATIVE, walking up from ${starts.joinToString()})",
        )
    }

    private fun codeSourceDirectory(): Path? = runCatching {
        val location = WebUiLogicTests::class.java.protectionDomain?.codeSource?.location ?: return@runCatching null
        val path = Path.of(location.toURI())
        if (Files.isDirectory(path)) path else path.parent
    }.getOrNull()

    private class NodeResult(val exitCode: Int, val stdout: String, val stderr: String)

    private companion object {
        const val NODE = "node"
        const val PROJECT_MANIFEST = "project.yaml"
        const val JS_RELATIVE = "webuitest/js"
        const val TEST_SUFFIX = ".test.js"

        // Node treats every positional argument as a glob and does not expand a bare directory,
        // so the directory is named through a pattern rather than on its own.
        const val TEST_PATTERN = "$JS_RELATIVE/**/*$TEST_SUFFIX"

        const val TESTS_SUMMARY = "# tests"
        const val FAIL_SUMMARY = "# fail"

        const val TIMEOUT_SECONDS = 120L
        const val DRAIN_JOIN_MILLIS = 2_000L
    }
}
