package io.kotgent.pty

/**
 * DECTCEM (`CSI ? 25 l` / `CSI ? 25 h`) tracking over a byte stream that arrives in arbitrary pieces.
 *
 * ## Why the fan-out cares about the cursor at all
 * A TUI — and tmux relaying it — turns the cursor OFF before repainting and back ON when the repaint is
 * complete, so no viewer ever watches the cursor walk the screen. That makes "the cursor is hidden" a
 * precise, in-band statement that **the screen is mid-repaint and therefore not consistent yet**. It is
 * the only such signal the byte stream carries, and it costs a six-byte scan to read.
 *
 * The bridge uses it to decide what NOT to send: an upstream read taken while the cursor is hidden is a
 * fragment of a repaint, and shipping it as its own frame is what lets a browser paint a half-drawn
 * screen. Measured on a live claude pane: one repaint arrives as 4–6 pty reads (median read: 1 KiB,
 * ~4.5 KiB per repaint) delivered within 0–10 ms — comfortably inside one 16.7 ms display frame, yet
 * split across it about 30% of the time, which dropped the cursor ~3 times a second.
 *
 * ## Split-safe by construction
 * A read boundary can fall anywhere, including inside the six bytes of the sequence itself, so the
 * scanner keeps the last [PREFIX] bytes of each chunk as [carry] and prepends them to the next one. A
 * sequence straddling a boundary is therefore still recognised; without it, the reader would see no
 * `?25h`, hold the repaint until its timeout, and add latency for nothing.
 *
 * Anything that is not exactly `?25l` / `?25h` is ignored — a combined `CSI ? 25 ; 1 l` leaves the state
 * untouched, which fails toward sending rather than toward holding. Pure Kotlin, so the rule is unit
 * tested for real in the test binary (KT-78062 keeps our cinterop out of it).
 */
class CursorVisibilityScanner {
    /** Whether the last DECTCEM seen so far turned the cursor OFF. Starts visible, as a terminal does. */
    var hidden: Boolean = false
        private set

    private var carry: ByteArray = ByteArray(0)

    /** Fold [chunk] into [hidden], honouring a sequence split across the previous chunk's boundary. */
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
                else -> i++                          // `CSI ? 25 …` but not DECTCEM — not ours
            }
        }
        carry = if (buf.size <= PREFIX.size) buf else buf.copyOfRange(buf.size - PREFIX.size, buf.size)
    }

    private fun matchesPrefixAt(buf: ByteArray, at: Int): Boolean {
        for (k in PREFIX.indices) if (buf[at + k] != PREFIX[k]) return false
        return true
    }

    companion object {
        /** `ESC [ ? 2 5` — everything up to the final byte that says which way the mode goes. */
        private val PREFIX: ByteArray = byteArrayOf(0x1b, '['.code.toByte(), '?'.code.toByte(), '2'.code.toByte(), '5'.code.toByte())

        /** `l` — DECRST, cursor off. */
        private const val FINAL_RESET: Byte = 'l'.code.toByte()

        /** `h` — DECSET, cursor on. */
        private const val FINAL_SET: Byte = 'h'.code.toByte()
    }
}
