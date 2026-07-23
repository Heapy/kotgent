package io.kotgent.adapter.codex

/**
 * PURE generation of the two artefacts that wire Codex's hooks to kotgent's local ingress:
 * the small `/bin/sh` hook script ([hookScript]) the daemon writes to disk, and the inline-TOML
 * `hooks={…}` config ([hooksToml]) that installs it for ONE launch via `codex -c`.
 *
 * String building only — no IO, fully unit-testable. It is the OUTGOING half of the Codex adapter;
 * the INCOMING side (the `/hooks/codex` route + [CodexHookNormalizer]) consumes what it produces.
 *
 * ## Why `-c hooks={…}` and not a file in `$CODEX_HOME`
 * Codex resolves hooks from several layers. Verified against codex-cli 0.145.0 with `hooks/list`:
 *  - `$CODEX_HOME/hooks.json` and a `[hooks]` table in `config.toml` both resolve as `source: user` —
 *    i.e. they would fire for EVERY codex session the user runs, kotgent-launched or not;
 *  - `-c 'hooks={…}'` resolves as `source: sessionFlags` — scoped to the single launch that carries it.
 * kotgent must not install itself into the user's environment, so the session layer is the only
 * acceptable one. This is the Codex analogue of `claude --settings <file>`, just carried in the argv.
 *
 * ## Why the hook command is a SCRIPT, not an inline `curl`
 * The Claude adapter can embed its `curl` directly in the settings JSON. Here the same string would sit
 * inside TOML, inside a `-c` argv element, inside the `/bin/sh` line tmux runs for `new-session` — three
 * levels of quoting over a command that itself needs literal single quotes and a live `$TMUX_PANE`.
 * Writing the `curl` into `codex-hook.sh` once and referencing it by path keeps the argv short and free
 * of nested quotes, and moves the delicate part into a file no one re-quotes.
 *
 * ## The secret token is never on a command line
 * Same discipline as the Claude adapter: the shared hook token lives in a `0600` header file the script
 * reads via `curl -H @<file>`, so it never appears in an argv (visible to any local user via `ps`).
 * Only the header file's PATH appears, which is not sensitive.
 */
object CodexHookConfig {

    /** The local hook-ingress path the Codex hooks POST to. */
    const val INGRESS_PATH: String = "/hooks/codex"

    /** Header carrying the shared hook token (validated by the ingress). */
    const val HOOK_TOKEN_HEADER: String = "X-Kotgent-Hook-Token"

    /** Header carrying `$TMUX_PANE` — the runtime handle the ingress maps back to a session. */
    const val TMUX_PANE_HEADER: String = "X-Kotgent-Tmux-Pane"

    /** Header (and `?event=` query param) carrying the hook event name. */
    const val HOOK_EVENT_HEADER: String = "X-Kotgent-Hook-Event"

    /** The interpreter every hook command names explicitly (see [hooksToml]). */
    const val SHELL_INTERPRETER: String = "/bin/sh"

    const val USER_PROMPT_SUBMIT: String = "UserPromptSubmit"
    const val POST_TOOL_USE: String = "PostToolUse"
    const val PERMISSION_REQUEST: String = "PermissionRequest"
    const val STOP: String = "Stop"
    const val SESSION_START: String = "SessionStart"
    const val SESSION_END: String = "SessionEnd"

    /**
     * The six Codex hook events kotgent wires. Their [CodexHookNormalizer] mapping:
     * `UserPromptSubmit`/`PostToolUse` → running, `PermissionRequest` → needs_approval, `Stop` → ready,
     * `SessionStart` → `SessionBound`, `SessionEnd` → `Exited`.
     *
     * Codex additionally offers `PreToolUse`, `PreCompact`, `PostCompact`, `SubagentStart`,
     * `SubagentStop` — deliberately NOT wired: none of them moves the v1 state machine, and a hook that
     * fires per tool call has a real cost (a process spawn plus an HTTP round trip) for an event we would
     * discard. `PostToolUse` alone already carries "the session is running".
     */
    val HOOK_EVENTS: List<String> = listOf(
        USER_PROMPT_SUBMIT, POST_TOOL_USE, PERMISSION_REQUEST, STOP, SESSION_START, SESSION_END,
    )

