package io.kotgent.adapter

/** Extracts the first non-empty `model` field without matching `model_provider`. */
fun extractModel(text: String): String? {
    val match = MODEL_FIELD.find(text) ?: return null
    return match.groupValues[1].ifEmpty { null }
}

/**
 * Extracts the most frequent model, resolving ties by first appearance. Junie records helper models
 * before and alongside the primary model, so its first model field is not authoritative.
 */
fun extractDominantModel(text: String): String? {
    val counts = LinkedHashMap<String, Int>()
    for (match in MODEL_FIELD.findAll(text)) {
        val value = match.groupValues[1]
        if (value.isEmpty()) continue
        counts[value] = (counts[value] ?: 0) + 1
    }
    return counts.maxByOrNull { it.value }?.key
}

private val MODEL_FIELD = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"")
