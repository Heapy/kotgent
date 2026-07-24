package io.kotgent.push

import io.kotgent.crypto.hex

/**
 * ECDSA signature transcoding: openssl's DER `SEQUENCE { INTEGER r, INTEGER s }` into the fixed-width
 * `r || s` that JWS ES256 — and therefore the VAPID `Authorization` header — requires.
 *
 * ## Why this exists
 * The two ends disagree about the encoding. `openssl dgst -sha256 -sign` emits an ASN.1 DER structure
 * whose length VARIES (69–72 bytes for P-256): each coordinate is a signed big-endian integer, so a
 * value with its top bit set gains a leading `0x00` sign byte, and a value below `2^248` loses its
 * leading zero byte instead. RFC 7515 §3.4 wants the opposite — exactly 64 bytes, each coordinate
 * left-padded to the curve's 32-byte field size, no tags and no sign bytes. Handing a push service the
 * DER verbatim yields an opaque `401 Unauthorized` from Apple or Google with no hint as to why, so the
 * conversion is done here, once, and tested against real openssl output of every shape.
 *
 * ## Why it is pure
 * No I/O, no openssl, no key material — just bytes in, bytes out, so the whole variable-length parsing
 * rule (the part that actually breaks, roughly once in every 128 signatures) is unit-testable without
 * spawning anything. The signing edge that produces the DER is [OpensslVapidSigner]'s problem.
 *
 * ## Strictness
 * Structure only: tags, lengths and bounds. Values are NOT range-checked against the curve order — a
 * mathematically invalid signature is the signer's or the key's fault and the push service will reject
 * it anyway, whereas a mis-parse here would silently ship a wrong 64 bytes. What IS rejected is
 * anything that could make the slicing ambiguous: a wrong tag, a length that runs off the end, an
 * integer too long to be a P-256 coordinate, and trailing bytes after the sequence. Non-minimal
 * encodings (a redundant leading `0x00`) are accepted rather than refused: stripping them is
 * unambiguous, and openssl never emits them, so refusing would only add a way for production signing to
 * fail without adding any safety.
 */

/** Bytes in a P-256 coordinate — the field size, and the width each of `r` and `s` is padded to. */
const val P256_COORDINATE_LENGTH: Int = 32

/** The JWS ES256 signature width: `r || s`, both left-padded. Exactly what [derToRawSignature] returns. */
const val P256_RAW_SIGNATURE_LENGTH: Int = 2 * P256_COORDINATE_LENGTH

/** A DER INTEGER holding a P-256 coordinate: 32 magnitude bytes plus at most one `0x00` sign byte. */
private const val MAX_COORDINATE_DER_LENGTH: Int = P256_COORDINATE_LENGTH + 1

/** `SEQUENCE`, constructed — the outer wrapper of an ECDSA-Sig-Value. */
private const val DER_SEQUENCE_TAG: Int = 0x30

/** `INTEGER`, primitive — the tag on both `r` and `s`. */
private const val DER_INTEGER_TAG: Int = 0x02

/** Set in a length byte means "long form": the byte counts further length bytes rather than content. */
private const val DER_LONG_FORM_LENGTH_FLAG: Int = 0x80

/** The smallest structurally possible ECDSA-Sig-Value: two headers, two headers, one byte each. */
private const val MIN_DER_SIGNATURE_LENGTH: Int = 8

/** Every structural problem in a DER signature. Distinct so the signing edge can attribute it to openssl. */
class EcdsaDerException(message: String) : IllegalArgumentException(message)

/**
 * The [P256_RAW_SIGNATURE_LENGTH]-byte `r || s` inside the DER ECDSA signature [der].
 *
 * Both coordinates come back left-padded with zeros to [P256_COORDINATE_LENGTH], which is the whole
 * point: the DER integers are variable-width and the JWS field is not.
 *
 * @throws EcdsaDerException if [der] is not a well-formed `SEQUENCE { INTEGER, INTEGER }` whose
 *   integers fit a P-256 coordinate.
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

/**
 * The coordinate whose DER INTEGER starts at [offset], left-padded to [P256_COORDINATE_LENGTH], paired
 * with the offset just past it. [name] (`r` or `s`) only shapes the error messages.
 *
 * The sign byte is handled by stripping leading zeros down to the last byte — that covers both the
 * 33-byte "top bit set" form and the ordinary 32-byte one, and leaves a genuine zero coordinate as a
 * single `0x00` rather than an empty magnitude.
 */
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

/** One byte as two lowercase hex digits, through the codebase's single hex encoder. */
private fun byteHex(b: Byte): String = hex(byteArrayOf(b))