    /** The ingress URL the hooks POST to for [port]. */
    fun ingressUrl(port: Int): String = "http://127.0.0.1:$port$INGRESS_PATH"

    /**
     * The content of the `0600` header file the hook script reads the secret token from (a single
     * `curl`-compatible header line, newline-terminated).
     */
    fun headerFileContent(token: String): String = "$HOOK_TOKEN_HEADER: $token\n"

    /**
     * The `/bin/sh` script the daemon writes (mode `0600`) and every Codex hook invokes as
     * `/bin/sh <script> <EventName>`: POST the hook's stdin payload to the ingress on [port], reading the secret
     * token header from [headerFilePath] (`-H @<file>`) and carrying `$TMUX_PANE` + the event name.
     *
     * `"$1"` (the event) and `$TMUX_PANE` are expanded by the shell at hook time — the pane value is set
     * by tmux in the pane where codex runs, which is exactly the correlation key the ingress needs.
     * `curl --data-binary @-` forwards the payload from stdin unchanged.
     */
    fun hookScript(port: Int, headerFilePath: String): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("# Generated by kotgent — do not edit. Invoked by codex hooks as: <script> <EventName>")
        appendLine("exec curl -sS -o /dev/null -X POST \\")
        appendLine("  ${shSingleQuote(ingressUrl(port) + "?event=")}\"\$1\" \\")
        // The secret token is read from the 0600 header file, never embedded in an argv.
        appendLine("  -H ${shSingleQuote("@$headerFilePath")} \\")
        // Double-quoted on purpose so the shell expands $TMUX_PANE / $1 at hook execution time.
        appendLine("  -H \"$TMUX_PANE_HEADER: \$TMUX_PANE\" \\")
        appendLine("  -H \"$HOOK_EVENT_HEADER: \$1\" \\")
        appendLine("  -H ${shSingleQuote("Content-Type: application/json")} \\")
        appendLine("  --data-binary @-")
    }

    /**
     * The inline-TOML value for `codex -c <this>`: wires every [HOOK_EVENTS] entry to
     * `<scriptPath> <EventName>`.
     *
     * Shape mirrors Codex's `hooks.json` (which is itself Claude-Code-shaped):
     * `hooks={Event=[{matcher?, hooks=[{type="command", command="…"}]}]}`. `PostToolUse` carries
     * `matcher="*"` so it fires for every tool; the others take no matcher.
     *
     * The rendered `command` is a shell line, so [scriptPath] is POSIX single-quoted — a path with a
     * space or a quote stays one literal word. The whole TOML string is in turn quoted by
     * [io.kotgent.daemon.SessionManager.shellCommand] when tmux launches it.
     */
    fun hooksToml(scriptPath: String): String = buildString {
        append("hooks={")
        HOOK_EVENTS.forEachIndexed { index, event ->
            if (index > 0) append(",")
            append(event).append("=[{")
            if (event == POST_TOOL_USE) append("matcher=\"*\",")
            append("hooks=[{type=\"command\",command=")
            // Invoked as `/bin/sh <script> <Event>`, not `<script> <Event>`: naming the interpreter
            // explicitly means the script file needs no execute bit, so it can be written `0600` next to
            // the token like every other kotgent-private file.
            append(tomlString("$SHELL_INTERPRETER ${shSingleQuote(scriptPath)} $event"))
            append("}]}]")
        }
        append("}")
    }

    /**
     * Render [s] as a TOML basic string: wrap in double quotes and escape what TOML requires there —
     * backslash and quote (the only characters a filesystem path can realistically contribute), plus the
     * control characters that are illegal raw inside a basic string.
     */
    private fun tomlString(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
            }
        }
        append('"')
    }

    /**
     * POSIX single-quote quoting: wrap in single quotes, rewriting every embedded `'` as the classic
     * `'\''` (close-quote, escaped literal quote, reopen-quote). Makes [s] one fully literal shell word.
     */
    private fun shSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
