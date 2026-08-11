package io.kotgent.adapter.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Generates per-launch Claude hook settings. The secret stays in a `0600` curl header file rather
 * than an argv; only `$TMUX_PANE` is deliberately shell-expanded at hook time.
 */
object ClaudeHookConfig {
    const val INGRESS_PATH: String = "/hooks/claude"

    const val HOOK_TOKEN_HEADER: String = "X-Kotgent-Hook-Token"

    const val TMUX_PANE_HEADER: String = "X-Kotgent-Tmux-Pane"

    const val HOOK_EVENT_HEADER: String = "X-Kotgent-Hook-Event"

    const val USER_PROMPT_SUBMIT: String = "UserPromptSubmit"
    const val POST_TOOL_USE: String = "PostToolUse"
    const val STOP: String = "Stop"
    const val NOTIFICATION: String = "Notification"
    const val SESSION_START: String = "SessionStart"

    val HOOK_EVENTS: List<String> = listOf(
        USER_PROMPT_SUBMIT, POST_TOOL_USE, STOP, NOTIFICATION, SESSION_START,
    )

    private val PRETTY: Json = Json { prettyPrint = true }

    fun ingressUrl(port: Int): String = "http://127.0.0.1:$port$INGRESS_PATH"

    fun headerFileContent(token: String): String = "$HOOK_TOKEN_HEADER: $token\n"

    fun hookCommand(port: Int, headerFilePath: String, event: String): String {
        val url = ingressUrl(port) + "?event=" + event
        return buildString {
            append("curl -sS -o /dev/null -X POST ").append(shSingleQuote(url))
            append(" -H ").append(shSingleQuote("@$headerFilePath"))
            // Unlike the other values, the pane id must expand at hook execution time.
            append(" -H \"").append(TMUX_PANE_HEADER).append(": \$TMUX_PANE\"")
            append(" -H ").append(shSingleQuote("$HOOK_EVENT_HEADER: $event"))
            append(" -H ").append(shSingleQuote("Content-Type: application/json"))
            append(" --data-binary @-")
        }
    }

    fun generate(port: Int, headerFilePath: String, json: Json = PRETTY): String {
        val root = buildJsonObject {
            putJsonObject("hooks") {
                for (event in HOOK_EVENTS) {
                    putJsonArray(event) {
                        addJsonObject {
                            if (event == POST_TOOL_USE) put("matcher", "*")
                            putJsonArray("hooks") {
                                addJsonObject {
                                    put("type", "command")
                                    put("command", hookCommand(port, headerFilePath, event))
                                }
                            }
                        }
                    }
                }
            }
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    private fun shSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
