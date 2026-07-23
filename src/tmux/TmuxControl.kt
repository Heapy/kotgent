package io.kotgent.tmux

import io.kotgent.core.PaneId

/**
 * The subset of tmux operations the daemon (Task 13) depends on, extracted as an interface so the
 * real popen-backed [Tmux] wrapper can be swapped for a host-free fake in unit tests. [Tmux]
 * implements this; [io.kotgent.daemon.SessionManager] and [io.kotgent.daemon.Reconciler] depend only
 * on the interface, so their logic (start/stop/resume/interrupt, reconciliation, registry rebuild) is
 * unit-testable with a `FakeTmux` — while the guarded integration tests still drive the real [Tmux]
 * against a throwaway `tmux -L kotgent-test` server.
 *
 * [decision] The interface lives in the `tmux` package (a tmux concept), covers exactly what the
 * daemon calls, and leaves the richer [Tmux] surface (capturePane / ensureServer / isAvailable) off
 * it — `capturePane` feeds the terminal bridge, and `ensureServer`/`isAvailable` are used at daemon
 * bootstrap / in tests against the concrete type, not through this seam.
 */
interface TmuxControl {
    /** The tmux session name for a logical short [id] (`kt-<id>`). */
    fun sessionName(id: String): String

    /** Create a detached `kt-<id>` session running [cmd] in [cwd] at [cols]x[rows]; return its pane id. */
    fun newSession(id: String, cwd: String, cmd: String, cols: Int, rows: Int): PaneId

    /** All panes across all sessions on this socket (empty on a fresh/torn-down socket). */
    fun listPanes(): List<TmuxPane>

    /** Kill session `kt-<id>`; `true` if a session was removed, `false` if there was nothing to kill. */
    fun killSession(id: String): Boolean

    /** Send raw [bytes] to `kt-<id>`'s active pane, byte-exact (used for Ctrl-C interrupt). */
    fun sendKeys(id: String, bytes: ByteArray)
}
