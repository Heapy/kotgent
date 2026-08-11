package io.kotgent.pty

/**
 * Tracks DECTCEM cursor visibility across arbitrary read boundaries. TUIs bracket incomplete repaints
 * by hiding and restoring the cursor, so the bridge can coalesce those fragments. The scanner carries
 * the sequence prefix across chunks and ignores non-exact forms, failing toward immediate delivery.
 */
class CursorVisibilityScanner {
    var hidden: Boolean = false
        private set

    private var carry: ByteArray = ByteArray(0)

    fun accept(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        val buf = if (carry.isEmpty()) chunk else carry + chunk
        var i = 0
        while (i + PREFIX.size < buf.size) {
            if (!matchesPrefixAt(buf, i)) {
                i++
                continue
            }
            when (buf[i + PREFIX.size]) {
                FINAL_RESET -> { hidden = true; i += PREFIX.size + 1 }
                FINAL_SET -> { hidden = false; i += PREFIX.size + 1 }
                else -> i++
            }
        }
        carry = if (buf.size <= PREFIX.size) buf else buf.copyOfRange(buf.size - PREFIX.size, buf.size)
    }

    private fun matchesPrefixAt(buf: ByteArray, at: Int): Boolean {
        for (k in PREFIX.indices) if (buf[at + k] != PREFIX[k]) return false
        return true
    }

    companion object {
        private val PREFIX: ByteArray = byteArrayOf(0x1b, '['.code.toByte(), '?'.code.toByte(), '2'.code.toByte(), '5'.code.toByte())

        private const val FINAL_RESET: Byte = 'l'.code.toByte()

        private const val FINAL_SET: Byte = 'h'.code.toByte()
    }
}
