package io.kotgent.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class Base64UrlTest {

    @Test
    fun matchesTheRfc4648TestVectors() {
        assertEquals("", base64Url("".encodeToByteArray()), "the empty input encodes to the empty string")
        assertEquals("Zg", base64Url("f".encodeToByteArray()))
        assertEquals("Zm8", base64Url("fo".encodeToByteArray()))
        assertEquals("Zm9v", base64Url("foo".encodeToByteArray()))
        assertEquals("Zm9vYg", base64Url("foob".encodeToByteArray()))
        assertEquals("Zm9vYmE", base64Url("fooba".encodeToByteArray()))
        assertEquals("Zm9vYmFy", base64Url("foobar".encodeToByteArray()))
    }

    @Test
    fun encodesEveryPaddingRemainderLength() {
        assertEquals(4, base64Url(byteArrayOf(0, 0, 0)).length, "a whole 3-byte group is 4 characters")
        assertEquals("AAAA", base64Url(byteArrayOf(0, 0, 0)))
        assertEquals("AA", base64Url(byteArrayOf(0)), "a 1-byte tail is 2 characters, not 4")
        assertEquals("AAA", base64Url(byteArrayOf(0, 0)), "a 2-byte tail is 3 characters, not 4")
        assertEquals("_w", base64Url(byteArrayOf(0xff.toByte())), "the 1-byte tail keeps its low bits")
        assertEquals("-_8", base64Url(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
        assertEquals("-_A", base64Url(byteArrayOf(0xfb.toByte(), 0xf0.toByte())))
    }

    @Test
    fun usesTheUrlSafeAlphabetForHighBitBytes() {
        assertEquals("____", base64Url(byteArrayOf(-1, -1, -1)), "index 63 is '_', never '/'")
        assertEquals("-_8", base64Url(byteArrayOf(0xfb.toByte(), 0xff.toByte())), "index 62 is '-', never '+'")
    }

    @Test
    fun neverEmitsPlusSlashOrPadding() {
        val allBytes = ByteArray(256) { it.toByte() }
        val encoded = base64Url(allBytes)
        assertFalse(encoded.contains('+'), "'+' would break a URL and a JWT segment")
        assertFalse(encoded.contains('/'), "'/' would break a URL and a JWT segment")
        assertFalse(encoded.contains('='), "padding is forbidden by RFC 7515 §2")
        for (size in 0..64) {
            assertFalse(base64Url(ByteArray(size) { 0xff.toByte() }).contains('='), "padding for $size bytes")
        }
    }

    @Test
    fun handlesEveryByteValue() {
        assertEquals(
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0-P0" +
                "BBQkNERUZHSElKS0xNTk9QUVJTVFVWV1hZWltcXV5fYGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn" +
                "-AgYKDhIWGh4iJiouMjY6PkJGSk5SVlpeYmZqbnJ2en6ChoqOkpaanqKmqq6ytrq-wsbKztLW2t7i5uru8vb" +
                "6_wMHCw8TFxsfIycrLzM3Oz9DR0tPU1dbX2Nna29zd3t_g4eLj5OXm5-jp6uvs7e7v8PHy8_T19vf4-fr7_P" +
                "3-_w",
            base64Url(ByteArray(256) { it.toByte() }),
        )
    }

    @Test
    fun encodesTheShapeVapidActuallySends() {
        val point = ByteArray(65) { if (it == 0) 0x04 else (it - 1).toByte() }
        val encoded = base64Url(point)
        assertEquals(87, encoded.length, "65 bytes is 21 full groups plus a 2-byte tail")
        assertEquals(
            "BAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
            encoded,
        )
    }

    @Test
    fun outputLengthIsFourCharactersPerThreeBytesRoundedUp() {
        for (size in 0..40) {
            val expected = (size + 2) / 3 * 4 - listOf(0, 2, 1)[size % 3]
            assertEquals(expected, base64Url(ByteArray(size)).length, "encoded length for $size bytes")
        }
    }
}
