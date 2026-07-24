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

/**
 * Per-session [TerminalBridge] registry (Task 14). Every terminal-WS connection AND the
 * `POST /sessions/{id}/input` REST call for the same session must share ONE bridge, so they fan out over
 * a single upstream `tmux attach` (two bridges = two attaches = broken fan-out). [getOrCreate] mints a
 * bridge lazily on first use for a session and caches it; the bridge itself then opens its upstream
 * lazily on the first subscriber and closes it on the last (Task 9). Bridges are created on the server's
 * application [scope] so a bridge's reader loop outlives any single WS connection.
 *
 * A bridge is kept in the map after its last subscriber leaves (it is reusable — it re-attaches on the
 * next subscribe); the map only grows with distinct session ids, which is fine for the local-only slice.
 *
 * [decision] Keyed by the logical short-id `String`, not `SessionId`: that `String` is the addressing
 * unit the [bridgeFactory] and the tmux/pty layer below it work in, so the `SessionId`-typed
 * [TerminalInputSink] seam unwraps to `.value` at this boundary rather than the registry re-wrapping it.
 */
class TerminalRegistry(
    private val scope: CoroutineScope,
    private val bridgeFactory: (id: String, scope: CoroutineScope) -> TerminalBridge,
) {
    private val mutex = Mutex()
    private val bridges = HashMap<String, TerminalBridge>()

    suspend fun getOrCreate(id: String): TerminalBridge =
        mutex.withLock { bridges.getOrPut(id) { bridgeFactory(id, scope) } }

    /** Tear down every bridge (server shutdown). */
    suspend fun shutdownAll(): Unit = mutex.withLock {
        bridges.values.forEach { it.shutdown() }
        bridges.clear()
    }
}

/**
 * The `GET /sessions/{id}/terminal` WebSocket (plan Task 14) — a browser/CLI attaching to a session's
 * terminal fan-out.
 *
 * On connect it [TerminalBridge.subscribe]s to the session's shared bridge (from [registry]); the first
 * subscriber lazily opens the upstream `tmux attach` via the injected `PtyFactory` and the new subscriber
 * is pre-seeded with a `capture-pane -e` snapshot before any live delta (Task 9 behaviour — this route
 * just wires the WS as the subscriber's sink). Then:
 *  - **server → client:** every byte off the subscriber's output channel is sent as a **binary** frame
 *    (seed first, then live deltas);
 *  - **client → server:** **binary** frames are terminal input → the shared upstream; a **text** frame is
 *    a control message — `{"type":"resize","cols":C,"rows":R}` → [Broadcaster.applyResize].
 *  - **disconnect:** the subscriber is removed; the last one leaving closes the upstream (Detach — the
 *    tmux session and the agent live on).
 *
 * [decision] Binary = terminal I/O, text = control. Keeping the two on distinct frame types avoids any
 * in-band escaping of raw pty bytes and matches xterm.js's natural binary transport.
 *
 * ## This path deliberately does NOT cancel copy-mode — and its known residual
 * Input here goes straight into the shared upstream pty. Unlike the programmatic
 * `POST /sessions/{id}/input` seam ([TerminalInputSink]), it does **not** call
 * `Tmux.leaveCopyMode` first: this is a human at a terminal, and someone who scrolled back and then
 * typed expects tmux's own behaviour, not to be yanked back to the bottom.
 *
 * **Recorded, NOT fixed here:** copy-mode is *shared pane* state, not per-client, and kotgent forces
 * `mouse on` ([io.kotgent.tmux.TMUX_SERVER_OPTIONS]) — so one wheel scroll in browser tab A parks THE
 * pane in copy-mode and every keystroke typed in tab B, or in `kotgent attach`, is routed to the
 * copy-mode key table and dropped. Silent input loss, on the primary input path, with no error
 * anywhere: the pty write succeeds, tmux exits 0, and nothing on this path can observe it (the
 * `#{pane_in_mode}` read-back that makes `Tmux.sendKeys` safe is a tmux call, not something a pty write
 * can carry). Cancelling unconditionally here is not the fix — it would break the deliberate behaviour
 * above for the very operator who scrolled. A real fix needs the fan-out to know WHICH subscriber is in
 * copy-mode (per-subscriber state in [io.kotgent.pty.Broadcaster], which is subscriber-agnostic about
 * input today) so it can cancel for everyone else's keystrokes and not for theirs.
 */
fun Route.terminalWs(registry: TerminalRegistry, store: EventStore, json: Json = TRANSPORT_JSON) {
    webSocket("/sessions/{id}/terminal") {
        val id = call.parameters["id"]
        if (id.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "missing session id"))
            return@webSocket
        }
        // Reject an unknown session BEFORE minting a bridge — otherwise an arbitrary id mints a
        // never-evicted bridge and opens a `tmux attach` that EOFs, leaking a pty (a DoS amplifier).
        val sid = runCatching { SessionId(id) }.getOrNull()
        if (sid == null || store.getSession(sid) == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such session"))
            return@webSocket
        }
        val bridge = registry.getOrCreate(id)
        // `?cols=&rows=` (optional) is the client's geometry, known before the first resize frame can
        // arrive. Handing it to subscribe() opens the upstream `tmux attach` at that size instead of at
        // the pty default, so the very first paint is already the right shape.
        val sub = bridge.subscribe(
            cols = call.request.queryParameters["cols"]?.toIntOrNull(),
            rows = call.request.queryParameters["rows"]?.toIntOrNull(),
        )
        val ws = this
        // server → client: pump the subscriber's output (seed, then deltas) out as binary frames.
        val pump = launch {
            for (bytes in sub.output) {
                ws.send(Frame.Binary(fin = true, data = bytes))
            }
            // The output channel closed = upstream reached EOF (pane/session died or the attach ended).
            runCatching { ws.close(CloseReason(CloseReason.Codes.NORMAL, "terminal ended")) }
        }
        try {
            // client → server: binary = input, text = control (resize).
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> sub.write(frame.readBytes())
                    is Frame.Text -> handleControlFrame(frame.readText(), sub, json)
                    else -> { /* ignore ping/pong/close */ }
                }
            }
        } finally {
            pump.cancel()
            sub.close() // detach; last subscriber leaving closes the upstream (Detach)
        }
    }
}

/** Apply a terminal control message. Only `resize` is defined in the slice; anything else is ignored. */
private suspend fun handleControlFrame(text: String, sub: Subscriber, json: Json) {
    val control = runCatching { json.decodeFromString(TerminalControl.serializer(), text) }.getOrNull() ?: return
    if (control.type == "resize" && control.cols > 0 && control.rows > 0) {
        sub.resize(control.cols, control.rows)
    }
}

/** Client → server terminal control frame (text sub-protocol). Only `resize` is used in the slice. */
@Serializable
private data class TerminalControl(
    val type: String,
    val cols: Int = 0,
    val rows: Int = 0,
)
