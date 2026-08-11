package io.kotgent.crypto

private const val IPAD: Int = 0x36

private const val OPAD: Int = 0x5c

/** RFC 2104 HMAC-SHA256, including hash-first normalization for overlong keys. */
fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val block = ByteArray(SHA256_BLOCK_BYTES)
    val normalized = if (key.size > SHA256_BLOCK_BYTES) sha256(key) else key
    normalized.copyInto(block)

    val inner = ByteArray(SHA256_BLOCK_BYTES + message.size)
    for (i in 0 until SHA256_BLOCK_BYTES) inner[i] = (block[i].toInt() xor IPAD).toByte()
    message.copyInto(inner, SHA256_BLOCK_BYTES)

    val innerDigest = sha256(inner)
    val outer = ByteArray(SHA256_BLOCK_BYTES + innerDigest.size)
    for (i in 0 until SHA256_BLOCK_BYTES) outer[i] = (block[i].toInt() xor OPAD).toByte()
    innerDigest.copyInto(outer, SHA256_BLOCK_BYTES)

    return sha256(outer)
}

fun hmacSha256Hex(key: String, message: String): String =
    hex(hmacSha256(key.encodeToByteArray(), message.encodeToByteArray()))
