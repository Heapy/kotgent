@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.kotgent.host

import io.kotgent.proc.CapturedProcess
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import kotlin.test.*

class DiskCacheFilesTest {
    private fun fixture(block: suspend (String) -> Unit) = runBlocking {
        val result = CapturedProcess.run(listOf("/usr/bin/mktemp", "-d", "/tmp/kotgent-cache-test.XXXXXX"), emptyMap())
        assertEquals(0, result.exitCode)
        val root = result.stdout.decodeToString().trim()
        try { block(root) } finally { assertEquals(0, CapturedProcess.run(listOf("/bin/rm", "-rf", root), emptyMap()).exitCode) }
    }

    private fun write(path: String, bytes: ByteArray) {
        val f = assertNotNull(fopen(path, "wb"))
        try { bytes.usePinned { assertEquals(bytes.size.toULong(), fwrite(it.addressOf(0), 1u, bytes.size.convert(), f)) } }
        finally { fclose(f) }
        assertEquals(0, chmod(path, 384u))
    }

    @Test fun restartExpiryEvictionCountAndByteBudgets() = fixture { root ->
        fun store() = DiskCacheFiles("$root/cache", maxBytes = 100, maxEntries = 2, maxEntryBytes = 50, ttlMillis = 100)
        val cache = store()
        val bytes = ByteArray(40) { 7 }
        cache.put(0, bytes, 1000)
        assertContentEquals(bytes, store().get(0, 1001))
        cache.put(1, bytes, 1002)
        cache.put(2, bytes, 1003)
        assertNull(cache.get(0, 1004))
        assertContentEquals(bytes, cache.get(1, 1004))
        assertContentEquals(bytes, cache.get(2, 1004))
        cache.put(3, ByteArray(51), 1004)
        assertNull(cache.get(3, 1005))
        assertNull(cache.get(1, 1102))
        assertNull(cache.get(2, 999)) // Clock reversal cannot extend retention indefinitely.
        cache.put(4, bytes, 2000)
        assertContentEquals(bytes, cache.get(4, 2000))
        val size = (0..<DiskCacheFiles.SLOTS).sumOf { readRegularFileBytesOrNull("$root/cache/$it")?.size ?: 0 }
        assertTrue(size <= 100)
    }

    @Test fun corruptOversizedSymlinkHardlinkAndSpecialRecordsMiss() = fixture { root ->
        val cache = DiskCacheFiles("$root/cache", maxEntryBytes = 50)
        cache.put(0, byteArrayOf(1), 1000)
        write("$root/cache/0", "broken".encodeToByteArray())
        assertNull(cache.get(0, 1001))
        write("$root/cache/0", ByteArray(100))
        assertNull(cache.get(0, 1001))
        write("$root/victim", "1000\nsecret".encodeToByteArray())
        assertEquals(0, symlink("$root/victim", "$root/cache/0"))
        assertNull(cache.get(0, 1001))
        assertEquals("1000\nsecret", readRegularFileBytesOrNull("$root/victim")?.decodeToString())
        assertEquals(0, link("$root/victim", "$root/cache/0"))
        assertNull(cache.get(0, 1001))
        assertEquals(0, mkfifo("$root/cache/0", 384u))
        assertNull(cache.get(0, 1001))
        cache.put(0, byteArrayOf(2), 1002)
        assertContentEquals(byteArrayOf(2), cache.get(0, 1003))
    }

    @Test fun missingUnsafeRootAndAbandonedStagingAreRecoverable() = fixture { root ->
        val absent = DiskCacheFiles("$root/missing/cache")
        absent.put(0, byteArrayOf(1), 1000)
        assertNull(absent.get(0, 1001))
        assertEquals(0, symlink(root, "$root/link"))
        assertNull(DiskCacheFiles("$root/link").get(0, 1001))
        val cache = DiskCacheFiles("$root/cache")
        cache.put(0, byteArrayOf(1), 1000)
        write("$root/cache/pending", ByteArray(200))
        cache.put(1, byteArrayOf(2), 1002)
        assertNull(readRegularFileBytesOrNull("$root/cache/pending"))
        assertContentEquals(byteArrayOf(2), cache.get(1, 1003))
        assertEquals(0, chmod("$root/cache", 511u))
        assertNull(cache.get(1, 1003))
    }

    @Test fun anUnremovableSlotStopsPublicationRatherThanHidingItsBudget() = fixture { root ->
        val cache = DiskCacheFiles("$root/cache", maxBytes = 100)
        cache.put(0, byteArrayOf(1), 1000)
        // A foreign directory cannot be unlinked as a cache record; cleanup must fail closed.
        assertEquals(0, mkdir("$root/cache/1", 448u))
        cache.put(2, ByteArray(80), 1001)
        assertNull(cache.get(2, 1002))
        assertEquals(0, rmdir("$root/cache/1"))
        cache.put(2, ByteArray(80), 1003)
        assertContentEquals(ByteArray(80), cache.get(2, 1004))
    }

    @Test fun competingInstancesStayBoundedAndCancellationEscapes() = fixture { root ->
        fun store() = DiskCacheFiles("$root/cache", maxBytes = 256, maxEntries = 4, maxEntryBytes = 60)
        coroutineScope {
            (0..<8).map { worker -> async(Dispatchers.Default) {
                val cache = store()
                repeat(16) { cache.put(worker * 16 + it, ByteArray(50) { worker.toByte() }, 1000L + it) }
            } }.awaitAll()
        }
        val records = (0..<DiskCacheFiles.SLOTS).mapNotNull { readRegularFileBytesOrNull("$root/cache/$it") }
        assertTrue(records.size <= 4)
        assertTrue(records.sumOf { it.size } <= 256)
        store().put(999, byteArrayOf(9), 2000)
        assertContentEquals(byteArrayOf(9), store().get(999, 2000))
        val job = CoroutineScope(currentCoroutineContext()).async(start = CoroutineStart.LAZY) { store().put(998, byteArrayOf(8), 2000) }
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertNull(store().get(998, 2000))
    }
}
