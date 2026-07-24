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

private val MODEL_FIELD = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"")
