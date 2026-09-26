package io.kotgent.push

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post

/**
 * Push uses the platform HTTPS engine and its trust store. Finite timeouts bound notification delivery.
 */
class HttpPushTransport(
    private val client: HttpClient = defaultPushHttpClient(),
) : PushTransport, AutoCloseable {

    override suspend fun post(url: String, headers: Map<String, String>): Int {
        val response = client.post(url) {
            for ([name, value] in headers) header(name, value)
        }
        return response.status.value
    }

    override fun close(): Unit = client.close()
}

expect fun platformPushHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient

fun defaultPushHttpClient(): HttpClient = platformPushHttpClient { configurePushTimeouts() }

internal fun HttpClientConfig<*>.configurePushTimeouts(requestMillis: Long = PUSH_REQUEST_TIMEOUT_MS) {
    install(HttpTimeout) {
        connectTimeoutMillis = PUSH_CONNECT_TIMEOUT_MS
        requestTimeoutMillis = requestMillis
        socketTimeoutMillis = requestMillis
    }
}

private const val PUSH_CONNECT_TIMEOUT_MS: Long = 10_000

private const val PUSH_REQUEST_TIMEOUT_MS: Long = 20_000
