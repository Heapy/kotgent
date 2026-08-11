package io.kotgent.push

import io.kotgent.crypto.hex

/**
 * OpenSSL emits variable-width signed DER integers, while JWS ES256 requires fixed 32-byte unsigned
 * `r || s`. Parsing is structurally strict but accepts unambiguous redundant leading zeros; curve-order
 * validation remains the signer's responsibility.
 */
const val P256_COORDINATE_LENGTH: Int = 32

const val P256_RAW_SIGNATURE_LENGTH: Int = 2 * P256_COORDINATE_LENGTH

private const val MAX_COORDINATE_DER_LENGTH: Int = P256_COORDINATE_LENGTH + 1

private const val DER_SEQUENCE_TAG: Int = 0x30

private const val DER_INTEGER_TAG: Int = 0x02

private const val DER_LONG_FORM_LENGTH_FLAG: Int = 0x80

private const val MIN_DER_SIGNATURE_LENGTH: Int = 8

class EcdsaDerException(message: String) : IllegalArgumentException(message)

/**
 * Returns fixed-width `r || s`, or throws [EcdsaDerException] for malformed structure.
 */
fun derToRawSignature(der: ByteArray): ByteArray {
    if (der.size < MIN_DER_SIGNATURE_LENGTH) {
        throw EcdsaDerException(
            "an ECDSA DER signature is at least $MIN_DER_SIGNATURE_LENGTH bytes, got ${der.size}",
        )
    }
    if (der[0].toInt() and 0xff != DER_SEQUENCE_TAG) {
        throw EcdsaDerException(
            "expected a DER SEQUENCE tag 0x30, got 0x${byteHex(der[0])} — this is not an ECDSA signature",
        )
    }
    val contentLength = der[1].toInt() and 0xff
    if (contentLength and DER_LONG_FORM_LENGTH_FLAG != 0) {
        throw EcdsaDerException(
            "the ECDSA signature declares a long-form DER length (0x${byteHex(der[1])}); " +
                "a P-256 signature never needs one",
        )
    }
    if (contentLength != der.size - 2) {
        throw EcdsaDerException(
            "the ECDSA signature declares $contentLength content bytes but carries ${der.size - 2}",
        )
    }

    val (r, afterR) = readCoordinate(der, offset = 2, name = "r")
    val (s, afterS) = readCoordinate(der, offset = afterR, name = "s")
    if (afterS != der.size) {
        throw EcdsaDerException("${der.size - afterS} trailing bytes after the ECDSA signature's s value")
    }
    return r + s
}

/** Leading zeros are stripped down to one byte so a genuine zero remains representable. */
private fun readCoordinate(der: ByteArray, offset: Int, name: String): Pair<ByteArray, Int> {
    if (offset + 2 > der.size) {
        throw EcdsaDerException("the ECDSA signature ends before the DER INTEGER header of $name")
    }
    if (der[offset].toInt() and 0xff != DER_INTEGER_TAG) {
        throw EcdsaDerException(
            "expected a DER INTEGER tag 0x02 for $name, got 0x${byteHex(der[offset])}",
        )
    }
    val length = der[offset + 1].toInt() and 0xff
    if (length and DER_LONG_FORM_LENGTH_FLAG != 0) {
        throw EcdsaDerException("$name declares a long-form DER length (0x${byteHex(der[offset + 1])})")
    }
    if (length == 0) {
        throw EcdsaDerException("$name is a zero-length DER INTEGER")
    }
    if (length > MAX_COORDINATE_DER_LENGTH) {
        throw EcdsaDerException(
            "$name is $length bytes; a P-256 coordinate is at most $MAX_COORDINATE_DER_LENGTH " +
                "(${P256_COORDINATE_LENGTH} plus a sign byte)",
        )
    }
    val valueStart = offset + 2
    val valueEnd = valueStart + length
    if (valueEnd > der.size) {
        throw EcdsaDerException(
            "$name declares $length bytes but only ${der.size - valueStart} remain in the signature",
        )
    }

    var start = valueStart
    while (start < valueEnd - 1 && der[start] == ZERO_BYTE) start++
    val magnitude = valueEnd - start
    if (magnitude > P256_COORDINATE_LENGTH) {
        throw EcdsaDerException(
            "$name holds $magnitude significant bytes, more than a " +
                "${P256_COORDINATE_LENGTH}-byte P-256 coordinate",
        )
    }

    val padded = ByteArray(P256_COORDINATE_LENGTH)
    der.copyInto(
        destination = padded,
        destinationOffset = P256_COORDINATE_LENGTH - magnitude,
        startIndex = start,
        endIndex = valueEnd,
    )
    return padded to valueEnd
}

private const val ZERO_BYTE: Byte = 0

private fun byteHex(b: Byte): String = hex(byteArrayOf(b))
