package io.kotgent.adapter.junie

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * PURE generation of the two artefacts that wire Junie's hooks to kotgent's local ingress: the small
 * `/bin/sh` hook script ([hookScript]) the daemon writes to disk, and the JSON config ([configJson])
 * that installs it for ONE launch via `junie --config-location <file>`.
 *
 * String/JSON building only — no IO, fully unit-testable. It is the OUTGOING half of the Junie adapter;
 * the INCOMING side (the `/hooks/junie` route + [JunieHookNormalizer]) consumes what it produces.
 *
 * ## Why `--config-location` and not `~/.junie/config.json`
 * Junie resolves `hooks` from several config layers. `~/.junie/config.json` is the USER layer — hooks
 * written there would fire for every junie session the user runs, kotgent-launched or not, which kotgent
 * must never do (the same rule that keeps its codex hooks out of `$CODEX_HOME`). A project-level
 * `.junie/config.json` is worse still: Junie deliberately IGNORES `hooks` from it (repository-controlled
 * shell commands). `--config-location <file>` is the one layer scoped to a single launch, and Junie
 * honors its hooks even for an untrusted project — the exact analogue of `claude --settings <file>`.
 *
 * ## The hook must never change Junie's behavior
 * Junie parses a hook's STDOUT as a decision object (and, failing that, feeds the raw text to the model
 * as `additionalContext`), and reads its EXIT CODE as control flow. So this script writes nothing to
 * stdout — the response body goes to `/dev/null` and stdout is redirected as well — and its exit status
 * is a fixed function of the event, never curl's:
 *
 *  - `PermissionRequest` → **exit 1**. Junie's contract: exit `0` AUTO-APPROVES the sensitive action
 *    (dialog skipped), exit `2` auto-DENIES it, and any other non-zero exit falls through to the normal
 *    dialog (with a small warning notification). kotgent only OBSERVES approvals — the operator answers
 *    them in the terminal, as with claude/codex — so the fall-through is the only admissible outcome and
 *    the TUI warning is its accepted cost. Exit 0 and exit 2 are forbidden here: either would silently
 *    answer a permission prompt on the operator's behalf.
 *  - every other event → **exit 0**, even when curl fails. These hooks are synchronous: a `Stop` or
 *    `UserPromptSubmit` hook that exits non-zero produces a TUI error, and with `blockOnError` semantics
 *    nearby it is not worth risking that a daemon hiccup interferes with the user's own submission.
 *
 * `curl --max-time` bounds the round trip for the same reason kotgent's own clients never issue an
 * untimed request at the daemon: a socket inherited by an orphaned process accepts the connection and
 * then stays silent forever, and these hooks block the action that triggered them.
 *
 * ## The secret token is never on a command line
 * Same discipline as the claude/codex adapters: the shared hook token lives in a `0600` header file the
 * script reads via `curl -H @<file>`, so it never appears in an argv (visible to any local user via
 * `ps`). Only the header file's PATH appears, which is not sensitive.
 */
object JunieHookConfig {

    /** The local hook-ingress path the Junie hooks POST to. */
    const val INGRESS_PATH: String = "/hooks/junie"

    /** Header carrying the shared hook token (validated by the ingress). */
    const val HOOK_TOKEN_HEADER: String = "X-Kotgent-Hook-Token"

    /** Header carrying `$TMUX_PANE` — the runtime handle the ingress maps back to a session. */
    const val TMUX_PANE_HEADER: String = "X-Kotgent-Tmux-Pane"

    /** Header (and `?event=` query param) carrying the hook event name. */
    const val HOOK_EVENT_HEADER: String = "X-Kotgent-Hook-Event"

    /** The interpreter every hook command names explicitly (see [configJson]). */
    const val SHELL_INTERPRETER: String = "/bin/sh"

    /** Upper bound on one hook's HTTP round trip, in seconds (see the class KDoc). */
    const val CURL_MAX_TIME_SECONDS: Int = 5

    const val USER_PROMPT_SUBMIT: String = "UserPromptSubmit"
    const val PRE_TOOL_USE: String = "PreToolUse"
    const val PERMISSION_REQUEST: String = "PermissionRequest"
    const val STOP: String = "Stop"
    const val STOP_FAILURE: String = "StopFailure"
    const val SESSION_START: String = "SessionStart"
    const val SESSION_END: String = "SessionEnd"

    /**
     * Every Junie hook event kotgent wires — all seven Junie currently offers. Their
     * [JunieHookNormalizer] mapping: `UserPromptSubmit`/`PreToolUse` → running,
     * `PermissionRequest` → needs_approval, `Stop`/`StopFailure` → ready, `SessionStart` → `SessionBound`
     * (when the payload carries an id), `SessionEnd` → `Exited`.
     *
     * Junie has no `PostToolUse`, so the running-producer here is the PRE-call hook — one process spawn
     * plus a loopback round trip per tool call, bounded by [CURL_MAX_TIME_SECONDS].
     */
    val HOOK_EVENTS: List<String> = listOf(
        USER_PROMPT_SUBMIT, PRE_TOOL_USE, PERMISSION_REQUEST, STOP, STOP_FAILURE, SESSION_START, SESSION_END,
    )

