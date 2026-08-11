package io.kotgent.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post

/**
 * Push HTTPS uses NSURLSession because Ktor CIO has no TLS on Kotlin/Native. Darwin also supplies the
 * system trust store. Finite timeouts prevent one silent service from wedging notification delivery.
 */
class DarwinPushTransport(
    private val client: HttpClient = defaultPushHttpClient(),
) : PushTransport, AutoCloseable {

    override suspend fun post(url: String, headers: Map<String, String>): Int {
        val response = client.post(url) {
            for ((name, value) in headers) header(name, value)
        }
        return response.status.value
    }

    override fun close(): Unit = client.close()
}

fun defaultPushHttpClient(): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        connectTimeoutMillis = PUSH_CONNECT_TIMEOUT_MS
        requestTimeoutMillis = PUSH_REQUEST_TIMEOUT_MS
        socketTimeoutMillis = PUSH_REQUEST_TIMEOUT_MS
    }
}

private const val PUSH_CONNECT_TIMEOUT_MS: Long = 10_000

private const val PUSH_REQUEST_TIMEOUT_MS: Long = 20_000
