package io.kotgent.adapter

/**
 * Pure, provider-neutral extraction of the model name from a chunk of a provider's on-disk record — a
 * Claude transcript JSONL line (`…"model":"claude-opus-4-8"…`) or a Codex rollout `turn_context` record
 * (`…"model":"gpt-5.5"…`). Host-free and side-effect-free, so it is unit-testable with sample text.
 *
 * It matches the FIRST `"model":"<value>"` occurrence. The `":` immediately after `model` is what keeps
 * it from matching Codex's neighbouring `"model_provider":"openai"` (there the key is followed by
 * `_provider":`, not `":`). Returns `null` when no model field is present or its value is empty.
 */
fun extractModel(text: String): String? {
    val match = MODEL_FIELD.find(text) ?: return null
    return match.groupValues[1].ifEmpty { null }
}

/**
 * Pure extraction of the DOMINANT model name from a chunk of a provider's on-disk record: the
 * `"model":"<value>"` value that occurs MOST OFTEN in [text], ties resolved in favour of the one seen
 * first. Empty values are ignored; `null` when there is no usable occurrence at all.
 *
 * Junie needs this rather than [extractModel] because its `events.jsonl` records a `modelUsage` list per
 * turn that mixes the session's primary model with the helper models Junie runs alongside it
 * (summarizers, classifiers, safety checks). Measured on a real session: the primary model appeared 40
 * times against 6/1/1 for three helpers — and, decisively, the FIRST occurrence in the file was a helper.
 * So "the first model mentioned" is the wrong answer here, while "the most frequent" is robust: the
 * primary model is used on every turn, a helper only on some.
 *
 * Frequency, not the largest usage numbers: the counts stay meaningful over a bounded, possibly
 * TRUNCATED head, which is what the caller reads.
 */
fun extractDominantModel(text: String): String? {
    // LinkedHashMap keeps first-seen order, and maxByOrNull returns the FIRST maximum it meets — that is
    // exactly the documented tie-break, with no second pass.
    val counts = LinkedHashMap<String, Int>()
    for (match in MODEL_FIELD.findAll(text)) {
        val value = match.groupValues[1]
        if (value.isEmpty()) continue
        counts[value] = (counts[value] ?: 0) + 1
    }
    return counts.maxByOrNull { it.value }?.key
}

private val MODEL_FIELD = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"")
