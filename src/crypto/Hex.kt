package io.kotgent.crypto

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
