package io.kotgent.crypto

/**
 * Lowercase hex encoding — the one place bytes become a secret-carrying string in this codebase.
 *
 * Every secret kotgent hands out (the master token, a session-cookie HMAC, a one-shot ticket) is
 * hex-encoded, so they all share this encoder rather than each re-deriving a `joinToString { … }`
 * one-liner: one function, one alphabet, one review.
 */

/** Lowercase hex of [bytes] — two characters per byte, no separators, no `0x` prefix. */
fun hex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        out.append(HEX_DIGITS[v ushr 4])
        out.append(HEX_DIGITS[v and 0x0f])
    }
    return out.toString()
}

private const val HEX_DIGITS: String = "0123456789abcdef"
