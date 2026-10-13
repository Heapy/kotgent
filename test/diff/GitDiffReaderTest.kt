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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
    @Test fun imageVersionsFollowLocalScopesAndPinnedHistory() = fixture {
        val red = kotlin.io.encoding.Base64.decode(IMAGE_RED)
        val blue = kotlin.io.encoding.Base64.decode(IMAGE_BLUE)
        val green = kotlin.io.encoding.Base64.decode(IMAGE_GREEN)
        writeBytes("photo.png", red); val first = commit()
        writeBytes("photo.png", blue); git("add", "photo.png")
        writeBytes("photo.png", green)
        suspend fun versions(selection: DiffSelection, before: String?, after: String?) {
            val list = files(selection)
            val images = assertNotNull(patch(list, list.files.single { it.newPath == "photo.png" }).ok().images)
            for ([version, expected] in listOf(DiffImageVersion.before to before, DiffImageVersion.after to after)) {
                if (expected == null) assertNull(if (version == DiffImageVersion.before) images.before else images.after)
                else assertContentEquals(kotlin.io.encoding.Base64.decode(expected), image(list, version).ok().bytes)
            }
            assertNull(images.before?.problem); assertNull(images.after?.problem)
        }
        versions(DiffSelection(scope = DiffLocalScope.all), IMAGE_RED, IMAGE_GREEN)
        versions(DiffSelection(scope = DiffLocalScope.staged), IMAGE_RED, IMAGE_BLUE)
        versions(DiffSelection(scope = DiffLocalScope.unstaged), IMAGE_BLUE, IMAGE_GREEN)
        val stale = files()
        writeBytes("photo.png", blue)
        assertEquals(DiffReason.snapshot_changed, patch(stale).error?.reason)
        git("commit", "-m", "blue")
        val tip = git("rev-parse", "HEAD")
        writeBytes("photo.png", green)
        versions(DiffSelection(DiffMode.commit, commitOid = tip), IMAGE_RED, IMAGE_BLUE)
        versions(DiffSelection(DiffMode.commit, commitOid = first), null, IMAGE_RED)
        git("branch", "base", first)
        versions(DiffSelection(DiffMode.branch, baseRef = "refs/heads/base"), IMAGE_RED, IMAGE_BLUE)
    }

    @Test fun imageAdditionDeletionRenameAndLimitsAreExplicit() = fixture {
        writeBytes("old\tname.png", kotlin.io.encoding.Base64.decode(IMAGE_RED)); commit()
        git("mv", "old\tname.png", "new\nname.png")
        val renamed = files(DiffSelection(scope = DiffLocalScope.staged))
        val images = assertNotNull(patch(renamed).ok().images)
        assertEquals(3, images.before?.image?.width)
        assertEquals(3, images.after?.image?.width)
        commit()
        git("rm", "new\nname.png")
        val deleted = assertNotNull(patch(files()).ok().images)
        assertEquals(3, deleted.before?.image?.width); assertNull(deleted.after)
        writeBytes("untracked.png", kotlin.io.encoding.Base64.decode(IMAGE_BLUE))
        val added = files()
        val untracked = assertNotNull(patch(added, added.files.single { it.newPath == "untracked.png" }).ok().images)
        assertNull(untracked.before); assertEquals(4, untracked.after?.image?.width)
        writeBytes("large.png", ByteArray(DIFF_MAX_IMAGE_BYTES + 1))
        git("add", "large.png")
        val staged = files(DiffSelection(scope = DiffLocalScope.staged))
        val large = assertNotNull(patch(staged, staged.files.single { it.newPath == "large.png" }).ok().images)
        assertNull(large.after?.image); assertEquals(DiffReason.patch_limit, large.after?.problem?.reason)
        assertEquals(0, symlink("/etc/passwd", "$root/link.png"))
        val linked = files()
        assertNull(patch(linked, linked.files.single { it.newPath == "link.png" }).ok().images)
    }

    @Test fun immutableImageMetadataSurvivesDiskCacheRestart() = fixture {
        writeBytes("photo.png", kotlin.io.encoding.Base64.decode(IMAGE_RED)); commit()
        writeBytes("photo.png", kotlin.io.encoding.Base64.decode(IMAGE_BLUE)); val tip = commit()
        val inventory = inventory(); val tree = inventory.worktrees.single()
        fun reader() = GitDiffReader(diskCache = DiffDiskCache(io.kotgent.host.DiskCacheFiles("$directory/cache")))
        val cold = reader()
        val list = cold.files(inventory.repository, tree, DiffSelection(DiffMode.commit, commitOid = tip)).ok()
        val first = cold.patch(inventory.repository, tree, list.snapshot, list.files.single()).ok()
        val warm = reader().patch(inventory.repository, tree, list.snapshot, list.files.single()).ok()
        assertEquals(first.images, warm.images)
        assertEquals(3, warm.images?.before?.image?.width)
        assertEquals(4, warm.images?.after?.image?.width)
    }

    @Test fun imageLimitIsIndependentOfTextAndSnapshotIncludesTheEntireImage() = fixture {
        // A raster with trailing bytes exercises transport size independently of decoded dimensions.
        val bytes = kotlin.io.encoding.Base64.decode(IMAGE_RED).copyOf(DIFF_MAX_IMAGE_BYTES)
        writeBytes("large.png", bytes)
        val local = files()
        assertEquals(DIFF_MAX_IMAGE_BYTES, patch(local).ok().images?.after?.image?.byteLength)
        assertContentEquals(bytes, image(local, DiffImageVersion.after).ok().bytes)
        bytes[bytes.lastIndex] = 1
        writeBytes("large.png", bytes)
        assertEquals(DiffReason.snapshot_changed, image(local, DiffImageVersion.after).error?.reason)
        git("add", "large.png")
        val staged = files(DiffSelection(scope = DiffLocalScope.staged))
        assertContentEquals(bytes, image(staged, DiffImageVersion.after).ok().bytes)
        val tip = commit()
        val committed = files(DiffSelection(DiffMode.commit, commitOid = tip))
        assertContentEquals(bytes, image(committed, DiffImageVersion.after).ok().bytes)
        writeBytes("large.png", bytes + byteArrayOf(0))
        val over = files()
        assertEquals(DiffReason.patch_limit, image(over, DiffImageVersion.after).error?.reason)
    }

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
        suspend fun image(files: DiffFiles, version: DiffImageVersion, file: DiffFile = files.files.first()): DiffResponse<DiffImageContent> {
            val inventory = inventory()
            return reader.image(inventory.repository, inventory.worktrees.first { it.current }, files.snapshot, file, version)
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

    @Test fun immutableDiskHitsSurviveReaderRestartAndUseFreshEnvelopes() = fixture {
        write("a", "one\n"); commit()
        write("a", "two\n"); val tip = commit("two")
        val counts = mutableMapOf<String, Long>()
        var clock = 1000L
        fun cached() = GitDiffReader(now = { clock }, diskCache = DiffDiskCache(io.kotgent.host.DiskCacheFiles("$directory/cache")),
            measure = { name, value -> counts[name] = (counts[name] ?: 0) + value })
        val inventory = inventory()
        val tree = inventory.worktrees.single()
        val selection = DiffSelection(DiffMode.commit, commitOid = tip)
        val cold = cached()
        val history = cold.commits(inventory.repository, tree).ok()
        val files = cold.files(inventory.repository, tree, selection).ok()
        val patch = cold.patch(inventory.repository, tree, files.snapshot, files.files.single()).ok()
        assertEquals(1L, counts["logCommands"])
        assertEquals(2L, counts["diffCommands"])
        counts.clear(); clock++
        val restarted = cached()
        val warmHistory = restarted.commits(inventory.repository, tree).ok()
        val warmFiles = restarted.files(inventory.repository, tree, selection).ok()
        val warmPatch = restarted.patch(inventory.repository, tree, warmFiles.snapshot, warmFiles.files.single()).ok()
        assertEquals(history.commits, warmHistory.commits)
        assertEquals(files.files, warmFiles.files)
        assertEquals(patch.hunks, warmPatch.hunks)
        assertEquals(clock, warmHistory.readAt)
        assertEquals(clock, warmFiles.snapshot.readAt)
        assertEquals(clock, warmPatch.snapshot.readAt)
        assertNull(counts["logCommands"])
        assertNull(counts["diffCommands"])
        assertTrue(counts.getValue("gitCommands") > 0) // Trust/source/object checks still execute.
        // A missing disk is just a cold read; local reads never enter this cache.
        assertEquals(0, CapturedProcess.run(listOf("/bin/rm", "-rf", "$directory/cache"), env).exitCode)
        restarted.files(inventory.repository, tree, selection).ok()
        assertEquals(1L, counts["diffCommands"])
        counts.clear()
        restarted.files(inventory.repository, tree, DiffSelection()).ok()
        restarted.files(inventory.repository, tree, DiffSelection()).ok()
        assertEquals(2L, counts["diffCommands"])
    }

    @Test fun immutableCacheInvalidatesConfigAttributesAndObjectMembership() = fixture {
        write("a", "one\n"); commit()
        write("a", "two\n"); val tip = commit("two")
        var diffs = 0L
        val cached = GitDiffReader(diskCache = DiffDiskCache(io.kotgent.host.DiskCacheFiles("$directory/cache")),
            measure = { name, n -> if (name == "diffCommands") diffs += n })
        val inventory = inventory()
        val tree = inventory.worktrees.single()
        val selection = DiffSelection(DiffMode.commit, commitOid = tip)
        suspend fun read() = cached.files(inventory.repository, tree, selection).ok()
        val original = read()
        val _ = read(); assertEquals(1L, diffs)
        git("config", "diff.algorithm", "patience")
        val _ = read(); assertEquals(2L, diffs)
        write(".gitattributes", "a -diff\n")
        assertEquals(DiffFileKind.binary, read().files.single().kind)
        assertEquals(3L, diffs)
        assertEquals(0, CapturedProcess.run(listOf("/bin/cp", "-p", "$root/.gitattributes", "$directory/attributes-stamp"), env).exitCode)
        write(".gitattributes", "a diff \n")
        assertEquals(0, CapturedProcess.run(listOf("/usr/bin/touch", "-r", "$directory/attributes-stamp", "$root/.gitattributes"), env).exitCode)
        assertEquals(DiffFileKind.text, read().files.single().kind)
        assertEquals(4L, diffs)
        cached.patch(inventory.repository, tree, original.snapshot, original.files.single()).ok()
        val blob = git("rev-parse", "$tip:a")
        assertEquals(0, platform.posix.unlink("$root/.git/objects/${blob.take(2)}/${blob.drop(2)}"))
        assertNotNull(cached.patch(inventory.repository, tree, original.snapshot, original.files.single()).error)
        assertEquals(0, platform.posix.unlink("$root/.git/objects/${tip.take(2)}/${tip.drop(2)}"))
        assertEquals(DiffReason.missing_object, cached.commits(inventory.repository, tree).error?.reason)
    }

    @Test fun immutableCacheResolvesMovedDeletedRefsAndIsolatesClonesAndWorktrees() = fixture {
        write("a", "one\n"); val first = commit()
        git("branch", "base")
        write("a", "two\n"); val second = commit("two")
        var diffs = 0L
        val cached = GitDiffReader(diskCache = DiffDiskCache(io.kotgent.host.DiskCacheFiles("$directory/cache")),
            measure = { name, n -> if (name == "diffCommands") diffs += n })
        val inventory = inventory(); val tree = inventory.worktrees.single()
        val branch = DiffSelection(DiffMode.branch, baseRef = "refs/heads/base")
        val files = cached.files(inventory.repository, tree, branch).ok()
        cached.files(inventory.repository, tree, branch).ok(); assertEquals(1L, diffs)
        git("branch", "-f", "base", second)
        assertTrue(cached.files(inventory.repository, tree, branch).ok().files.isEmpty())
        git("branch", "-D", "base")
        assertEquals(DiffReason.missing_ref, cached.files(inventory.repository, tree, branch).error?.reason)
        // Pinned patch still uses its OIDs even after its mutable ref disappears.
        assertEquals(first, files.snapshot.comparison.leftOid)
        assertTrue(cached.patch(inventory.repository, tree, files.snapshot, files.files.single()).ok().hunks.isNotEmpty())
        val selection = DiffSelection(DiffMode.commit, commitOid = second)
        cached.files(inventory.repository, tree, selection).ok()
        git("worktree", "add", "--detach", "$directory/linked", second)
        val linked = cached.worktrees("$directory/linked").ok()
        val beforeLinked = diffs
        val linkedFiles = cached.files(linked.repository, linked.worktrees.single { it.current }, selection).ok()
        assertTrue(diffs > beforeLinked)
        assertEquals(io.kotgent.host.canonicalWorktreePath("$directory/linked"), linkedFiles.snapshot.comparison.worktreeId)
        git("clone", "--no-hardlinks", root, "$directory/clone")
        val clone = cached.worktrees("$directory/clone").ok(); val beforeClone = diffs
        cached.files(clone.repository, clone.worktrees.single(), selection).ok()
        assertTrue(diffs > beforeClone)
        assertEquals(DiffReason.foreign_worktree, cached.files(inventory.repository, clone.worktrees.single(), selection).error?.reason)
    }

    @Test fun cacheBypassesUnprovableAttributesAndNeverStoresLimitedPatches() = fixture {
        write("a", "one\n"); commit()
        write("a", "line\n".repeat(DIFF_MAX_PATCH_LINES + 1)); val tip = commit("large")
        var diffs = 0L
        val cached = GitDiffReader(diskCache = DiffDiskCache(io.kotgent.host.DiskCacheFiles("$directory/cache")),
            measure = { name, n -> if (name == "diffCommands") diffs += n })
        val inventory = inventory(); val tree = inventory.worktrees.single()
        val selection = DiffSelection(DiffMode.commit, commitOid = tip)
        val files = cached.files(inventory.repository, tree, selection).ok()
        repeat(2) {
            val before = diffs
            assertNotNull(cached.patch(inventory.repository, tree, files.snapshot, files.files.single()).ok().limited)
            assertTrue(diffs > before)
        }
        write("../attributes", "a -diff\n")
        assertEquals(0, symlink("$directory/attributes", "$root/.gitattributes"))
        val before = diffs
        repeat(2) { cached.files(inventory.repository, tree, selection).ok() }
        assertEquals(before + 2, diffs)
    }

    @Test fun cacheInsideASiblingWorktreeIsBypassedBeforeAnyAccess() = fixture {
        write("a", "one\n"); val tip = commit()
        git("worktree", "add", "--detach", "$directory/linked", "HEAD")
        val inventory = inventory()
        val main = inventory.worktrees.single { it.current }
        val linked = inventory.worktrees.single { !it.current }
        // Exercise both directions: main-hosted cache while reading linked, and vice versa.
        for ([host, selected] in listOf(main to linked, linked to main)) {
            val cacheRoot = "${host.path}/cache"
            val counts = mutableMapOf<String, Long>()
            val cached = GitDiffReader(diskCache = DiffDiskCache(io.kotgent.host.DiskCacheFiles(cacheRoot)),
                measure = { name, value -> counts[name] = (counts[name] ?: 0) + value })
            repeat(2) {
                assertEquals(1, cached.commits(inventory.repository, selected).ok().commits.size)
                val files = cached.files(inventory.repository, selected, DiffSelection(DiffMode.commit, commitOid = tip)).ok()
                assertTrue(cached.patch(inventory.repository, selected, files.snapshot, files.files.single()).ok().hunks.isNotEmpty())
                assertNull(io.kotgent.host.canonicalWorktreePath(cacheRoot))
            }
            assertEquals(2L, counts["logCommands"])
            assertEquals(8L, counts["diffCommands"])
        }
    }

    @Test fun cacheMustStayOutsideTheCheckoutAndGitDirectory() = fixture {
        write("a", "one\n"); val tip = commit()
        val inventory = inventory(); val tree = inventory.worktrees.single()
        for (path in listOf("$root/cache", "$root/.git/cache")) {
            var diffs = 0L
            val cached = GitDiffReader(diskCache = DiffDiskCache(io.kotgent.host.DiskCacheFiles(path)),
                measure = { name, n -> if (name == "diffCommands") diffs += n })
            repeat(2) { cached.files(inventory.repository, tree, DiffSelection(DiffMode.commit, commitOid = tip)).ok() }
            assertEquals(2L, diffs)
            assertNull(io.kotgent.host.canonicalWorktreePath(path))
        }
    }

    @Test fun infoGlobalIgnoredNestedAndIndexAttributesAreCacheDependencies() = fixture {
        assertEquals(0, platform.posix.mkdir("$root/d", 448u))
        write("d/a", "one\n"); commit()
        write("d/a", "two\n"); val tip = commit("two")
        var diffs = 0L
        val cached = GitDiffReader(diskCache = DiffDiskCache(io.kotgent.host.DiskCacheFiles("$directory/cache")),
            measure = { name, n -> if (name == "diffCommands") diffs += n })
        val inventory = inventory(); val tree = inventory.worktrees.single()
        suspend fun kind(): DiffFileKind {
            val first = cached.files(inventory.repository, tree, DiffSelection(DiffMode.commit, commitOid = tip)).ok().files.single().kind
            val before = diffs
            val second = cached.files(inventory.repository, tree, DiffSelection(DiffMode.commit, commitOid = tip)).ok().files.single().kind
            assertEquals(first, second)
            assertEquals(before, diffs) // Each dependency state really populates and hits the cache.
            return first
        }
        assertEquals(DiffFileKind.text, kind())
        write(".git/info/attributes", "d/a -diff\n")
        assertEquals(DiffFileKind.binary, kind())
        assertEquals(0, platform.posix.unlink("$root/.git/info/attributes"))
        write("../global-attributes", "d/a -diff\n")
        git("config", "core.attributesFile", "${io.kotgent.host.canonicalWorktreePath(directory)}/global-attributes")
        assertEquals(DiffFileKind.binary, kind())
        write("../global-attributes", "d/a diff \n")
        assertEquals(DiffFileKind.text, kind())
        write(".gitignore", "**/.gitattributes\n")
        write("d/.gitattributes", "a -diff\n")
        assertEquals("d/.gitattributes", git("check-ignore", "d/.gitattributes"))
        assertEquals(DiffFileKind.binary, kind())
        git("add", "-f", "d/.gitattributes")
        assertEquals(0, platform.posix.unlink("$root/d/.gitattributes"))
        // Match fresh Git when the checkout attribute is absent; tree-to-tree diff need not load the index.
        assertEquals(reader.files(inventory.repository, tree, DiffSelection(DiffMode.commit, commitOid = tip)).ok().files.single().kind, kind())
        write("d/.gitattributes", "a diff \n"); git("add", "-f", "d/.gitattributes")
        assertEquals(0, platform.posix.unlink("$root/d/.gitattributes"))
        assertEquals(DiffFileKind.text, kind())
    }

    @Test fun dependencyAccountingMeasuresWarmAttributeReads() = fixture {
        write("a", "one\n"); commit()
        write("a", "two\n"); val tip = commit("two")
        write(".gitattributes", "a diff\n")
        git("config", "core.attributesFile", "${io.kotgent.host.canonicalWorktreePath(directory)}/absent-attributes")
        var bytes = 0L
        val cached = GitDiffReader(diskCache = DiffDiskCache(io.kotgent.host.DiskCacheFiles("$directory/cache")),
            measure = { name, n -> if (name == "dependencyReadBytes") bytes += n })
        val inventory = inventory(); val tree = inventory.worktrees.single()
        val selection = DiffSelection(DiffMode.commit, commitOid = tip)
        cached.files(inventory.repository, tree, selection).ok()
        assertEquals(14L, bytes) // Before and after publication.
        bytes = 0
        cached.files(inventory.repository, tree, selection).ok()
        assertEquals(7L, bytes) // A warm hit still reads its attribute dependency.
    }

    @Test fun configuredOrderFileBypassesDiffCacheAndPreservesGitFailures() = fixture {
        write("a", "one\n"); write("b", "one\n"); commit()
        write("a", "two\n"); write("b", "two\n"); val tip = commit("two")
        val order = "${io.kotgent.host.canonicalWorktreePath(directory)}/order"
        write("../order", "b\na\n")
        git("config", "diff.orderFile", order)
        var diffs = 0L; var logs = 0L
        val cached = GitDiffReader(diskCache = DiffDiskCache(io.kotgent.host.DiskCacheFiles("$directory/cache")),
            measure = { name, n -> if (name == "diffCommands") diffs += n else if (name == "logCommands") logs += n })
        val inventory = inventory(); val tree = inventory.worktrees.single()
        val selection = DiffSelection(DiffMode.commit, commitOid = tip)
        val first = cached.files(inventory.repository, tree, selection).ok()
        assertEquals(listOf("b", "a"), first.files.map { it.newPath })
        cached.patch(inventory.repository, tree, first.snapshot, first.files.first()).ok()
        val before = diffs
        cached.files(inventory.repository, tree, selection).ok()
        cached.patch(inventory.repository, tree, first.snapshot, first.files.first()).ok()
        assertEquals(before + 4, diffs) // Files plus both patch observations and the selected patch.
        cached.commits(inventory.repository, tree).ok()
        write("../order", "a\nb\n")
        val changed = cached.files(inventory.repository, tree, selection).ok()
        assertEquals(listOf("a", "b"), changed.files.map { it.newPath })
        cached.patch(inventory.repository, tree, changed.snapshot, changed.files.first()).ok()
        assertEquals(0, platform.posix.unlink(order))
        assertEquals(DiffReason.git_failed, cached.files(inventory.repository, tree, selection).error?.reason)
        assertEquals(DiffReason.git_failed, cached.patch(inventory.repository, tree, changed.snapshot, changed.files.first()).error?.reason)
        cached.commits(inventory.repository, tree).ok()
        assertEquals(1L, logs) // Ordering only affects diff; history remains cacheable.
    }

    @Test fun cancellationDuringCacheValidationDoesNotFallBackOrPublish() = fixture {
        write("a", "one\n"); commit()
        val inventory = inventory(); val tree = inventory.worktrees.single()
        val marker = "$directory/validation-entered"
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val wrapper = "$directory/cache-git"
        write("../cache-git", "#!/bin/sh\nfor arg do\nif [ \"\$arg\" = --batch-all-objects ]; then " +
            "/usr/bin/touch ${quote(marker)}; /bin/sleep 30; fi\ndone\nexec /usr/bin/git \"\$@\"\n")
        assertEquals(0, platform.posix.chmod(wrapper, 448u))
        var logs = 0L
        val cached = GitDiffReader(executable = wrapper, diskCache = DiffDiskCache(io.kotgent.host.DiskCacheFiles("$directory/cache")),
            measure = { name, n -> if (name == "logCommands") logs += n })
        coroutineScope {
            val pending = async { cached.commits(inventory.repository, tree) }
            withTimeout(5_000) { while (readRegularFileBytesOrNull(marker) == null) delay(10) }
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
        assertEquals(0L, logs)
        assertNull(io.kotgent.host.canonicalWorktreePath("$directory/cache"))
    }

    @Test fun historyCacheTracksShallowDeepeningWithUnchangedTip() = fixture {
        repeat(3) { write("a", "$it\n"); commit("commit $it") }
        git("clone", "--depth=1", "file://$root", "$directory/shallow")
        var logs = 0L
        val cached = GitDiffReader(diskCache = DiffDiskCache(io.kotgent.host.DiskCacheFiles("$directory/cache")),
            measure = { name, n -> if (name == "logCommands") logs += n })
        val inventory = cached.worktrees("$directory/shallow").ok(); val tree = inventory.worktrees.single()
        val first = cached.commits(inventory.repository, tree).ok()
        assertEquals(1, first.commits.size)
        assertTrue(first.commits.single().parents.isNotEmpty())
        cached.commits(inventory.repository, tree).ok(); assertEquals(1L, logs)
        git("fetch", "--deepen=2", path = tree.path)
        val deeper = cached.commits(inventory.repository, tree).ok()
        assertEquals(first.cursor?.headOid, deeper.cursor?.headOid)
        assertEquals(3, deeper.commits.size)
        assertEquals(2L, logs)
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

    @Test fun combinedMetadataMatchesSeparateGitOutputsIncludingCopiesAndOddPaths() = fixture {
        write(":old\t\nname", "one\ntwo\nthree\n")
        write("source", "copy content\n")
        write("mode", "mode\n")
        writeBytes("binary", byteArrayOf(0, 1, 2))
        commit()
        git("mv", ":old\t\nname", ":new\t\nname")
        write("1\t2\tcopy", "copy content\n")
        writeBytes("binary", byteArrayOf(0, 2, 3))
        git("add", "--all")
        git("update-index", "--chmod=+x", "mode")
        suspend fun output(vararg formats: String): ByteArray {
            val result = CapturedProcess.run(listOf("/usr/bin/git", "-C", root, "diff", "--cached",
                "--find-renames", "--find-copies-harder", "--no-abbrev", "-z") + formats + "--", env)
            assertEquals(0, result.exitCode)
            return result.stdout
        }
        val combined = parseGitRawNumstat(output("--raw", "--numstat"))
        assertEquals(parseGitRaw(output("--raw")), combined.raw)
        assertEquals(parseGitNumstat(output("--numstat")), combined.stats)
        assertTrue(combined.raw.any { it.file.change == DiffChange.copied })
        assertTrue(combined.raw.any { it.file.change == DiffChange.renamed })
        assertNull(combined.stats.single { it.newPath == "binary" }.added)
        assertEquals(0, combined.stats.single { it.newPath == "mode" }.added)
    }

    @Test fun longPathInventoryRetainsSeparateOutputBudgets() = fixture {
        git("commit", "--allow-empty", "-m", "empty root")
        val base = git("rev-parse", "HEAD")
        git("branch", "base")
        var prefix = ""
        repeat(4) { index ->
            prefix += "d".repeat(190) + index + "/"
            assertEquals(0, platform.posix.mkdir("$root/$prefix", 448u))
        }
        fun populate(start: Int, end: Int) {
            for (index in start..<end) {
                val path = prefix + index.toString().padStart(32, '0')
                assertEquals(800, path.length)
                write(path, "content\n")
            }
        }
        populate(0, DIFF_MAX_FILES)
        val tip = commit("long paths")
        suspend fun metadata(format: String) = CapturedProcess.run(listOf("/usr/bin/git", "-C", root,
            "diff", base, tip, format, "--no-abbrev", "-z", "--"), env,
            stdoutLimitBytes = DIFF_PROCESS_STDOUT_BYTES)
        val raw = metadata("--raw")
        val stats = metadata("--numstat")
        assertEquals(0, raw.exitCode); assertEquals(0, stats.exitCode)
        assertTrue(raw.stdout.size + stats.stdout.size > DIFF_PROCESS_STDOUT_BYTES)
        val selection = DiffSelection(DiffMode.branch, baseRef = "refs/heads/base")
        val list = files(selection)
        assertEquals(DIFF_MAX_FILES, list.files.size)
        assertNull(list.limited)
        assertEquals("content", patch(list, list.files.first()).ok().hunks.single().lines.single().text)

        // The individual raw output now exceeds the same 8 MiB cap: fallback must not raise it.
        populate(DIFF_MAX_FILES, DIFF_MAX_FILES * 2)
        commit("overflow raw output")
        val inventory = inventory()
        val failed = reader.files(inventory.repository, inventory.worktrees.single(), selection)
        assertEquals(DiffReason.output_limit, failed.error?.reason)
        assertEquals(DiffErrorCode.limited, failed.error?.code)
    }

    @Test fun metadataFallbackKeepsFingerprintAndDoesNotRetryStderrLimits() = fixture {
        write("a", "before\n"); commit()
        write("a", "after\n")
        val inventory = inventory()
        val tree = inventory.worktrees.single()
        val normal = reader.files(inventory.repository, tree, DiffSelection()).ok()
        fun wrapper(name: String, stream: String, cap: Int): String {
            val executable = "$directory/$name"
            val marker = "$directory/$name-fallback"
            fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
            write("../$name", "#!/bin/sh\nraw=0\nstats=0\nfor arg do\n" +
                "case \"\$arg\" in --raw) raw=1;; --numstat) stats=1;; esac\ndone\n" +
                "if [ \"\$raw:\$stats\" = 1:1 ]; then /usr/bin/head -c ${cap + 1} /dev/zero $stream; exit 0; fi\n" +
                "if [ \"\$raw:\$stats\" = 1:0 ]; then /usr/bin/touch ${quote(marker)}; fi\n" +
                "exec /usr/bin/git \"\$@\"\n")
            assertEquals(0, platform.posix.chmod(executable, 448u))
            return executable
        }
        val fallback = GitDiffReader(executable = wrapper("stdout-git", "", DIFF_PROCESS_STDOUT_BYTES))
        val retried = fallback.files(inventory.repository, tree, DiffSelection()).ok()
        assertEquals(normal.snapshot.id, retried.snapshot.id)
        assertEquals(normal.files, retried.files)
        assertNotNull(readRegularFileBytesOrNull("$directory/stdout-git-fallback"))
        assertTrue(fallback.patch(inventory.repository, tree, normal.snapshot, normal.files.single()).ok().hunks.isNotEmpty())

        val stderr = GitDiffReader(executable = wrapper("stderr-git", ">&2", DIFF_PROCESS_STDERR_BYTES))
        assertEquals(DiffReason.output_limit, stderr.files(inventory.repository, tree, DiffSelection()).error?.reason)
        assertNull(readRegularFileBytesOrNull("$directory/stderr-git-fallback"))
    }

    @Test fun unrelatedEditDuringPatchIsRejectedByTheSecondGlobalObservation() = fixture {
        write("a", "before\n"); commit()
        write("a", "after\n")
        writeBytes("unrelated", byteArrayOf(0, 1, 2))
        // This test executable changes an unrelated file only after Git produced the selected patch.
        // Both metadata reads use the same real Git executable and ordinary read-only flags.
        val wrapper = "$directory/git-wrapper"
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        write("../git-wrapper", "#!/bin/sh\n/usr/bin/git \"\$@\"\nresult=\$?\n" +
            "for arg do\nif [ \"\$arg\" = --patch ]; then printf '\\000\\003\\004' > " +
            quote("$root/unrelated") + "; fi\ndone\nexit \$result\n")
        assertEquals(0, platform.posix.chmod(wrapper, 448u))
        val hooked = GitDiffReader(executable = wrapper)
        val inventory = hooked.worktrees(root).ok()
        val tree = inventory.worktrees.single()
        val list = hooked.files(inventory.repository, tree, DiffSelection()).ok()
        val selected = list.files.single { it.newPath == "a" }
        assertEquals(DiffReason.snapshot_changed, hooked.patch(inventory.repository, tree, list.snapshot, selected).error?.reason)
    }

    @Test fun discoveryPreservesNewlinesInCommonDirectoryAndNestedCwd() = fixture {
        write("a", "one\n"); commit()
        val moved = "$directory/repo\nfalse\n"
        assertEquals(0, CapturedProcess.run(listOf("/bin/mv", root, moved), env).exitCode)
        assertEquals(0, platform.posix.mkdir("$moved/nested", 448u))
        val discovered = reader.worktrees("$moved/nested").ok()
        val canonical = assertNotNull(io.kotgent.host.canonicalWorktreePath(moved))
        assertEquals("$canonical/.git", discovered.repository.commonDir)
        assertEquals(canonical, discovered.currentWorktreeId)
        assertFalse(discovered.worktrees.single().bare)
        assertTrue(reader.files(discovered.repository, discovered.worktrees.single(), DiffSelection()).ok().files.isEmpty())
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

    @Test fun shallowBranchComparisonAcceptsProvenAncestorEndpointsButRejectsMissingHistory() = fixture {
        write("a", "initial\n"); val initial = commit()
        write("a", "base\n"); val base = commit("base")
        git("checkout", "-b", "unseen", initial)
        write("other", "divergent\n"); commit("other branch")
        git("checkout", "main")
        val clone = "$directory/shallow"
        git("clone", "--depth=1", "--no-single-branch", "file://$root", clone)
        assertEquals("true", git("rev-parse", "--is-shallow-repository", path = clone))
        git("checkout", "-b", "feature", path = clone)
        write("../shallow/feature.txt", "feature content\n")
        git("add", "feature.txt", path = clone)
        git("commit", "-m", "feature", path = clone)
        val feature = git("rev-parse", "HEAD", path = clone)
        val inventory = reader.worktrees(clone).ok()
        val tree = inventory.worktrees.single()
        fun branch(ref: String) = DiffSelection(DiffMode.branch, baseRef = ref)

        val files = reader.files(inventory.repository, tree, branch("refs/heads/main")).ok()
        assertEquals(base, files.snapshot.comparison.mergeBaseOid)
        assertEquals(feature, files.snapshot.comparison.rightOid)
        assertEquals("feature.txt", files.files.single().newPath)
        val patch = reader.patch(inventory.repository, tree, files.snapshot, files.files.single()).ok()
        assertEquals("feature content", patch.hunks.single().lines.single().text)
        assertEquals(DiffReason.shallow_history, reader.files(inventory.repository, tree,
            branch("refs/remotes/origin/unseen")).error?.reason)
        // The shallow boundary still has a missing parent, so it must not become a root commit.
        assertEquals(DiffReason.shallow_history, reader.files(inventory.repository, tree,
            DiffSelection(DiffMode.commit, commitOid = base)).error?.reason)
        // Reverse ancestry is also proven: HEAD is itself the unique merge base, yielding no changes.
        git("checkout", "main", path = clone)
        val reverseTree = reader.worktrees(clone).ok().worktrees.single()
        val reverse = reader.files(inventory.repository, reverseTree, branch("refs/heads/feature")).ok()
        assertEquals(base, reverse.snapshot.comparison.mergeBaseOid)
        assertTrue(reverse.files.isEmpty())
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

    @Test fun largeLocalInventoryKeepsFilesAndSelectedPatchesAvailable() = fixture {
        write("tracked", "before\n"); commit()
        write("tracked", "after!\n")
        // Screenshot-heavy untracked directories must not consume a shared patch budget.
        val binary = ByteArray(1024 * 1024) { 1 }.also { it[0] = 0 }
        val text = "x".repeat(1024 * 1024)
        repeat(5) {
            writeBytes("image-$it", binary)
            write("text-$it", text)
        }
        write("small", "readable\n")
        write("oversize", "x".repeat(DIFF_MAX_PATCH_BYTES + 1))
        for (scope in listOf(DiffLocalScope.all, DiffLocalScope.unstaged)) {
            val list = files(DiffSelection(scope = scope))
            assertNull(list.limited)
            assertEquals(13, list.files.size)
            assertEquals(5, list.files.count { it.kind == DiffFileKind.binary })
            assertEquals(DiffFileKind.limited, list.files.single { it.newPath == "oversize" }.kind)
            assertTrue(patch(list, list.files.single { it.newPath == "tracked" }).ok().hunks.isNotEmpty())
            assertEquals("readable", patch(list, list.files.single { it.newPath == "small" }).ok()
                .hunks.single().lines.single().text)
            assertEquals(DiffReason.patch_limit,
                patch(list, list.files.single { it.newPath == "oversize" }).ok().limited?.reason)
        }
        val original = files()
        assertEquals(0, CapturedProcess.run(listOf("/bin/cp", "-p", "$root/image-0", "$directory/stamp"), env).exitCode)
        binary[binary.lastIndex] = 2
        writeBytes("image-0", binary)
        assertEquals(0, CapturedProcess.run(listOf("/usr/bin/touch", "-r", "$directory/stamp", "$root/image-0"), env).exitCode)
        assertEquals(DiffReason.snapshot_changed,
            patch(original, original.files.single { it.newPath == "small" }).error?.reason)
        val refreshed = files()
        assertNotEquals(original.snapshot.id, refreshed.snapshot.id)
        assertEquals("readable", patch(refreshed, refreshed.files.single { it.newPath == "small" }).ok()
            .hunks.single().lines.single().text)
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

    @Test fun filterNamesContainingEqualsCannotLaunchCommands() = fixture {
        write("a", "one\n"); commit()
        write(".gitattributes", "* filter=evil=driver\n")
        val marker = "$directory/equal-filter-executed"
        for (part in listOf("clean", "smudge", "process")) {
            git("config", "filter.evil=driver.$part", "touch $marker")
        }
        git("config", "filter.evil=driver.required", "true")
        write("a", "two\n")
        val inventory = inventory()
        val tree = inventory.worktrees.single()
        for (scope in DiffLocalScope.entries) {
            val response = reader.files(inventory.repository, tree, DiffSelection(scope = scope))
            assertNull(readRegularFileBytesOrNull(marker), "A configured filter command ran during $scope metadata")
            val files = response.ok()
            if (scope != DiffLocalScope.staged) {
                val patch = reader.patch(inventory.repository, tree, files.snapshot, files.files.single { it.newPath == "a" })
                assertNull(readRegularFileBytesOrNull(marker), "A configured filter command ran during $scope patch")
                assertTrue(patch.ok().hunks.isNotEmpty())
            }
        }
    }

    @Test fun submoduleHeadChangesInvalidateLocalSnapshotWithoutReadingNestedContent() = fixture {
        write("a", "one\n"); commit()
        val nested = "$root/module"
        git("init", "-b", "main", nested)
        git("commit", "--allow-empty", "-m", "A", path = nested)
        val a = git("rev-parse", "HEAD", path = nested)
        git("update-index", "--add", "--cacheinfo", "160000,$a,module")
        git("commit", "-m", "record gitlink")
        git("commit", "--allow-empty", "-m", "B", path = nested)
        val atB = files()
        assertEquals(DiffFileKind.submodule, atB.files.single().kind)
        assertTrue(patch(atB).ok().hunks.isEmpty())
        git("commit", "--allow-empty", "-m", "C", path = nested)
        assertEquals(DiffReason.snapshot_changed, patch(atB).error?.reason)
        assertNotEquals(atB.snapshot.id, files().snapshot.id)
    }

    @Test fun removalFromIndexKeepsTrackedAndUntrackedStatusesSeparate() = fixture {
        write("a", "one\n"); commit()
        git("rm", "--cached", "a")
        val staged = files(DiffSelection(scope = DiffLocalScope.staged)).files.single()
        assertEquals(DiffChange.deleted, staged.change)
        assertEquals("D", staged.indexStatus)
        assertEquals(" ", staged.worktreeStatus)
        val unstaged = files(DiffSelection(scope = DiffLocalScope.unstaged)).files.single()
        assertEquals(DiffChange.untracked, unstaged.change)
        assertEquals("?", unstaged.indexStatus)
        assertEquals("?", unstaged.worktreeStatus)
    }

    @Test fun displayConfigCannotSuppressBlankPatchContext() = fixture {
        write("a", "top\n\nbottom\n"); commit()
        git("config", "diff.suppressBlankEmpty", "true")
        write("a", "TOP\n\nbottom\n")
        val patch = patch(files()).ok()
        val blank = patch.hunks.single().lines.single { it.kind == DiffLineKind.context && it.text.isEmpty() }
        assertEquals(2, blank.oldLine)
        assertEquals(2, blank.newLine)
    }
}
