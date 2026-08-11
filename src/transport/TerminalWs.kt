package io.kotgent.transport

import io.kotgent.core.SessionId
import io.kotgent.pty.Subscriber
import io.kotgent.pty.TerminalBridge
import io.kotgent.store.EventStore
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class TerminalRegistry(
    private val scope: CoroutineScope,
    private val bridgeFactory: (id: String, scope: CoroutineScope) -> TerminalBridge,
) {
    // WebSocket viewers and REST input for a session must share one upstream tmux attach.
    private val mutex = Mutex()
    private val bridges = HashMap<String, TerminalBridge>()

    suspend fun getOrCreate(id: String): TerminalBridge =
        mutex.withLock { bridges.getOrPut(id) { bridgeFactory(id, scope) } }

    suspend fun shutdownAll(): Unit = mutex.withLock {
        bridges.values.forEach { it.shutdown() }
        bridges.clear()
    }
}

fun Route.terminalWs(registry: TerminalRegistry, store: EventStore, json: Json = TRANSPORT_JSON) {
    webSocket("/sessions/{id}/terminal") {
        val id = call.parameters["id"]
        if (id.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "missing session id"))
            return@webSocket
        }
        val sid = runCatching { SessionId(id) }.getOrNull()
        // Validate before creating a never-evicted bridge/pty for attacker-chosen ids.
        if (sid == null || store.getSession(sid) == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such session"))
            return@webSocket
        }
        val bridge = registry.getOrCreate(id)
        // Initial geometry reaches tmux before the first capture/paint, not after a resize frame.
        val sub = bridge.subscribe(
            cols = call.request.queryParameters["cols"]?.toIntOrNull(),
            rows = call.request.queryParameters["rows"]?.toIntOrNull(),
        )
        val ws = this
        val pump = launch {
            for (bytes in sub.output) {
                ws.send(Frame.Binary(fin = true, data = bytes))
            }
            runCatching { ws.close(CloseReason(CloseReason.Codes.NORMAL, "terminal ended")) }
        }
        try {
            // Interactive input intentionally preserves the scrolling viewer's tmux copy-mode; unlike
            // REST input, this path cannot safely cancel shared pane state per subscriber.
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> sub.write(frame.readBytes())
                    is Frame.Text -> handleControlFrame(frame.readText(), sub, json)
                    else -> {   }
                }
            }
        } finally {
            pump.cancel()
            sub.close()
        }
    }
}

private suspend fun handleControlFrame(text: String, sub: Subscriber, json: Json) {
    val control = runCatching { json.decodeFromString(TerminalControl.serializer(), text) }.getOrNull() ?: return
    if (control.type == "resize" && control.cols > 0 && control.rows > 0) {
        sub.resize(control.cols, control.rows)
    }
}

@Serializable
private data class TerminalControl(
    val type: String,
    val cols: Int = 0,
    val rows: Int = 0,
)
