package io.kotgent.transport

internal sealed interface ContentEncodingSelection {
    data class Encoded(val coding: String) : ContentEncodingSelection
    data object Identity : ContentEncodingSelection
    data object NotAcceptable : ContentEncodingSelection
}

internal fun negotiateContentEncoding(header: String?, available: Set<String>): ContentEncodingSelection {
    if (header.isNullOrBlank()) return ContentEncodingSelection.Identity
    val weights = mutableMapOf<String, Double>()
    for (value in header.split(',')) {
        val parts = value.split(';')
        val coding = parts.first().trim().lowercase().let { if (it == "x-gzip") "gzip" else it }
        val quality = parts.drop(1).firstOrNull {
            it.substringBefore('=').trim().equals("q", ignoreCase = true)
        }
        val weight = if (quality == null) 1.0 else {
            val qvalue = quality.substringAfter('=', "").trim()
            if (QVALUE.matches(qvalue)) qvalue.toDouble() else 0.0
        }
        weights[coding] = minOf(weights[coding] ?: 1.0, weight)
    }
    fun weight(coding: String): Double = weights[coding] ?: weights["*"] ?: 0.0
    val coding = listOf("br", "gzip")
        .filter { it in available && weight(it) > 0.0 }
        .maxByOrNull(::weight)
    val identityWeight = weights["identity"] ?: if (weights["*"] == 0.0) 0.0 else 1.0
    if (coding != null && (weights["identity"] ?: 0.0) <= weight(coding)) {
        return ContentEncodingSelection.Encoded(coding)
    }
    return if (identityWeight > 0.0) ContentEncodingSelection.Identity else ContentEncodingSelection.NotAcceptable
}

private val QVALUE = Regex("""(?:0(?:\.[0-9]{0,3})?|1(?:\.0{0,3})?)""")
