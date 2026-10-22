@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.kotgent.host

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class ZstdReaderTest {
    // Generated from testResources/codex-rollout/synthetic.jsonl by the pinned upstream 1.5.7 CLI --check.
    private val compressed = ("28b52ffd2473c50200c245131890b739bdd415a50e44c4fcd82fb91b75d7" +
        "0a3b5218cb133a66d7d73be02a60f371299117ee98a8c08470b36b64fc04" +
        "c7a5c4204985b03929c88b399d3cd6511e7c7e488169a89eda7c6add4b02" +
        "004026fe9a820261b8d888").hexToByteArray()
    private val text = """{"type":"session_meta","payload":{"cwd":"/work/synthetic"}}""" + "\n" +
        """{"type":"turn_context","payload":{"model":"gpt-test"}}""" + "\n"

    @Test fun actualCompressedFixtureAndPlainHaveIdenticalWindows() = fixture { path ->
        write(path, compressed)
        val prefix = assertIs<DecodedFileRead.Window>(readDecodedPrefix(path, true, 20))
        assertContentEquals(text.encodeToByteArray().copyOf(20), prefix.bytes)
        assertEquals(0L, prefix.logicalOffset)
        val tail = runBlocking { assertIs<DecodedFileRead.Window>(readDecodedTail(path, true, 31)) }
        assertContentEquals(text.encodeToByteArray().takeLast(31).toByteArray(), tail.bytes)
        assertEquals((text.encodeToByteArray().size - 31).toLong(), tail.logicalOffset)
        write(path, text.encodeToByteArray())
        val plain = runBlocking { assertIs<DecodedFileRead.Window>(readDecodedTail(path, false, 31)) }
        assertContentEquals(tail.bytes, plain.bytes)
        assertEquals(tail.logicalOffset, plain.logicalOffset)
    }

    @Test fun concatenatedFramesAndUtf8WindowsUseDecodedByteOffsets() = fixture { path ->
        val unicode = "é🎉中".repeat(11).encodeToByteArray()
        write(path, compressed + rawFrame(unicode) + compressed)
        val all = text.encodeToByteArray() + unicode + text.encodeToByteArray()
        val take = text.length + unicode.size - 1 // starts inside a multibyte code point
        val tail = runBlocking { assertIs<DecodedFileRead.Window>(readDecodedTail(path, true, take)) }
        assertContentEquals(all.takeLast(take).toByteArray(), tail.bytes)
        assertEquals((all.size - take).toLong(), tail.logicalOffset)
    }

    @Test fun largeExpansionKeepsOnlyTheBoundedTail() = fixture { path ->
        val expanded = 16 * 1024 * 1024
        write(path, repeatedFrame(expanded))
        val tail = runBlocking { withTimeout(10.seconds) {
            assertIs<DecodedFileRead.Window>(readDecodedTail(path, true, DECODED_WINDOW_MAX_BYTES))
        } }
        assertEquals(DECODED_WINDOW_MAX_BYTES, tail.bytes.size)
        assertTrue(tail.bytes.all { it == 'a'.code.toByte() })
        assertEquals((expanded - DECODED_WINDOW_MAX_BYTES).toLong(), tail.logicalOffset)
        assertEquals(8192, assertIs<DecodedFileRead.Window>(readDecodedPrefix(path, true, 8192)).bytes.size)
    }

    @Test fun partialAndCorruptTailsNeverPublishEarlierBytes() = fixture { path ->
        val badChecksum = compressed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        for (bytes in listOf(byteArrayOf(), compressed.copyOf(7), compressed.copyOf(compressed.size - 1),
            badChecksum, compressed + compressed.copyOf(12), compressed + byteArrayOf(1, 2, 3))) {
            write(path, bytes)
            assertEquals(DecodedFileRead.Unreadable, runBlocking { readDecodedTail(path, true, 256) })
        }
        write(path, compressed + byteArrayOf(1, 2, 3))
        assertEquals(text.take(12), assertIs<DecodedFileRead.Window>(readDecodedPrefix(path, true, 12)).bytes.decodeToString())
    }

    @Test fun excessiveWindowAndPrefixWorkAreTypedLimits() = fixture { path ->
        // Non-single-segment frame requesting a 2^28 window; our supported maximum is 2^27.
        write(path, "28b52ffd0090010000".hexToByteArray())
        assertEquals(DecodedFileRead.ResourceLimit, readDecodedPrefix(path, true, 1))
        assertEquals(DecodedFileRead.ResourceLimit, runBlocking { readDecodedTail(path, true, 1) })
        val emptyFrame = "28b52ffd2000010000".hexToByteArray()
        write(path, ByteArray(emptyFrame.size * (PREFIX_MAX_STEPS + 1)) { emptyFrame[it % emptyFrame.size] })
        assertEquals(DecodedFileRead.ResourceLimit, readDecodedPrefix(path, true, 1))
        // Skippable payload consumes input but produces nothing; an independent byte budget stops it.
        val length = PREFIX_MAX_INPUT_BYTES.toInt()
        write(path, "502a4d18".hexToByteArray() + little(length, 4) + ByteArray(length))
        assertEquals(DecodedFileRead.ResourceLimit, readDecodedPrefix(path, true, 1))
        // An empty valid frame is a valid empty stream, unlike an empty physical file.
        write(path, emptyFrame)
        assertContentEquals(byteArrayOf(), runBlocking { assertIs<DecodedFileRead.Window>(readDecodedTail(path, true, 1)).bytes })
    }

    @Test fun missingAndNonregularFilesDoNotBlockOrMasqueradeAsContent() = fixture { path ->
        assertEquals(DecodedFileRead.Missing, readDecodedPrefix(path, true, 10))
        assertEquals(RegularFileStatus.MISSING, regularFileStatus(path))
        assertEquals(0, mkfifo(path, 384u))
        assertEquals(DecodedFileRead.Unreadable, readDecodedPrefix(path, true, 10))
        assertEquals(RegularFileStatus.UNREADABLE, regularFileStatus(path))
        assertEquals(0, unlink(path))
        assertEquals(0, mkdir(path, 448u))
        try { assertEquals(DecodedFileRead.Unreadable, readDecodedPrefix(path, false, 10)) }
        finally { assertEquals(0, rmdir(path)) }
        write(path, byteArrayOf())
        assertContentEquals(byteArrayOf(), assertIs<DecodedFileRead.Window>(readDecodedPrefix(path, false, 10)).bytes)
    }

    @Test fun cancellationDuringDecodingAndErrorsCloseDescriptorsAndAllowRecovery() = fixture { path ->
        write(path, repeatedFrame(16 * 1024 * 1024))
        val before = descriptorCount()
        runBlocking {
            withTimeout(10.seconds) {
                val reading = async(start = CoroutineStart.UNDISPATCHED) { readDecodedTail(path, true, 1024) }
                // Each yield lets the reader execute another bounded native step on this same dispatcher.
                repeat(5) { yield() }
                assertFalse(reading.isCompleted)
                reading.cancelAndJoin()
                assertFailsWith<CancellationException> { reading.await() }
            }
        }
        assertEquals(before, descriptorCount())
        write(path, compressed.copyOf(compressed.size - 1))
        repeat(20) { assertEquals(DecodedFileRead.Unreadable, runBlocking { readDecodedTail(path, true, 1024) }) }
        assertEquals(before, descriptorCount())
        write(path, compressed)
        assertEquals(text, runBlocking { assertIs<DecodedFileRead.Window>(readDecodedTail(path, true, 1024)).bytes.decodeToString() })
    }

    private fun little(value: Int, bytes: Int): ByteArray = ByteArray(bytes) { (value ushr (8 * it)).toByte() }
    private fun rawFrame(bytes: ByteArray): ByteArray =
        "28b52ffda0".hexToByteArray() + little(bytes.size, 4) + little((bytes.size shl 3) or 1, 3) + bytes

    private fun repeatedFrame(size: Int): ByteArray {
        require(size % (128 * 1024) == 0)
        val blocks = size / (128 * 1024)
        return "28b52ffda0".hexToByteArray() + little(size, 4) + ByteArray(blocks * 4) { index ->
            val block = index / 4
            val header = (128 * 1024 shl 3) or 2 or (if (block == blocks - 1) 1 else 0)
            if (index % 4 == 3) 'a'.code.toByte() else (header ushr (8 * (index % 4))).toByte()
        }
    }

    private fun descriptorCount(): Int {
        val directory = assertNotNull(opendir("/dev/fd"))
        var count = 0
        try { while (readdir(directory) != null) count++ } finally { closedir(directory) }
        return count
    }

    private fun fixture(block: (String) -> Unit) {
        val root = memScoped { mkdtemp("/tmp/kotgent-zstd.XXXXXX".cstr.ptr)?.toKString() }!!
        val path = "$root/input"
        try { block(path) } finally { unlink(path); rmdir(root) }
    }

    private fun write(path: String, bytes: ByteArray) {
        val file = assertNotNull(fopen(path, "wb"))
        try {
            if (bytes.isNotEmpty()) bytes.usePinned { assertEquals(bytes.size.toULong(), fwrite(it.addressOf(0), 1u, bytes.size.convert(), file)) }
        } finally { fclose(file) }
    }
}
