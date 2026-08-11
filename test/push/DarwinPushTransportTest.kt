package io.kotgent.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DarwinPushTransportTest {

    @Test
    fun postSendsTheSuppliedHeadersWithAnEmptyBodyAndReturnsTheStatus() = runBlocking {
        withTimeout(20_000) {
            val endpoint = "https://web.push.apple.com/3/device/test"
            val headers = mapOf(
                PUSH_AUTHORIZATION_HEADER to "vapid t=jwt, k=public-key",
                PUSH_TTL_HEADER to "1800",
                PUSH_TOPIC_HEADER to "topic",
            )
            val client = HttpClient(
                MockEngine { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals(endpoint, request.url.toString())
                    for ((name, value) in headers) {
                        assertEquals(value, request.headers[name], "the $name header reaches the engine")
                    }
                    assertIs<OutgoingContent.NoContent>(
                        request.body,
                        "payload-less means Ktor sends NoContent, not a typed zero-byte payload",
                    )
                    assertNull(request.body.contentType, "a payload-less push has no entity content type")
                    assertNull(request.headers[HttpHeaders.ContentType], "no Content-Type header reaches the engine")
                    assertContentEquals(
                        ByteArray(0),
                        request.body.toByteArray(),
                        "payload-less push means zero body bytes",
                    )
                    respond(content = "", status = HttpStatusCode.Gone)
                },
            )
            val transport = DarwinPushTransport(client)
            try {
                assertEquals(HttpStatusCode.Gone.value, transport.post(endpoint, headers))
            } finally {
                transport.close()
            }
        }
    }
}
