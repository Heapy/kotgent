package io.kotgent.daemon

import io.kotgent.core.PaneId
import io.kotgent.tmux.TmuxControl
import io.kotgent.tmux.TmuxCopyModeException
import io.kotgent.tmux.TmuxException
import io.kotgent.tmux.TmuxPane

/**
 * A host-free fake [TmuxControl] for the daemon unit tests — no real tmux, no cinterop, so it runs in
 * the test binary. It models a tiny in-memory tmux: [newSession] adds a live pane, [killSession]
 * removes it, and [listPanes] reports the current set. Every mutating call is recorded
 * ([newSessionCommands] / [killed] / [sentKeys]) so tests can assert what the [SessionManager] asked
 * tmux to do. Panes handed out are `%100`, `%101`, … so they are unambiguous.
 *
 * The [Reconciler] tests seed the live-pane set directly via [seedPanes]; sessions with no seeded pane
 * are "gone" (dead/torn-down).
 *
 * It lives in the `fakes` module — with its package deliberately UNCHANGED, so none of its consumers
 * needed an edit when it moved — because the `webuicheck` harness stands a real `KotgentServer` on it
 * too: the browser tier must never reach a real tmux server. See `fakes/module.yaml`.
 */
class FakeTmux(seedPanes: List<TmuxPane> = emptyList()) : TmuxControl {

    private val panes = seedPanes.toMutableList()
    private var paneCounter = 100

    /** (logical id, rendered command) for every [newSession] call, in order. */
    val newSessionCommands = mutableListOf<Pair<String, String>>()

    /** Logical ids passed to [killSession], in order. */
    val killed = mutableListOf<String>()

    /** (logical id, bytes) for every [sendKeys] call, in order. */
    val sentKeys = mutableListOf<Pair<String, ByteArray>>()

    /** Logical ids passed to [leaveCopyMode], in order. */
    val copyModeCancels = mutableListOf<String>()

    /**
     * When true, [leaveCopyMode] reports failure — the fake stand-in for a pane a wheel scroll keeps
     * dragging back into copy-mode, which makes the "input write did not proceed" path testable.
     */
    var copyModeStuck: Boolean = false

    /**
     * When true, [sendKeys] throws [TmuxCopyModeException] — the real wrapper's `#{pane_in_mode}`
     * read-back catching a send that went to the copy-mode key table. Distinct from [sendKeysFailure]
     * because the two carry different wire contracts (retryable 409 vs generic failure 400).
     */
    var sendKeysCopyModeStuck: Boolean = false

    /** When non-null, [sendKeys] throws a plain [TmuxException] with this message (an ordinary failure). */
    var sendKeysFailure: String? = null

    override fun sessionName(id: String): String = "kt-$id"

    override fun newSession(id: String, cwd: String, cmd: String, cols: Int, rows: Int): PaneId {
        newSessionCommands.add(id to cmd)
        val name = sessionName(id)
        val pane = PaneId("%${paneCounter++}")
        panes.removeAll { it.session == name }
        panes.add(TmuxPane(session = name, paneId = pane, pid = 4242, dead = false, width = cols, height = rows))
        return pane
    }

    override fun listPanes(): List<TmuxPane> = panes.toList()

    override fun killSession(id: String): Boolean {
        killed.add(id)
        val name = sessionName(id)
        val had = panes.any { it.session == name }
        panes.removeAll { it.session == name }
        return had
    }

    override fun sendKeys(id: String, bytes: ByteArray) {
        if (sendKeysCopyModeStuck) {
            throw TmuxCopyModeException(
                "tmux send-keys for '$id' was not delivered: ${sessionName(id)} is in copy-mode",
            )
        }
        sendKeysFailure?.let { throw TmuxException(it) }
        sentKeys.add(id to bytes)
    }

    override fun leaveCopyMode(id: String): Boolean {
        copyModeCancels.add(id)
        return !copyModeStuck
    }
}
