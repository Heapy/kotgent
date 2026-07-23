package io.kotgent.adapter.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * PURE generation of the Claude Code settings JSON handed to `claude --settings <file>` (plan Task
 * 11). It wires the five v1 hook events to kotgent's local hook ingress: each hook runs a `curl`
 * that POSTs the hook payload (delivered on the hook's stdin) to `POST /hooks/claude`, carrying the
 * hook token, `$TMUX_PANE`, and the hook event name so the ingress can authenticate the callback and
 * correlate it to a session by pane.
 *
 * This object is string/JSON building only — no IO, fully unit-testable. It is the OUTGOING half of
 * the Claude adapter; the INCOMING side (the `/hooks/claude` route + the payload→`AgentEvent`
 * normalizer) is Task 12 and consumes what this config produces.
 *
 * ## Shape
 * Claude Code's settings hooks are `{"hooks": {<Event>: [ {"matcher"?, "hooks": [ {"type":"command",
 * "command": ...} ]} ]}}`. Tool-scoped events take a `matcher` (we use `"*"` on [POST_TOOL_USE] to
 * match every tool); the others omit it.
 *
 * ## Quoting
 * The generated command is a `/bin/sh` line. Fixed, kotgent-controlled arguments (URL, token header,
 * event header) are wrapped in POSIX single quotes so every byte is literal — no expansion, no
 * re-splitting, robust to any token bytes. The one exception is the `$TMUX_PANE` header, wrapped in
 * double quotes precisely so the shell expands `$TMUX_PANE` at hook time (its value is set by tmux in
 * the pane where claude runs).
 */
object ClaudeHookConfig {

    /** The local hook-ingress path Task 12 serves. */
    const val INGRESS_PATH: String = "/hooks/claude"

    /** Header carrying the shared hook token (validated by the ingress; separate bearer is backlog). */
    const val HOOK_TOKEN_HEADER: String = "X-Kotgent-Hook-Token"

    /** Header carrying `$TMUX_PANE` — the runtime handle the ingress maps back to a session. */
    const val TMUX_PANE_HEADER: String = "X-Kotgent-Tmux-Pane"

    /** Header (and `?event=` query param) carrying the hook event name, for convenience/robustness. */
    const val HOOK_EVENT_HEADER: String = "X-Kotgent-Hook-Event"

    const val USER_PROMPT_SUBMIT: String = "UserPromptSubmit"
    const val POST_TOOL_USE: String = "PostToolUse"
    const val STOP: String = "Stop"
    const val NOTIFICATION: String = "Notification"
    const val SESSION_START: String = "SessionStart"

    /**
     * The five Claude hook events the v1 slice wires to the ingress. Their Task-12 mapping:
     * `UserPromptSubmit`/`PostToolUse` → running, `Stop` → ready, `Notification` → needs_attention
     * (coarse mapping — see the Notification spike note in Task 11), `SessionStart` → `SessionBound`.
     */
    val HOOK_EVENTS: List<String> = listOf(
        USER_PROMPT_SUBMIT, POST_TOOL_USE, STOP, NOTIFICATION, SESSION_START,
    )

    private val PRETTY: Json = Json { prettyPrint = true }

    /** The ingress URL the hooks POST to for [port]. */
    fun ingressUrl(port: Int): String = "http://127.0.0.1:$port$INGRESS_PATH"

    /**
     * The `/bin/sh` command a hook for [event] runs: POST the hook's stdin payload to the ingress on
     * [port], carrying [token], `$TMUX_PANE`, and the event name. `curl --data-binary @-` forwards the
     * full payload from stdin unchanged.
     */
    fun hookCommand(port: Int, token: String, event: String): String {
        val url = ingressUrl(port) + "?event=" + event
        return buildString {
            append("curl -sS -o /dev/null -X POST ").append(shSingleQuote(url))
            append(" -H ").append(shSingleQuote("$HOOK_TOKEN_HEADER: $token"))
            // Double-quoted on purpose so the shell expands $TMUX_PANE at hook execution time.
            append(" -H \"").append(TMUX_PANE_HEADER).append(": \$TMUX_PANE\"")
            append(" -H ").append(shSingleQuote("$HOOK_EVENT_HEADER: $event"))
            append(" -H ").append(shSingleQuote("Content-Type: application/json"))
            append(" --data-binary @-")
        }
    }

    /**
     * Generate the full settings JSON for `claude --settings <file>`, wiring every [HOOK_EVENTS]
     * entry to [hookCommand] against [port] and [token]. Well-formed by construction (built via the
     * kotlinx JSON DSL, which escapes values). Pretty-printed by default for human inspection.
     */
    fun generate(port: Int, token: String, json: Json = PRETTY): String {
        val root = buildJsonObject {
            putJsonObject("hooks") {
                for (event in HOOK_EVENTS) {
                    putJsonArray(event) {
                        addJsonObject {
                            if (event == POST_TOOL_USE) put("matcher", "*")
                            putJsonArray("hooks") {
                                addJsonObject {
                                    put("type", "command")
                                    put("command", hookCommand(port, token, event))
                                }
                            }
                        }
                    }
                }
            }
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    /**
     * POSIX single-quote quoting: wrap in single quotes, rewriting every embedded `'` as the classic
     * `'\''` (close-quote, escaped literal quote, reopen-quote). Makes [s] one fully literal shell
     * word — no expansion, no re-splitting.
     */
    private fun shSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
