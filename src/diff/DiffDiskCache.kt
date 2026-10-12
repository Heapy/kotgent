package io.kotgent.diff

import io.kotgent.crypto.sha256
import io.kotgent.host.DiskCacheFiles
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.text.CharacterCodingException

/** Disk-only payload storage. Neither this adapter nor its file store retains payloads between calls. */
class DiffDiskCache(
    private val files: DiskCacheFiles,
    private val measure: ((String, Long) -> Unit)? = null,
) {
    internal fun isOutside(vararg paths: String): Boolean = files.isOutside(*paths)

    internal suspend inline fun <reified T> get(key: String, now: Long): T? {
        val bytes = getBytes(key, now) ?: return null
        return try { Json.decodeFromString(serializer<T>(), bytes.decodeToString(throwOnInvalidSequence = true)) }
        catch (_: SerializationException) { null }
        catch (_: CharacterCodingException) { null }
        catch (_: IllegalArgumentException) { null }
    }

    internal suspend inline fun <reified T> put(key: String, value: T, now: Long) {
        putBytes(key, Json.encodeToString(serializer<T>(), value).encodeToByteArray(), now)
    }

    internal suspend fun getBytes(key: String, now: Long): ByteArray? {
        val hash = hash(key.encodeToByteArray())
        for (offset in 0..<WAYS) {
            val record = files.get(slot(hash) + offset, now) ?: continue
            // Fixed-size schema/key/checksum header makes malformed and cross-slot records a miss.
            if (record.size < HEADER_BYTES) continue
            val headerBytes = record.copyOfRange(0, HEADER_BYTES)
            // Offsets below are byte offsets: multibyte or malformed UTF-8 must never shorten them.
            if (headerBytes.any { it < 0 }) continue
            val header = headerBytes.decodeToString()
            if (!header.startsWith("v1\n$hash\n") || header.last() != '\n') continue
            val payload = record.copyOfRange(HEADER_BYTES, record.size)
            if (header.substring(68, 132) == hash(payload)) return payload
        }
        return null
    }

    internal suspend fun putBytes(key: String, bytes: ByteArray, now: Long) {
        val hash = hash(key.encodeToByteArray())
        val base = slot(hash)
        var empty: Int? = null
        var matching: Int? = null
        // Four slots per set keep a cold history/files/patch trio from evicting one another.
        // Competing publishers may replace a collision; that is a miss, never a wrong-key hit.
        for (offset in 0..<WAYS) {
            val record = files.get(base + offset, now)
            if (record == null) {
                if (empty == null) empty = base + offset
            } else if (record.size >= HEADER_BYTES && record.copyOfRange(0, 68).decodeToString() == "v1\n$hash\n") {
                matching = base + offset
                break
            }
        }
        files.put(matching ?: empty ?: base, "v1\n$hash\n${hash(bytes)}\n".encodeToByteArray() + bytes, now)
    }

    private fun hash(bytes: ByteArray): String {
        measure?.invoke("cacheHashBytes", bytes.size.toLong())
        return sha256(bytes).toHexString()
    }
    private fun slot(hash: String): Int = hash.take(8).toUInt(16).rem((DiskCacheFiles.SLOTS / WAYS).toUInt()).toInt() * WAYS

    private companion object {
        const val WAYS = 4
        const val HEADER_BYTES = 133
    }
}
