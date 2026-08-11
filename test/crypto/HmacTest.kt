package io.kotgent.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HmacTest {

    @Test
    fun rfc4231Case1ShortKeyShortMessage() {
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            hex(hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray())),
        )
    }

    @Test
    fun rfc4231Case2TextKey() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hmacSha256Hex("Jefe", "what do ya want for nothing?"),
        )
    }

    @Test
    fun rfc4231Case3BinaryMessage() {
        assertEquals(
            "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe",
            hex(hmacSha256(ByteArray(20) { 0xaa.toByte() }, ByteArray(50) { 0xdd.toByte() })),
        )
    }

    @Test
    fun rfc4231Case4CountingKey() {
        val key = ByteArray(25) { (it + 1).toByte() }
        assertEquals(
            "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b",
            hex(hmacSha256(key, ByteArray(50) { 0xcd.toByte() })),
        )
    }

    @Test
    fun rfc4231Case6KeyLongerThanTheBlock() {
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            hex(
                hmacSha256(
                    ByteArray(131) { 0xaa.toByte() },
                    "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
                ),
            ),
        )
    }

    @Test
    fun rfc4231Case7LongKeyAndLongMessage() {
        assertEquals(
            "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2",
            hex(
                hmacSha256(
                    ByteArray(131) { 0xaa.toByte() },
                    (
                        "This is a test using a larger than block-size key and a larger than block-size data. " +
                            "The key needs to be hashed before being used by the HMAC algorithm."
                        ).encodeToByteArray(),
                ),
            ),
        )
    }

    @Test
    fun aKeyOfExactlyOneBlockIsUsedVerbatim() {
        assertEquals(
            "a070cce143022ab2ac2136358023c8c78babe36c586ccf6dac456c18dfa00eba",
            hex(hmacSha256(ByteArray(SHA256_BLOCK_BYTES) { 0xaa.toByte() }, "exactly one block key".encodeToByteArray())),
        )
    }

    @Test
    fun anEmptyKeyAndEmptyMessageStillProduceTheStandardMac() {
        assertEquals(
            "b613679a0814d9ec772f95d778c35fc5ff1697c493715653c6c712144292c5ad",
            hmacSha256Hex("", ""),
        )
    }

    @Test
    fun macIsThirtyTwoBytesAndKeyDependent() {
        val mac = hmacSha256("token-a".encodeToByteArray(), "v1|17".encodeToByteArray())
        assertEquals(SHA256_DIGEST_BYTES, mac.size)
        assertNotEquals(
            hmacSha256Hex("token-a", "v1|17"),
            hmacSha256Hex("token-b", "v1|17"),
            "a different key yields a different MAC — this is what makes token rotation revoke cookies",
        )
        assertNotEquals(
            hmacSha256Hex("token-a", "v1|17"),
            hmacSha256Hex("token-a", "v1|18"),
            "a different message yields a different MAC",
        )
        assertEquals(
            hmacSha256Hex("token-a", "v1|17"),
            hmacSha256Hex("token-a", "v1|17"),
            "and the same inputs are reproducible — the cookie is verified by recomputation",
        )
    }
}
