package io.kotgent.pty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Per-session, **lazy** terminal bridge (Task 9): the single upstream `tmux attach` that N clients
 * fan out over via a [Broadcaster].
 *
 * ## Lazy lifecycle (the whole point)
 * The upstream is only alive while someone is watching:
 *  - the **first** [subscribe] opens the upstream [PtyHandle] for [upstreamCommand]
 *    (`tmux -L kotgent attach -t kt-<id>`) and starts a reader loop pumping its bytes into the
 *    [Broadcaster];
 *  - the **last** subscriber leaving closes the upstream — ending only *this* attach client. The
 *    tmux session (and the agent running in it) keeps running independently, which is what gives
 *    Detach (closing the IDE drops the last WS subscriber; the session survives) and what removes
 *    the attach after a daemon restart so there is no respawn to reconcile;
 *  - a later [subscribe] transparently **re-opens** a fresh upstream (the re-attach path).
 *
 * The open/close *decisions* are driven by the [Broadcaster] (it owns the subscriber set, so it
 * sees the 0→1 / 1→0 transitions) which calls back into the hooks wired here — see [broadcaster].
 * This class owns the *mechanism*: the [ptyFactory], the [scope] the reader loop runs on, the
 * reader loop itself, and the [seedProvider] snapshot.
 *
 * ## Testability
 * The bridge depends only on the pure-Kotlin [PtyHandle] + [PtyFactory] + a `() -> ByteArray`
 * [seedProvider] — never on the concrete cinterop [io.kotgent.pty.Pty] or on tmux directly. That
 * lets the fan-out/lifecycle logic be unit-tested with a fake factory in the test binary (KT-78062
 * keeps our custom cinterop out of it). Production wires [ptyFactory] to [realPtyFactory] and
 * [upstreamCommand] / [seedProvider] to a real [io.kotgent.tmux.Tmux] — see [terminalBridgeForSession].
 *
 * @param upstreamCommand argv for the upstream pty, e.g. `[tmux, -L, kotgent, attach, -t, kt-<id>]`.
 * @param seedProvider    the `capture-pane -e` snapshot handed to each new subscriber before live
 *                        deltas. Invoked on [subscribe] under the broadcaster lock; keep it quick
 *                        (a real tmux `capture-pane` is a sub-millisecond subprocess call).
 * @param ptyFactory      how to open the upstream [PtyHandle] for [upstreamCommand].
 * @param scope           the scope the reader loop coroutine is launched on.
 */
class TerminalBridge(
    private val upstreamCommand: List<String>,
    private val seedProvider: () -> ByteArray,
    private val ptyFactory: PtyFactory,
    private val scope: CoroutineScope,
) {
    /** The reader loop for the currently-open upstream; cancelled when that upstream is closed. */
    private var readerJob: Job? = null

    private val broadcaster = Broadcaster(
        openUpstream = {
            // 0→1: open the upstream and start pumping its bytes into the broadcaster. Runs under
            // the broadcaster lock; launch() only schedules the reader, so the lock is held briefly.
            val up = ptyFactory(upstreamCommand)
            readerJob = scope.launch { readerLoop(up) }
            up
        },
        closeUpstream = { up ->
            // 1→0: stop the reader before closing so a cancelled loop never touches a dead handle,
            // then close the upstream (ends this attach; the session survives).
            readerJob?.cancel()
            readerJob = null
            up.close()
        },
        seedProvider = seedProvider,
    )

    /**
     * Attach a new client. Opens (or re-opens) the upstream if this is the first subscriber, seeds
     * the client with the current screen, and returns its [Subscriber] handle (output stream +
     * input/resize + detach).
     */
    suspend fun subscribe(): Subscriber = broadcaster.attach()

    /** Current number of attached subscribers (observability / test synchronization). */
    suspend fun subscriberCount(): Int = broadcaster.subscriberCount()

    /**
     * Write terminal input to the shared upstream — the `POST /sessions/{id}/input` REST seam (Task 14).
     * Routes through the same [Broadcaster] as attached subscribers' input, so it reaches the one shared
     * `tmux attach` upstream and stays consistent with the terminal-WS input path.
     *
     * [decision] Because the upstream is lazy, this reaches the agent only while a terminal is attached
     * (a subscriber is present); with none attached it is a no-op — the browser's normal flow keeps a
     * terminal-WS attached, and terminal input over that WS is the primary path. (`tmux send-keys` would
     * deliver subscriber-independently, but bypasses the single-upstream fan-out; the Broadcaster path is
     * chosen so `/input` and terminal-WS input are one channel.)
     */
    suspend fun write(bytes: ByteArray): Unit = broadcaster.writeInput(bytes)

    /** Tear the bridge down regardless of subscribers (daemon shutdown / test teardown). */
    suspend fun shutdown(): Unit = broadcaster.shutdown()

    /**
     * Pump upstream bytes into the fan-out until the upstream reaches EOF. On natural EOF (the pane
     * or session died, or the attach ended externally) the broadcaster drops the dead upstream and
     * detaches remaining clients. When we close the upstream ourselves (last subscriber left) this
     * loop is cancelled instead and never reaches the EOF handler.
     */
    private suspend fun readerLoop(up: PtyHandle) {
        for (bytes in up.output) {
            broadcaster.broadcast(bytes)
        }
        broadcaster.onUpstreamEof(up)
    }
}
