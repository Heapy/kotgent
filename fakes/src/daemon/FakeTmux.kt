package io.kotgent.daemon

import io.kotgent.core.PaneId
import io.kotgent.tmux.TmuxControl
import io.kotgent.tmux.TmuxCopyModeException
import io.kotgent.tmux.TmuxException
import io.kotgent.tmux.TmuxPane
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A host-free fake [TmuxControl] for the daemon unit tests — no real tmux, no cinterop, so it runs in
 * the test binary. It models a tiny in-memory tmux: [newSession] adds a live pane, [killSession]
 * removes it, and [listPanes] reports the current set. Every mutating call is recorded
 * ([newSessionCommands] / [killed] / [sentKeys]) so tests can assert what the [SessionManager] asked
 * tmux to do. Panes handed out are `%100`, `%101`, … so they are unambiguous.
 *
 * The [Reconciler] tests seed the live-pane set through the constructor; a scenario that seeds its rows
 * one at a time uses [seedPane]. Sessions with no seeded pane are "gone" (dead/torn-down).
 *
 * It lives in the `fakes` module — with its package deliberately UNCHANGED, so none of its consumers
 * needed an edit when it moved — because the `webuicheck` harness stands a real `KotgentServer` on it
 * too: the browser tier must never reach a real tmux server. See `fakes/module.yaml`.
 *
 * ## Concurrency
 * All mutable state lives in ONE immutable [State] behind an [AtomicReference], mutated by CAS. It was
 * written for single-threaded unit tests and now also backs a live `KotgentServer` whose engine threads
 * call it concurrently (two browser tabs interrupting two sessions is two threads inside [sendKeys]);
 * plain `MutableList` fields would corrupt under that, and the corruption would present as an
 * inexplicable browser assertion rather than as a fault here. The recorders are therefore read as
 * SNAPSHOTS — every accessor answers an immutable list — which is what every existing call site already
 * does with them.
 */
@OptIn(ExperimentalAtomicApi::class)
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

    /** Apply [transform] under a CAS retry loop and answer whatever it computed alongside the new state. */
    private fun <T> mutate(transform: (State) -> Pair<State, T>): T {
        while (true) {
            val current = state.load()
            val (next, result) = transform(current)
            if (state.compareAndSet(current, next)) return result
        }
    }

    /** (logical id, rendered command) for every [newSession] call, in order. */
    val newSessionCommands: List<Pair<String, String>> get() = state.load().newSessionCommands

    /** Logical ids passed to [killSession], in order. */
    val killed: List<String> get() = state.load().killed

    /** (logical id, bytes) for every [sendKeys] call, in order. */
    val sentKeys: List<Pair<String, ByteArray>> get() = state.load().sentKeys

    /** Logical ids passed to [leaveCopyMode], in order. */
    val copyModeCancels: List<String> get() = state.load().copyModeCancels

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

    /**
     * Register a live pane for [id] without a [newSession] call, and answer it — what a fixture that
     * seeds an ALIVE session row owes, since a session the daemon believes is running has a pane behind
     * it in production. Recorded nowhere: seeding is not a command the [SessionManager] issued.
     */
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
        // The pane set is CONSULTED, not ignored. Real `Tmux.sendKeys` (src/tmux/Tmux.kt:327-332) reads
        // its chain's answer and throws on `isAbsence()` — "no live server/session/pane" — before it can
        // ever record a delivery, so a control action against a session tmux does not hold FAILS. A fake
        // that recorded the bytes anyway made every such action succeed in the harness and turned the
        // route's own failure branch into dead code no browser test could reach.
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
