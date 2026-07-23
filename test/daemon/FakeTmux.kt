package io.kotgent.daemon

import io.kotgent.core.PaneId
import io.kotgent.tmux.TmuxControl
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
        sentKeys.add(id to bytes)
    }
}
