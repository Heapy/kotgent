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

class ClaudeModelCapture(
    private val store: EventStore,
    private val readTranscriptTail: (String) -> String? = ::readFileTail,
    private val now: () -> Long = ::captureEpochMillis,
) {
    // Hook delivery must not fail when transcript IO/model extraction misses; later hooks retry.
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

        // Claude repeats model on assistant records, so a bounded recent window is sufficient.
        const val TAIL_BYTES: Int = 64 * 1024
    }
}

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

@OptIn(ExperimentalTime::class)
private fun captureEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
