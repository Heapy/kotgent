package io.kotgent.transport

import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.store.SqliteEventStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [ClaudeModelCapture]: it reads the model from a (faked) transcript tail and persists it
 * once per session, best-effort — a missing `transcript_path`, an unreadable file, or a transcript with no
 * model all leave the session's model null.
 */
class ClaudeModelCaptureTest {

    private fun payload(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun meta(id: String) = SessionMeta(
        id = SessionId(id), name = id, agent = "claude", cwd = "/w",
        tmuxSession = "kt-$id", state = SessionState.running, createdAt = 1L, updatedAt = 1L,
    )

    @Test
    fun capturesTheModelFromTheTranscriptTailOnce() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            store.upsertSession(meta("s1"))
            var reads = 0
            val capture = ClaudeModelCapture(
                store,
                readTranscriptTail = { path ->
                    reads++
                    if (path == "/t.jsonl") """{"message":{"model":"claude-opus-4-8"}}""" else null
                },
                now = { 2L },
            )

            capture.maybeCapture(SessionId("s1"), payload("""{"transcript_path":"/t.jsonl"}"""))
            assertEquals("claude-opus-4-8", store.getSession(SessionId("s1"))!!.model, "model captured from the tail")
            assertEquals(1, reads)

            // Idempotent: a second hook does NOT re-read or overwrite an already-known model.
            capture.maybeCapture(SessionId("s1"), payload("""{"transcript_path":"/t.jsonl"}"""))
            assertEquals("claude-opus-4-8", store.getSession(SessionId("s1"))!!.model)
            assertEquals(1, reads, "an already-known model short-circuits before reading the transcript")
        }
    }

    @Test
    fun leavesModelNullOnAMissWithoutFailing() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            store.upsertSession(meta("s2"))
            val capture = ClaudeModelCapture(store, readTranscriptTail = { "no model in here" }, now = { 2L })

            // No transcript_path in the payload → nothing to read.
            capture.maybeCapture(SessionId("s2"), payload("""{"session_id":"x"}"""))
            assertNull(store.getSession(SessionId("s2"))!!.model)

            // A transcript with no model → still null.
            capture.maybeCapture(SessionId("s2"), payload("""{"transcript_path":"/t.jsonl"}"""))
            assertNull(store.getSession(SessionId("s2"))!!.model)
        }
    }

    @Test
    fun aMissingSessionIsANoOp() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val capture = ClaudeModelCapture(store, readTranscriptTail = { """{"model":"x"}""" }, now = { 2L })
            capture.maybeCapture(SessionId("ghost"), payload("""{"transcript_path":"/t.jsonl"}"""))
            assertNull(store.getSession(SessionId("ghost")), "no row was created")
        }
    }
}
