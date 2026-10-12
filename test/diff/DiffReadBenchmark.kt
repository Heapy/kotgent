@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.kotgent.diff

import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.crypto.sha256
import io.kotgent.proc.CapturedProcess
import io.kotgent.proc.CaptureTimings
import io.kotgent.store.FakeEventStore
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import platform.posix.*
import kotlin.test.*
import kotlin.time.TimeSource

/** Explicit opt-in only. Each invocation owns and removes its entire temporary fixture. */
class DiffReadBenchmark {
    @Test fun temporaryFixture() = runBlocking<Unit> {
        if (getenv("KOTGENT_DIFF_BENCH")?.toKString() != "1") return@runBlocking
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
        val directory = command("/usr/bin/mktemp", "-d", "/tmp/kotgent-diff-benchmark.XXXXXX")
        val root = "$directory/repo"
        suspend fun git(vararg args: String): String = command("/usr/bin/git", "-C", root, *args)
        fun write(name: String, bytes: ByteArray) {
            val file = assertNotNull(fopen("$root/$name", "wb"))
            try { bytes.usePinned { assertEquals(bytes.size.toULong(), fwrite(it.addressOf(0), 1u, bytes.size.convert(), file)) } }
            finally { fclose(file) }
        }
        val counters = mutableMapOf<String, Long>()
        val samples = mutableMapOf<String, MutableList<Long>>()
        suspend fun <T> measure(label: String, run: Int, block: suspend () -> T): T {
            counters.clear()
            val start = TimeSource.Monotonic.markNow()
            val result = block()
            val nanos = start.elapsedNow().inWholeNanoseconds
            samples.getOrPut(label) { mutableListOf() }.add(nanos)
            val totalReadBytes = listOf("gitBytes", "fileBytes", "dependencyReadBytes", "cacheReadBytes").sumOf { counters[it] ?: 0 }
            val totalHashBytes = (counters["hashBytes"] ?: 0) + (counters["cacheHashBytes"] ?: 0)
            println("DIFF_BENCH run=$run scenario=$label ns=$nanos " +
                listOf("gitCommands", "gitBytes", "fileBytes", "hashBytes", "logCommands", "diffCommands",
                    "dependencyReadBytes", "cacheReadBytes", "cacheWriteBytes", "cacheHashBytes")
                    .joinToString(" ") { "$it=" + (counters[it] ?: 0) } +
                " totalReadBytes=$totalReadBytes totalHashBytes=$totalHashBytes")
            return result
        }
        fun <T> DiffResponse<T>.ok(): T = assertNotNull(data, error.toString())
        try {
            val _ = command("/usr/bin/git", "init", "-b", "main", root)
            write("selected.txt", "original\n".encodeToByteArray())
            repeat(24) { write("tracked-$it.txt", "original $it\n".encodeToByteArray()) }
            val _ = git("add", "--all")
            val _ = git("commit", "-m", "root")
            val rootOid = git("rev-parse", "HEAD")
            write("selected.txt", "staged\n".encodeToByteArray())
            val _ = git("add", "selected.txt")
            write("selected.txt", "working\n".encodeToByteArray())
            repeat(24) { write("tracked-$it.txt", "changed $it\n".encodeToByteArray()) }
            // 12 MiB of unrelated, below-per-file-cap binary data exercises global fingerprints.
            repeat(12) { write("untracked-$it.bin", ByteArray(1024 * 1024) { (it % 251).toByte() }) }
            val reader = GitDiffReader(measure = { name, value -> counters[name] = (counters[name] ?: 0) + value })
            val inventory = reader.worktrees(root).ok()
            val tree = inventory.worktrees.single()
            val id = SessionId("diff-benchmark")
            val store = FakeEventStore()
            store.upsertSession(SessionMeta(id, name = "", agent = "shell", cwd = root, tmuxSession = "unused",
                state = SessionState.running, createdAt = 0, updatedAt = 0))
            val service = DiffService(store, reader)
            println("DIFF_BENCH fixture=v1 tracked=25 untracked=12 binaryBytes=12582912 runs=$runs cache=none osCache=uncontrolled")
            repeat(runs) { run ->
                val _ = measure("sha256-16MiB", run) { sha256(ByteArray(16 * 1024 * 1024) { 1 }) }
                measure("capture-10-true", run) {
                    repeat(10) { call ->
                        val phases = CaptureTimings()
                        assertEquals(0, CapturedProcess.run(listOf("/usr/bin/true"), timings = phases).exitCode)
                        println("CAPTURE_BENCH run=$run call=$call dispatchNs=" + phases.dispatchNanos +
                            " spawnNs=" + phases.spawnNanos + " drainNs=" + phases.drainNanos +
                            " reapNs=" + phases.reapNanos + " returnNs=" + phases.returnNanos + " closeNs=" + phases.closeNanos)
                    }
                }
                val _ = measure("inventory", run) { reader.worktrees(root).ok() }
                for (scope in DiffLocalScope.entries) {
                    val files = measure("reader-files-$scope", run) {
                        reader.files(inventory.repository, tree, DiffSelection(scope = scope)).ok()
                    }
                    val selected = files.files.single { it.newPath == "selected.txt" }
                    val _ = measure("reader-patch-$scope", run) { reader.patch(inventory.repository, tree, files.snapshot, selected).ok() }
                }
                val files = measure("service-files", run) { service.files(id, tree.id, DiffSelection()).ok() }
                val selected = files.files.single { it.newPath == "selected.txt" }
                val _ = measure("service-patch", run) { service.patch(id, tree.id, files.snapshot.id, selected.fileId).ok() }
                val history = measure("history", run) { reader.commits(inventory.repository, tree, null, null).ok() }
                val rootFiles = measure("root-files", run) { reader.files(inventory.repository, tree, DiffSelection(mode = DiffMode.commit, commitOid = rootOid)).ok() }
                // Additional reads leave the v1 fixture unchanged, so its original scenarios remain comparable.
                val _ = measure("history-pinned-page", run) {
                    reader.commits(inventory.repository, tree, null, assertNotNull(history.cursor).copy(offset = DIFF_COMMIT_PAGE_SIZE)).ok()
                }
                val _ = measure("service-inventory", run) { service.worktrees(id).ok() }
                val _ = measure("service-history", run) { service.commits(id, tree.id, null, null).ok() }
                val _ = measure("root-patch", run) {
                    reader.patch(inventory.repository, tree, rootFiles.snapshot, rootFiles.files.single { it.newPath == "selected.txt" }).ok()
                }
            }
            // Application disk cold/warm, separate from all original v1 cache-disabled scenarios.
            repeat(runs) { run ->
                val cacheRoot = "$directory/cache-$run"
                val report: (String, Long) -> Unit = { name, value -> counters[name] = (counters[name] ?: 0) + value }
                fun cached() = GitDiffReader(diskCache = DiffDiskCache(
                    io.kotgent.host.DiskCacheFiles(cacheRoot, measure = report), measure = report), measure = report)
                val cold = cached()
                val _ = measure("disk-cold-history", run) { cold.commits(inventory.repository, tree).ok() }
                val list = measure("disk-cold-root-files", run) {
                    cold.files(inventory.repository, tree, DiffSelection(DiffMode.commit, commitOid = rootOid)).ok()
                }
                val selected = list.files.single { it.newPath == "selected.txt" }
                val _ = measure("disk-cold-root-patch", run) { cold.patch(inventory.repository, tree, list.snapshot, selected).ok() }
                val restarted = cached()
                val _ = measure("disk-warm-history", run) { restarted.commits(inventory.repository, tree).ok() }
                val warm = measure("disk-warm-root-files", run) {
                    restarted.files(inventory.repository, tree, DiffSelection(DiffMode.commit, commitOid = rootOid)).ok()
                }
                val _ = measure("disk-warm-root-patch", run) { restarted.patch(inventory.repository, tree, warm.snapshot, selected).ok() }
            }
            for ((key, value) in samples) {
                val sorted = value.sorted()
                val n = sorted.size
                println("DIFF_SUMMARY scenario=$key count=$n p50Ns=" + sorted[(n - 1) / 2] +
                    " p95Ns=" + sorted[(kotlin.math.ceil(n * .95).toInt() - 1)])
            }
        } finally {
            val _ = command("/bin/rm", "-rf", directory)
        }
    }
}
