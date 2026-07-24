package io.kotgent.transport

import io.kotgent.adapter.extractModel
import io.kotgent.core.SessionId
import io.kotgent.store.EventStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Best-effort capture of the Claude model into `SessionMeta.model`, driven from the hook ingress.
 *
 * Every Claude hook payload carries a `transcript_path`; the transcript JSONL's assistant messages carry
 * `"model":"…"`. On a hook for a session whose model is not yet known, this reads a bounded TAIL of that
 * transcript (the model is on every assistant line, so the tail always has the latest), extracts it with
 * the pure [extractModel], and persists it once. It never fails the hook — any miss (no path, unreadable
 * file, no model) simply leaves the model null, to be tried again on the next hook.
 *
 * File IO is behind the injectable [readTranscriptTail] so it is unit-testable with a fake reader; the
 * default reads the real file via stock `platform.posix` (which links into the test binary).
 */
class ClaudeModelCapture(
    private val store: EventStore,
    private val readTranscriptTail: (String) -> String? = ::readFileTail,
    private val now: () -> Long = ::captureEpochMillis,
) {
    /**
     * If [sessionId]'s model is still unknown and [payload] has a readable `transcript_path` carrying a
     * model, persist it. Best-effort and idempotent (a session that already has a model is skipped).
     */
    suspend fun maybeCapture(sessionId: SessionId, payload: JsonElement) {
        val meta = store.getSession(sessionId) ?: return
        if (meta.model != null) return
        val transcriptPath = payload.stringField(FIELD_TRANSCRIPT_PATH) ?: return
        val tail = readTranscriptTail(transcriptPath) ?: return
        val model = extractModel(tail) ?: return
        store.setModel(sessionId, model, now())
    }

    private fun JsonElement.stringField(name: String): String? {
        val obj = this as? JsonObject ?: return null
        val prim = obj[name] as? JsonPrimitive ?: return null
        return prim.contentOrNull
    }

    companion object {
        private const val FIELD_TRANSCRIPT_PATH = "transcript_path"

        /** How much of the transcript tail to read — the model sits on every assistant line, so a modest
         *  window always contains the most recent one while keeping the read O(1). */
        const val TAIL_BYTES: Int = 64 * 1024
    }
}

/** Read up to the last [ClaudeModelCapture.TAIL_BYTES] of [path] as text, or `null` if unreadable. */
@OptIn(ExperimentalForeignApi::class)
fun readFileTail(path: String): String? {
    val fp = fopen(path, "rb") ?: return null
    try {
        if (fseek(fp, 0, SEEK_END) != 0) return null
        val size = ftell(fp)
        if (size <= 0L) return null
        val window = minOf(size, ClaudeModelCapture.TAIL_BYTES.toLong())
        if (fseek(fp, size - window, SEEK_SET) != 0) return null
        val n = window.toInt()
        val buffer = ByteArray(n)
        val read = buffer.usePinned { fread(it.addressOf(0), 1.convert(), n.convert(), fp) }
        val got = read.toInt()
        if (got <= 0) return null
        return buffer.decodeToString(0, got)
    } finally {
        fclose(fp)
    }
}

/** Default wall-clock for the model-capture write: epoch millis. */
@OptIn(ExperimentalTime::class)
private fun captureEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
