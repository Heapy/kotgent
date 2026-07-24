package io.kotgent.pty

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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
 *  - [seedProvider] — per attach: the `capture-pane -p -e` snapshot handed to a newly-attached
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
 * [mutex] guards the subscriber set, the lifecycle-facing [upstream] reference and [lastSize]. Every
 * sink is a BOUNDED [Channel] ([SUBSCRIBER_BUFFER]), so [broadcast]'s `trySend` never blocks while
 * holding it. The atomic [writableUpstream] publishes the input handle, while [upstreamIoMutex] encloses
 * each use of that handle and makes write-vs-close mutually exclusive: teardown unpublishes it, calls
 * [PtyHandle.prepareClose] **outside** the gate to terminate the child without freeing the raw fd, then
 * waits for the now-unblocked write before closing. A stale raw fd can therefore never be reused
 * underneath a write. [PtyHandle.write] can block on a full pty, which is why it holds only this
 * input/lifecycle gate — healthy output fan-out, seed ordering and resize remain on [mutex] and are not
 * serialized behind input. Teardown cannot deadlock behind that blocking call: the real
 * [PtyHandle.prepareClose] is bounded and closes the child/slave side that the master write is waiting on.
 *
 * The reader loop calls [broadcast] (which takes [mutex]) while [attach] may hold it across a seed
 * capture; because both serialize on [mutex], a new subscriber's seed is enqueued strictly before any
 * live delta it should see, and no committed delta is lost across the join boundary.
 *
 * [decision] The reader thread starts (in [openUpstream]) before a new subscriber's `capture-pane` seed
 * is taken, but because live delivery is gated by [mutex] (held across seed-enqueue-then-add-to-set),
 * the seed is always FIRST in the subscriber's channel — the ordering invariant holds. The only residual
 * effect is that the initial `tmux attach` full-repaint may be delivered both inside the seed and as an
 * early delta; a duplicated FULL repaint is self-correcting (it overwrites, not appends), so this is
 * cosmetic for a local terminal and does not corrupt the stream.
 *
 * ## Backpressure: bound + disconnect (never drop mid-stream)
 * A stalled authenticated subscriber that stops draining its channel must not accumulate terminal output
 * until the daemon OOMs. Sinks are bounded; on sustained overflow [broadcast] DISCONNECTS that one slow
 * subscriber (closes its channel — its WS then drops) rather than dropping bytes mid-stream (which would
 * corrupt its terminal) or growing without bound. Healthy subscribers are unaffected — and if the
 * disconnect empties the set, the 1→0 teardown runs right there (a wedged client may never detach).
 */
