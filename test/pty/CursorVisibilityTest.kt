package io.kotgent.pty

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CursorVisibilityTest {
    private fun bytes(text: String): ByteArray = text.encodeToByteArray()

    @Test
    fun aTerminalStartsWithAVisibleCursor() {
        assertFalse(CursorVisibilityScanner().hidden, "nothing seen yet means nothing is hidden")
    }

    @Test
    fun decsetAndDecrstMoveTheStateBothWays() {
        val scanner = CursorVisibilityScanner()
        scanner.accept(bytes("\u001b[?25l"))
        assertTrue(scanner.hidden, "DECRST hides the cursor")
        scanner.accept(bytes("\u001b[?25h"))
        assertFalse(scanner.hidden, "DECSET shows it again")
    }

    @Test
    fun theLastSequenceInAChunkWins() {
        val scanner = CursorVisibilityScanner()
        scanner.accept(bytes("\u001b[?25l" + "rows of output" + "\u001b[?25h" + "\u001b[?25l"))
        assertTrue(scanner.hidden, "the last DECTCEM in the chunk decides")
    }

    @Test
    fun aSequenceSplitAcrossChunksIsStillRecognised() {
        for (cut in 1 until 6) {
            val whole = "\u001b[?25h"
            val scanner = CursorVisibilityScanner()
            scanner.accept(bytes("\u001b[?25l"))
            assertTrue(scanner.hidden, "cut $cut: hidden before the split show arrives")
            scanner.accept(bytes(whole.substring(0, cut)))
            scanner.accept(bytes(whole.substring(cut)))
            assertFalse(scanner.hidden, "cut $cut: a show split across two chunks still lands")
        }
    }

    @Test
    fun aSplitHideIsRecognisedToo() {
        for (cut in 1 until 6) {
            val whole = "\u001b[?25l"
            val scanner = CursorVisibilityScanner()
            scanner.accept(bytes(whole.substring(0, cut)))
            scanner.accept(bytes(whole.substring(cut)))
            assertTrue(scanner.hidden, "cut $cut: a hide split across two chunks still lands")
        }
    }

    @Test
    fun unrelatedPrivateModesAndParameterisedFormsLeaveTheStateAlone() {
        val scanner = CursorVisibilityScanner()
        scanner.accept(bytes("\u001b[?25l"))
        scanner.accept(bytes("\u001b[?1049h\u001b[?2004h\u001b[?1006h"))
        assertTrue(scanner.hidden, "other private modes do not move the cursor state")
        scanner.accept(bytes("\u001b[?25;1l"))
        assertTrue(scanner.hidden, "an undecoded combined form leaves the known state untouched")
    }

    @Test
    fun ordinaryOutputNeverHidesTheCursor() {
        val scanner = CursorVisibilityScanner()
        scanner.accept(bytes("ls -la\r\ntotal 0\r\n"))
        scanner.accept(ByteArray(0))
        assertFalse(scanner.hidden, "plain output carries no DECTCEM and must not hold a frame back")
    }

    @Test
    fun aChunkShorterThanTheSequenceCannotCorruptTheCarry() {
        val scanner = CursorVisibilityScanner()
        for (byte in "\u001b[?25l".encodeToByteArray()) scanner.accept(byteArrayOf(byte))
        assertTrue(scanner.hidden, "a byte-at-a-time hide is reassembled through the carry")
    }
}
