package io.kotgent.tmux

import io.kotgent.core.PaneId
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.X_OK
import platform.posix.access

/** A tmux pane as parsed from `list-panes -a -F`. */
data class TmuxPane(
    /** Owning session name (`kt-<id>`). */
    val session: String,
    /** Runtime pane correlation handle (`#{pane_id}`, e.g. `%3`). */
    val paneId: PaneId,
    /** Pid of the process running in the pane (`#{pane_pid}`). */
    val pid: Int,
    /** Whether the pane's process has exited (`#{pane_dead}` == 1). */
    val dead: Boolean,
    /** Pane/window width in columns (`#{window_width}`). */
    val width: Int,
    /** Pane/window height in rows (`#{window_height}`). */
    val height: Int,
)

/** Thrown when a tmux command fails in a way that is not an expected "not found"/"no server". */
class TmuxException(message: String) : RuntimeException(message)

/**
 * A thin, typed wrapper over `tmux -L <socket> <sub …>` (Task 8), built on [ProcessRunner]
 * (stock `platform.posix`, so it also runs from the test binary against a throwaway server).
 *
 * ## Session identity
 * Callers address sessions by the **logical short id** (`id`); the wrapper maps it to the tmux
 * session name `kt-<id>` ([sessionName]). The runtime correlation handle is the [PaneId]
 * (`#{pane_id}`) that [newSession] returns and [listPanes] reports — that is what hooks send as
 * `$TMUX_PANE` and what the reconciler keys liveness on.
 *
 * ## Robustness
 * Argument construction goes through [ProcessRunner]'s strict quoting, so cwd paths, commands,
 * and env labels cannot be re-split by the shell. "Soft" tmux failures are normalized rather than
 * thrown: a missing session/pane or a torn-down server reads as an empty list / `false` / `null`
 * (an empty tmux server does not persist, so a fresh socket legitimately reports "no server
 * running"). Genuinely unexpected non-zero exits raise [TmuxException] with tmux's stderr.
 */
class Tmux(
    /** The `-L` socket label, e.g. `kotgent` (prod) or `kotgent-test` (throwaway in tests). */
    val socket: String,
    /** Path to the tmux binary; resolved from common locations by default. */
    val tmuxPath: String = defaultTmuxPath(),
) : TmuxControl {
    /** The tmux session name for a logical [id]. */
    override fun sessionName(id: String): String = "kt-$id"

    /** True if the configured tmux binary is runnable (`tmux -V` succeeds) — the tests' skip-guard. */
    fun isAvailable(): Boolean = ProcessRunner.run(listOf(tmuxPath, "-V")).isSuccess

    /** Run `tmux -L <socket> <args…>`. */
    private fun tmux(vararg args: String): ProcessResult =
        ProcessRunner.run(listOf(tmuxPath, "-L", socket) + args.toList())

    /** True when a soft "there is nothing there" failure (no server / unknown target). */
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
     * Start the tmux server for this socket (`start-server`). Best-effort: a server with no
     * sessions does not stay resident, so this mainly proves the socket is reachable; the real
     * server comes up when [newSession] creates the first session.
     */
    fun ensureServer() {
        val r = tmux("start-server")
        if (!r.isSuccess) throw TmuxException("tmux start-server failed: ${r.stderr.trim()}")
    }

    /**
     * Create a detached session named `kt-<id>` running [cmd] in [cwd] at [cols]x[rows], and
     * return its pane id (`new-session -P -F '#{pane_id}'`). `KOTGENT_SESSION_ID=<id>` is set as
     * a **debug label only** via `-e` (env-poisoning is never trusted for identity).
     */
    override fun newSession(id: String, cwd: String, cmd: String, cols: Int, rows: Int): PaneId {
        val r = tmux(
            "new-session", "-d",
            "-s", sessionName(id),
            "-c", cwd,
            "-x", cols.toString(),
            "-y", rows.toString(),
            "-e", "KOTGENT_SESSION_ID=$id",
            "-P", "-F", "#{pane_id}",
            cmd,
        )
        if (!r.isSuccess) throw TmuxException("tmux new-session for '$id' failed: ${r.stderr.trim()}")
        val paneId = r.stdout.trim()
        if (paneId.isEmpty()) throw TmuxException("tmux new-session for '$id' returned no pane id")
        return PaneId(paneId)
    }

    /** List all panes across all sessions on this socket. A torn-down socket reads as empty. */
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

    /**
     * Capture the visible content of session `kt-<id>`'s active pane (`capture-pane -p -e`,
     * `-e` preserving escape sequences so the terminal seed is faithful). Returns the raw
     * captured text; an unknown session/torn-down server yields an empty string.
     */
    fun capturePane(id: String): String {
        val r = tmux("capture-pane", "-p", "-e", "-t", sessionName(id))
        if (r.isAbsence()) return ""
        if (!r.isSuccess) throw TmuxException("tmux capture-pane for '$id' failed: ${r.stderr.trim()}")
        return r.stdout
    }

    /**
     * Kill session `kt-<id>`. Returns `true` if a session was actually removed, `false` if there
     * was nothing to kill (unknown session or no server) — so double-kill and killing a
     * nonexistent session are both graceful, not errors.
     */
    override fun killSession(id: String): Boolean {
        val r = tmux("kill-session", "-t", sessionName(id))
        if (r.isSuccess) return true
        if (r.isAbsence()) return false
        throw TmuxException("tmux kill-session for '$id' failed: ${r.stderr.trim()}")
    }

    /**
     * Send raw [bytes] to session `kt-<id>`'s active pane, byte-exact, via `send-keys -H` (hex).
     * `-H` avoids any key-name interpretation, so arbitrary terminal input (control chars, UTF-8)
     * round-trips unchanged. Empty input is a no-op.
     */
    override fun sendKeys(id: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val hex = bytes.map { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val r = tmux(*(arrayOf("send-keys", "-t", sessionName(id), "-H") + hex))
        if (!r.isSuccess && !r.isAbsence()) {
            throw TmuxException("tmux send-keys for '$id' failed: ${r.stderr.trim()}")
        }
    }

    private fun fields(vararg specs: String): String = specs.joinToString(FS)

    companion object {
        /** Field separator embedded in `-F` formats: a raw TAB, absent from names/pids/dims. */
        private const val FS = "\t"

        /** First runnable tmux among common install locations, else bare `tmux` (rely on PATH). */
        @OptIn(ExperimentalForeignApi::class)
        fun defaultTmuxPath(): String {
            val candidates = listOf("/opt/homebrew/bin/tmux", "/usr/local/bin/tmux", "/usr/bin/tmux")
            return candidates.firstOrNull { access(it, X_OK) == 0 } ?: "tmux"
        }
    }
}
