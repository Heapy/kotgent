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
 * A thin, typed wrapper over `tmux -f /dev/null -L <socket> <sub …>` (Task 8), built on
 * [ProcessRunner] (stock `platform.posix`, so it also runs from the test binary against a throwaway
 * server). Every argv is assembled by [tmuxCommand]; see [TMUX_CONFIG_ISOLATION] for why `-L` alone
 * is not enough isolation.
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
    /**
     * The options forced onto this socket's server, chained ahead of every [newSession].
     *
     * A constructor parameter rather than a direct read of [TMUX_SERVER_OPTIONS] purely so the
     * degradation path is testable without a second tmux build: a test passes an option this tmux
     * rejects and asserts a session is still created. Deliberately **not** on [TmuxControl] — no
     * caller of the daemon-facing seam has any business choosing tmux options.
     */
    val serverOptions: List<TmuxOption> = TMUX_SERVER_OPTIONS,
) : TmuxControl {
    /** The tmux session name for a logical [id]. */
    override fun sessionName(id: String): String = "kt-$id"

    /**
     * True if the configured tmux binary is runnable (`tmux -V` succeeds) — the tests' skip-guard.
     *
     * Deliberately bypasses [tmux] and therefore carries no `-f /dev/null`: `tmux -V` prints the
     * version and exits without starting a server or parsing any config, so there is nothing for the
     * isolation flag to isolate. It is the one argv here that is not a control-plane call.
     */
    fun isAvailable(): Boolean = ProcessRunner.run(listOf(tmuxPath, "-V")).isSuccess

    /**
     * Run `tmux -f /dev/null -L <socket> <args…>` — the single argv assembly point for every
     * control-plane call, so [TMUX_CONFIG_ISOLATION] cannot be forgotten at a new call site.
     *
     * Assembly lives in the pure [tmuxCommand], which is where the isolation is asserted: the
     * integration probe in `TmuxTest` can only measure raw tmux under a fake `$HOME` ([ProcessRunner]
     * takes no env map), so the link from that measurement to production is this delegation plus
     * [tmuxCommand]'s unit test, not an end-to-end run of [newSession].
     */
    private fun tmux(vararg args: String): ProcessResult =
        ProcessRunner.run(tmuxCommand(tmuxPath, socket, args.toList()))

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
     *
     * This is the production first-start (called once from `Commands.kt`), and it goes through
     * [tmux], so it carries `-f /dev/null` transitively — no separate test or call site. The
     * forced-option chain is deliberately NOT applied here: it rides with `new-session` precisely
     * because a session-less server does not persist, so options set on this one would die with it.
     */
    fun ensureServer() {
        val r = tmux("start-server")
        if (!r.isSuccess) throw TmuxException("tmux start-server failed: ${r.stderr.trim()}")
    }

    /**
     * Create a detached session named `kt-<id>` running [cmd] in [cwd] at [cols]x[rows], and
     * return its pane id (`new-session -P -F '#{pane_id}'`). `KOTGENT_SESSION_ID=<id>` is set as
     * a **debug label only** via `-e` (env-poisoning is never trusted for identity).
     *
     * ## Why [serverOptions] ride in this one invocation
     * A standalone `set-option` does **not** start a server (measured: `error connecting to …`,
     * exit 1, nothing applied), so the options cannot be applied before `new-session` in a call of
     * their own — and `default-terminal` is read when the pane is CREATED, so applying them after
     * `new-session` would already be too late for the agent running in that pane. Chaining is not an
     * optimisation, it is the only ordering that works. Re-applying on every session is intended: it
     * is idempotent, and a server that came up some other way converges to kotgent's options.
     *
     * ## Degradation
     * Every command in a tmux chain must succeed or the whole invocation fails, so a single option
     * name or scope that a different tmux build rejects would take `new-session` down with it and no
     * session could be created at all — a cosmetic option bricking the product on that host. Since
     * tmux's built-in defaults are already safe for the Detach invariant (`destroy-unattached off`),
     * degrading costs only ergonomics: on a failed chain this retries **once** with a bare
     * `new-session` and then applies the options individually, ignoring failures, on the now-running
     * server. `default-terminal` is lost for that pane on the degraded path (it was read at pane
     * creation) — the accepted trade against not starting at all. The retry cannot collide with a
     * half-created session: a rejected chain aborts before `new-session` runs, so nothing exists yet.
     */
    override fun newSession(id: String, cwd: String, cmd: String, cols: Int, rows: Int): PaneId {
        val create = arrayOf(
            "new-session", "-d",
            "-s", sessionName(id),
            "-c", cwd,
            "-x", cols.toString(),
            "-y", rows.toString(),
            "-e", "KOTGENT_SESSION_ID=$id",
            "-P", "-F", "#{pane_id}",
            cmd,
        )
        val chained = tmux(*(tmuxOptionCommands(serverOptions).toTypedArray() + create))
        val r = if (chained.isSuccess) {
            chained
        } else {
            val bare = tmux(*create)
            if (!bare.isSuccess) {
                throw TmuxException(
                    "tmux new-session for '$id' failed: ${bare.stderr.trim()} " +
                        "(the option chain failed first: ${chained.stderr.trim()})",
                )
            }
            applyServerOptionsBestEffort()
            bare
        }
        val paneId = r.stdout.trim()
        if (paneId.isEmpty()) throw TmuxException("tmux new-session for '$id' returned no pane id")
        return PaneId(paneId)
    }

    /**
     * Set each of [serverOptions] on the already-running server, one call per option, swallowing
     * every failure. Only reached on the degraded path, where the point is precisely that one
     * rejected option must not cost the others (or the session).
     */
    private fun applyServerOptionsBestEffort() {
        serverOptions.forEach { tmux("set-option", it.scope, it.name, it.value) }
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

        /**
         * An ABSOLUTE path to the tmux binary. Tries the common install locations first, then resolves
         * via the shell PATH (`command -v tmux`, run through `/bin/sh` by [ProcessRunner], which honors
         * PATH). An absolute path is REQUIRED for the terminal-attach upstream: it opens tmux via
         * [io.kotgent.pty.Pty.open] → `posix_spawn`, which does NOT search PATH, so a bare `tmux` there
         * ENOENTs under launchd's minimal env even though shell-based tmux CONTROL (`popen`) still works.
         * Only if resolution fails does it fall back to the bare name (control-plane keeps functioning;
         * terminal attach may not).
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
