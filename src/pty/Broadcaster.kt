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
 * Fan-out over one lazy upstream: first attach opens it, last detach closes it, and the latest resize
 * wins. The subscriber lock also orders each capture seed before live output. Slow subscribers are
 * disconnected on bounded-buffer overflow rather than receiving a corrupt stream.
 *
 * Input uses a separate gate because a full pty write can block. Teardown first unpublishes the handle,
 * then [PtyHandle.prepareClose] unblocks writes without freeing the fd, then drains the gate before final
 * close. This prevents fd reuse under an in-flight write without stalling output fan-out.
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
     * Initial geometry is recorded before open because tmux reads the client's window size at startup;
     * applying it later causes a visible reflow.
     */
    suspend fun attach(size: Pair<Int, Int>? = null): Subscriber = mutex.withLock {
        if (size != null) lastSize = size
        val opened = subscribers.isEmpty()
        if (opened) {
            val up = openUpstream()
            try {
                lastSize?.let { (cols, rows) -> up.resize(cols, rows) }
            } catch (e: Throwable) {
                closeUpstream(up)
                throw e
            }
            upstream = up
        }
        val sub = Subscriber(this)
        try {
            val seed = seedProvider()
            if (seed.isNotEmpty()) sub.sink.trySend(seed)
        } catch (e: Throwable) {
            if (opened) closeUpstreamLocked()
            throw e
        }
        subscribers.add(sub)
        if (opened) {
            writableUpstream.store(upstream)
        }
        sub
    }

    suspend fun detach(sub: Subscriber): Unit = mutex.withLock {
        val removed = subscribers.remove(sub)
        if (removed) sub.sink.close()
        // Overflow may already have removed this subscriber but still left teardown for detach.
        if (subscribers.isEmpty()) closeUpstreamLocked()
    }

    /**
     * Overflow closes the slow subscriber instead of dropping terminal bytes. If none remain, teardown
     * happens here because a wedged client may never call detach.
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
     * False means full-write completion was not observed, either because no upstream existed or because
     * a write failed after possibly delivering a prefix. It never means retrying the whole body is safe.
     */
    suspend fun writeInput(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        return upstreamIoMutex.withLock {
            val up = writableUpstream.load() ?: return@withLock false
            runCatching { up.write(bytes) }.isSuccess
        }
    }

    suspend fun applyResize(cols: Int, rows: Int): Unit = mutex.withLock {
        lastSize = cols to rows
        upstream?.resize(cols, rows)
    }

    /**
     * Identity guarding prevents a stale reader from closing a reopened upstream. Natural EOF still
     * requires close to release the master fd, reader thread, and zombie child.
     */
    suspend fun onUpstreamEof(which: PtyHandle): Unit = mutex.withLock {
        if (upstream !== which) return@withLock
        val gone = subscribers.toList()
        subscribers.clear()
        gone.forEach { it.sink.close() }
        closeUpstreamLocked()
    }

    suspend fun shutdown(): Unit = mutex.withLock {
        val gone = subscribers.toList()
        subscribers.clear()
        gone.forEach { it.sink.close() }
        closeUpstreamLocked()
    }

    /**
     * Clear the logical handle first, then finish two-phase cleanup non-cancellably. This prevents
     * double-close and ensures a throwing or cancelled detach cannot leak behind a later reattach.
     */
    private suspend fun closeUpstreamLocked() {
        val up = upstream ?: return
        upstream = null
        writableUpstream.store(null)
        withContext(NonCancellable) {
            up.prepareClose()
            upstreamIoMutex.withLock {}
            closeUpstream(up)
        }
    }

    suspend fun subscriberCount(): Int = mutex.withLock { subscribers.size }

    companion object {
        /** Bounded per subscriber; sustained overflow triggers disconnect. */
        const val SUBSCRIBER_BUFFER: Int = 1024
    }
}

class Subscriber internal constructor(private val broadcaster: Broadcaster) {
    internal val sink: Channel<ByteArray> = Channel(Broadcaster.SUBSCRIBER_BUFFER)

    val output: ReceiveChannel<ByteArray> get() = sink

    /** A subscriber keeps the upstream open, so it has no useful action for writeInput's Boolean. */
    suspend fun write(bytes: ByteArray) {
        broadcaster.writeInput(bytes)
    }

    suspend fun resize(cols: Int, rows: Int): Unit = broadcaster.applyResize(cols, rows)

    suspend fun close(): Unit = broadcaster.detach(this)
}
