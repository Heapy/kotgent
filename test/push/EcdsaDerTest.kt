package io.kotgent.push

import io.kotgent.crypto.hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EcdsaDerTest {

    private val der3232 =
        "304402206bdfc37cfe15377f1d60f6a43af698c943e09e71049f2f6d8905f46ce8e11819" +
            "022008b7df673fb6fd8aafaf9b9289aeee4278124ff74898e16e97265695225f3e9d"
    private val raw3232 =
        "6bdfc37cfe15377f1d60f6a43af698c943e09e71049f2f6d8905f46ce8e11819" +
            "08b7df673fb6fd8aafaf9b9289aeee4278124ff74898e16e97265695225f3e9d"

    private val der3332 =
        "3045022100c8e7f5fcd07698820ea350b6480b4f307361ffaebef7b7d657e711d0ae57777d" +
            "022072e26a0743e34dad0d0ae754dc55039a194d17f19c27873684077f01524ed069"
    private val raw3332 =
        "c8e7f5fcd07698820ea350b6480b4f307361ffaebef7b7d657e711d0ae57777d" +
            "72e26a0743e34dad0d0ae754dc55039a194d17f19c27873684077f01524ed069"

    private val der3233 =
        "3045022024e83791f03db46f7c1edf4684ed550d97e6b25588d7807cea294e0ef5fa59d8" +
            "022100e2379fc646f9b73560928744ee8e187e7465bc755dce4e39b3ee46e6e85419d6"
    private val raw3233 =
        "24e83791f03db46f7c1edf4684ed550d97e6b25588d7807cea294e0ef5fa59d8" +
            "e2379fc646f9b73560928744ee8e187e7465bc755dce4e39b3ee46e6e85419d6"

    private val der3333 =
        "30460221009c95c31f2db96fade421f021e3109c4d57e53b740423cdacf867d310b1436ed6" +
            "022100e203901ca1b797425c23dbcb535d00a756cabefd7784ab51bf9545bd5e070797"
    private val raw3333 =
        "9c95c31f2db96fade421f021e3109c4d57e53b740423cdacf867d310b1436ed6" +
            "e203901ca1b797425c23dbcb535d00a756cabefd7784ab51bf9545bd5e070797"

    private val der3132 =
        "3043021f39dac52d7ec57d438b2ee10ed45527414fbad905ba3e7d68e8d5f78235ed59" +
            "02203fc3c90528257af87eaadc972d3b7c8e9ba8032728b73aab8821d64186e96a4f"
    private val raw3132 =
        "0039dac52d7ec57d438b2ee10ed45527414fbad905ba3e7d68e8d5f78235ed59" +
            "3fc3c90528257af87eaadc972d3b7c8e9ba8032728b73aab8821d64186e96a4f"

    private val der3231 =
        "3043022065110fe2a9060a743d78483d9f0988cbc3c9a4ea8c800b6bca8808104af4b20f" +
            "021f405e37fc68115fdf72f04a8d719c103a6381a29bf8344ba44ced6533e97786"
    private val raw3231 =
        "65110fe2a9060a743d78483d9f0988cbc3c9a4ea8c800b6bca8808104af4b20f" +
            "00405e37fc68115fdf72f04a8d719c103a6381a29bf8344ba44ced6533e97786"

    private fun bytes(hexText: String): ByteArray {
        check(hexText.length % 2 == 0) { "odd-length hex fixture" }
        return ByteArray(hexText.length / 2) { hexText.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun derOf(r: ByteArray, s: ByteArray): ByteArray {
        val content = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, content.size.toByte()) + content
    }

    private fun assertDecodes(der: String, expected: String, shape: String) {
        val raw = derToRawSignature(bytes(der))
        assertEquals(P256_RAW_SIGNATURE_LENGTH, raw.size, "$shape decodes to a fixed 64-byte r||s")
        assertEquals(expected, hex(raw), "$shape decodes to the r and s openssl asn1parse reports")
    }

    @Test
    fun decodesTheCommonSeventyByteForm() {
        assertEquals(70, bytes(der3232).size, "fixture precondition")
        assertDecodes(der3232, raw3232, "the 32/32 form")
    }

    @Test
    fun stripsTheSignByteFromEitherCoordinate() {
        assertEquals(71, bytes(der3332).size, "fixture precondition")
        assertEquals(71, bytes(der3233).size, "fixture precondition")
        assertEquals(72, bytes(der3333).size, "fixture precondition")

        assertDecodes(der3332, raw3332, "a 0x00 sign byte on r")
        assertDecodes(der3233, raw3233, "a 0x00 sign byte on s")
        assertDecodes(der3333, raw3333, "a 0x00 sign byte on both")
    }

    @Test
    fun leftPadsAShortCoordinate() {
        assertEquals(69, bytes(der3132).size, "fixture precondition")
        assertEquals(69, bytes(der3231).size, "fixture precondition")

        assertDecodes(der3132, raw3132, "a 31-byte r")
        assertDecodes(der3231, raw3231, "a 31-byte s")

        assertTrue(raw3132.startsWith("00"), "the short r is padded at the FRONT, not the back")
        assertTrue(
            raw3231.substring(P256_COORDINATE_LENGTH * 2).startsWith("00"),
            "the short s is padded at the front of its own half",
        )
    }

    @Test
    fun padsCoordinatesOfAnyLengthIntoTheirOwnHalf() {
        val raw = derToRawSignature(derOf(byteArrayOf(0x01), byteArrayOf(0x00, 0xff.toByte())))

        assertEquals(P256_RAW_SIGNATURE_LENGTH, raw.size, "the output width never depends on the input")
        assertEquals("00".repeat(31) + "01", hex(raw).substring(0, P256_COORDINATE_LENGTH * 2), "r = 1")
        assertEquals("00".repeat(31) + "ff", hex(raw).substring(P256_COORDINATE_LENGTH * 2), "s = 255")
    }

    @Test
    fun keepsAZeroCoordinateAsThirtyTwoZeroBytes() {
        val raw = derToRawSignature(derOf(byteArrayOf(0x00), ByteArray(32) { 0x11 }))

        assertEquals("00".repeat(32) + "11".repeat(32), hex(raw), "a zero coordinate still fills its half")
    }

    @Test
    fun rejectsAWrongOuterTag() {
        val notASequence = bytes(der3232).also { it[0] = 0x31 }

        val failure = assertFailsWith<EcdsaDerException> { derToRawSignature(notASequence) }
        assertTrue(failure.message!!.contains("0x31"), "the message reports the tag it saw: ${failure.message}")
    }

    @Test
    fun rejectsInputTooShortToBeASignature() {
        assertFailsWith<EcdsaDerException>("empty input is not a signature") {
            derToRawSignature(ByteArray(0))
        }
        assertFailsWith<EcdsaDerException>("a stub is not a signature") {
            derToRawSignature(byteArrayOf(0x30, 0x05, 0x02, 0x01, 0x01, 0x02, 0x01))
        }
    }

    @Test
    fun rejectsADeclaredLengthThatDisagreesWithTheInput() {
        val padded = bytes(der3232) + byteArrayOf(0x00)
        val failure = assertFailsWith<EcdsaDerException> { derToRawSignature(padded) }
        assertTrue(
            failure.message!!.contains("content bytes"),
            "the mismatch is named: ${failure.message}",
        )

        val truncated = bytes(der3232).copyOfRange(0, 69)
        assertFailsWith<EcdsaDerException>("a truncated signature is not silently short-parsed") {
            derToRawSignature(truncated)
        }
    }

    @Test
    fun rejectsACoordinateThatIsNotAnInteger() {
        val badRTag = bytes(der3232).also { it[2] = 0x04 }
        val rFailure = assertFailsWith<EcdsaDerException> { derToRawSignature(badRTag) }
        assertTrue(rFailure.message!!.contains("for r"), "the offending value is named: ${rFailure.message}")

        val badSTag = bytes(der3232).also { it[36] = 0x04 }
        val sFailure = assertFailsWith<EcdsaDerException> { derToRawSignature(badSTag) }
        assertTrue(sFailure.message!!.contains("for s"), "the offending value is named: ${sFailure.message}")
    }

    @Test
    fun rejectsACoordinateLengthThatRunsOffTheEnd() {
        val overrun = byteArrayOf(0x30, 0x08, 0x02, 0x20) + ByteArray(6)
        val failure = assertFailsWith<EcdsaDerException> { derToRawSignature(overrun) }
        assertTrue(failure.message!!.contains("remain"), "the overrun is named: ${failure.message}")

        val noRoomForS = byteArrayOf(0x30, 0x06, 0x02, 0x04, 0x01, 0x02, 0x03, 0x04)
        val sFailure = assertFailsWith<EcdsaDerException> { derToRawSignature(noRoomForS) }
        assertTrue(sFailure.message!!.contains("of s"), "the missing value is named: ${sFailure.message}")
    }

    @Test
    fun rejectsACoordinateTooLargeForP256() {
        val tooLong = assertFailsWith<EcdsaDerException> {
            derToRawSignature(derOf(ByteArray(34) { 0x11 }, ByteArray(32) { 0x11 }))
        }
        assertTrue(tooLong.message!!.contains("at most 33"), "the limit is stated: ${tooLong.message}")

        val notASignByte = assertFailsWith<EcdsaDerException> {
            derToRawSignature(derOf(ByteArray(33) { 0x11 }, ByteArray(32) { 0x11 }))
        }
        assertTrue(
            notASignByte.message!!.contains("significant bytes"),
            "the magnitude is what is rejected: ${notASignByte.message}",
        )
    }

    @Test
    fun rejectsAnEmptyCoordinate() {
        val failure = assertFailsWith<EcdsaDerException> {
            derToRawSignature(derOf(ByteArray(0), ByteArray(32) { 0x11 }))
        }
        assertTrue(failure.message!!.contains("zero-length"), "the message says what is wrong: ${failure.message}")
    }

    @Test
    fun rejectsLongFormLengths() {
        val outer = byteArrayOf(0x30, 0x81.toByte(), 0x44) + bytes(der3232).copyOfRange(2, 70)
        val outerFailure = assertFailsWith<EcdsaDerException> { derToRawSignature(outer) }
        assertTrue(
            outerFailure.message!!.contains("long-form"),
            "a long-form length is refused rather than misread: ${outerFailure.message}",
        )

        val inner = byteArrayOf(0x30, 0x08, 0x02, 0x81.toByte()) + ByteArray(6)
        val innerFailure = assertFailsWith<EcdsaDerException> { derToRawSignature(inner) }
        assertTrue(
            innerFailure.message!!.contains("long-form"),
            "the same rule applies inside a coordinate: ${innerFailure.message}",
        )
    }

    @Test
    fun rejectsTrailingBytesAfterTheSignature() {
        val trailing = byteArrayOf(0x30, 0x0a, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02) + ByteArray(4)

        val failure = assertFailsWith<EcdsaDerException> { derToRawSignature(trailing) }
        assertTrue(
            failure.message!!.contains("trailing"),
            "extra bytes are refused, not ignored: ${failure.message}",
        )
    }
}
