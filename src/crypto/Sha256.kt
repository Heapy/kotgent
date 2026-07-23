package io.kotgent.crypto

/**
 * SHA-256 (FIPS 180-4) in **pure Kotlin** — no cinterop, no platform library.
 *
 * ## Why not CommonCrypto
 * macOS ships `CC_SHA256` in `<CommonCrypto/CommonDigest.h>`, but that header is not part of the stock
 * `platform.posix` binding set, so reaching it would mean a new `.def` in `sysnative/` — and a custom
 * cinterop klib does NOT link into the TEST binary (KT-78062, see CLAUDE.md). The session cookie's HMAC
 * has to be verifiable from unit tests (that is the whole point of keeping it a pure function), so the
 * digest is implemented here instead. It hashes a few dozen bytes per request; the cost is irrelevant.
 *
 * The implementation is the textbook one: message padding to a multiple of [SHA256_BLOCK_BYTES], then the
 * 64-round compression function over a 16-word schedule extended to 64 words.
 */

/** SHA-256 operates on 512-bit (64-byte) blocks — also the HMAC block size (RFC 2104). */
const val SHA256_BLOCK_BYTES: Int = 64

/** SHA-256 produces a 256-bit (32-byte) digest. */
const val SHA256_DIGEST_BYTES: Int = 32

/**
 * The 32-bit round constants: the first 32 bits of the fractional parts of the cube roots of the first 64
 * primes. Spelled as the standard's unsigned hex and narrowed with [Long.toInt] — a hand-converted signed
 * literal (`-0x4a3f0431`) would be transcribed straight from the spec exactly once and be unreviewable
 * afterwards.
 */
private val ROUND_CONSTANTS: IntArray = longArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
).let { longs -> IntArray(longs.size) { longs[it].toInt() } }

/** The eight initial hash words: the first 32 bits of the fractional parts of the square roots of the first 8 primes. */
private val INITIAL_STATE: IntArray = longArrayOf(
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
).let { longs -> IntArray(longs.size) { longs[it].toInt() } }

/** The SHA-256 digest of [message] — [SHA256_DIGEST_BYTES] bytes, big-endian, as the standard defines it. */
fun sha256(message: ByteArray): ByteArray {
    val state = INITIAL_STATE.copyOf()
    val padded = padded(message)
    val schedule = IntArray(64)
    var offset = 0
    while (offset < padded.size) {
        compress(state, padded, offset, schedule)
        offset += SHA256_BLOCK_BYTES
    }
    val digest = ByteArray(SHA256_DIGEST_BYTES)
    for (i in state.indices) {
        digest[i * 4] = (state[i] ushr 24).toByte()
        digest[i * 4 + 1] = (state[i] ushr 16).toByte()
        digest[i * 4 + 2] = (state[i] ushr 8).toByte()
        digest[i * 4 + 3] = state[i].toByte()
    }
    return digest
}

/**
 * [message] padded per FIPS 180-4 §5.1.1: a `0x80` byte, then zeros, then the message length in BITS as a
 * big-endian 64-bit integer — sized so the result is a whole number of [SHA256_BLOCK_BYTES] blocks. A
 * 55-byte message therefore still fits one block while a 56-byte one needs two; those boundaries are the
 * classic off-by-one in this routine and are asserted in `Sha256Test`.
 */
private fun padded(message: ByteArray): ByteArray {
    val bitLength = message.size.toLong() * 8L
    // 1 byte for the 0x80 terminator + 8 bytes for the length; round the total up to a whole block.
    val minimum = message.size + 9
    val total = ((minimum + SHA256_BLOCK_BYTES - 1) / SHA256_BLOCK_BYTES) * SHA256_BLOCK_BYTES
    val out = ByteArray(total)
    message.copyInto(out)
    out[message.size] = 0x80.toByte()
    for (i in 0 until 8) {
        out[total - 1 - i] = (bitLength ushr (8 * i)).toByte()
    }
    return out
}

/** One 64-round compression of the block at [offset] into [state]; [schedule] is a reused 64-word scratch buffer. */
@Suppress("LongMethod") // the round loop is the algorithm; splitting it would only hide it
private fun compress(state: IntArray, block: ByteArray, offset: Int, schedule: IntArray) {
    for (i in 0 until 16) {
        val p = offset + i * 4
        schedule[i] = ((block[p].toInt() and 0xff) shl 24) or
            ((block[p + 1].toInt() and 0xff) shl 16) or
            ((block[p + 2].toInt() and 0xff) shl 8) or
            (block[p + 3].toInt() and 0xff)
    }
    for (i in 16 until 64) {
        val x = schedule[i - 15]
        val y = schedule[i - 2]
        val s0 = x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)
        val s1 = y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10)
        schedule[i] = schedule[i - 16] + s0 + schedule[i - 7] + s1
    }

    var a = state[0]
    var b = state[1]
    var c = state[2]
    var d = state[3]
    var e = state[4]
    var f = state[5]
    var g = state[6]
    var h = state[7]

    for (i in 0 until 64) {
        val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
        val ch = (e and f) xor (e.inv() and g)
        val t1 = h + s1 + ch + ROUND_CONSTANTS[i] + schedule[i]
        val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
        val maj = (a and b) xor (a and c) xor (b and c)
        val t2 = s0 + maj
        h = g
        g = f
        f = e
        e = d + t1
        d = c
        c = b
        b = a
        a = t1 + t2
    }

    state[0] += a
    state[1] += b
    state[2] += c
    state[3] += d
    state[4] += e
    state[5] += f
    state[6] += g
    state[7] += h
}
