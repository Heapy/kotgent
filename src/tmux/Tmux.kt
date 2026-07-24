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
open class TmuxException(message: String) : RuntimeException(message)

/**
 * The one tmux failure that is **transient and retryable**: the pane was in copy-mode, so tmux routed
 * the keys to the copy-mode key table instead of to the process ([Tmux.sendKeys]'s read-back caught it).
 *
 * A subtype rather than a message convention because the wire contract differs: a plain [TmuxException]
 * is a `400` ("this request was malformed, do not retry"), while this one is a `409` carrying the same
 * operator hint `POST /sessions/{id}/input` gives ("scroll the pane back to the bottom"). Nothing about
 * the request was wrong — a viewer's wheel scroll was, and scrolling back down fixes it.
 */
class TmuxCopyModeException(message: String) : TmuxException(message)

/**
 * A thin, typed wrapper over `tmux -f /dev/null -L <socket> <sub …>`, built on
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
     * A constructor parameter rather than a direct read of [TMUX_SERVER_OPTIONS] because it is the
     * only seam that makes the design's central claim falsifiable: the integration test builds a
     * `Tmux` whose `default-terminal` is a NON-default value and reads `$TERM` back out of the pane,
     * proving the chain took effect *before the pane existed*. With the production list every value
     * that a pane can report also happens to be tmux's own built-in, so the same assertion would
     * pass with the whole option chain deleted. Do not "tidy" this into a direct read.
     * Deliberately **not** on [TmuxControl] — no caller of the daemon-facing seam has any business
     * choosing tmux options.
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
     * ## Failure is loud, not degraded
     * Every command in a tmux chain must succeed or the whole invocation fails, so an option name a
     * different tmux build rejected would take `new-session` down with it. That is deliberate: it
     * raises [TmuxException] carrying tmux's own stderr, which already names the culprit
     * (`invalid option: …`), the same fail-fast shape as `AgentBinaryNotFoundException`. A bare
     * retry plus best-effort re-application was tried and removed — it fired on *every* failure
     * (duplicate session name, bad `-c` cwd, dead socket), doubling the spawn count and
     * misattributing the real error to the option chain, and it lost `history-limit` and
     * `default-terminal` for the pane anyway (both are read at pane creation) while reporting
     * nothing. Every forced option predates tmux 2.1 (2015) and macOS ships no tmux at all, so the
     * binary is a current Homebrew/MacPorts build; the one plausible failure — a typo in
     * [TMUX_SERVER_OPTIONS] — is caught by `newSessionForcesEveryServerOption` at build time.
     * A rejected chain aborts *before* `new-session` runs, so a failure leaves nothing half-created.
     */
    override fun newSession(id: String, cwd: String, cmd: String, cols: Int, rows: Int): PaneId {
        val argv = tmuxOptionCommands(serverOptions) + listOf(
            "new-session", "-d",
            "-s", sessionName(id),
            "-c", cwd,
            "-x", cols.toString(),
            "-y", rows.toString(),
            "-e", "KOTGENT_SESSION_ID=$id",
            "-P", "-F", "#{pane_id}",
            cmd,
        )
        val r = tmux(*argv.toTypedArray())
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
     * The `#{pane_in_mode}` a verified chain printed as its LAST stdout line: `true` = the pane is in
     * copy-mode (or any other mode), `false` = it is delivering keys to its process, `null` = the
     * question was not answered (no server, unknown pane, unparseable output).
     *
     * `null` is deliberately distinct from `false`: it means "nothing left to prove", so callers treat
     * it as a graceful no-op rather than as evidence either way.
     */
    private fun paneModeFrom(r: ProcessResult): Boolean? =
        when (r.stdout.trim().lineSequence().lastOrNull()?.trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }

    /**
     * Leave copy-mode on `kt-<id>`'s active pane and **verify it**: `true` only when the pane afterwards
     * *answered* "not in a mode", or when the question is moot (a soft absence — no server / unknown
     * session, [isAbsence]); `false` for everything else, including a failure that never answered.
     *
     * "Provably clear or a soft absence" is the whole contract, and the asymmetry is deliberate: the one
     * caller ([io.kotgent.transport.TerminalInputSink]) turns `false` into a refusal, so guessing `true`
     * would report `ok` for bytes tmux discarded — the exact silent swallow this method exists to
     * prevent. A wrong `tmuxPath`, a permission error, a half-dead server or an unparseable
     * `display-message` are therefore all `false`: none of them proves the pane is delivering keys. Only
     * the *soft* failures every other method here normalizes ([listPanes], [capturePane], [killSession],
     * [sendKeys]) read as `true` — there is no pane left to swallow anything.
     *
     * A pane in copy-mode routes every keystroke — whether it arrives via `send-keys` or by being
     * written into an attached client's pty — to the **copy-mode key table** instead of the process.
     * kotgent forces `mouse on` ([TMUX_SERVER_OPTIONS]), so one wheel scroll by *any* subscriber puts
     * the *shared* pane there for everyone, and the tmux prefix does the same. Every **programmatic**
     * input path must therefore cancel first: [sendKeys] and the `POST /sessions/{id}/input` REST seam
     * ([io.kotgent.transport.TerminalInputSink]). The interactive terminal WebSocket deliberately does
     * **not** — a human who scrolled back and then typed expects tmux's own behaviour, and yanking
     * them out of their scrollback would be the surprise.
     *
     * The cancel is `copy-mode -q`, not `send-keys -X … cancel`: `-q` exits copy mode *and any other
     * mode* and is a silent no-op on a pane that is in none, whereas `send-keys -X cancel` fails with
     * "not in a mode" — which, chained, aborts the whole invocation and takes the real command with it
     * (measured). That is what lets the cancel and its read-back ride ONE tmux invocation, so no client
     * event can land in between.
     *
     * The residual gap here is the caller's, not this method's: `/input` writes its bytes into the
     * upstream *pty* afterwards (the single-upstream invariant forbids routing them through tmux), so
     * a wheel scroll in that window can still re-enter copy-mode. [sendKeys], which does go through
     * tmux, closes even that.
     */
    override fun leaveCopyMode(id: String): Boolean {
        val target = sessionName(id)
        val r = tmux("copy-mode", "-q", "-t", target, ";", "display-message", "-p", "-t", target, PANE_IN_MODE)
        if (r.isAbsence()) return true // no server / unknown pane: there is nothing left to refuse over
        if (!r.isSuccess) return false // a real failure proves nothing about the pane — do not claim clear
        return paneModeFrom(r) == false // only an ANSWERED "0" counts as clear; `null` is unanswered
    }

    /**
     * Send raw [bytes] to session `kt-<id>`'s active pane, byte-exact, via `send-keys -H` (hex).
     * `-H` avoids any key-name interpretation, so arbitrary terminal input (control chars, UTF-8)
     * round-trips unchanged. Empty input is a no-op.
     *
     * ## Cancel, send and PROOF ride one invocation
     * A pane in copy-mode routes `send-keys` to the **copy-mode key table**, not to the process —
     * measured: the bytes vanish, the pane is unchanged, and tmux still exits **0**. So the exit
     * status proves nothing. kotgent forces `mouse on` ([TMUX_SERVER_OPTIONS]), so any subscriber's
     * wheel scroll parks the *shared* pane there; the one production caller is
     * `SessionManager.interrupt`, which sends `0x03` and then reduces the session to `ready`, so an
     * unproven send would record an interrupt in the projection that never happened.
     *
     * A cancel in its own invocation cannot fix that on its own — a wheel event landing between the
     * two calls re-enters copy-mode and the send is eaten anyway. So all three commands are chained
     * into a SINGLE tmux invocation, which tmux runs as one command list without returning to its
     * event loop:
     *
     *  1. `copy-mode -q` — leave any mode (silent no-op when there is none; see [leaveCopyMode] for
     *     why `send-keys -X cancel` cannot be chained);
     *  2. `send-keys -H …` — the real send;
     *  3. `display-message -p '#{pane_in_mode}'` — the proof, read with nothing able to intervene.
     *
     * A trailing `1` means the keys went to the copy-mode key table, and this **fails loudly** with a
     * [TmuxCopyModeException] — its own subtype, because that condition is transient and retryable and
     * the transport answers it with a `409` + hint rather than a `400` — instead of letting the caller
     * reduce to `ready`. Any OTHER non-zero exit stays a plain [TmuxException]. There is no retry:
     * a duplicated `0x03` is not harmless (a second Ctrl-C quits some agent TUIs outright), and with
     * the chain atomic a retry would only be papering over a tmux that no longer behaves as measured.
     * This is what makes `mouse on` safe; do not weaken it while that option is set.
     */
    override fun sendKeys(id: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val target = sessionName(id)
        val argv = listOf("copy-mode", "-q", "-t", target, ";", "send-keys", "-t", target, "-H") +
            bytes.map { (it.toInt() and 0xff).toString(16).padStart(2, '0') } +
            listOf(";", "display-message", "-p", "-t", target, PANE_IN_MODE)
        val r = tmux(*argv.toTypedArray())
        if (r.isAbsence()) return // no server / unknown session: graceful, as everywhere else here
        // Checked BEFORE the exit status: a swallowed send can also fail the invocation for an unrelated
        // reason (a copy-mode binding reporting "no current client"), and copy-mode is the real diagnosis.
        if (paneModeFrom(r) == true) {
            throw TmuxCopyModeException(
                "tmux send-keys for '$id' was not delivered: $target is in copy-mode, so the keys went " +
                    "to the copy-mode key table instead of the process",
            )
        }
        if (!r.isSuccess) throw TmuxException("tmux send-keys for '$id' failed: ${r.stderr.trim()}")
    }

    private fun fields(vararg specs: String): String = specs.joinToString(FS)

    companion object {
        /** Field separator embedded in `-F` formats: a raw TAB, absent from names/pids/dims. */
        private const val FS = "\t"

        /**
         * The format both verified chains end with. `1` means the pane is in copy-mode (or another
         * mode) and is routing keys to the mode's key table instead of to its process.
         */
        private const val PANE_IN_MODE = "#{pane_in_mode}"

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
