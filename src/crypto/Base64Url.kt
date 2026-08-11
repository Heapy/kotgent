package io.kotgent.crypto

import kotlin.io.encoding.Base64

/** RFC 4648 base64url without padding, as required by JWT and Web Push. */
fun base64Url(bytes: ByteArray): String =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
