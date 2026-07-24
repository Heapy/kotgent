package io.kotgent.crypto

import kotlin.io.encoding.Base64

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
 * Kotlin's canonical RFC 4648 encoder owns the alphabet and tail handling; the padding mode is explicit
 * because JWT and Web Push values must not carry `=`.
 */
fun base64Url(bytes: ByteArray): String =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
