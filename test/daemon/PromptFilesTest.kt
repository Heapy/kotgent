package io.kotgent.daemon

import io.kotgent.core.SessionId
import io.kotgent.transport.readFileTextOrNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
class PromptFilesTest {

    private val dir: String = run {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        "$tmp/kotgent-prompts-${getpid()}"
    }

    private val files = PrivatePromptFiles(dir)

    private val sessions = listOf(SessionId("p0000001"), SessionId("p0000002"))

    @AfterTest
    fun cleanup() {
        sessions.forEach { unlink(files.pathFor(it)) }
        rmdir(dir)
    }

    private fun mode(path: String): Int? = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) null else st.st_mode.toInt() and 0b111_111_111
    }

    @Test
    fun writesEachPromptPrivatelyUnderItsSessionId() {
        val first = sessions[0]
        val second = sessions[1]

        files.write(first, "- first prompt\n")
        files.write(second, "second")

        assertEquals("$dir/p0000001.md", files.pathFor(first))
        assertEquals("- first prompt\n", readFileTextOrNull(files.pathFor(first)))
        assertEquals("second", readFileTextOrNull(files.pathFor(second)))
        assertEquals(0b110_000_000, mode(files.pathFor(first)), "the prompt is readable by its owner only")
        assertEquals(0b111_000_000, mode(dir), "the directory is created private")
    }
}
