package io.kotgent.diff

import io.kotgent.crypto.sha256
import io.kotgent.host.DiskCacheFiles
import io.kotgent.proc.CapturedProcess
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DiffDiskCacheTest {
    @Test fun checksumSchemaKeyAndSerializationCorruptionAreMisses() = runBlocking {
        val temp = CapturedProcess.run(listOf("/usr/bin/mktemp", "-d", "/tmp/kotgent-payload-cache.XXXXXX"), emptyMap())
        assertEquals(0, temp.exitCode)
        val root = temp.stdout.decodeToString().trim()
        try {
            val files = DiskCacheFiles(root)
            val cache = DiffDiskCache(files)
            val key = "test-key"
            val hash = sha256(key.encodeToByteArray()).toHexString()
            val slot = hash.take(8).toUInt(16).rem((DiskCacheFiles.SLOTS / 4).toUInt()).toInt() * 4
            cache.put(key, listOf("payload"), 1000)
            assertEquals(listOf("payload"), DiffDiskCache(files).get<List<String>>(key, 1001))
            val record = assertNotNull(files.get(slot, 1001))
            files.put(slot, record.copyOf().also { it[it.lastIndex] = 'x'.code.toByte() }, 1000)
            assertNull(cache.get<List<String>>(key, 1001))
            files.put(slot, record.copyOf().also { it[1] = '0'.code.toByte() }, 1000)
            assertNull(cache.get<List<String>>(key, 1001))
            files.put(slot, record.copyOf().also { it[3] = if (it[3] == 97.toByte()) 98 else 97 }, 1000)
            assertNull(cache.get<List<String>>(key, 1001))
            cache.putBytes(key, "not JSON".encodeToByteArray(), 1000)
            assertNull(cache.get<List<String>>(key, 1001))
            cache.put(key, listOf("recovered"), 1002)
            assertEquals(listOf("recovered"), cache.get<List<String>>(key, 1003))
            val collisions = listOf("collision-441", "collision-538", "collision-570", "collision-1251")
            for (collision in collisions) cache.put(collision, listOf(collision), 2000)
            for (collision in collisions) assertEquals(listOf(collision), cache.get<List<String>>(collision, 2001))
        } finally { assertEquals(0, CapturedProcess.run(listOf("/bin/rm", "-rf", root), emptyMap()).exitCode) }
    }
}
