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
 * Single-threaded by construction: the suite drives it from a `runBlocking` event loop and launches
 * the bridge's reader on that same dispatcher, so the recording lists are only ever touched from
 * one thread. [output] is an UNLIMITED channel, so [emit] never blocks.
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

    /**
     * When true, [write] throws — the stand-in for a pty whose master fd lost its child (a close racing
     * the write). [Broadcaster.writeInput] guards the write, so this exercises the "not delivered"
     * answer rather than a propagated exception.
     */
    var failWrites: Boolean = false

    /** Simulate the child writing [bytes] to the pty. */
    fun emit(bytes: ByteArray) {
        channel.trySend(bytes)
    }

    /** Simulate the child exiting: the master fd EOFs and [output] closes. */
    fun eof() {
        channel.close()
    }

    override fun write(bytes: ByteArray) {
        if (failWrites) throw IllegalStateException("fake pty write failed (closed master fd)")
        written.add(bytes)
    }

    override fun resize(cols: Int, rows: Int) {
        resizes.add(cols to rows)
    }

    override fun close() {
        if (closed) return
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
