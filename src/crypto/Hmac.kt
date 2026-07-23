package io.kotgent.crypto

/**
 * HMAC-SHA256 (RFC 2104) on top of the pure-Kotlin [sha256] — the primitive the stateless session cookie
 * is built from (`hmac = HMAC-SHA256(master token, "v1|" + issuedAt)`).
 *
 * Plain `sha256(secret + message)` would NOT do: SHA-256 is a Merkle–Damgård construction, so anyone
 * holding one valid `(message, digest)` pair can extend the message and compute the digest of the longer
 * one without knowing the secret. HMAC's two-pass ipad/opad construction is exactly the fix, which is why
 * this exists instead of a hand-rolled keyed hash.
 */

/** RFC 2104 inner padding byte, XORed over the block-sized key. */
private const val IPAD: Int = 0x36

/** RFC 2104 outer padding byte, XORed over the block-sized key. */
private const val OPAD: Int = 0x5c

/**
 * `HMAC-SHA256(key, message)` — [SHA256_DIGEST_BYTES] raw bytes.
 *
 * Key handling follows RFC 2104 §2 exactly: a key longer than [SHA256_BLOCK_BYTES] is replaced by its own
 * SHA-256 digest first, and any key shorter than a block is zero-padded up to one. Both edges matter here
 * — kotgent's master token is a 64-character hex string, i.e. exactly 64 bytes of UTF-8, sitting right on
 * the block boundary, so an off-by-one in either branch would show up only after a token format change.
 */
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

/** `HMAC-SHA256(key, message)` over UTF-8 text, hex-encoded — the form the session cookie carries. */
fun hmacSha256Hex(key: String, message: String): String =
    hex(hmacSha256(key.encodeToByteArray(), message.encodeToByteArray()))
