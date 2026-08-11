package io.kotgent.transport

import io.ktor.utils.io.ByteReadChannel
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.closedir
import platform.posix.mkdir
import platform.posix.mkdtemp
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class FileUploadTest {

    @Test
    fun validLeafNamesAreAcceptedAndTraversalIsRejected() {
        assertNull(uploadFileNameProblem("photo 01.jpg"))
        assertNull(uploadFileNameProblem("notes\\from-phone.txt"), "backslash is an ordinary POSIX leaf character")

        for (name in listOf(null, "", "   ", ".", "..", "../escape", "nested/file", "line\nbreak")) {
            assertNotNull(uploadFileNameProblem(name), "'$name' must not be accepted as an upload leaf")
        }
        assertNotNull(
            uploadFileNameProblem("é".repeat(128)),
            "the component limit is measured in UTF-8 bytes, not Kotlin characters",
        )
    }

    @Test
    fun uploadStreamsTheExactBytesAndPublishesOnlyTheFinishedFile() = runBlocking {
        withTempDir { dir ->
            val payload = ByteArray(150_000) { index -> (index % 251).toByte() }
            val result = saveUploadedFile(
                directory = dir,
                fileName = "from-phone.bin",
                body = ByteReadChannel(payload),
                expectedBytes = payload.size.toLong(),
            )

            assertEquals(FileUploadResult.Stored(payload.size.toLong()), result)
            assertContentEquals(payload, readFileBytesOrNull("$dir/from-phone.bin"))
            assertTrue(
                childNames(dir).none { it.startsWith(".kotgent-upload-") },
                "the sibling staging file is removed after publication",
            )
        }
    }

    @Test
    fun uploadNeverOverwritesAnExistingDestination() = runBlocking {
        withTempDir { dir ->
            val target = "$dir/notes.txt"
            writePrivateFile(target, "keep me".encodeToByteArray())

            val result = saveUploadedFile(
                directory = dir,
                fileName = "notes.txt",
                body = ByteReadChannel("replacement".encodeToByteArray()),
                expectedBytes = 11L,
            )

            assertEquals(FileUploadResult.AlreadyExists, result)
            assertEquals("keep me", readFileTextOrNull(target))
            assertTrue(
                childNames(dir).none { it.startsWith(".kotgent-upload-") },
                "a losing no-clobber publish removes its private temp",
            )
        }
    }

    @Test
    fun oversizedAndIncompleteBodiesLeaveNoDestinationOrTemp() = runBlocking {
        withTempDir { dir ->
            assertEquals(
                FileUploadResult.TooLarge,
                saveUploadedFile(
                    directory = dir,
                    fileName = "large.bin",
                    body = ByteReadChannel("12345".encodeToByteArray()),
                    expectedBytes = null,
                    maxBytes = 4,
                ),
            )
            assertEquals(
                FileUploadResult.LengthMismatch,
                saveUploadedFile(
                    directory = dir,
                    fileName = "short.bin",
                    body = ByteReadChannel("123".encodeToByteArray()),
                    expectedBytes = 5,
                    maxBytes = 10,
                ),
            )

            assertNull(readFileBytesOrNull("$dir/large.bin"))
            assertNull(readFileBytesOrNull("$dir/short.bin"))
            assertTrue(
                childNames(dir).none { it.startsWith(".kotgent-upload-") },
                "rejected bodies leave no staging litter",
            )
        }
    }

    private inline fun withTempDir(block: (String) -> Unit) {
        val dir = makeTempDir()
        try {
            block(dir)
        } finally {
            for (name in childNames(dir)) unlink("$dir/$name")
            assertEquals(0, rmdir(dir), "the upload left no untracked files in its throwaway directory")
        }
    }

    private fun makeTempDir(): String = memScoped {
        val template = "/tmp/kotgent-upload-test-XXXXXX"
        val encoded = template.encodeToByteArray()
        val chars = allocArray<ByteVar>(encoded.size + 1)
        encoded.forEachIndexed { index, byte -> chars[index] = byte }
        chars[encoded.size] = 0
        mkdtemp(chars)?.toKString() ?: run {
            val path = "/tmp/kotgent-upload-test-fallback-${kotlin.random.Random.nextInt().toUInt()}"
            assertEquals(0, mkdir(path, 0b111_000_000.convert()), "could not create upload test directory")
            path
        }
    }

    private fun childNames(path: String): List<String> {
        val dir = opendir(path) ?: return emptyList()
        return try {
            buildList {
                while (true) {
                    val entry = readdir(dir) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (name != "." && name != "..") add(name)
                }
            }
        } finally {
            closedir(dir)
        }
    }
}
