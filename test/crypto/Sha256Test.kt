package io.kotgent.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {

    @Test
    fun matchesTheFips1804Examples() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(sha256(ByteArray(0))),
            "the empty message",
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hexOf("abc"),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hexOf("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
            "the 56-byte two-block example",
        )
        assertEquals(
            "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            hexOf(
                "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno" +
                    "ijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu",
            ),
            "the 112-byte example",
        )
    }

    @Test
    fun handlesThePaddingBoundaries() {
        assertEquals("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb", hexOf(repeated(1)))
        assertEquals("9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318", hexOf(repeated(55)))
        assertEquals("b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a", hexOf(repeated(56)))
        assertEquals("f13b2d724659eb3bf47f2dd6af1accc87b81f09f59f2b75e5c0bed6589dfe8c6", hexOf(repeated(57)))
        assertEquals("7d3e74a05d7db15bce4ad9ec0658ea98e3f06eeecf16b4c6fff2da457ddc2f34", hexOf(repeated(63)))
        assertEquals("ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb", hexOf(repeated(64)))
        assertEquals("635361c48bb9eab14198e76ea8ab7f1a41685d6ad62aa9146d301d4f17eb0ae0", hexOf(repeated(65)))
        assertEquals("31eba51c313a5c08226adf18d4a359cfdfd8d2e816b13f4af952f7ea6584dcfb", hexOf(repeated(119)))
        assertEquals("2f3d335432c70b580af0e8e1b3674a7c020d683aa5f73aaaedfdc55af904c21c", hexOf(repeated(120)))
    }

    @Test
    fun handlesMessagesLongerThanSeveralBlocks() {
        assertEquals(
            "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3",
            hexOf(repeated(1000)),
            "1000 bytes — ~16 blocks, so the schedule and the running state are exercised repeatedly",
        )
    }

    @Test
    fun handlesEveryByteValue() {
        val allBytes = ByteArray(256) { it.toByte() }
        assertEquals(
            "40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880",
            hex(sha256(allBytes)),
        )
    }

    @Test
    fun digestIsAlwaysThirtyTwoBytes() {
        for (size in intArrayOf(0, 1, 55, 56, 63, 64, 65, 200)) {
            assertEquals(SHA256_DIGEST_BYTES, sha256(ByteArray(size)).size, "digest length for $size input bytes")
        }
    }

    @Test
    fun hexEncodesLowercaseTwoDigitsPerByte() {
        assertEquals("", hex(ByteArray(0)))
        assertEquals("00", hex(byteArrayOf(0)))
        assertEquals("0f", hex(byteArrayOf(0x0f)), "a value below 0x10 keeps its leading zero")
        assertEquals("ff", hex(byteArrayOf(0xff.toByte())), "the sign bit does not leak into the encoding")
        assertEquals("00010aff", hex(byteArrayOf(0, 1, 10, 0xff.toByte())))
    }

    private fun hexOf(text: String): String = hex(sha256(text.encodeToByteArray()))

    private fun repeated(n: Int): String = "a".repeat(n)
}
