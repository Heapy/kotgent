package io.kotgent.webuitest

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs the browser-independent `webuitest/js/` suite under Node from the aggregate Kotlin suite. */
class WebUiLogicTest {

    @Test
    fun theBrowserIndependentWebLogicSuitePasses() {
        assertNodeMeetsTheFloor()
        val root = locateRepoRoot()
        val jsDir = root.resolve(JS_RELATIVE)
        val testFiles = listTestFiles(jsDir)
        assertTrue(
            testFiles.isNotEmpty(),
            "$jsDir holds no *$TEST_SUFFIX file, so the runner below would prove nothing.",
        )

        val result = runNode(root)
        val report = "\n--- node --test stdout ---\n${result.stdout}\n--- node --test stderr ---\n${result.stderr}"

        // Node can exit successfully with an empty plan, so validate the TAP count.
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
        // TAP cannot attribute passing tests to files; this floor catches a collapsed tier, not exact coverage.
        assertTrue(
            ran >= testFiles.size,
            "node --test ran $ran tests, fewer than the ${testFiles.size} test files $jsDir holds " +
                "(${testFiles.joinToString()}), so at least one of them contributed nothing$report",
        )
        assertEquals(0, tapCount(result.stdout, FAIL_SUMMARY) ?: -1, "the JavaScript tier reported failures$report")
        // Node reports a timed-out test as cancelled rather than failed.
        assertEquals(
            0,
            tapCount(result.stdout, CANCELLED_SUMMARY) ?: -1,
            "the JavaScript tier cancelled a test, which is how a test that never settles is reported " +
                "once --test-timeout=${TEST_TIMEOUT_MILLIS}ms cuts it off$report",
        )
        assertEquals(0, result.exitCode, "node --test exited non-zero$report")
    }

    // Check the runtime prerequisite before interpreting module parse failures.
    private fun assertNodeMeetsTheFloor() {
        val printed = try {
            val process = ProcessBuilder(NODE, "--version").redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            text
        } catch (e: IOException) {
            fail(missingNodeMessage(e.message))
        }
        val major = printed.removePrefix("v").substringBefore('.').toIntOrNull()
            ?: fail("`$NODE --version` printed '$printed', which names no major version")
        assertTrue(
            major >= NODE_MAJOR_FLOOR,
            "the browser-independent Web UI tier needs Node v$NODE_MAJOR_FLOOR or newer; the `$NODE` on " +
                "PATH is $printed. Upgrade it rather than reading the failures the runner would report " +
                "from inside the test files. This check never skips.",
        )
    }

    private fun missingNodeMessage(cause: String?): String =
        "`$NODE` is not on PATH, so the browser-independent Web UI tier cannot run ($cause). Node is a " +
            "system prerequisite of this module, recorded in webuitest/module.yaml; install Node " +
            "(v$NODE_MAJOR_FLOOR or newer) and re-run. This check never skips."

    private fun runNode(root: Path): NodeResult {
        // Node has no default per-test timeout; cancelled tests are checked separately above.
        val command = listOf(
            NODE, "--test", "--test-reporter=tap", "--test-timeout=$TEST_TIMEOUT_MILLIS", TEST_PATTERN,
        )
        val process = try {
            ProcessBuilder(command).directory(root.toFile()).start()
        } catch (e: IOException) {
            fail(missingNodeMessage(e.message))
        }

        // Drain both pipes concurrently so neither the child nor the watchdog can block on a full pipe.
        val out = drain("node-test-stdout", process.inputStream)
        val err = drain("node-test-stderr", process.errorStream)
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            out.thread.join(DRAIN_JOIN_MILLIS)
            err.thread.join(DRAIN_JOIN_MILLIS)
            fail(
                "`${command.joinToString(" ")}` did not finish within ${TIMEOUT_SECONDS}s. Every test " +
                    "carries a ${TEST_TIMEOUT_MILLIS}ms timeout of its own, so this is the runner or a " +
                    "module's top level hanging, not one test." +
                    "\n--- partial node --test stdout ---\n${out.text()}" +
                    "\n--- partial node --test stderr ---\n${err.text()}",
            )
        }
        out.thread.join(DRAIN_JOIN_MILLIS)
        err.thread.join(DRAIN_JOIN_MILLIS)
        return NodeResult(process.exitValue(), out.text(), err.text())
    }

    private fun drain(name: String, stream: InputStream): Drain {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val thread = Thread {
            val _ = runCatching { stream.bufferedReader().forEachLine { lines.add(it) } }
        }
        thread.isDaemon = true
        thread.name = name
        thread.start()
        return Drain(thread, lines)
    }

    private fun listTestFiles(jsDir: Path): List<String> {
        if (!Files.isDirectory(jsDir)) {
            fail("the browser-independent Web UI tier is missing: $jsDir is not a directory")
        }
        // Match the runner's recursive pattern when validating its test-file floor.
        return Files.walk(jsDir).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(TEST_SUFFIX) }
                .map { jsDir.relativize(it).toString() }
                .sorted()
                .toList()
        }
    }

    /** Reads the final TAP summary, skipping nested child output. */
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
        val location = WebUiLogicTest::class.java.protectionDomain?.codeSource?.location ?: return@runCatching null
        val path = Path.of(location.toURI())
        if (Files.isDirectory(path)) path else path.parent
    }.getOrNull()

    private class NodeResult(val exitCode: Int, val stdout: String, val stderr: String)

    /** One pipe being read by its own thread, and whatever it has seen so far. */
    private class Drain(val thread: Thread, private val lines: MutableList<String>) {
        fun text(): String = synchronized(lines) { lines.joinToString("\n") }
    }

    private companion object {
        const val NODE = "node"

        // Executable counterpart to the documented Node prerequisite.
        const val NODE_MAJOR_FLOOR = 24
        const val PROJECT_MANIFEST = "project.yaml"
        const val JS_RELATIVE = "webuitest/js"
        const val TEST_SUFFIX = ".test.js"

        // Node treats every positional argument as a glob and does not expand a bare directory,
        // so the directory is named through a pattern rather than on its own.
        const val TEST_PATTERN = "$JS_RELATIVE/**/*$TEST_SUFFIX"

        const val TESTS_SUMMARY = "# tests"
        const val FAIL_SUMMARY = "# fail"
        const val CANCELLED_SUMMARY = "# cancelled"

        // Per test, inside node. The whole tier runs in well under a second, so this is a hang detector
        // rather than a budget.
        const val TEST_TIMEOUT_MILLIS = 30_000L

        const val TIMEOUT_SECONDS = 120L
        const val DRAIN_JOIN_MILLIS = 2_000L
    }
}
