package io.kotgent.webuicheck.scenarios

import io.kotgent.core.SessionId
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.proc.CapturedProcess
import io.kotgent.task.TaskState
import io.kotgent.webuicheck.COMMAND_ACK_PREFIX
import io.kotgent.webuicheck.Scenario
import io.kotgent.webuicheck.writeStdoutLine

/** All writing Git commands are confined to this fixture's mktemp directory. The server uses GitDiffReader. */
fun diffScenario(): Scenario {
    var directory: String? = null
    @IgnorableReturnValue
    suspend fun execute(argv: List<String>): String {
        val result = CapturedProcess.run(argv, DIFF_FIXTURE_ENV)
        check(result.exitCode == 0) { "Diff fixture: ${result.stderr.decodeToString()}" }
        return result.stdout.decodeToString().trim()
    }
    return Scenario(
        name = "diff",
        seed = { fakes ->
            val dir = execute(listOf("/usr/bin/mktemp", "-d", "/tmp/kotgent-web-diff.XXXXXX"))
            directory = dir
            execute(listOf("/usr/bin/python3", "-c", DIFF_SETUP, dir))
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
                    execute(listOf("/usr/bin/python3", "-c", DIFF_MUTATE, checkNotNull(directory), words.single()))
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
)

private val DIFF_SETUP = """
import pathlib, subprocess, sys, shutil
root = pathlib.Path(sys.argv[1])
repo = root / 'repo'
repo.mkdir()
def git(*args):
    return subprocess.check_output(['/usr/bin/git', '-C', str(repo), *args], stderr=subprocess.PIPE).decode().strip()
def write(name, text):
    (repo / name).write_text(text)
git('init', '-b', 'main', str(repo))
write('tracked.txt', ''.join('original line %d\n' % i for i in range(160)))
write('reversed.txt', 'original\n')
write('removed-index.txt', 'still on disk\n')
git('add', '.'); git('commit', '-m', 'Root fixture')
git('branch', 'base')
git('checkout', '-b', 'topic')
write('topic.txt', 'topic content\n'); git('add', '.'); git('commit', '-m', 'Topic change')
for i in range(101):
    git('commit', '--allow-empty', '-m', 'History %03d' % i)
git('checkout', '-b', 'side', 'main')
write('side.txt', 'side content\n'); git('add', '.'); git('commit', '-m', 'Side change')
git('checkout', 'topic'); git('merge', '--no-ff', 'side', '-m', 'Merge side fixture')
git('worktree', 'add', '-b', 'no-session', str(root / 'no-session'), 'main')
(root / 'no-session' / 'unowned.txt').write_text('worktree without a session\n')
git('worktree', 'add', '--detach', str(root / 'clean'), 'main')
git('worktree', 'lock', str(root / 'clean'))
git('worktree', 'add', '-b', 'missing', str(root / 'missing'), 'main')
shutil.rmtree(root / 'missing')
write('tracked.txt', ''.join('staged line %d\n' % i for i in range(160)))
write('reversed.txt', 'staged reversal\n')
git('add', 'tracked.txt', 'reversed.txt')
write('tracked.txt', ''.join('working line %d\n' % i for i in range(160)))
write('reversed.txt', 'original\n')
git('rm', '--cached', 'removed-index.txt')
write('untracked.txt', 'untracked content\n')
""".trimIndent()

private val DIFF_MUTATE = """
import pathlib, subprocess, sys
repo = pathlib.Path(sys.argv[1]) / 'repo'
if sys.argv[2] == 'diff-edit':
    (repo / 'tracked.txt').write_text('refreshed content\n')
else:
    subprocess.check_call(['/usr/bin/git', '-C', str(repo), 'commit', '--allow-empty', '-m', 'Advanced fixture HEAD'], stdout=subprocess.DEVNULL)
""".trimIndent()
