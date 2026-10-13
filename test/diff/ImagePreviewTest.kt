package io.kotgent.diff

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal const val IMAGE_RED = "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAEUlEQVR4nGP4z8DwH4YZkDkAm34L9XKwuTwAAAAASUVORK5CYII="
internal const val IMAGE_BLUE = "iVBORw0KGgoAAAANSUhEUgAAAAQAAAADCAYAAAC09K7GAAAAEUlEQVR4nGNgYPjfgIoJCQAAuxYR9TLcTxQAAAAASUVORK5CYII="
internal const val IMAGE_GREEN = "iVBORw0KGgoAAAANSUhEUgAAAAUAAAAECAYAAABGM/VAAAAAD0lEQVR4nGNg+I8FUiYIAHYyJ9nWsUaJAAAAAElFTkSuQmCC"

class ImagePreviewTest {
    @Test fun identifiesRasterHeadersAndByteLengths() {
        val gif = "47494638396103000200800000".hexToByteArray()
        val jpeg = "ffd8ffe100040000ffc0000b080002000301011100ffd9".hexToByteArray()
        val webpExtended = "524946461600000057454250565038580a00000000000000020000010000".hexToByteArray()
        val webpLossless = "5249464611000000574542505650384c050000002f02400000".hexToByteArray()
        val webpLossy = "524946461600000057454250565038200a0000000000009d012a03000200".hexToByteArray()
        for ([bytes, type] in listOf(Base64.decode(IMAGE_RED) to "image/png", gif to "image/gif", jpeg to "image/jpeg",
            webpExtended to "image/webp", webpLossless to "image/webp", webpLossy to "image/webp")) {
            val image = assertNotNull(previewDiffImage(bytes).image)
            assertEquals(type, image.mediaType)
            assertEquals(3, image.width); assertEquals(2, image.height)
            assertEquals(bytes.size, image.byteLength)
        }
    }

    @Test fun rejectsActiveFormatsMalformedHeadersAndExcessiveDimensions() {
        for (bytes in listOf("<svg onload='alert(1)'/>".encodeToByteArray(), "<html>hello</html>".encodeToByteArray(),
            byteArrayOf(), Base64.decode(IMAGE_RED).copyOf(20), "ffd8ffe1ffff".hexToByteArray())) {
            val side = previewDiffImage(bytes)
            assertNull(side.image)
            assertEquals(DiffReason.unsupported_encoding, side.problem?.reason)
        }
        for (dimensions in listOf("0000138800001388", "ffffffffffffffff", "0000400100000001")) {
            val png = Base64.decode(IMAGE_RED)
            dimensions.hexToByteArray().copyInto(png, 16)
            val side = previewDiffImage(png)
            assertNull(side.image)
            assertEquals(DiffErrorCode.limited, side.problem?.code)
        }
    }
}
