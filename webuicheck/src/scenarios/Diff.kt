package io.kotgent.webuicheck.scenarios

import io.kotgent.core.SessionId
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.proc.CapturedProcess
import io.kotgent.task.TaskState
import io.kotgent.webuicheck.COMMAND_ACK_PREFIX
import io.kotgent.webuicheck.Scenario
import io.kotgent.webuicheck.writeStdoutLine
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

/** All writing Git commands are confined to this fixture's mktemp directory. The server uses GitDiffReader. */
fun diffScenario(longHistory: Boolean = false): Scenario {
    var directory: String? = null
    @IgnorableReturnValue
    suspend fun execute(argv: List<String>): String {
        val started = TimeSource.Monotonic.markNow()
        val command = if (argv.first() == "/bin/sh") argv[3] else argv.joinToString(" ")
        val result = try {
            CapturedProcess.run(argv, DIFF_FIXTURE_ENV)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw IllegalStateException("Diff fixture: $command failed after ${started.elapsedNow()}", failure)
        }
        check(result.exitCode == 0) { "Diff fixture: $command exited ${result.exitCode}: ${result.stderr.decodeToString()}" }
        return result.stdout.decodeToString().trim()
    }
    @IgnorableReturnValue
    suspend fun git(vararg args: String): String =
        execute(listOf("/usr/bin/git", "-C", "${checkNotNull(directory)}/repo") + args)
    suspend fun write(path: String, content: String) {
        // Only shell redirection is needed; paths and fixture bytes are literal positional arguments.
        execute(listOf("/bin/sh", "-c", "printf '%s' \"\$2\" > \"\$1\"", "write-fixture", path, content))
    }
    return Scenario(
        name = if (longHistory) "diff-history" else "diff",
        seed = { fakes ->
            val dir = execute(listOf("/usr/bin/mktemp", "-d", "/tmp/kotgent-web-diff.XXXXXX"))
            directory = dir
            execute(listOf("/usr/bin/git", "init", "-b", "main", "$dir/repo"))
            val importFile = "$dir/history.fi"
            write(importFile, diffHistoryImport(if (longHistory) 101 else 0))
            execute(listOf("/bin/sh", "-c", "exec /usr/bin/git -C \"\$1\" fast-import --quiet --done < \"\$2\"",
                "import-fixture", "$dir/repo", importFile))
            git("symbolic-ref", "HEAD", "refs/heads/topic")
            git("reset", "--hard", "HEAD")
            git("worktree", "add", "-b", "no-session", "$dir/no-session", "main")
            write("$dir/no-session/unowned.txt", "worktree without a session\n")
            git("worktree", "add", "--detach", "$dir/clean", "main")
            git("worktree", "lock", "$dir/clean")
            git("worktree", "add", "-b", "missing", "$dir/missing", "main")
            execute(listOf("/bin/rm", "-rf", "--", "$dir/missing"))
            write("$dir/repo/tracked.txt", fixtureLines("staged"))
            write("$dir/repo/reversed.txt", "staged reversal\n")
            git("add", "tracked.txt", "reversed.txt")
            write("$dir/repo/tracked.txt", fixtureLines("working"))
            write("$dir/repo/reversed.txt", "original\n")
            git("rm", "--cached", "removed-index.txt")
            write("$dir/repo/untracked.txt", "untracked content\n")
            val project = fixtureProject(fakes, "dddddddd-dddd-4ddd-8ddd-dddddddddddd", "Diff fixture", "$dir/repo")
            fakes.taskStore.seedTask(TaskRef("local:1"), project, "Review Git changes", state = TaskState.in_progress)
            fakes.taskStore.seedTask(TaskRef("local:2"), project, "Dependency without a session")
            fakes.taskStore.seedDependency(TaskRef("local:1"), TaskRef("local:2"))
            fixtureSession(fakes, "s-diff", "diff-review", "claude", "$dir/repo", SEED_EPOCH_MS,
                project, TaskRef("local:1"), SessionState.running)
        },
        terminalUpstream = deterministicUpstream("KOTGENT-DIFF-READY"),
        command = { words, fakes ->
            when (words.firstOrNull()) {
                "diff-edit", "diff-head" -> {
                    check(words.size == 1)
                    if (words.single() == "diff-edit") {
                        write("${checkNotNull(directory)}/repo/tracked.txt", "refreshed content\n")
                    } else {
                        git("commit", "--allow-empty", "-m", "Advanced fixture HEAD")
                    }
                    writeStdoutLine(COMMAND_ACK_PREFIX + words.single())
                    true
                }
                "diff-unlink" -> {
                    check(words.size == 1)
                    fakes.eventStore.setTaskRef(SessionId("s-diff"), null)
                    writeStdoutLine(COMMAND_ACK_PREFIX + words.single())
                    true
                }
                else -> null
            }
        },
        cleanup = {
            directory?.let { dir ->
                check(dir.startsWith("/tmp/kotgent-web-diff.") && '/' !in dir.removePrefix("/tmp/"))
                execute(listOf("/bin/rm", "-rf", "--", dir))
                directory = null
            }
        },
    )
}

