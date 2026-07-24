package io.kotgent.pty

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * A pure-Kotlin [PtyHandle] for unit-testing the fan-out logic ([Broadcaster] / [TerminalBridge])
 * without any cinterop — so it runs for real in the test binary despite KT-78062 (see [PtyHandle]).
 *
 * The test drives the "child" side: [emit] pushes bytes onto [output] as if the pty produced them,
 * and [eof] closes [output] to simulate the child exiting. Everything the fan-out does to the
 * handle is recorded: input [written], [resizes], and whether it was [closed].
 *
 * Normally single-threaded: the suite drives it from a `runBlocking` event loop and launches the
 * bridge's reader on that same dispatcher. The close-vs-write regression is the one deliberate
 * exception: it parks [write] on a dedicated writer thread behind cross-thread
 * `CompletableDeferred` barriers before reading any recording list. [output] is an UNLIMITED channel,
 * so [emit] never blocks.
 */
class FakePtyHandle(val command: List<String>) : PtyHandle {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)

    override val output: ReceiveChannel<ByteArray> get() = channel

    /** Input the fan-out routed to this upstream, in order. */
    val written: MutableList<ByteArray> = mutableListOf()

    /** Resizes applied to this upstream, in order (cols to rows). */
    val resizes: MutableList<Pair<Int, Int>> = mutableListOf()

    /** True once [close] has been called (the attach was torn down). */
    var closed: Boolean = false
        private set

    /** True once the two-phase teardown asked the fake to unblock I/O without releasing its handle. */
    var closePrepared: Boolean = false
        private set

    /**
     * When true, [write] throws before recording a byte — the stand-in for a pty whose master fd lost
     * its child before the write began. [Broadcaster.writeInput] guards the write, so this exercises
     * its unconfirmed-completion answer rather than a propagated exception.
     */
    var failWrites: Boolean = false

    /**
     * When non-null, [write] records this many prefix bytes and then throws. The real [Pty.write] loops
     * over partial POSIX writes and can fail on a later syscall, so a caught exception cannot prove
     * zero delivery. Unit tests use this to pin that ambiguity.
     */
    var failWritesAfterBytes: Int? = null

    /**
     * Optional synchronous hook run at the start of [write]. A concurrency regression test parks a
     * write here to prove teardown cannot close this handle until the write returns.
     */
    var beforeWrite: (() -> Unit)? = null

    /**
     * Optional synchronous hook run by [prepareClose]. The close-vs-write regression uses it to model
     * the real child's slave closing and waking a master write that is blocked on a full input queue.
     */
    var afterPrepareClose: (() -> Unit)? = null

    /** Simulate the child writing [bytes] to the pty. */
    fun emit(bytes: ByteArray) {
        channel.trySend(bytes)
    }

    /** Simulate the child exiting: the master fd EOFs and [output] closes. */
    fun eof() {
        channel.close()
    }

    override fun write(bytes: ByteArray) {
        beforeWrite?.invoke()
        if (failWrites) throw IllegalStateException("fake pty write failed (closed master fd)")
        failWritesAfterBytes?.let { count ->
            val prefixSize = count.coerceIn(0, bytes.size)
            if (prefixSize > 0) written.add(bytes.copyOfRange(0, prefixSize))
            throw IllegalStateException("fake pty write failed after $prefixSize bytes")
        }
        written.add(bytes)
    }

    override fun resize(cols: Int, rows: Int) {
        resizes.add(cols to rows)
    }

    override fun prepareClose() {
        closePrepared = true
        afterPrepareClose?.invoke()
    }

    override fun close() {
        if (closed) return
        prepareClose()
        closed = true
        channel.close()
    }
}

/**
 * A [PtyFactory] that mints [FakePtyHandle]s and records every open, so tests can assert the lazy
 * lifecycle: how many upstreams were opened ([openCount]), with what [command], and — because each
 * opened handle is retained in [created] — whether each was later closed.
 *
 * Modeling note: the persistent tmux session is represented by the factory's standing ability to
 * mint a *fresh* attach handle. Closing a handle only ends that one attach; a subsequent [invoke]
 * yields a new live handle — exactly "the session stays alive; a new subscriber re-attaches".
 */
class FakePtyFactory : PtyFactory {
    val created: MutableList<FakePtyHandle> = mutableListOf()

    /** The [env] passed on the most recent open (asserts the production TERM/HOME/PATH seam is threaded). */
    var lastEnv: Map<String, String> = emptyMap()
        private set

    override fun invoke(command: List<String>, env: Map<String, String>): PtyHandle =
        FakePtyHandle(command).also { created.add(it); lastEnv = env }

    /** Number of upstreams opened so far. */
    val openCount: Int get() = created.size

    /** The most recently opened upstream. */
    val current: FakePtyHandle get() = created.last()
}
