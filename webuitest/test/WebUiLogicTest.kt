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

/**
 * Gate for the browser-independent Web UI tier in `webuitest/js/`, which runs under Node's built-in test
 * runner. The tier stays outside `resources/webui/` because `webUiRevision` digests and serves every file
 * under that tree; this class is the only thing that wires it into the aggregate suite.
 */
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
        // A floor, not a per-file count: TAP does not attribute a passing test to the file it came from,
        // so a file that contributes nothing while another contributes extra stays invisible here. What
        // it does catch is the whole tier collapsing — a rename, a bad pattern, a file that throws on
        // import — which is the failure that would otherwise read as a clean pass.
        assertTrue(
            ran >= testFiles.size,
            "node --test ran $ran tests, fewer than the ${testFiles.size} test files $jsDir holds " +
                "(${testFiles.joinToString()}), so at least one of them contributed nothing$report",
        )
        assertEquals(0, tapCount(result.stdout, FAIL_SUMMARY) ?: -1, "the JavaScript tier reported failures$report")
        // A test that exceeds --test-timeout is cancelled, not failed, so the fail count alone would
        // report a hung test as a clean tier.
        assertEquals(
            0,
            tapCount(result.stdout, CANCELLED_SUMMARY) ?: -1,
            "the JavaScript tier cancelled a test, which is how a test that never settles is reported " +
                "once --test-timeout=${TEST_TIMEOUT_MILLIS}ms cuts it off$report",
        )
        assertEquals(0, result.exitCode, "node --test exited non-zero$report")
    }

    // The floor is a prerequisite, so it is asserted before the run rather than discovered as a parse
    // error somewhere inside a module. Every other record of the number is prose — webuitest/module.yaml,
    // README.md, docs/TESTING.md and the CI step — and none of them fails a build.
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
        // --test-timeout turns a test that never settles into a named TAP failure instead of a hang.
        // Node's runner has no default per-test timeout, and no test in webuitest/js/ sets its own, so
        // without this a single unresolved promise stalls `./kotlin test` itself. A timed-out test is
        // reported as cancelled rather than failed, which is why the count is asserted separately above.
        val command = listOf(
            NODE, "--test", "--test-reporter=tap", "--test-timeout=$TEST_TIMEOUT_MILLIS", TEST_PATTERN,
        )
        val process = try {
            ProcessBuilder(command).directory(root.toFile()).start()
        } catch (e: IOException) {
            fail(missingNodeMessage(e.message))
        }

        // Both pipes are drained on their own threads and the watchdog runs before either is joined.
        // Reading one of them inline would block until the child closed it, so the watchdog would only
        // be reached after the child had already finished — no help at all against the failure it exists
        // for, which is a child still alive with its stdout pipe open. A full pipe also stalls the child.
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
        // Recursive, because TEST_PATTERN is: a test file in a subdirectory would otherwise run without
        // ever being counted, and the floor below would not notice it going missing.
        return Files.walk(jsDir).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(TEST_SUFFIX) }
                .map { jsDir.relativize(it).toString() }
                .sorted()
                .toList()
        }
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

        // The same floor webuitest/module.yaml, README.md, docs/TESTING.md and .github/workflows/ci.yml
        // state in prose. This is the only copy that can fail a run.
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