private val DIFF_FIXTURE_ENV = mapOf(
    "PATH" to "/usr/bin:/bin", "HOME" to "/nonexistent", "GIT_CONFIG_NOSYSTEM" to "1",
    "GIT_AUTHOR_NAME" to "Fixture", "GIT_AUTHOR_EMAIL" to "fixture@example.test",
    "GIT_COMMITTER_NAME" to "Fixture", "GIT_COMMITTER_EMAIL" to "fixture@example.test",
    "GIT_AUTHOR_DATE" to "2026-01-01T00:00:00Z", "GIT_COMMITTER_DATE" to "2026-01-01T00:00:00Z",
    "GIT_TERMINAL_PROMPT" to "0",
    // Fixture children must not detach maintenance that races with removal of their temporary repo.
    "GIT_CONFIG_COUNT" to "2", "GIT_CONFIG_KEY_0" to "maintenance.auto", "GIT_CONFIG_VALUE_0" to "false",
    "GIT_CONFIG_KEY_1" to "gc.auto", "GIT_CONFIG_VALUE_1" to "0",
)

private fun fixtureLines(kind: String): String = (0..<160).joinToString("") { "$kind line $it\n" }

/** One Git process writes the graph, including the real merge and its distinct parent trees. */
private fun diffHistoryImport(emptyCommits: Int): String = buildString {
    fun data(text: String) {
        append("data ${text.encodeToByteArray().size}\n$text\n")
    }
    fun commit(branch: String, mark: Int, message: String, parent: Int? = null, merge: Int? = null,
               files: Map<String, String> = emptyMap()) {
        append("commit refs/heads/$branch\nmark :$mark\n")
        append("committer Fixture <fixture@example.test> 1767225600 +0000\n")
        data(message)
        if (parent != null) append("from :$parent\n")
        if (merge != null) append("merge :$merge\n")
        for ((key, value) in files) {
            append("M 100644 inline $key\n")
            data(value)
        }
        append('\n')
    }
    commit("main", 1, "Root fixture", files = mapOf(
        "tracked.txt" to fixtureLines("original"), "reversed.txt" to "original\n",
        "removed-index.txt" to "still on disk\n",
    ))
    append("reset refs/heads/base\nfrom :1\n\n")
    commit("topic", 2, "Topic change", parent = 1, files = mapOf("topic.txt" to "topic content\n"))
    repeat(emptyCommits) { index ->
        commit("topic", index + 3, "History ${index.toString().padStart(3, '0')}", parent = index + 2)
    }
    val side = emptyCommits + 3
    commit("side", side, "Side change", parent = 1, files = mapOf("side.txt" to "side content\n"))
    commit("topic", side + 1, "Merge side fixture", parent = side - 1, merge = side,
        files = mapOf("side.txt" to "side content\n"))
    append("done\n")
}
