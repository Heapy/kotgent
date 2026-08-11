package io.kotgent.pty

import io.kotgent.sys.utf8LocaleOrDefault
import io.kotgent.tmux.TMUX_CONFIG_ISOLATION
import kotlinx.coroutines.channels.ReceiveChannel

interface PtyHandle {
    val output: ReceiveChannel<ByteArray>

    /**
     * Normal return means the full array was written. Failure may follow a successful prefix write and
     * does not make a whole-body retry safe.
     */
    fun write(bytes: ByteArray)

    fun resize(cols: Int, rows: Int)

    /**
     * Terminates the child to unblock writes without releasing the master fd. This first teardown phase
     * prevents fd reuse while [Broadcaster] drains in-flight writes. Idempotent.
     */
    fun prepareClose()

    /**
     * Releases all resources. Closing a tmux attach handle ends only that client; the session survives.
     */
    fun close()
}

/** The tmux attach environment must include TERM and a UTF-8 locale. */
typealias PtyFactory = (command: List<String>, env: Map<String, String>) -> PtyHandle

/**
 * A stable system terminfo entry avoids inheriting terminal-specific entries unavailable to launchd.
 */
const val ATTACH_TERM: String = "xterm-256color"

const val ATTACH_FALLBACK_PATH: String = "/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"

/**
 * `-u` independently forces UTF-8 even if the requested locale is unavailable. Isolation and socket
 * flags are global and must precede attach; isolation matters only if this call starts the server.
 */
fun attachUpstreamCommand(tmuxPath: String, socket: String, session: String): List<String> =
    listOf(tmuxPath) + TMUX_CONFIG_ISOLATION + listOf("-u", "-L", socket, "attach", "-t", session)

/**
 * Empty TERM makes tmux attach exit immediately. LANG is always forced through
 * [utf8LocaleOrDefault] because launchd does not supply one.
 */
fun terminalAttachEnv(lang: String?, home: String?, path: String?): Map<String, String> {
    val env = LinkedHashMap<String, String>()
    env["TERM"] = ATTACH_TERM
    home?.ifBlank { null }?.let { env["HOME"] = it }
    env["PATH"] = path?.ifBlank { null } ?: ATTACH_FALLBACK_PATH
    env["LANG"] = utf8LocaleOrDefault(lang)
    return env
}

/**
 * Capture-pane omits private modes, so joiners need tmux's mouse modes synthesized to reach pane
 * history. Any-motion `1003h` remains app-owned and is not reproduced until tmux's next full repaint.
 */
const val TERMINAL_MOUSE_ENABLE: String = "\u001b[?1006h\u001b[?1000h\u001b[?1002h"

/**
 * Capture-pane omits tmux's bracketed-paste mode; without synthesizing it for joiners, multiline paste
 * may execute line by line.
 */
const val TERMINAL_BRACKETED_PASTE_ENABLE: String = "\u001b[?2004h"

/**
 * Client modes precede the captured repaint. Empty capture stays empty so an absent session receives no
 * stray mode changes; app-owned modes are intentionally not synthesized.
 */
fun terminalSeed(capturedPane: String, mouseForced: Boolean): ByteArray = when {
    capturedPane.isEmpty() -> ByteArray(0)
    mouseForced ->
        (TERMINAL_BRACKETED_PASTE_ENABLE + TERMINAL_MOUSE_ENABLE + capturedPane).encodeToByteArray()
    else -> (TERMINAL_BRACKETED_PASTE_ENABLE + capturedPane).encodeToByteArray()
}
