package io.kotgent.crypto

/**
 * Base64url encoding (RFC 4648 §5) — the alphabet the Web Push stack speaks.
 *
 * Every value kotgent hands to a browser or to a push service is base64url, unpadded: the VAPID
 * application server key the page passes to `pushManager.subscribe`, and the two halves of the VAPID
 * `Authorization` header (the JWT's dot-joined segments and the `k=` public point). Standard base64 will
 * not do for any of them — `+` and `/` are not URL/JWT-safe, and `=` padding is forbidden by RFC 7515 §2
 * and rejected by push services, so this sits next to [hex] as the second (and last) byte-to-string
 * encoder in the codebase.
 *
 * Encode-only on purpose: the payload-less push design never decodes base64url on the Kotlin side
 * (`p256dh`/`auth` are stored as the opaque strings the browser sent). A decoder arrives only if RFC 8291
 * encryption does.
 */

/**
 * Base64url of [bytes] — RFC 4648 §5 alphabet (`-` and `_` for index 62/63), **no `=` padding**.
 *
 * The tail is the whole trick: a 1-byte remainder yields 2 characters and a 2-byte remainder yields 3,
 * carrying the leftover bits left-shifted into the next sextet rather than dropped.
 */
fun base64Url(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i + 3 <= bytes.size) {
        val word = (bytes[i].toInt() and 0xff shl 16) or
            (bytes[i + 1].toInt() and 0xff shl 8) or
            (bytes[i + 2].toInt() and 0xff)
        out.append(BASE64_URL_DIGITS[word ushr 18 and 0x3f])
        out.append(BASE64_URL_DIGITS[word ushr 12 and 0x3f])
        out.append(BASE64_URL_DIGITS[word ushr 6 and 0x3f])
        out.append(BASE64_URL_DIGITS[word and 0x3f])
        i += 3
    }
    when (bytes.size - i) {
        1 -> {
            val word = bytes[i].toInt() and 0xff shl 16
            out.append(BASE64_URL_DIGITS[word ushr 18 and 0x3f])
            out.append(BASE64_URL_DIGITS[word ushr 12 and 0x3f])
        }
        2 -> {
            val word = (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8)
            out.append(BASE64_URL_DIGITS[word ushr 18 and 0x3f])
            out.append(BASE64_URL_DIGITS[word ushr 12 and 0x3f])
            out.append(BASE64_URL_DIGITS[word ushr 6 and 0x3f])
        }
    }
    return out.toString()
}

private const val BASE64_URL_DIGITS: String =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
