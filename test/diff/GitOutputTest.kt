package io.kotgent.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitOutputTest {
    @Test fun nulDelimitedNamesAndVariableOidLengths() {
        for (length in listOf(40, 64)) {
            val raw = ":100644 100755 ${"a".repeat(length)} ${"b".repeat(length)} R100\u0000old\tline\n.kt\u0000new\nline.kt\u0000"
            val file = parseGitRaw(raw.encodeToByteArray()).single().file
            assertEquals("old\tline\n.kt", file.oldPath)
            assertEquals("new\nline.kt", file.newPath)
            assertEquals(DiffChange.renamed, file.change)
            val stat = parseGitNumstat("1\t2\t\u0000old\tline\n.kt\u0000new\nline.kt\u0000".encodeToByteArray()).single()
            assertEquals(file.oldPath, stat.oldPath)
            assertEquals(1, stat.added)
        }
    }

    @Test fun undecodableOrEscapingPathsNeverBecomeReplacementNames() {
        assertEquals(DiffReason.unsupported_path, assertFailsWith<GitReadFailure> { gitPath(byteArrayOf(-1)) }.problem.reason)
        for (path in listOf("../secret", "/absolute", "a/../secret", "a//b")) {
            assertFailsWith<GitReadFailure> { gitPath(path.encodeToByteArray()) }
        }
        assertFailsWith<GitReadFailure> { gitFields("unterminated".encodeToByteArray()) }
    }

    @Test fun worktreesAndStatusPreserveWhitespace() {
        val tree = parseGitWorktrees("worktree /a\nb\u0000HEAD ${"f".repeat(64)}\u0000detached\u0000locked reason\u0000\u0000".encodeToByteArray()).single()
        assertEquals("/a\nb", tree.path)
        assertTrue(tree.locked && tree.detached)
        val status = parseGitStatus("R  new\nfile\u0000old\tfile\u0000?? untracked\nfile\u0000".encodeToByteArray())
        assertEquals("old\tfile", status[0].oldPath)
        assertEquals(" ", status[0].worktree)
        assertEquals("untracked\nfile", status[1].path)
    }

    @Test fun patchKeepsCrAndNoFinalNewlineAndDropsIncompleteHunks() {
        val patch = "diff --git a/a b/a\n@@ -1 +1 @@ heading\n-old\r\n+new\r\n\\ No newline at end of file\n@@ -4 +4 @@\n-a\n+b\n"
        val parsed = parseGitPatch(patch.encodeToByteArray(), 1000, 1000)
        assertEquals(2, parsed.hunks.size)
        assertEquals("new\r", parsed.hunks[0].lines.last().text)
        assertTrue(parsed.hunks[0].lines.last().noNewline)
        assertEquals(1, parsed.hunks[0].lines.last().newLine)
        val limited = parseGitPatch(patch.encodeToByteArray(), patch.indexOf("+b"), 1000)
        assertEquals(1, limited.hunks.size)
        assertNotNull(limited.limited)
    }
}