    private val PRETTY: Json = Json { prettyPrint = true }

    /** The ingress URL the hooks POST to for [port]. */
    fun ingressUrl(port: Int): String = "http://127.0.0.1:$port$INGRESS_PATH"

    /**
     * The content of the `0600` header file the hook script reads the secret token from (a single
     * `curl`-compatible header line, newline-terminated).
     */
    fun headerFileContent(token: String): String = "$HOOK_TOKEN_HEADER: $token\n"

    /**
     * The `/bin/sh` script the daemon writes (mode `0600`) and every Junie hook invokes as
     * `/bin/sh <script> <EventName>`: POST the hook's stdin payload to the ingress on [port], reading the
     * secret token header from [headerFilePath] (`-H @<file>`) and carrying `$TMUX_PANE` + the event name.
     *
     * `"$1"` (the event) and `$TMUX_PANE` are expanded by the shell at hook time — the pane value is set
     * by tmux in the pane where junie runs, which is exactly the correlation key the ingress needs.
     * `curl --data-binary @-` forwards the payload from stdin unchanged.
     *
     * The trailing exit contract is the load-bearing part — see the class KDoc. Nothing between the
     * shebang and the final `exit` writes to stdout.
     */
    fun hookScript(port: Int, headerFilePath: String): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("# Generated by kotgent — do not edit. Invoked by junie hooks as: <script> <EventName>")
        // No `exec`: the exit status below must be kotgent's, not curl's (see the class KDoc).
        appendLine("curl -sS --max-time $CURL_MAX_TIME_SECONDS -o /dev/null -X POST \\")
        appendLine("  ${shSingleQuote(ingressUrl(port) + "?event=")}\"\$1\" \\")
        // The secret token is read from the 0600 header file, never embedded in an argv.
        appendLine("  -H ${shSingleQuote("@$headerFilePath")} \\")
        // Double-quoted on purpose so the shell expands $TMUX_PANE / $1 at hook execution time.
        appendLine("  -H \"$TMUX_PANE_HEADER: \$TMUX_PANE\" \\")
        appendLine("  -H \"$HOOK_EVENT_HEADER: \$1\" \\")
        appendLine("  -H ${shSingleQuote("Content-Type: application/json")} \\")
        // Junie READS this script's stdout as a decision object; belt-and-braces with curl's -o.
        appendLine("  --data-binary @- >/dev/null")
        appendLine("# A permission request must fall through to Junie's OWN dialog: exit 0 would")
        appendLine("# auto-approve the action and exit 2 would auto-deny it. Every other event exits 0")
        appendLine("# unconditionally — a curl failure must never disturb the session.")
        appendLine("if [ \"\$1\" = ${shSingleQuote(PERMISSION_REQUEST)} ]; then")
        appendLine("  exit 1")
        appendLine("fi")
        appendLine("exit 0")
    }

    /**
     * The full config file content for `junie --config-location <file>`: wires every [HOOK_EVENTS] entry
     * to `<SHELL_INTERPRETER> <scriptPath> <EventName>`.
     *
     * Shape mirrors Junie's `config.json` (itself Claude-Code-shaped):
     * `{"hooks": {<Event>: [{"matcher"?, "hooks": [{"type":"command","command":"…"}]}]}}`. No `matcher`
     * is emitted anywhere — an absent matcher means "run for every value", which is what kotgent wants
     * for all seven events (and two of them, `UserPromptSubmit`/`Stop`, reject matchers outright).
     *
     * The rendered `command` is a shell line (Junie runs it through `sh -c`), so [scriptPath] is POSIX
     * single-quoted — a path with a space or a quote stays one literal word. Naming the interpreter
     * explicitly means the script file needs no execute bit, so it can be written `0600` next to the
     * token like every other kotgent-private file. Well-formed by construction (built via the kotlinx
     * JSON DSL, which escapes values); the secret token is NOT part of this output (it lives in the
     * header file).
     */
    fun configJson(scriptPath: String, json: Json = PRETTY): String {
        val root = buildJsonObject {
            putJsonObject("hooks") {
                for (event in HOOK_EVENTS) {
                    putJsonArray(event) {
                        addJsonObject {
                            putJsonArray("hooks") {
                                addJsonObject {
                                    put("type", "command")
                                    put("command", hookCommand(scriptPath, event))
                                }
                            }
                        }
                    }
                }
            }
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    /** The shell line Junie runs for [event]: the generated script, invoked with the event name. */
    fun hookCommand(scriptPath: String, event: String): String =
        "$SHELL_INTERPRETER ${shSingleQuote(scriptPath)} $event"

    /**
     * POSIX single-quote quoting: wrap in single quotes, rewriting every embedded `'` as the classic
     * `'\''` (close-quote, escaped literal quote, reopen-quote). Makes [s] one fully literal shell word.
     */
    private fun shSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
