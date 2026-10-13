package io.kotgent.diff

internal const val DIFF_MAX_IMAGE_PIXELS = 20_000_000L

internal fun isDiffImagePath(path: String?): Boolean =
    path?.substringAfterLast('.', "")?.lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp")

/** Inspect bounded raster headers before asking a browser to allocate a decoded image. Never emit SVG/HTML. */
internal fun previewDiffImage(bytes: ByteArray): DiffImageSide {
    fun byte(offset: Int) = bytes.getOrNull(offset)?.toInt()?.and(255) ?: -1
    fun matches(offset: Int, value: String) = value.indices.all { byte(offset + it) == value[it].code }
    fun little(offset: Int, length: Int): Long = (0..<length).fold(0L) { result, index ->
        val value = byte(offset + index)
        if (result < 0 || value < 0) -1 else result or (value.toLong() shl (8 * index))
    }
    fun big(offset: Int, length: Int): Long = (0..<length).fold(0L) { result, index ->
        val value = byte(offset + index)
        if (result < 0 || value < 0) -1 else (result shl 8) or value.toLong()
    }
    var mediaType = ""
    var width = -1L
    var height = -1L
    when {
        byte(0) == 137 && matches(1, "PNG\r\n\u001a\n") && matches(12, "IHDR") -> {
            mediaType = "image/png"; width = big(16, 4); height = big(20, 4)
        }
        matches(0, "GIF87a") || matches(0, "GIF89a") -> {
            mediaType = "image/gif"; width = little(6, 2); height = little(8, 2)
        }
        byte(0) == 255 && byte(1) == 216 -> {
            mediaType = "image/jpeg"
            var offset = 2
            while (offset < bytes.size && byte(offset) == 255) {
                while (byte(offset) == 255) offset++
                val marker = byte(offset++)
                if (marker == 217 || marker == 218 || marker < 0) break
                if (marker == 1 || marker in 208..215) continue
                val length = big(offset, 2).toInt()
                if (length < 2 || length > bytes.size - offset) break
                if (marker in setOf(192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207) && length >= 8) {
                    height = big(offset + 3, 2); width = big(offset + 5, 2); break
                }
                offset += length
            }
        }
        matches(0, "RIFF") && matches(8, "WEBP") -> {
            mediaType = "image/webp"
            when {
                matches(12, "VP8X") && bytes.size >= 30 -> {
                    width = little(24, 3) + 1; height = little(27, 3) + 1
                }
                matches(12, "VP8L") && byte(20) == 47 && bytes.size >= 25 -> {
                    val bits = little(21, 4)
                    width = (bits and 0x3fff) + 1; height = ((bits shr 14) and 0x3fff) + 1
                }
                matches(12, "VP8 ") && byte(23) == 157 && byte(24) == 1 && byte(25) == 42 && bytes.size >= 30 -> {
                    width = little(26, 2) and 0x3fff; height = little(28, 2) and 0x3fff
                }
            }
        }
    }
    if (width <= 0 || height <= 0) return DiffImageSide(problem = DiffProblem(
        DiffErrorCode.unavailable, DiffReason.unsupported_encoding, "Not a supported PNG, JPEG, GIF or WebP image"))
    // Check individual dimensions first, so even an unsigned 32-bit PNG size cannot overflow the product.
    if (width > 16_384 || height > 16_384 || width * height > DIFF_MAX_IMAGE_PIXELS)
        return DiffImageSide(problem = DiffProblem(DiffErrorCode.limited, DiffReason.patch_limit,
            "Image exceeds the preview limit (20 million pixels or 16,384 pixels per side)"))
    return DiffImageSide(image = DiffImage(mediaType, bytes.size, width.toInt(), height.toInt()))
}
