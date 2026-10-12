package io.kotgent.diff

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffComparisonTest {
    private val repository = DiffRepository("/repo/.git")
    private val head = "a".repeat(40)
    private val base = "b".repeat(40)
    private val ancestor = "c".repeat(40)
    private val main = DiffRef("refs/heads/main", base, DiffRefKind.local)
    private val remote = DiffRef("refs/remotes/origin/trunk", base, DiffRefKind.remote)
    private val feature = DiffRef("refs/heads/feature", head, DiffRefKind.local)
    private val json = Json { encodeDefaults = true }

    @Test
    fun baseDefaultsPreferSavedThenRemoteThenMainThenMasterWithoutGuessingUpstream() {
        val refs = listOf(main, remote, feature)
        assertEquals(feature.name, defaultDiffBase(refs, feature.name, remote.name))
        assertEquals(remote.name, defaultDiffBase(refs, "refs/heads/deleted", remote.name))
        assertEquals(main.name, defaultDiffBase(refs, null, null))
        val master = main.copy(name = "refs/heads/master")
        assertEquals(master.name, defaultDiffBase(listOf(master), null, null))
        assertNull(defaultDiffBase(listOf(feature), null, null))
        assertNull(defaultDiffBase(emptyList(), main.name, remote.name))
    }

    @Test
    fun localScopesDistinguishIndexAndWorkingCopyIncludingUnborn() {
        val all = localDiffComparison(repository, "/repo", head)
        val staged = localDiffComparison(repository, "/repo", head, DiffLocalScope.staged)
        val unstaged = localDiffComparison(repository, "/repo", head, DiffLocalScope.unstaged)
        assertEquals(head, all.leftOid)
        assertEquals(head, staged.leftOid)
        assertNull(unstaged.leftOid)
        assertEquals(3, setOf(all, staged, unstaged).size)
        assertNull(localDiffComparison(repository, "/repo", null).leftOid)
        assertNotEquals(all, all.copy(worktreeId = "/linked"))
        assertNotEquals(all, all.copy(repository = DiffRepository("/clone/.git")))
        assertNotEquals(all, all.copy(headOid = base))
    }

    @Test
    fun commitParentsAreOneBasedAndRootUsesEmptyTree() {
        val root = DiffCommit(head, emptyList(), "Author", 0, "Root")
        val rootDiff = commitDiffComparison(repository, "/repo", head, root).data!!
        assertNull(rootDiff.parent)
        assertNull(rootDiff.leftOid)
        assertEquals(head, rootDiff.rightOid)
        assertNotNull(commitDiffComparison(repository, "/repo", head, root, 1).error)
        val merge = root.copy(parents = listOf(base, ancestor))
        val first = commitDiffComparison(repository, "/repo", head, merge).data!!
        val second = commitDiffComparison(repository, "/repo", head, merge, 2).data!!
        assertEquals(1, first.parent)
        assertEquals(base, first.leftOid)
        assertEquals(ancestor, second.leftOid)
        assertNotEquals(first, second)
        for (parent in listOf(0, -1, 3, Int.MAX_VALUE)) {
            assertEquals(DiffErrorCode.invalid, commitDiffComparison(repository, "/repo", head, merge, parent).error?.code)
        }
        assertEquals(base, commitDiffComparison(repository, "/repo", head, root.copy(parents = listOf(base))).data?.leftOid)
    }

    @Test
    fun branchUsesMergeBaseRatherThanBaseTipAndRejectsUnavailableHistory() {
        val comparison = branchDiffComparison(repository, "/repo", head, main, listOf(ancestor)).data!!
        assertEquals(base, comparison.baseOid)
        assertEquals(ancestor, comparison.leftOid)
        assertEquals(head, comparison.rightOid)
        assertNotEquals(comparison, comparison.copy(baseOid = ancestor))
        assertEquals(DiffReason.no_merge_base, branchDiffComparison(repository, "/repo", head, main, emptyList()).error?.reason)
        assertEquals(DiffReason.ambiguous_merge_base, branchDiffComparison(repository, "/repo", head, main, listOf(base, ancestor)).error?.reason)
        assertEquals(DiffReason.shallow_history, branchDiffComparison(repository, "/repo", head, main, listOf(base), true).error?.reason)
        val same = branchDiffComparison(repository, "/repo", base, main, listOf(base)).data!!
        assertEquals(same.leftOid, same.rightOid)
    }

    @Test
    fun requestsRejectMixedModesAndGitExpressions() {
        for (oid in listOf(head, "d".repeat(64))) assertTrue(isDiffOid(oid))
        for (oid in listOf("HEAD", "--help", "a".repeat(7), "a".repeat(41), "G".repeat(40))) assertFalse(isDiffOid(oid))
        for (ref in listOf(main.name, remote.name, "refs/heads/тема")) assertTrue(isDiffBranchRef(ref))
        for (ref in listOf("main", "refs/tags/v1", "--help", "refs/heads/", "refs/heads/a..b", "refs/heads/a.lock", "refs/heads/a b", "refs/heads/a\nb", "refs/heads/a@{1}", "refs/heads/a\\b")) {
            assertFalse(isDiffBranchRef(ref), ref)
        }
        assertNull(validateDiffSelection(DiffSelection()))
        assertNull(validateDiffSelection(DiffSelection(DiffMode.commit, commitOid = head)))
        assertNull(validateDiffSelection(DiffSelection(DiffMode.branch, baseRef = main.name)))
        for (selection in listOf(
            DiffSelection(commitOid = head), DiffSelection(parent = 1),
            DiffSelection(DiffMode.commit), DiffSelection(DiffMode.commit, commitOid = head, parent = 0),
            DiffSelection(DiffMode.branch), DiffSelection(DiffMode.branch, scope = DiffLocalScope.all, baseRef = main.name),
        )) assertEquals(DiffErrorCode.invalid, validateDiffSelection(selection)?.code)
        assertNull(validateDiffCursor(DiffCommitCursor(head, base, 100)))
        assertNotNull(validateDiffCursor(DiffCommitCursor(head, offset = -1)))
        assertNotNull(validateDiffCursor(DiffCommitCursor("HEAD")))
        assertNotNull(validateDiffCursor(DiffCommitCursor(head, "main")))
    }

    @Test
    fun serializationKeepsExplicitEmptyErrorAndSpecialPatchShapes() {
        val snapshot = DiffSnapshot("s1", localDiffComparison(repository, "/repo", head), 123)
        val empty = DiffResponse(data = DiffFiles(snapshot, emptyList()))
        val encoded = json.encodeToString(empty)
        assertTrue("\"mode\":\"local\"" in encoded)
        assertTrue("\"files\":[]" in encoded)
        assertTrue("\"error\":null" in encoded)
        assertEquals(empty, json.decodeFromString<DiffResponse<DiffFiles>>(encoded))
        val error = DiffResponse<DiffFiles>(error = DiffProblem(DiffErrorCode.stale, DiffReason.snapshot_changed, "Refresh"))
        assertEquals("{\"data\":null,\"error\":{\"code\":\"stale\",\"reason\":\"snapshot_changed\",\"message\":\"Refresh\"}}", json.encodeToString(error))
        for (kind in DiffFileKind.entries) {
            val file = DiffFile("f1", newPath = "a\t\nb.kt", change = DiffChange.added, kind = kind)
            val patch = DiffPatch(snapshot, file, listOf(DiffHunk(0, 0, 1, 1, lines = listOf(
                DiffLine(DiffLineKind.addition, "text\r", newLine = 1, noNewline = true),
            ))))
            assertEquals(patch, json.decodeFromString<DiffPatch>(json.encodeToString(patch)))
        }
        val cursor = DiffCommitCursor(head, base, 100)
        assertEquals(cursor, json.decodeFromString<DiffCommitCursor>(json.encodeToString(cursor)))
    }
}
