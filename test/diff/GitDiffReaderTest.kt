@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.kotgent.diff

import io.kotgent.host.readRegularFileBytesOrNull
import io.kotgent.proc.CapturedProcess
import kotlinx.cinterop.cstr
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.symlink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitDiffReaderTest {
    private val env = mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/nonexistent",
        "GIT_CONFIG_NOSYSTEM" to "1", "GIT_AUTHOR_NAME" to "Ada", "GIT_AUTHOR_EMAIL" to "ada@example.test",
        "GIT_COMMITTER_NAME" to "Ada", "GIT_COMMITTER_EMAIL" to "ada@example.test", "GIT_TERMINAL_PROMPT" to "0")

    private inner class Repo(val directory: String) {
        val root = "$directory/repo"
        val reader = GitDiffReader()
        @IgnorableReturnValue
        suspend fun git(vararg args: String, path: String = root, success: Boolean = true): String {
            val result = CapturedProcess.run(listOf("/usr/bin/git", "-C", path) + args, env)
            if (success) assertEquals(0, result.exitCode, result.stderr.decodeToString())
            return result.stdout.decodeToString().removeSuffix("\n")
        }
        fun write(path: String, content: String) = writeBytes(path, content.encodeToByteArray())
        fun writeBytes(path: String, content: ByteArray) {
            val file = assertNotNull(fopen("$root/$path", "wb"))
            try { if (content.isNotEmpty()) content.usePinned {
                assertEquals(content.size.toULong(), fwrite(it.addressOf(0), 1u, content.size.convert(), file))
            } } finally { fclose(file) }
        }
        @IgnorableReturnValue
        suspend fun commit(text: String = "initial"): String {
            git("add", "--all"); git("commit", "-m", text)
            return git("rev-parse", "HEAD")
        }
        suspend fun inventory(): DiffWorktrees = reader.worktrees(root).ok()
        suspend fun files(selection: DiffSelection = DiffSelection()): DiffFiles {
            val inventory = inventory()
            return reader.files(inventory.repository, inventory.worktrees.first { it.current }, selection).ok()
        }
        suspend fun patch(files: DiffFiles, file: DiffFile = files.files.first()): DiffResponse<DiffPatch> {
            val inventory = inventory()
            return reader.patch(inventory.repository, inventory.worktrees.first { it.current }, files.snapshot, file)
        }
    }

    @IgnorableReturnValue
    private fun <T> DiffResponse<T>.ok(): T = assertNotNull(data, error.toString())

    private fun fixture(format: String = "sha1", block: suspend Repo.() -> Unit) = runBlocking {
        val temp = CapturedProcess.run(listOf("/usr/bin/mktemp", "-d", "/tmp/kotgent-git-test.XXXXXX"), env)
        assertEquals(0, temp.exitCode)
        val directory = temp.stdout.decodeToString().trim()
        try {
            val init = CapturedProcess.run(listOf("/usr/bin/git", "init", "--object-format=$format", "-b", "main", "$directory/repo"), env)
            assertEquals(0, init.exitCode)
            Repo(directory).block()
        } finally {
            assertEquals(0, CapturedProcess.run(listOf("/bin/rm", "-rf", directory), env).exitCode)
        }
    }

    @Test fun inventoriesLinkedDetachedLockedMissingAndBareRepositories() = fixture {
        write("a", "one\n"); commit()
        git("worktree", "add", "-b", "topic", "$directory/linked\nspace")
        git("worktree", "add", "--detach", "$directory/detached")
        git("worktree", "lock", "$directory/linked\nspace")
        val inventory = inventory()
        assertEquals(3, inventory.worktrees.size)
        assertTrue(inventory.worktrees.single { it.locked }.path.endsWith("linked\nspace"))
        assertTrue(inventory.worktrees.single { it.detached }.headOid != null)
        val linked = inventory.worktrees.single { it.locked }
        assertTrue(reader.files(inventory.repository, linked, DiffSelection()).ok().files.isEmpty())
        val _ = CapturedProcess.run(listOf("/bin/rm", "-rf", "$directory/detached"), env)
        assertNotNull(inventory().worktrees.single { it.detached }.unavailable)
        git("clone", "--bare", root, "$directory/bare")
        val bare = reader.worktrees("$directory/bare").ok()
        assertEquals(DiffReason.bare_worktree, reader.files(bare.repository, bare.worktrees.single(), DiffSelection()).error?.reason)
        assertEquals(DiffReason.foreign_worktree, reader.refs(bare.repository, linked).error?.reason)
    }

    @Test fun allIsNetChangeAndLocalSnapshotsDetectSameStatusSameSizeEdits() = fixture {
        write("a", "original\n"); commit()
        write("a", "staged!!\n"); git("add", "a"); write("a", "original\n")
        assertEquals(emptyList(), files().files)
        assertEquals(1, files(DiffSelection(scope = DiffLocalScope.staged)).files.size)
        assertEquals(1, files(DiffSelection(scope = DiffLocalScope.unstaged)).files.size)
        write("a", "changed!\n")
        val first = files()
        assertTrue(patch(first).ok().hunks.isNotEmpty())
        assertEquals(0, CapturedProcess.run(listOf("/bin/cp", "-p", "$root/a", "$directory/stamp"), env).exitCode)
        write("a", "another!\n")
        assertEquals(0, CapturedProcess.run(listOf("/usr/bin/touch", "-r", "$directory/stamp", "$root/a"), env).exitCode)
        assertEquals(DiffReason.snapshot_changed, patch(first).error?.reason)
        assertNotEquals(first.snapshot.id, files().snapshot.id)
    }

    @Test fun untrackedIgnoredUnbornBinarySymlinkAndOddPaths() = fixture {
        write(".gitignore", "ignored\n")
        write("ignored", "hidden")
        write("odd\tline\nname", "hello\r\nlast")
        writeBytes("binary", byteArrayOf(0, 1, 2))
        assertEquals(0, symlink("/etc/passwd", "$root/link"))
        val files = files()
        assertFalse(files.files.any { it.newPath == "ignored" })
        assertEquals(DiffFileKind.binary, files.files.single { it.newPath == "binary" }.kind)
        assertEquals(DiffFileKind.symlink, files.files.single { it.newPath == "link" }.kind)
        val patch = patch(files, files.files.single { it.newPath == "odd\tline\nname" }).ok()
        assertEquals("hello\r", patch.hunks.single().lines.first().text)
        assertTrue(patch.hunks.single().lines.last().noNewline)
        val inventory = inventory()
        val history = reader.commits(inventory.repository, inventory.worktrees.single()).ok()
        assertTrue(history.commits.isEmpty()); assertNull(history.cursor)
        git("add", ".gitignore")
        assertEquals(1, files(DiffSelection(scope = DiffLocalScope.staged)).files.size)
        commit("special files")
        writeBytes("binary", byteArrayOf(0, 3, 4))
        val tracked = files()
        val binary = tracked.files.single { it.newPath == "binary" }
        assertEquals(DiffFileKind.binary, binary.kind)
        assertNull(binary.additions)
        assertTrue(patch(tracked, binary).ok().hunks.isEmpty())
    }

    @Test fun immutableCommitRootMergeParentsAndBranchDivergence() = fixture {
        write("a", "root\n"); val rootOid = commit()
        val rootFiles = files(DiffSelection(DiffMode.commit, commitOid = rootOid))
        assertNull(rootFiles.snapshot.comparison.leftOid)
        assertEquals(DiffChange.added, rootFiles.files.single().change)
        git("checkout", "-b", "topic")
        write("topic", "topic\n"); val topic = commit("topic")
        git("checkout", "main")
        write("main", "main\n"); val base = commit("main")
        git("checkout", "topic")
        val branch = files(DiffSelection(DiffMode.branch, baseRef = "refs/heads/main"))
        assertEquals(rootOid, branch.snapshot.comparison.mergeBaseOid)
        assertEquals(listOf("topic"), branch.files.map { it.newPath })
        val inventory = inventory()
        val commits = reader.commits(inventory.repository, inventory.worktrees.single(), "refs/heads/main").ok()
        assertEquals(listOf(topic), commits.commits.map { it.oid })
        git("merge", "--no-ff", "main", "-m", "merge")
        val merge = git("rev-parse", "HEAD")
        val second = files(DiffSelection(DiffMode.commit, commitOid = merge, parent = 2))
        assertEquals(base, second.snapshot.comparison.leftOid)
        assertEquals(listOf("topic"), second.files.map { it.newPath })
        git("checkout", "--detach", rootOid)
        assertTrue(patch(branch).ok().hunks.isNotEmpty())
        assertEquals(topic, files(DiffSelection(DiffMode.commit, commitOid = topic)).snapshot.comparison.commitOid)
        val treeOid = git("rev-parse", "$rootOid^{tree}")
        assertEquals(DiffReason.invalid_request, reader.files(inventory.repository, inventory.worktrees.single(),
            DiffSelection(DiffMode.commit, commitOid = treeOid)).error?.reason)
    }

    @Test fun renameDeletionModeOnlyCrLfAndConflicts() = fixture {
        write("old\nname", "one\r\ntwo"); write("delete", "gone\n"); write("mode", "mode\n"); commit()
        git("mv", "old\nname", "new\tname"); git("rm", "delete")
        git("update-index", "--chmod=+x", "mode")
        val files = files(DiffSelection(scope = DiffLocalScope.staged))
        val rename = files.files.single { it.change == DiffChange.renamed }
        assertEquals("old\nname", rename.oldPath); assertEquals("new\tname", rename.newPath)
        assertNull(files.files.single { it.change == DiffChange.deleted }.newPath)
        assertTrue(patch(files, files.files.single { it.newPath == "mode" }).ok().hunks.isEmpty())
        commit("rename")
        git("checkout", "-b", "other")
        write("new\tname", "other\n"); commit("other")
        git("checkout", "main"); write("new\tname", "main\n"); commit("main")
        git("merge", "other", success = false)
        val conflicts = files()
        val conflict = conflicts.files.single { it.newPath == "new\tname" }
        assertEquals(DiffFileKind.conflict, conflict.kind)
        assertTrue(patch(conflicts, conflict).ok().hunks.isEmpty())
    }

    @Test fun readsDoNotMutateIndexRefsOrInvokeExternalCommands() = fixture {
        write("a", "one\n"); commit()
        write(".gitattributes", "* filter=evil diff=evil\n")
        val marker = "$directory/executed"
        for (key in listOf("diff.external", "diff.evil.command", "diff.evil.textconv", "filter.evil.clean", "filter.evil.smudge", "filter.evil.process", "core.fsmonitor")) {
            git("config", key, "touch $marker")
        }
        git("config", "filter.evil.required", "true")
        write("a", "two\n")
        val indexBefore = readRegularFileBytesOrNull("$root/.git/index")
        val headBefore = readRegularFileBytesOrNull("$root/.git/refs/heads/main")
        val contentBefore = readRegularFileBytesOrNull("$root/a")
        val files = files()
        patch(files, files.files.single { it.newPath == "a" }).ok()
        assertNull(readRegularFileBytesOrNull(marker))
        assertContentEquals(indexBefore, readRegularFileBytesOrNull("$root/.git/index"))
        assertContentEquals(headBefore, readRegularFileBytesOrNull("$root/.git/refs/heads/main"))
        assertContentEquals(contentBefore, readRegularFileBytesOrNull("$root/a"))
    }

    @Test fun historyPagesStayPinnedAndShallowParentsAreNotRoots() = fixture {
        write("a", "one\n"); val initial = commit()
        repeat(101) { git("commit", "--allow-empty", "-m", "commit $it") }
        val inventory = inventory()
        val first = reader.commits(inventory.repository, inventory.worktrees.single()).ok()
        assertEquals(100, first.commits.size)
        git("reset", "--hard", initial)
        val next = reader.commits(inventory.repository, inventory.worktrees.single(), cursor = first.nextCursor).ok()
        assertEquals(2, next.commits.size)
        assertEquals(first.cursor?.headOid, next.cursor?.headOid)
        git("reset", "--hard", first.cursor!!.headOid)
        git("clone", "--depth=1", "file://$root", "$directory/shallow")
        val shallow = reader.worktrees("$directory/shallow").ok()
        val outcome = reader.files(shallow.repository, shallow.worktrees.single(), DiffSelection(DiffMode.commit, commitOid = first.cursor.headOid))
        assertEquals(DiffReason.shallow_history, outcome.error?.reason)
    }

    @Test fun sha256ObjectIdsAndSubmodulesAreExplicit() = fixture("sha256") {
        write("a", "one\n"); val initial = commit()
        assertEquals(64, initial.length)
        git("update-index", "--add", "--cacheinfo", "160000,$initial,module")
        val staged = files(DiffSelection(scope = DiffLocalScope.staged))
        val submodule = staged.files.single()
        assertEquals(DiffFileKind.submodule, submodule.kind)
        assertTrue(patch(staged, submodule).ok().hunks.isEmpty())
        val inventory = inventory()
        assertEquals(initial, reader.commits(inventory.repository, inventory.worktrees.single()).ok().commits.single().oid)
        assertEquals(DiffReason.missing_object, reader.files(inventory.repository, inventory.worktrees.single(),
            DiffSelection(DiffMode.commit, commitOid = "a".repeat(64))).error?.reason)
    }

    @Test fun limitsAndUnsupportedTextAreVisibleAndReadsRecover() = fixture {
        write("tracked", "initial\n"); commit()
        write("many-lines", "line\n".repeat(DIFF_MAX_PATCH_LINES + 1))
        write("oversize", "x".repeat(DIFF_MAX_PATCH_BYTES + 1))
        writeBytes("non-utf8", byteArrayOf(-1, -2, 10))
        val list = files()
        assertEquals(DiffFileKind.limited, list.files.single { it.newPath == "oversize" }.kind)
        assertEquals(DiffReason.unsupported_encoding, list.files.single { it.newPath == "non-utf8" }.problem?.reason)
        assertEquals(DiffReason.patch_limit, patch(list, list.files.single { it.newPath == "many-lines" }).ok().limited?.reason)
        git("clean", "-fd")
        repeat(DIFF_MAX_FILES + 1) { write("file-$it", "") }
        val limited = files()
        assertEquals(DIFF_MAX_FILES, limited.files.size)
        assertEquals(DiffReason.file_limit, limited.limited?.reason)
        git("clean", "-fd")
        assertEquals(emptyList(), files().files)
    }

    @Test fun aRenamePatchCannotIncludeAnAdditionUnderItsOldPath() = fixture {
        write("old", "original\n"); commit()
        git("mv", "old", "new")
        assertEquals(0, platform.posix.mkdir("$root/old", 448u))
        write("old/child", "unrelated addition\n")
        git("add", "old")
        val staged = files(DiffSelection(scope = DiffLocalScope.staged))
        val rename = staged.files.single { it.change == DiffChange.renamed }
        assertTrue(patch(staged, rename).ok().hunks.isEmpty())
        val added = staged.files.single { it.change == DiffChange.added }
        assertEquals("unrelated addition", patch(staged, added).ok().hunks.single().lines.single().text)
    }

    @Test fun nonUtf8FilenameIsRejectedByFilesystemOrReader() = fixture {
        memScoped {
            val bytes = (root + "/bad-").encodeToByteArray() + byteArrayOf(-1, 0)
            val name = allocArray<ByteVar>(bytes.size)
            bytes.forEachIndexed { i, byte -> name[i] = byte }
            val file = fopen(name, "wb".cstr.ptr)
            if (file == null) {
                // APFS rejects non-UTF-8 names at creation; Linux exercises the reader outcome.
                assertEquals(io.kotgent.host.HostOs.MACOS, io.kotgent.host.hostOs)
                assertEquals(platform.posix.EILSEQ, platform.posix.errno)
                return@fixture
            }
            fclose(file)
        }
        val inventory = inventory()
        assertEquals(DiffReason.unsupported_path, reader.files(inventory.repository, inventory.worktrees.single(), DiffSelection()).error?.reason)
    }

    @Test fun remoteDefaultIsUnambiguousAndUnrelatedBranchesAreUnavailable() = fixture {
        write("a", "initial\n"); val initial = commit()
        git("update-ref", "refs/remotes/one/trunk", initial)
        git("symbolic-ref", "refs/remotes/one/HEAD", "refs/remotes/one/trunk")
        val inventory = inventory()
        val tree = inventory.worktrees.single()
        assertEquals("refs/remotes/one/trunk", reader.refs(inventory.repository, tree).ok().defaultBaseRef)
        git("update-ref", "refs/remotes/two/other", initial)
        git("symbolic-ref", "refs/remotes/two/HEAD", "refs/remotes/two/other")
        assertEquals("refs/heads/main", reader.refs(inventory.repository, tree).ok().defaultBaseRef)
        assertEquals(emptyList(), files(DiffSelection(DiffMode.branch, baseRef = "refs/heads/main")).files)
        git("checkout", "--orphan", "unrelated")
        git("commit", "-m", "unrelated")
        assertEquals(DiffReason.no_merge_base, reader.files(inventory.repository, tree,
            DiffSelection(DiffMode.branch, baseRef = "refs/heads/main")).error?.reason)
    }
}
