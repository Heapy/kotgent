package io.kotgent.push

import io.kotgent.pty.Pty
import io.kotgent.tmux.ProcessRunner
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class CurlPushTransportTest {
    @Test
    fun realTlsVerifiesCertificatesDeliversAnEmptyPostAndBoundsStalledRequests(): Unit = runBlocking {
        withTimeout(30.seconds) {
            val root = ProcessRunner.run(listOf("mktemp", "-d", "/tmp/kotgent-tls-test-XXXXXX"))
                .also { assertEquals(0, it.exitCode, it.stderr) }.stdout.trim()
            var server: Pty? = null
            try {
                val certificate = "$root/cert.pem"
                val key = "$root/key.pem"
                val generate = ProcessRunner.run(listOf(
                    "/usr/bin/openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                    "-keyout", key, "-out", certificate, "-subj", "/CN=localhost",
                    "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1",
                ))
                assertEquals(0, generate.exitCode, generate.stderr)
                val helper = Pty.open(listOf("/usr/bin/python3", "-u", "-c", TLS_SERVER, certificate, key))
                server = helper
                val output = StringBuilder()
                val port = withTimeout(10.seconds) {
                    while ('\n' !in output) output.append(helper.output.receive().decodeToString())
                    output.toString().trim().removePrefix("PORT=").toInt()
                }
                val endpoint = "https://127.0.0.1:$port"
                val trusted = HttpPushTransport(HttpClient(Curl) {
                    configurePushTimeouts()
                    engine { caInfo = certificate }
                })
                try {
                    assertEquals(201, trusted.post(endpoint, mapOf("X-Fixture" to "empty-post")))
                } finally { trusted.close() }

                val untrusted = HttpPushTransport()
                try {
                    assertFails("the production trust store must reject the fixture's self-signed certificate") {
                        untrusted.post(endpoint, emptyMap())
                    }
                } finally { untrusted.close() }

                val bounded = HttpPushTransport(HttpClient(Curl) {
                    configurePushTimeouts(requestMillis = 250)
                    engine { caInfo = certificate }
                })
                try {
                    assertFailsWith<HttpRequestTimeoutException> { bounded.post("$endpoint/stall", emptyMap()) }
                } finally { bounded.close() }
            } finally {
                val _ = server?.close()
                val _ = ProcessRunner.run(listOf("rm", "-rf", root))
            }
        }
    }
}

private val TLS_SERVER = """
    import http.server, signal, ssl, sys, time
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path == '/stall':
                time.sleep(2)
            valid = self.headers.get('X-Fixture') == 'empty-post' and int(self.headers.get('Content-Length', '0')) == 0
            self.send_response(201 if valid else 400)
            self.send_header('Content-Length', '0')
            self.end_headers()
        def log_message(self, *args):
            pass
    signal.alarm(30)
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(sys.argv[1], sys.argv[2])
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print('PORT=' + str(server.server_port), flush=True)
    server.serve_forever()
""".trimIndent()
