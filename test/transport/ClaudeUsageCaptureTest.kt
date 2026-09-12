package io.kotgent.transport

import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.adapter.claude.ClaudeHookConfig
import io.kotgent.core.UsageSource
import io.kotgent.db.KotgentDatabase
import io.kotgent.store.SqliteUsageStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

class ClaudeUsageCaptureTest {
    private val epoch = 1_800_000_000_000L
    private val token = "usage-hook-secret"
    private val resetSeconds = 1_800_604_800L

    @Test
    fun parsesBothNativeWindowsWithSourceProvenanceAndMillis() {
        val parsed = assertNotNull(parseClaudeUsageCapture(capture(
            """"five_hour":${window("23.5")},"seven_day":${window("41.2")}""",
            revision = "7",
            capturedAt = "1800000000123",
        )))
        assertEquals(listOf("five_hour", "seven_day"), parsed.map { it.windowKey })
        assertEquals(listOf(23.5, 41.2), parsed.map { it.usedPercent })
        assertEquals(listOf(18_000L, 604_800L), parsed.map { it.windowSeconds })
        for (observation in parsed) {
            assertEquals("claude", observation.provider)
            assertEquals(resetSeconds * 1_000L, observation.resetsAt)
            assertEquals(0L, observation.observedAt)
            assertEquals(UsageSource("session-1:inc-1", 7, 1_800_000_000_123L), observation.source)
        }
    }

    @Test
    fun oneWindowIsEnoughAndAbsentOrUnsupportedLimitsAreAnEmptyCapture() {
        assertEquals(1, assertNotNull(parseClaudeUsageCapture(capture(""""seven_day":${window("10")}"""))).size)
        for (limits in listOf(null, "", "\"five_hour\":null", "\"spend_limit\":{\"used_percentage\":10}")) {
            assertTrue(assertNotNull(parseClaudeUsageCapture(capture(limits))).isEmpty())
        }
    }

    @Test
    fun malformedJsonAndMissingCaptureEnvelopeAreRejected() {
        for (text in listOf(
            "", "{", "[]", "null", "{}", "\"text\"",
            """{"payload":{"session_id":"session-1"}}""",
            """{"source":{"id":"session-1:inc-1","revision":1,"capturedAt":1},"payload":null}""",
            """{"source":{"id":"session-1:inc-1","revision":1,"capturedAt":1},"payload":{}}""",
        )) assertNull(parseClaudeUsageCapture(text), text)
    }

    @Test
    fun sourceIdentityMustContainThePayloadSessionAndASafeBoundedIncarnation() {
        for (source in listOf(
            "session-1", "session-1:", "other:inc-1", "session-1:inc:again", "session-1:../bad",
            "session-1:with space", "session-1:é", "session-1:" + "x".repeat(512),
        )) assertNull(parseClaudeUsageCapture(capture(sourceId = source)), source)
        for (session in listOf("", "with space", "../bad", "é", "s".repeat(257))) {
            assertNull(parseClaudeUsageCapture(capture(sessionId = session, sourceId = "$session:inc")), session)
        }
        val longestSession = "s".repeat(256)
        assertNotNull(parseClaudeUsageCapture(capture(
            sessionId = longestSession,
            sourceId = "$longestSession:" + "i".repeat(255),
        )))
    }

    @Test
    fun sourceSequenceAndCaptureTimeRequireNonnegativeJsonIntegers() {
        for (invalid in listOf("-1", "1.5", "\"1\"", "null", "true", "{}", "9223372036854775808")) {
            assertNull(parseClaudeUsageCapture(capture(revision = invalid)), "revision $invalid")
            assertNull(parseClaudeUsageCapture(capture(capturedAt = invalid)), "capturedAt $invalid")
        }
        val zero = assertNotNull(parseClaudeUsageCapture(capture(revision = "0", capturedAt = "0"))).single()
        assertEquals(UsageSource("session-1:inc-1", 0, 0), zero.source)
    }

