package io.kotgent.daemon

import io.kotgent.core.PaneId
import io.kotgent.tmux.TmuxControl
import io.kotgent.tmux.TmuxCopyModeException
import io.kotgent.tmux.TmuxException
import io.kotgent.tmux.TmuxPane
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
/** Shared by unit tests and the multithreaded browser harness, so all exposed lists are CAS snapshots. */
class FakeTmux(seedPanes: List<TmuxPane> = emptyList()) : TmuxControl {

    private class State(
        val panes: List<TmuxPane>,
        val paneCounter: Int,
        val newSessionCommands: List<Pair<String, String>>,
        val killed: List<String>,
        val sentKeys: List<Pair<String, ByteArray>>,
        val copyModeCancels: List<String>,
    )

    private val state = AtomicReference(
        State(seedPanes.toList(), 100, emptyList(), emptyList(), emptyList(), emptyList()),
    )

    private fun <T> mutate(transform: (State) -> Pair<State, T>): T {
        while (true) {
            val current = state.load()
            val (next, result) = transform(current)
            if (state.compareAndSet(current, next)) return result
        }
    }

    val newSessionCommands: List<Pair<String, String>> get() = state.load().newSessionCommands

    val killed: List<String> get() = state.load().killed

    val sentKeys: List<Pair<String, ByteArray>> get() = state.load().sentKeys

    val copyModeCancels: List<String> get() = state.load().copyModeCancels

    var copyModeStuck: Boolean = false

    // Models the post-send copy-mode check, distinct from failure to leave copy mode before sending.
    var sendKeysCopyModeStuck: Boolean = false

    var sendKeysFailure: String? = null

    override fun sessionName(id: String): String = "kt-$id"

    /** Adds fixture state without recording a SessionManager command. */
    fun seedPane(id: String): PaneId = mutate { current ->
        val name = sessionName(id)
        val pane = PaneId("%${current.paneCounter}")
        val panes = current.panes.filterNot { it.session == name } +
            TmuxPane(session = name, paneId = pane, pid = 4242, dead = false, width = 80, height = 24)
        State(
            panes, current.paneCounter + 1, current.newSessionCommands, current.killed,
            current.sentKeys, current.copyModeCancels,
        ) to pane
    }

    override fun newSession(id: String, cwd: String, cmd: String, cols: Int, rows: Int): PaneId = mutate { current ->
        val name = sessionName(id)
        val pane = PaneId("%${current.paneCounter}")
        val panes = current.panes.filterNot { it.session == name } +
            TmuxPane(session = name, paneId = pane, pid = 4242, dead = false, width = cols, height = rows)
        State(
            panes, current.paneCounter + 1, current.newSessionCommands + (id to cmd), current.killed,
            current.sentKeys, current.copyModeCancels,
        ) to pane
    }

    override fun listPanes(): List<TmuxPane> = state.load().panes

    override fun killSession(id: String): Boolean = mutate { current ->
        val name = sessionName(id)
        val had = current.panes.any { it.session == name }
        State(
            current.panes.filterNot { it.session == name }, current.paneCounter,
            current.newSessionCommands, current.killed + id, current.sentKeys, current.copyModeCancels,
        ) to had
    }

    override fun sendKeys(id: String, bytes: ByteArray) {
        if (sendKeysCopyModeStuck) {
            throw TmuxCopyModeException(
                "tmux send-keys for '$id' was not delivered: ${sessionName(id)} is in copy-mode",
            )
        }
        sendKeysFailure?.let { throw TmuxException(it) }
        // Real tmux refuses delivery when the session has no live pane; the fake must not record it.
        mutate { current ->
            val name = sessionName(id)
            if (current.panes.none { it.session == name }) {
                throw TmuxException(
                    "tmux send-keys for '$id' was not delivered: $name has no live server/session/pane",
                )
            }
            State(
                current.panes, current.paneCounter, current.newSessionCommands, current.killed,
                current.sentKeys + (id to bytes), current.copyModeCancels,
            ) to Unit
        }
    }

    override fun leaveCopyMode(id: String): Boolean {
        mutate { current ->
            State(
                current.panes, current.paneCounter, current.newSessionCommands, current.killed,
                current.sentKeys, current.copyModeCancels + id,
            ) to Unit
        }
        return !copyModeStuck
    }
}
