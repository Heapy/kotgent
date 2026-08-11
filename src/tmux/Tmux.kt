package io.kotgent.tmux

import io.kotgent.core.PaneId
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.X_OK
import platform.posix.access

data class TmuxPane(
    val session: String,
    val paneId: PaneId,
    val pid: Int,
    val dead: Boolean,
    val width: Int,
    val height: Int,
)

/**
 * Observational operations may normalize absence; delivery-bearing operations must fail because no
 * process received the input.
 */
open class TmuxException(message: String) : RuntimeException(message)

/**
 * Retryable copy-mode delivery failure. Its subtype drives the transport's distinct 409 response and
 * recovery hint.
 */
class TmuxCopyModeException(message: String) : TmuxException(message)

/**
 * Wrapper for kotgent's isolated tmux server. Observations normalize a missing server/session, while
 * delivery operations fail on absence. Runtime identity is the pane id, not the inherited debug label.
 */
class Tmux(
    val socket: String,
    val tmuxPath: String = defaultTmuxPath(),
    val serverOptions: List<TmuxOption> = TMUX_SERVER_OPTIONS,
    val hookScriptPath: String? = null,
) : TmuxControl {
    override fun sessionName(id: String): String = "kt-$id"

    /**
     * `tmux -V` starts no server and parses no config, so isolation flags are unnecessary here.
     */
    fun isAvailable(): Boolean = ProcessRunner.run(listOf(tmuxPath, "-V")).isSuccess

    private fun tmux(vararg args: String): ProcessResult =
        ProcessRunner.run(tmuxCommand(tmuxPath, socket, args.toList()))

    private fun ProcessResult.isAbsence(): Boolean {
        val e = stderr
        return !isSuccess && (
            "no server running" in e ||
                "can't find session" in e ||
                "can't find pane" in e ||
                "session not found" in e
            )
    }

    /**
     * A sessionless server does not persist, so forced options are applied by [newSession], not here.
     */
    fun ensureServer() {
        val r = tmux("start-server")
        if (!r.isSuccess) throw TmuxException("tmux start-server failed: ${r.stderr.trim()}")
    }

    /**
     * Standalone set-hook/set-option cannot start a server, and `default-terminal` is read at pane
     * creation. Hooks, options, and new-session therefore share one fail-fast chain. The inherited
     * `KOTGENT_SESSION_ID` is only a debug label and is never trusted as identity.
     */
    override fun newSession(id: String, cwd: String, cmd: String, cols: Int, rows: Int): PaneId {
        val argv = newSessionArgv(serverOptions, hookScriptPath, id, cwd, cmd, cols, rows)
        val r = tmux(*argv.toTypedArray())
        if (!r.isSuccess) throw TmuxException("tmux new-session for '$id' failed: ${r.stderr.trim()}")
        val paneId = r.stdout.trim()
        if (paneId.isEmpty()) throw TmuxException("tmux new-session for '$id' returned no pane id")
        return PaneId(paneId)
    }

    override fun listPanes(): List<TmuxPane> {
        val r = tmux(
            "list-panes", "-a", "-F",
            fields(
                "#{session_name}", "#{pane_id}", "#{pane_pid}",
                "#{pane_dead}", "#{window_width}", "#{window_height}",
            ),
        )
        if (r.isAbsence()) return emptyList()
        if (!r.isSuccess) throw TmuxException("tmux list-panes failed: ${r.stderr.trim()}")
        return r.stdout.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val f = line.split(FS)
                val rawPane = f.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TmuxPane(
                    session = f[0],
                    paneId = PaneId(rawPane),
                    pid = f.getOrNull(2)?.toIntOrNull() ?: 0,
                    dead = f.getOrNull(3) == "1",
                    width = f.getOrNull(4)?.toIntOrNull() ?: 0,
                    height = f.getOrNull(5)?.toIntOrNull() ?: 0,
                )
            }
            .toList()
    }

    /** `-e` preserves escape sequences needed by a faithful terminal seed. */
    fun capturePane(id: String): String {
        val r = tmux("capture-pane", "-p", "-e", "-t", sessionName(id))
        if (r.isAbsence()) return ""
        if (!r.isSuccess) throw TmuxException("tmux capture-pane for '$id' failed: ${r.stderr.trim()}")
        return r.stdout
    }

    override fun killSession(id: String): Boolean {
        val r = tmux("kill-session", "-t", sessionName(id))
        if (r.isSuccess) return true
        if (r.isAbsence()) return false
        throw TmuxException("tmux kill-session for '$id' failed: ${r.stderr.trim()}")
    }

    /**
     * Null means the pane did not answer and must not be treated as delivery proof.
     */
    private fun paneModeFrom(r: ProcessResult): Boolean? =
        when (r.stdout.trim().lineSequence().lastOrNull()?.trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }

    /**
     * Programmatic pty input must leave shared copy-mode or tmux silently routes bytes to its mode key
     * table. `copy-mode -q` is chainable even when no mode is active. True requires an answered clear
     * state or soft absence; other failures are not proof. The interactive WebSocket deliberately keeps
     * native tmux behavior. A wheel can still re-enter copy-mode after this check and before a later pty
     * write; only [sendKeys]' single tmux chain closes that gap.
     */
    override fun leaveCopyMode(id: String): Boolean {
        val target = sessionName(id)
        val r = tmux("copy-mode", "-q", "-t", target, ";", "display-message", "-p", "-t", target, PANE_IN_MODE)
        if (r.isAbsence()) return true
        if (!r.isSuccess) return false
        return paneModeFrom(r) == false
    }

    /**
     * Hex mode preserves arbitrary bytes. Copy-mode cancel, send, and `pane_in_mode` read-back share one
     * invocation so a wheel event cannot reopen the mode between them. Only an answered 0 proves
     * delivery; 1 raises [TmuxCopyModeException], and absence or malformed output raises [TmuxException].
     * Never retry automatically: duplicating Ctrl-C can terminate an agent TUI.
     */
    override fun sendKeys(id: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val target = sessionName(id)
        val argv = listOf("copy-mode", "-q", "-t", target, ";", "send-keys", "-t", target, "-H") +
            bytes.map { (it.toInt() and 0xff).toString(16).padStart(2, '0') } +
            listOf(";", "display-message", "-p", "-t", target, PANE_IN_MODE)
        val r = tmux(*argv.toTypedArray())
        if (r.isAbsence()) {
            throw TmuxException(
                "tmux send-keys for '$id' was not delivered: $target has no live server/session/pane " +
                    "(${r.stderr.trim()})",
            )
        }
        val paneMode = paneModeFrom(r)
        // Copy-mode is the useful diagnosis even if its key binding also made the chain fail.
        if (paneMode == true) {
            throw TmuxCopyModeException(
                "tmux send-keys for '$id' was not delivered: $target is in copy-mode, so the keys went " +
                    "to the copy-mode key table instead of the process",
            )
        }
        if (!r.isSuccess) throw TmuxException("tmux send-keys for '$id' failed: ${r.stderr.trim()}")
        if (paneMode == null) {
            throw TmuxException(
                "tmux send-keys for '$id' could not verify delivery: expected a $PANE_IN_MODE read-back " +
                    "of 0 or 1, got <${r.stdout.trim()}>",
            )
        }
    }

    private fun fields(vararg specs: String): String = specs.joinToString(FS)

    companion object {
        private const val FS = "\t"

        private const val PANE_IN_MODE = "#{pane_in_mode}"

        /**
         * The PTY attach path uses `posix_spawn`, which does not search PATH, so it needs an absolute
         * tmux executable. A bare-name fallback preserves shell-based control calls only.
         */
        @OptIn(ExperimentalForeignApi::class)
        fun defaultTmuxPath(): String {
            val candidates = listOf("/opt/homebrew/bin/tmux", "/usr/local/bin/tmux", "/usr/bin/tmux")
            candidates.firstOrNull { access(it, X_OK) == 0 }?.let { return it }
            val resolved = ProcessRunner.run(listOf("command", "-v", "tmux"))
                .takeIf { it.isSuccess }
                ?.stdout?.trim()?.lineSequence()?.firstOrNull()?.takeIf { it.startsWith("/") }
            return resolved ?: "tmux"
        }
    }
}