    @Test
    fun invalidPercentagesDoNotDiscardAnotherValidWindow() {
        for (percent in listOf("-0.01", "100.01", "1e999", "\"42\"", "true", "null", "{}")) {
            val parsed = assertNotNull(parseClaudeUsageCapture(capture(
                """"five_hour":${window(percent)},"seven_day":${window("100")}""",
            )))
            assertEquals(listOf("seven_day"), parsed.map { it.windowKey }, percent)
            assertEquals(100.0, parsed.single().usedPercent)
        }
        assertEquals(0.0, assertNotNull(parseClaudeUsageCapture(capture(""""five_hour":${window("0")}"""))).single().usedPercent)
        assertTrue(assertNotNull(parseClaudeUsageCapture(capture(""""seven_day":{"used_percent":25}"""))).isEmpty())
    }

    @Test
    fun absentInvalidAndOverflowingResetTimesStayUnknown() {
        for (reset in listOf("null", "-1", "1.5", "\"1\"", "true", "9223372036854775807")) {
            val parsed = assertNotNull(parseClaudeUsageCapture(capture(""""seven_day":${window("5", reset)}"""))).single()
            assertNull(parsed.resetsAt, reset)
        }
        assertNull(assertNotNull(parseClaudeUsageCapture(capture(""""seven_day":{"used_percentage":5}"""))).single().resetsAt)
        assertEquals(0L, assertNotNull(parseClaudeUsageCapture(capture(""""seven_day":${window("5", "0")}"""))).single().resetsAt)
    }

    @Test
    fun authenticatedLoopbackIngressPersistsTheCaptureWithoutAPaneHeader() = withIngress { context ->
        val response = context.post(capture(""""five_hour":${window("20")},"seven_day":${window("40")}"""))
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
        assertEquals(listOf(20.0, 40.0), context.store.list().map { it.current.usedPercent })
    }

    @Test
    fun absentAndWrongTokensAreRejectedBeforeParsingOrWriting() = withIngress { context ->
        for (presented in listOf(null, "wrong")) {
            assertEquals(HttpStatusCode.Unauthorized, context.post("malformed", presented).status)
        }
        assertTrue(context.store.list().isEmpty())
    }

    @Test
    fun tunnelHostCannotUseTheIngressEvenWithTheCorrectHeader() = withIngress { context ->
        assertEquals(HttpStatusCode.Forbidden, context.post(capture(), host = "kotgent.example.com").status)
        assertTrue(context.store.list().isEmpty())
    }

    @Test
    fun malformedEnvelopeIs400AndAValidCaptureWithoutQuotasIs200() = withIngress { context ->
        assertEquals(HttpStatusCode.BadRequest, context.post("{}").status)
        assertEquals(HttpStatusCode.BadRequest, context.post(capture(sourceId = "other:inc")).status)
        assertEquals(HttpStatusCode.OK, context.post(capture(limits = null)).status)
        assertTrue(context.store.list().isEmpty())
    }

