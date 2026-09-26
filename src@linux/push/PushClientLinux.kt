package io.kotgent.push

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.curl.Curl

actual fun platformPushHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Curl, configure)
