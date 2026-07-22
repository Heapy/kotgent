package io.kotgent.pty

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The fan-out hub for one session's terminal (Task 9). N [Subscriber]s (each a browser / CLI
 * client) attach to a single shared upstream [PtyHandle]:
 *  - **downstream:** every byte read off the upstream is fanned out to every subscriber's sink;
 *  - **upstream (input):** input from *any* subscriber is written to the one shared upstream;
 *  - **resize:** a subscriber resize applies the **"last active"** policy — the most recent
 *    resize wins and is remembered so it can be re-applied when the upstream is (re)opened.
 *
 * ## Lazy lifecycle, delegated
 * The Broadcaster owns the subscriber set, so it is what naturally observes the 0→1 and 1→0
 * transitions. It does not know *how* to open a `tmux attach` pty, run a reader loop, or capture
 * a seed — that is [TerminalBridge]'s job. So the Broadcaster is handed three hooks and invokes
 * them **under its own lock**, keeping "open on first subscriber" / "close on last subscriber"
 * atomic with the set mutation that triggers them:
 *  - [openUpstream] — 0→1: [TerminalBridge] opens the upstream and starts the reader loop, returns
 *    the handle;
 *  - [closeUpstream] — 1→0: [TerminalBridge] stops the reader and closes the upstream (ending this
 *    attach — the tmux session lives on);
 *  - [seedProvider] — per attach: the `capture-pane -e` snapshot handed to a newly-attached
 *    subscriber *before* any live delta, so a late joiner sees the current screen then updates.
 *
 * ## `window-size latest` reflow caveat
 * tmux's default `window-size latest` sizes a session's window to the most recently active client.
 * The capture-pane seed reflects the pane's size at capture time; if a new subscriber then resizes
 * to a different geometry, tmux reflows and the seed's wrapping can momentarily look off until the
 * next full redraw. This is cosmetic and self-heals — the alternative (`window-size largest`/
 * `smallest` or forcing a uniform client size) is a Task 14/17 policy choice, not a fan-out concern.
 *
 * ## Concurrency
 * A single [mutex] guards the subscriber set, the current [upstream] reference and [lastSize].
 * Every sink is an UNLIMITED [Channel], so [broadcast]'s `trySend` never blocks while holding the
 * lock. The reader loop calls [broadcast] (which takes the lock) while [attach] may hold it across
 * a seed capture; because both serialize on [mutex], a new subscriber's seed is enqueued strictly
 * before any live delta it should see, and no committed delta is lost across the join boundary.
 */
class Broadcaster(
    private val openUpstream: () -> PtyHandle,
    private val closeUpstream: (PtyHandle) -> Unit,
    private val seedProvider: () -> ByteArray,
) {
    private val mutex = Mutex()
    private val subscribers = mutableListOf<Subscriber>()
    private var upstream: PtyHandle? = null
    private var lastSize: Pair<Int, Int>? = null

    /**
     * Attach a new subscriber. If it is the first, the upstream is opened first (and the last
     * known size re-applied). The subscriber is pre-seeded with the current [seedProvider] snapshot
     * before any live delta, then added to the fan-out set.
     */
    suspend fun attach(): Subscriber = mutex.withLock {
        if (subscribers.isEmpty()) {
            val up = openUpstream()
            // Re-apply the last active geometry so a re-opened upstream matches what clients expect.
            lastSize?.let { (cols, rows) -> up.resize(cols, rows) }
            upstream = up
        }
        val sub = Subscriber(this)
        // Seed BEFORE registering for live deltas (both under this lock) so the snapshot is first
        // in the subscriber's channel and subsequent broadcasts append after it, in order.
        val seed = seedProvider()
        if (seed.isNotEmpty()) sub.sink.trySend(seed)
        subscribers.add(sub)
        sub
    }

    /**
     * Detach [sub]. Idempotent. When the last subscriber leaves, the upstream is closed (ending the
     * `tmux attach`, leaving the session alive) — a later [attach] re-opens a fresh one.
     */
    suspend fun detach(sub: Subscriber): Unit = mutex.withLock {
        if (!subscribers.remove(sub)) return@withLock
        sub.sink.close()
        if (subscribers.isEmpty()) {
            upstream?.let(closeUpstream)
            upstream = null
        }
    }

    /** Fan [bytes] out to every current subscriber. Called by [TerminalBridge]'s reader loop. */
    suspend fun broadcast(bytes: ByteArray): Unit = mutex.withLock {
        for (s in subscribers) s.sink.trySend(bytes)
    }

    /** Route input from a subscriber to the single shared upstream (dropped if none is open). */
    suspend fun writeInput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val up = mutex.withLock { upstream }
        up?.write(bytes)
    }

    /** Apply a subscriber resize with the "last active" policy and remember it for re-opens. */
    suspend fun applyResize(cols: Int, rows: Int): Unit = mutex.withLock {
        lastSize = cols to rows
        upstream?.resize(cols, rows)
    }

    /**
     * Called by the reader loop when the upstream reaches EOF on its own (the pane/session died or
     * the attach ended externally) — as opposed to us closing it on the last detach. Drops the dead
     * upstream and closes out every remaining subscriber so clients learn the terminal ended and a
     * subsequent [attach] re-opens. Guarded by identity so a stale reader cannot clobber an
     * already-reopened upstream.
     */
    suspend fun onUpstreamEof(which: PtyHandle): Unit = mutex.withLock {
        if (upstream !== which) return@withLock
        upstream = null
        val gone = subscribers.toList()
        subscribers.clear()
        gone.forEach { it.sink.close() }
    }

    /** Tear everything down (test teardown / daemon shutdown): close subscribers and the upstream. */
    suspend fun shutdown(): Unit = mutex.withLock {
        val gone = subscribers.toList()
        subscribers.clear()
        gone.forEach { it.sink.close() }
        upstream?.let(closeUpstream)
        upstream = null
    }

    /** Current number of attached subscribers (observability; lets tests await transitions). */
    suspend fun subscriberCount(): Int = mutex.withLock { subscribers.size }
}

/**
 * One client's attachment to a session's terminal fan-out. Its [output] delivers the capture-pane
 * seed first, then live deltas; [write] / [resize] act on the shared upstream; [close] detaches
 * (and closes the upstream if it was the last). Obtained only from [TerminalBridge.subscribe] /
 * [Broadcaster.attach] — the constructor is internal so a client cannot fabricate one.
 */
class Subscriber internal constructor(private val broadcaster: Broadcaster) {
    /** This subscriber's private buffer; the send side is driven by [Broadcaster] under its lock. */
    internal val sink: Channel<ByteArray> = Channel(Channel.UNLIMITED)

    /** Terminal output for this client: the capture-pane seed first, then live deltas. */
    val output: ReceiveChannel<ByteArray> get() = sink

    /** Send terminal input from this client to the shared upstream. */
    suspend fun write(bytes: ByteArray): Unit = broadcaster.writeInput(bytes)

    /** Resize the shared upstream (last-active policy). */
    suspend fun resize(cols: Int, rows: Int): Unit = broadcaster.applyResize(cols, rows)

    /** Detach this client; closes the upstream if it was the last subscriber. */
    suspend fun close(): Unit = broadcaster.detach(this)
}