@OptIn(ExperimentalAtomicApi::class)
class Broadcaster(
    private val openUpstream: () -> PtyHandle,
    private val closeUpstream: (PtyHandle) -> Unit,
    private val seedProvider: () -> ByteArray,
) {
    private val mutex = Mutex()
    private val upstreamIoMutex = Mutex()
    private val subscribers = mutableListOf<Subscriber>()
    private var upstream: PtyHandle? = null
    private val writableUpstream = AtomicReference<PtyHandle?>(null)
    private var lastSize: Pair<Int, Int>? = null

    /**
     * Attach a new subscriber. If it is the first, the upstream is opened first (and the last
     * known size re-applied). The subscriber is pre-seeded with the current [seedProvider] snapshot
     * before any live delta, then added to the fan-out set.
     *
     * [size] is the joining client's own geometry, when it knows it up front (the terminal WS carries
     * it as `?cols=&rows=`). Recorded as the new "last active" size **before** the upstream is opened,
     * so a `tmux attach` reads the subscriber's real size from `TIOCGWINSZ` at startup instead of
     * being born at the pty's default and reflowing the agent's TUI a few milliseconds later. A client
     * that cannot declare a size still gets the old behaviour: open at the default, correct via the
     * first resize frame.
     */
    suspend fun attach(size: Pair<Int, Int>? = null): Subscriber = mutex.withLock {
        if (size != null) lastSize = size
        val opened = subscribers.isEmpty()
        if (opened) {
            val up = openUpstream()
            try {
                // Re-apply the last active geometry so a re-opened upstream matches what clients expect.
                lastSize?.let { (cols, rows) -> up.resize(cols, rows) }
            } catch (e: Throwable) {
                // Resize failed before the upstream was published: close it so no orphaned pty + reader
                // thread leaks with zero subscribers.
                closeUpstream(up)
                throw e
            }
            upstream = up
        }
        val sub = Subscriber(this)
        try {
            // Seed BEFORE registering for live deltas (both under this lock) so the snapshot is first
            // in the subscriber's channel and subsequent broadcasts append after it, in order.
            val seed = seedProvider()
            if (seed.isNotEmpty()) sub.sink.trySend(seed)
        } catch (e: Throwable) {
            // Seed capture failed. If THIS attach opened the upstream, roll it back — the subscriber was
            // never added, so nothing would otherwise close the just-opened upstream.
            if (opened) closeUpstreamLocked()
            throw e
        }
        subscribers.add(sub)
        if (opened) {
            // Publish for input only after the subscriber exists. A concurrent REST write before
            // subscribe() completes must still observe the lazy bridge as having no writable upstream.
            writableUpstream.store(upstream)
        }
        sub
    }

    /**
     * Detach [sub]. Idempotent. When the last subscriber leaves, the upstream is closed (ending the
     * `tmux attach`, leaving the session alive) — a later [attach] re-opens a fresh one.
     */
    suspend fun detach(sub: Subscriber): Unit = mutex.withLock {
        val removed = subscribers.remove(sub)
        if (removed) sub.sink.close()
        // Close the upstream once the last subscriber is gone — even when THIS sub was already removed by
        // a broadcast-overflow disconnect (its later detach must still drive the 1→0 upstream teardown).
        if (subscribers.isEmpty()) closeUpstreamLocked()
    }

    /**
     * Fan [bytes] out to every current subscriber. Called by [TerminalBridge]'s reader loop. A subscriber
     * whose bounded channel is full has stalled: it is DISCONNECTED (its channel closed, so its WS drops)
     * rather than dropping bytes into its stream (which would corrupt its terminal) or letting an
     * unbounded backlog OOM the daemon.
     *
     * An overflow disconnect that empties the set runs the 1→0 teardown HERE rather than waiting for the
     * dropped subscriber's own [detach]: a stalled client is exactly the one that may never detach (its
     * WS is wedged), and an upstream left open with zero subscribers leaks the pty + reader thread AND
     * would be silently overwritten by the next [attach] (two live upstreams → mixed terminal streams).
     */
    suspend fun broadcast(bytes: ByteArray): Unit = mutex.withLock {
        var overflowed: MutableList<Subscriber>? = null
        for (s in subscribers) {
            if (s.sink.trySend(bytes).isFailure) {
                (overflowed ?: ArrayList<Subscriber>().also { overflowed = it }).add(s)
            }
        }
        val dropped = overflowed
        if (dropped != null) {
            dropped.forEach { s ->
                subscribers.remove(s)
                s.sink.close()
            }
            if (subscribers.isEmpty()) closeUpstreamLocked()
        }
    }

    /**
     * Route input from a subscriber to the single shared upstream. [PtyHandle.write] runs under the
     * dedicated [upstreamIoMutex], not the subscriber/output [mutex]: a pty write can block on a full
     * input queue, so it must not serialize healthy output fan-out. Teardown atomically clears
     * [writableUpstream], prepares the handle to unblock I/O without freeing its fd, then drains this
     * gate before final close. It therefore either waits for this whole write or makes a later write
     * observe no upstream; close can never free and recycle the raw fd while this call is using it.
     *
     * **Returns whether the full pty write completed without throwing** — `false` when there was no
     * upstream to write to (the lazy bridge's default: with zero subscribers no `tmux attach` is open,
     * see [TerminalBridge.write]) or when the write threw (for example, on a racing close). Those two
     * false cases are not equivalent: no upstream means zero bytes were written, while
     * [PtyHandle.write] may successfully write a prefix before a later syscall throws. Thus `false`
     * means "full pty write completion was not observed", never "safe to retry the whole body".
     * Normal return proves only that the pty accepted the full body, not that the pane's process
     * consumed it. The programmatic
     * `POST /sessions/{id}/input` seam reports that uncertainty; the interactive terminal WS ignores
     * the answer because a subscriber writing always has its own upstream open. Empty input is a
     * vacuous `true`: there was nothing to deliver (and the REST seam refuses it even earlier).
     */
    suspend fun writeInput(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        return upstreamIoMutex.withLock {
            val up = writableUpstream.load() ?: return@withLock false
            runCatching { up.write(bytes) }.isSuccess
        }
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
     *
     * Crucially it also [closeUpstream]s the dead handle: on a natural EOF the child (`tmux attach`)
     * has exited but its pty master fd, reader thread and zombie child are NOT reclaimed unless we
     * close the handle. Without this an authenticated client could leak an fd + OS thread + zombie
     * per attach over the daemon's lifetime.
     */
    suspend fun onUpstreamEof(which: PtyHandle): Unit = mutex.withLock {
        if (upstream !== which) return@withLock
        val gone = subscribers.toList()
        subscribers.clear()
        gone.forEach { it.sink.close() }
        closeUpstreamLocked()
    }

    /** Tear everything down (test teardown / daemon shutdown): close subscribers and the upstream. */
    suspend fun shutdown(): Unit = mutex.withLock {
        val gone = subscribers.toList()
        subscribers.clear()
        gone.forEach { it.sink.close() }
        closeUpstreamLocked()
    }

    /**
     * Close the current upstream EXACTLY ONCE (caller holds [mutex]). The reference is cleared *before*
     * [closeUpstream] runs, so every teardown path — last detach, overflow disconnect, natural EOF,
     * shutdown — is a no-op once another has already fired (and a throwing hook cannot leave a stale
     * handle behind that a later close would double-free). A subsequent [attach] then sees `upstream ==
     * null` and opens a fresh one.
     */
    private suspend fun closeUpstreamLocked() {
        val up = upstream ?: return
        upstream = null
        // Unpublish first so no later writer can obtain the handle. Then finish the cleanup even if the
        // detach/request coroutine was cancelled: the logical state already says there is no upstream,
        // and abandoning the wait here would leak the old pty behind a future re-attach.
        writableUpstream.store(null)
        withContext(NonCancellable) {
            // Terminate the child WITHOUT freeing the master fd. This bounded phase closes the slave and
            // makes a full-queue master write return, so waiting on the gate below cannot deadlock teardown.
            up.prepareClose()
            // Acquiring the gate waits for any write that linearized before the unpublish. Only after it
            // releases is it safe for closeUpstream to free the raw fd.
            upstreamIoMutex.withLock {}
            closeUpstream(up)
        }
    }

    /** Current number of attached subscribers (observability; lets tests await transitions). */
    suspend fun subscriberCount(): Int = mutex.withLock { subscribers.size }

    companion object {
        /**
         * Per-subscriber channel capacity (in fan-out messages, each up to one pty read ≈ 8 KiB). A
         * subscriber that lets this many messages queue without draining has stalled and is disconnected
         * by [broadcast]. Bounds worst-case per-subscriber memory to ≈ this × the read size.
         */
        const val SUBSCRIBER_BUFFER: Int = 1024
    }
}

/**
 * One client's attachment to a session's terminal fan-out. Its [output] delivers the capture-pane
 * seed first, then live deltas; [write] / [resize] act on the shared upstream; [close] detaches
 * (and closes the upstream if it was the last). Obtained only from [TerminalBridge.subscribe] /
 * [Broadcaster.attach] — the constructor is internal so a client cannot fabricate one.
 */
class Subscriber internal constructor(private val broadcaster: Broadcaster) {
    /**
     * This subscriber's private buffer; the send side is driven by [Broadcaster] under its lock. BOUNDED
     * ([Broadcaster.SUBSCRIBER_BUFFER]) so a stalled reader cannot grow it without bound — on sustained
     * overflow [Broadcaster.broadcast] closes it, disconnecting this one slow subscriber.
     */
    internal val sink: Channel<ByteArray> = Channel(Broadcaster.SUBSCRIBER_BUFFER)

    /** Terminal output for this client: the capture-pane seed first, then live deltas. */
    val output: ReceiveChannel<ByteArray> get() = sink

    /**
     * Send terminal input from this client to the shared upstream.
     *
     * Deliberately drops [Broadcaster.writeInput]'s completion answer: a subscriber holds the upstream
     * open by existing, so the "no upstream" case this client could act on cannot happen to it. The
     * REST seam, which writes without being a subscriber, is the caller that needs the Boolean.
     */
    suspend fun write(bytes: ByteArray) {
        broadcaster.writeInput(bytes)
    }

    /** Resize the shared upstream (last-active policy). */
    suspend fun resize(cols: Int, rows: Int): Unit = broadcaster.applyResize(cols, rows)

    /** Detach this client; closes the upstream if it was the last subscriber. */
    suspend fun close(): Unit = broadcaster.detach(this)
}
