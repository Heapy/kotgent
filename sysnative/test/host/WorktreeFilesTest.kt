@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.kotgent.host

import io.kotgent.proc.CapturedProcess
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import platform.posix.*
import kotlin.test.*

class WorktreeFilesTest {
    private fun fixture(block: (String) -> Unit) {
        val root = memScoped { mkdtemp("/tmp/kotgent-worktree.XXXXXX".cstr.ptr)?.toKString() }!!
        try { block(root) } finally {
            runBlocking { assertEquals(0, CapturedProcess.run(listOf("/bin/rm", "-rf", root)).exitCode) }
        }
    }
    private fun write(path: String, value: String) {
        val file = assertNotNull(fopen(path, "wb"))
        try {
            val bytes = value.encodeToByteArray()
            if (bytes.isNotEmpty()) bytes.usePinned { assertEquals(bytes.size.toULong(), fwrite(it.addressOf(0), 1u, bytes.size.convert(), file)) }
        } finally { fclose(file) }
    }

    @Test fun boundedRegularReadsAndExplicitNonRegularOutcomes() = fixture { root ->
        write("$root/file", "hello")
        assertEquals("hello", readWorktreeFile(root, "file", 5).bytes.decodeToString())
        assertEquals(WorktreeFileKind.limited, readWorktreeFile(root, "file", 4).kind)
        write("$root/empty", "")
        assertEquals(WorktreeFileKind.regular, readWorktreeFile(root, "empty", 0).kind)
        assertEquals(WorktreeFileKind.missing, readWorktreeFile(root, "missing", 5).kind)
        assertEquals(0, mkfifo("$root/fifo", 384u))
        assertEquals(WorktreeFileKind.special, readWorktreeFile(root, "fifo", 5).kind)
        assertEquals(0, mkdir("$root/dir", 448u))
        assertEquals(WorktreeFileKind.special, readWorktreeFile(root, "dir", 5).kind)
        assertEquals(0, chmod("$root/file", 448u))
        assertTrue(readWorktreeFile(root, "file", 5).executable)
    }

    @Test fun rejectsTraversalAndNeverFollowsFinalOrIntermediateLinks() = fixture { root ->
        assertEquals(0, symlink("/etc/passwd", "$root/link"))
        assertEquals(0, symlink("/etc", "$root/dir"))
        val link = readWorktreeFile(root, "link", 100)
        assertEquals(WorktreeFileKind.symlink, link.kind)
        assertEquals("/etc/passwd", link.bytes.decodeToString())
        assertEquals(WorktreeFileKind.unsafePath, readWorktreeFile(root, "dir/passwd", 100).kind)
        for (name in listOf("/etc/passwd", "../passwd", "a/../passwd", "a//b", "a\u0000b")) {
            assertEquals(WorktreeFileKind.unsafePath, readWorktreeFile(root, name, 100).kind)
        }
    }

    @Test fun replacingLeafWithSymlinkCannotReadItsTarget() = fixture { root ->
        write("$root/secret", "outside-content")
        write("$root/file", "inside")
        runBlocking {
            val writer = async(Dispatchers.Default) {
                repeat(300) {
                    assertEquals(0, symlink("$root/secret", "$root/replacement"))
                    assertEquals(0, rename("$root/replacement", "$root/file"))
                    write("$root/replacement", "inside")
                    assertEquals(0, rename("$root/replacement", "$root/file"))
                }
            }
            repeat(1000) {
                val read = readWorktreeFile(root, "file", 100)
                if (read.kind == WorktreeFileKind.regular) assertEquals("inside", read.bytes.decodeToString())
                else assertTrue(read.kind in listOf(WorktreeFileKind.symlink, WorktreeFileKind.unreadable))
            }
            writer.await()
        }
    }
}
