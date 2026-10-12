@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.kotgent.diff

import io.kotgent.host.DiskCacheFiles
import io.kotgent.proc.CapturedProcess
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import platform.posix.*
import kotlin.test.*
import kotlin.time.TimeSource

/** Opt-in integration matrix; fixture setup and cache priming are outside timed samples. */
class DiffMatrixBenchmark {
    @Test fun temporaryMatrix() = runBlocking<Unit> {
        if (getenv("KOTGENT_DIFF_MATRIX")?.toKString() != "1") return@runBlocking
        val runs = getenv("KOTGENT_DIFF_BENCH_RUNS")?.toKString()?.toInt() ?: 5
        require(runs >= 5)
        val env = mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/nonexistent", "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_AUTHOR_NAME" to "Benchmark", "GIT_AUTHOR_EMAIL" to "benchmark@example.test",
            "GIT_COMMITTER_NAME" to "Benchmark", "GIT_COMMITTER_EMAIL" to "benchmark@example.test",
            "GIT_AUTHOR_DATE" to "2026-01-01T00:00:00Z", "GIT_COMMITTER_DATE" to "2026-01-01T00:00:00Z")
        suspend fun command(vararg args: String): String {
            val result = CapturedProcess.run(args.toList(), env)
            assertEquals(0, result.exitCode, result.stderr.decodeToString())
            return result.stdout.decodeToString().trim()
        }
        val directory = command("/usr/bin/mktemp", "-d", "/tmp/kotgent-diff-matrix.XXXXXX")
        val root = "$directory/repo"
        suspend fun git(vararg args: String): String = command("/usr/bin/git", "-C", root, *args)
        fun write(path: String, bytes: ByteArray) {
            val file = assertNotNull(fopen(path, "wb"))
            try { bytes.usePinned { assertEquals(bytes.size.toULong(), fwrite(it.addressOf(0), 1u, bytes.size.convert(), file)) } }
            finally { fclose(file) }
        }
        fun <T> DiffResponse<T>.ok(): T = assertNotNull(data, error.toString())
        val counters = mutableMapOf<String, Long>()
        val report: (String, Long) -> Unit = { name, value -> counters[name] = (counters[name] ?: 0) + value }
        fun reader(cacheRoot: String?) = GitDiffReader(measure = report,
            diskCache = cacheRoot?.let { DiffDiskCache(DiskCacheFiles(it, measure = report), measure = report) })
        val samples = mutableMapOf<String, MutableList<Long>>()
        suspend fun measure(label: String, run: Int, block: suspend () -> Unit) {
            counters.clear()
            val start = TimeSource.Monotonic.markNow()
            block()
            val nanos = start.elapsedNow().inWholeNanoseconds
            samples.getOrPut(label) { mutableListOf() }.add(nanos)
            val totalRead = listOf("gitBytes", "fileBytes", "dependencyReadBytes", "cacheReadBytes").sumOf { counters[it] ?: 0 }
            val totalHash = (counters["hashBytes"] ?: 0) + (counters["cacheHashBytes"] ?: 0)
            println("DIFF_MATRIX run=$run scenario=$label ns=$nanos " +
                listOf("gitCommands", "gitBytes", "fileBytes", "hashBytes", "logCommands", "diffCommands",
                    "dependencyReadBytes", "cacheReadBytes", "cacheWriteBytes", "cacheHashBytes")
                    .joinToString(" ") { "$it=" + (counters[it] ?: 0) } +
                " totalReadBytes=$totalRead totalHashBytes=$totalHash")
            if (label.startsWith("warm-") && !label.contains("local-")) {
                assertEquals(0L, counters["logCommands"] ?: 0, label)
                assertEquals(0L, counters["diffCommands"] ?: 0, label)
            }
        }
        try {
            val _ = command("/usr/bin/git", "init", "-b", "main", root)
            write("$root/selected.txt", "root\n".encodeToByteArray())
            repeat(24) { write("$root/tracked-$it.txt", "original $it\n".encodeToByteArray()) }
            val _ = git("add", ".")
            val _ = git("commit", "-m", "root")
            val rootOid = git("rev-parse", "HEAD")
            // Empty commits make a real nonempty second page without changing the local byte workload.
            repeat(105) { val _ = git("commit", "--allow-empty", "-m", "history-$it") }
            val _ = git("branch", "base")
            val _ = git("checkout", "-b", "side")
            write("$root/side.txt", "side\n".encodeToByteArray())
            val _ = git("add", ".")
            val _ = git("commit", "-m", "side")
            val _ = git("checkout", "main")
            write("$root/selected.txt", "committed\n".encodeToByteArray())
            val _ = git("add", ".")
            val _ = git("commit", "-m", "ordinary")
            val ordinaryOid = git("rev-parse", "HEAD")
            val _ = git("merge", "--no-ff", "side", "-m", "merge")
            val mergeOid = git("rev-parse", "HEAD")
            val _ = git("worktree", "add", "--detach", "$directory/linked", "HEAD")
            for (path in listOf(root, "$directory/linked")) {
                write("$path/selected.txt", "staged\n".encodeToByteArray())
                val _ = command("/usr/bin/git", "-C", path, "add", "selected.txt")
                write("$path/selected.txt", "working\n".encodeToByteArray())
                repeat(24) { write("$path/tracked-$it.txt", "changed $it\n".encodeToByteArray()) }
                repeat(12) { write("$path/untracked-$it.bin", ByteArray(1024 * 1024) { (it % 251).toByte() }) }
            }
            val discovery = reader(null).worktrees(root).ok()
            println("DIFF_MATRIX fixture=v2 commits=109 worktrees=2 tracked=26 untrackedPerTree=12 binaryBytesPerTree=12582912 runs=$runs osCache=uncontrolled rootOid=$rootOid headOid=$mergeOid")
            for ([treeIndex, tree] in discovery.worktrees.withIndex()) {
                val repository = discovery.repository
                val history = reader(null).commits(repository, tree).ok()
                val cursor = assertNotNull(history.nextCursor)
                assertTrue(reader(null).commits(repository, tree, null, cursor).ok().commits.isNotEmpty())
                val selections = buildMap {
                    for (scope in DiffLocalScope.entries) put("local-$scope", DiffSelection(scope = scope))
                    put("root", DiffSelection(DiffMode.commit, commitOid = rootOid))
                    put("commit", DiffSelection(DiffMode.commit, commitOid = ordinaryOid))
                    put("merge-parent1", DiffSelection(DiffMode.commit, commitOid = mergeOid, parent = 1))
                    put("merge-parent2", DiffSelection(DiffMode.commit, commitOid = mergeOid, parent = 2))
                    put("branch", DiffSelection(DiffMode.branch, baseRef = "refs/heads/base"))
                }
                for (state in listOf("uncached", "cold", "warm")) repeat(runs) { run ->
                    suspend fun sampled(name: String, prime: suspend (GitDiffReader) -> Unit = {}, action: suspend (GitDiffReader) -> Unit) {
                        val cacheRoot = if (state == "uncached") null else "$directory/cache-$treeIndex-$state-$run-$name"
                        if (state == "warm") prime(reader(cacheRoot))
                        measure("$state-tree$treeIndex-$name", run) { action(reader(cacheRoot)) }
                    }
                    for ([name, page] in listOf("history" to null, "next-page" to cursor)) {
                        val action: suspend (GitDiffReader) -> Unit = { r ->
                            assertTrue(r.commits(repository, tree, null, page).ok().commits.isNotEmpty())
                        }
                        sampled(name, action, action)
                    }
                    for ([name, selection] in selections) {
                        val filesAction: suspend (GitDiffReader) -> Unit = { r ->
                            assertTrue(r.files(repository, tree, selection).ok().files.isNotEmpty())
                        }
                        sampled("$name-files", filesAction, filesAction)
                        // Prepare snapshot without populating the timed patch cache.
                        val files = reader(null).files(repository, tree, selection).ok()
                        val selected = files.files.first { it.kind == DiffFileKind.text }
                        val patchAction: suspend (GitDiffReader) -> Unit = { r ->
                            assertNull(r.patch(repository, tree, files.snapshot, selected).ok().limited)
                        }
                        sampled("$name-patch", patchAction, patchAction)
                    }
                }
            }
            for ([key, values] in samples) {
                val sorted = values.sorted()
                println("DIFF_MATRIX_SUMMARY scenario=$key count=${sorted.size} p50Ns=${sorted[(sorted.size - 1) / 2]} " +
                    "p95Ns=${sorted[kotlin.math.ceil(sorted.size * .95).toInt() - 1]}")
            }
        } finally { val _ = command("/bin/rm", "-rf", directory) }
    }
}