    @Test
    fun oversizedDeclaredAndStreamingBodiesAreRejectedWithoutAnObservation() = withIngress { context ->
        val bytes = ByteArray(CLAUDE_USAGE_MAX_BODY_BYTES + 1) { 'x'.code.toByte() }
        val declared = context.client.post(context.url) {
            header(ClaudeHookConfig.HOOK_TOKEN_HEADER, token)
            setBody(bytes)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)
        val streamed = context.client.post(context.url) {
            header(ClaudeHookConfig.HOOK_TOKEN_HEADER, token)
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType: ContentType = ContentType.Application.Json
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeFully(bytes)
                }
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, streamed.status)
        assertTrue(context.store.list().isEmpty())
    }

    @Test
    fun earlyRejectionFlushesItsResponseAndClosesAnUnconsumedKeepAliveConnection() = withIngress { context ->
        val unauthorized = context.unconsumedRequestUntilClosed(contentLength = 1, presented = "wrong")
        assertTrue(unauthorized.startsWith("HTTP/1.1 401"), unauthorized)
        assertTrue(unauthorized.contains("Connection: close", ignoreCase = true), unauthorized)
        assertTrue(unauthorized.contains("unauthorized"), unauthorized)

        val oversized = context.unconsumedRequestUntilClosed(contentLength = CLAUDE_USAGE_MAX_BODY_BYTES + 1)
        assertTrue(oversized.startsWith("HTTP/1.1 413"), oversized)
        assertTrue(oversized.contains("request body too large"), oversized)
        assertTrue(context.store.list().isEmpty())
        assertEquals(HttpStatusCode.OK, context.post(capture()).status, "closing rejected sockets leaves the server healthy")
    }

    @Test
    fun theStoreRequiresASameSessionDecreaseAfterAFirstSourceBaseline() = withIngress { context ->
        assertEquals(HttpStatusCode.OK, context.post(capture()).status)
        assertTrue(context.store.pendingResetNotifications(0).isEmpty())
        assertEquals(HttpStatusCode.OK, context.post(capture(
            """"seven_day":${window("20")}""", sessionId = "session-2", sourceId = "session-2:inc-2",
            capturedAt = (epoch + 1).toString(),
        )).status)
        assertTrue(context.store.pendingResetNotifications(0).isEmpty(), "a lower first reading cannot prove a reset")
        assertEquals(HttpStatusCode.OK, context.post(capture(
            """"seven_day":${window("10")}""", sessionId = "session-2", sourceId = "session-2:inc-2",
            revision = "2", capturedAt = (epoch + 2).toString(),
        )).status)
        val reset = context.store.pendingResetNotifications(0).single()
        assertEquals(20.0, reset.usedBefore)
        assertTrue(reset.early)
        assertTrue(reset.id > 0)
    }

    private fun window(percent: String, reset: String = resetSeconds.toString()): String =
        """{"used_percentage":$percent,"resets_at":$reset}"""

    private fun capture(
        limits: String? = "\"seven_day\":" + window("40"),
        sessionId: String = "session-1",
        sourceId: String = "$sessionId:inc-1",
        revision: String = "1",
        capturedAt: String = epoch.toString(),
    ): String {
        val rateField = limits?.let { ",\"rate_limits\":{$it}" } ?: ""
        return """{"source":{"id":${JsonPrimitive(sourceId)},"revision":$revision,"capturedAt":$capturedAt},"payload":{"session_id":${JsonPrimitive(sessionId)}$rateField}}"""
    }

    private inner class Context(val port: Int, val client: HttpClient, val store: SqliteUsageStore) {
        val url: String get() = "http://127.0.0.1:$port${ClaudeHookConfig.USAGE_INGRESS_PATH}"

        suspend fun post(body: String, presented: String? = token, host: String? = null): HttpResponse = client.post(url) {
            if (presented != null) header(ClaudeHookConfig.HOOK_TOKEN_HEADER, presented)
            if (host != null) header(HttpHeaders.Host, host)
            setBody(body)
        }

        suspend fun unconsumedRequestUntilClosed(contentLength: Int, presented: String = token): String =
            withTimeout(3.seconds) {
                val selector = SelectorManager(Dispatchers.Default)
                val socket = aSocket(selector).tcp().connect("127.0.0.1", port)
                try {
                    val input = socket.openReadChannel()
                    val output = socket.openWriteChannel(autoFlush = true)
                    val request = buildString {
                        append("POST ${ClaudeHookConfig.USAGE_INGRESS_PATH} HTTP/1.1\r\n")
                        append("Host: 127.0.0.1:$port\r\n")
                        append("${ClaudeHookConfig.HOOK_TOKEN_HEADER}: $presented\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: $contentLength\r\n")
                        append("Connection: keep-alive\r\n\r\n")
                    }
                    output.writeFully(request.encodeToByteArray())
                    val response = StringBuilder()
                    val bytes = ByteArray(1_024)
                    while (true) {
                        val read = try {
                            input.readAvailable(bytes)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Native CIO may reset a connection whose promised request body never arrived.
                            break
                        }
                        if (read < 0) break
                        response.append(bytes.decodeToString(endIndex = read))
                        check(response.length <= 16_384) { "rejection response exceeded its bound" }
                    }
                    response.toString()
                } finally {
                    socket.close()
                    selector.close()
                }
            }
    }

    private fun withIngress(block: suspend (Context) -> Unit) = runBlocking {
        withTimeout(20.seconds) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            val store = SqliteUsageStore(driver) { epoch }
            val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
                routing { val _ = claudeUsageRoutes({ token }, store) }
            }
            val client = HttpClient(CIO)
            try {
                server.start(wait = false)
                block(Context(server.engine.resolvedConnectors().first().port, client, store))
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
                driver.close()
            }
        }
    }
}
