package io.kotgent.pty

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the PTY fan-out (Task 9): the lazy upstream lifecycle ([TerminalBridge]) and the
 * multi-subscriber fan-out / input / resize / seed ([Broadcaster]).
 *
 * ## Why these run for real (the KT-78062 split, by design)
 * Our own custom cinterop ([io.kotgent.pty.Pty]) does not link into the test binary on Kotlin
 * Toolchain 0.11.x (KT-78062). So the fan-out depends only on the pure-Kotlin [PtyHandle] +
 * [PtyFactory], and every test below drives it through a [FakePtyFactory] / [FakePtyHandle] — no
 * cinterop, so they execute the *actual* fan-out and lifecycle LOGIC in the test binary. The one
 * genuine end-to-end path (a real `Pty.open("tmux … attach …")` fanned out to two subscribers) runs
 * in the `ptycheck` module's main binary, which does link the cinterop; [io.kotgent.pty.PtyTest]
 * execs it as part of this suite.
 *
 * Each test runs on the `runBlocking` event loop with the bridge's reader launched on that same
 * dispatcher (deterministic, single-threaded), bounded by [withTimeout] (anti-flaky), and tears the
 * bridge + reader scope down in a `finally`.
 */
class TerminalBridgeTest {

    /** Receive one chunk from a subscriber, decoded, bounded so a broken fan-out fails fast. */
    private suspend fun ReceiveChannel<ByteArray>.receiveText(timeoutMs: Long = 5_000): String =
        withTimeout(timeoutMs) { receive().decodeToString() }

    @Test
    fun productionAttachUsesAPortableTermInsteadOfInheritingTheDaemonTerminal() {
        assertEquals(
            "xterm-256color",
            terminalAttachEnv()["TERM"],
            "the shared browser/CLI attach must not depend on a custom terminfo such as xterm-ghostty",
        )
    }

    /**
     * Run [body] with a fresh [TerminalBridge] over a [FakePtyFactory]. The reader loop is launched
     * on an independent child scope (same event-loop dispatcher) that is cancelled on teardown so a
     * test can never leak a reader and hang `runBlocking`.
     */
    private fun bridgeTest(
        seed: () -> ByteArray = { ByteArray(0) },
        command: List<String> = listOf("tmux", "-L", "kotgent", "attach", "-t", "kt-x"),
        body: suspend (bridge: TerminalBridge, factory: FakePtyFactory) -> Unit,
    ) = runBlocking {
        withTimeout(20_000) {
            val factory = FakePtyFactory()
            val readerScope = CoroutineScope(coroutineContext + Job())
            val bridge = TerminalBridge(command, seed, factory, readerScope)
            try {
                body(bridge, factory)
            } finally {
                bridge.shutdown()
                readerScope.cancel()
            }
        }
    }

    @Test
    fun theUpstreamEnvIsThreadedThroughToThePtyFactory() = runBlocking {
        withTimeout(20_000) {
            val factory = FakePtyFactory()
            val readerScope = CoroutineScope(coroutineContext + Job())
            // Production threads TERM/HOME/PATH here so `tmux attach` can build a terminal (an empty env
            // makes it EOF immediately). Assert the bridge passes the env down to the pty factory.
            val env = mapOf("TERM" to "xterm-256color", "HOME" to "/home/x", "PATH" to "/usr/bin")
            val bridge = TerminalBridge(listOf("tmux", "attach"), { ByteArray(0) }, factory, readerScope, env)
            try {
                val a = bridge.subscribe()
                assertEquals(env, factory.lastEnv, "the upstream env reaches the pty factory unchanged")
                a.close()
            } finally {
                bridge.shutdown()
                readerScope.cancel()
            }
        }
    }

    @Test
    fun firstSubscriberOpensTheUpstreamLazilyAndOnlyOnce() = bridgeTest { bridge, factory ->
        assertEquals(0, factory.openCount, "nothing is opened before the first subscriber (lazy)")

        val a = bridge.subscribe()
        assertEquals(1, factory.openCount, "the first subscriber opens the upstream exactly once")
        assertEquals(listOf("tmux", "-L", "kotgent", "attach", "-t", "kt-x"), factory.current.command)
        assertEquals(1, bridge.subscriberCount())

        val b = bridge.subscribe()
        assertEquals(1, factory.openCount, "a second subscriber reuses the single upstream — no re-open")
        assertEquals(2, bridge.subscriberCount())

        a.close()
        b.close()
    }

    @Test
    fun twoSubscribersBothReceiveTheSameOutput() = bridgeTest { bridge, factory ->
        val a = bridge.subscribe()
        val b = bridge.subscribe()

        factory.current.emit("hello".encodeToByteArray())
        assertEquals("hello", a.output.receiveText())
        assertEquals("hello", b.output.receiveText())

        factory.current.emit("world".encodeToByteArray())
        assertEquals("world", a.output.receiveText())
        assertEquals("world", b.output.receiveText())

        a.close()
        b.close()
    }

    /**
     * A repaint reaches the daemon as several pty reads — measured on a live claude pane: 4–6 of them,
     * median 1 KiB, ~4.5 KiB total, delivered within 0–10 ms. Forwarding each as its own frame lets a
     * browser paint the half-drawn states between them, which is what dropped the cursor ~3 times a
     * second. The bridge therefore holds a repaint until the stream says it finished.
     */
    @Test
    fun aRepaintIsDeliveredAsOneFrameInsteadOfTheReadsItArrivedIn() = bridgeTest { bridge, factory ->
        val sub = bridge.subscribe()

        factory.current.emit("\u001b[?25lfirst ".encodeToByteArray())
        factory.current.emit("second ".encodeToByteArray())
        factory.current.emit("third\u001b[?25h".encodeToByteArray())

        assertEquals(
            "\u001b[?25lfirst second third\u001b[?25h",
            sub.output.receiveText(),
            "the whole repaint arrives as ONE frame, so no intermediate state can be painted",
        )
        sub.close()
    }

    /**
     * The hold must stay off the latency path. Ordinary output — shell echo, log lines, the seed — carries
     * no DECTCEM, so it has to be forwarded exactly as before: one read, one frame, no delay. This also
     * keeps [Broadcaster]'s overflow accounting (frames, not bytes) unchanged for a stalled subscriber.
     */
    @Test
    fun outputWithAVisibleCursorIsForwardedReadForRead() = bridgeTest { bridge, factory ->
        val sub = bridge.subscribe()

        factory.current.emit("one".encodeToByteArray())
        assertEquals("one", sub.output.receiveText(), "a visible cursor means send now")
        factory.current.emit("two".encodeToByteArray())
        assertEquals("two", sub.output.receiveText(), "reads are not merged when nothing is mid-repaint")

        sub.close()
    }

    /**
     * A full-screen app routinely hides the cursor for its entire run — measured, htop emits ONE `?25l` at
     * startup and never a `?25h`. Holding on "the cursor is hidden" rather than on "this read hid it" made
     * every later read wait out the full timeout, batching htop's output into 50 ms chunks and turning its
     * scrolling visibly sluggish. It is also pointless: a cursor that is never drawn cannot drop out.
     */
    @Test
    fun anAppThatKeepsItsCursorHiddenIsForwardedWithoutHolding() = bridgeTest { bridge, factory ->
        val sub = bridge.subscribe()

        // The startup hide is a real transition, so this one frame is held (and released by the bound).
        factory.current.emit("\u001b[?25l".encodeToByteArray())
        assertEquals("\u001b[?25l", sub.output.receiveText())

        // From here the cursor simply stays hidden. BOTH reads are emitted before either is asserted, so
        // an armed hold would have a second read available inside its window and would merge the two into
        // one frame — which is exactly the batching that made htop sluggish. Separate frames prove the
        // hold never armed; asserting one read at a time would pass either way, since a hold with nothing
        // to merge only delays.
        factory.current.emit("rows one".encodeToByteArray())
        factory.current.emit("rows two".encodeToByteArray())
        assertEquals("rows one", sub.output.receiveText(), "an already-hidden cursor does not arm a hold")
        assertEquals("rows two", sub.output.receiveText(), "so the next read is its own frame, not merged")

        sub.close()
    }

    /**
     * An app may hide the cursor and simply leave it hidden (or the repaint may never finish). The hold is
     * bounded so that is a delay, never a stall: the bytes go out once [TerminalBridge.HIDDEN_CURSOR_HOLD]
     * expires, and the terminal keeps updating.
     */
    @Test
    fun aHideThatIsNeverAnsweredIsStillDeliveredOnceTheHoldExpires() = bridgeTest { bridge, factory ->
        val sub = bridge.subscribe()

        factory.current.emit("\u001b[?25lhalf a repaint".encodeToByteArray())

        assertEquals(
            "\u001b[?25lhalf a repaint",
            sub.output.receiveText(),
            "an unanswered hide is sent anyway rather than parking the stream",
        )
        sub.close()
    }

    /**
     * The size bound is a memory bound, not a policy: a repaint that never ends must not accumulate. A
     * read that is already over the limit is forwarded without ever entering the hold.
     */
    @Test
    fun aReadPastTheSizeBoundIsForwardedWithoutHolding() = bridgeTest { bridge, factory ->
        val sub = bridge.subscribe()

        val oversized = "\u001b[?25l".encodeToByteArray() +
            ByteArray(TerminalBridge.MAX_COALESCED_FRAME) { 'x'.code.toByte() }
        factory.current.emit(oversized)

        val received = withTimeout(5_000) { sub.output.receive() }
        assertEquals(oversized.size, received.size, "an over-limit read is sent as it came")
        sub.close()
    }

    @Test
    fun inputFromAnySubscriberIsWrittenToTheUpstream() = bridgeTest { bridge, factory ->
        val a = bridge.subscribe()
        val b = bridge.subscribe()

        a.write("A".encodeToByteArray())
        b.write("B".encodeToByteArray())

        // Both subscribers feed the ONE shared upstream, in the order sent.
        assertEquals(listOf("A", "B"), factory.current.written.map { it.decodeToString() })

        a.close()
        b.close()
    }

    /**
     * [TerminalBridge.write] is the `POST /sessions/{id}/input` seam, and its Boolean is the whole
     * reason that endpoint can answer honestly: the bridge is LAZY, so with no terminal subscriber
     * attached there is no `tmux attach` upstream to write into and the bytes are dropped — the
     * COMMON failure, more common than the copy-mode one. It used to be silent, with the REST caller
     * told `ok`; it must be reported so the route can answer `409`. A pty exception is different:
     * full pty write completion was not observed, but a prefix may already have been written.
     */
    @Test
    fun theRestWriteSeamReportsWhetherTheFullPtyWriteCompleted() = bridgeTest { bridge, factory ->
        assertFalse(bridge.write("nobody-home".encodeToByteArray()), "no subscriber = no upstream write")
        assertEquals(0, factory.openCount, "and a bare write never opens one — only a subscriber does")

        val a = bridge.subscribe()
        assertTrue(bridge.write("landed".encodeToByteArray()), "the full pty write returned normally")
        assertEquals(listOf("landed"), factory.current.written.map { it.decodeToString() })

        // A close racing the write makes the underlying pty write throw. The guard keeps that a clean
        // `false` (not a 500), but full pty write completion was not observed.
        factory.current.failWrites = true
        assertFalse(bridge.write("thrown".encodeToByteArray()), "a thrown write did not confirm full completion")

        // The real pty loops over partial writes: one syscall can land a prefix and a later one throw.
        // `false` must therefore never be interpreted as "zero delivered, safe to retry everything".
        factory.current.failWrites = false
        factory.current.failWritesAfterBytes = 4
        assertFalse(bridge.write("prefix-tail".encodeToByteArray()), "a partial pty write did not complete")
        assertEquals(
            "pref",
            factory.current.written.last().decodeToString(),
            "the false result can coexist with a prefix already present upstream",
        )

        // Empty input is vacuously delivered: there was nothing to lose. (The REST seam refuses an
        // empty body even earlier, so it never gets here — see ControlRoutes.)
        factory.current.failWritesAfterBytes = null
        assertTrue(bridge.write(ByteArray(0)), "an empty write has nothing to deliver and nothing to lose")

        a.close()
    }

    /**
     * A write owns the raw pty fd until it returns. If last-detach could close the handle meanwhile,
     * another session's `openpty` could reuse that fd and the stale write could reach the wrong agent
     * while `/input` reported success. The dedicated writer thread makes the interleaving deterministic:
     * park inside [FakePtyHandle.write], attempt last-detach undispatched, and require production
     * [PtyHandle.prepareClose] to unblock the write before final close can take the fd.
     */
    @Test
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    fun lastDetachWaitsForAnInFlightUpstreamWriteBeforeClosingItsHandle() = bridgeTest { bridge, factory ->
        val sub = bridge.subscribe()
        val up = factory.current
        val writeEntered = CompletableDeferred<Unit>()
        val prepareReleasedWrite = CompletableDeferred<Unit>()
        val writeSawPrepare = CompletableDeferred<Unit>()
        val allowWriteReturn = CompletableDeferred<Unit>()
        up.beforeWrite = {
            writeEntered.complete(Unit)
            runBlocking {
                // Bounded independently of the outer test: last-detach is NonCancellable while it
                // drains the I/O gate, so an omitted/no-op prepareClose must fail instead of hanging.
                withTimeout(5_000) { prepareReleasedWrite.await() }
                writeSawPrepare.complete(Unit)
                allowWriteReturn.await()
            }
        }
        up.afterPrepareClose = { prepareReleasedWrite.complete(Unit) }
        val writerContext = newSingleThreadContext("terminal-bridge-write-race")
        try {
            coroutineScope {
                val writing = async(writerContext) { bridge.write("atomic-body".encodeToByteArray()) }
                writeEntered.await()
                val closing = async(start = CoroutineStart.UNDISPATCHED) { sub.close() }
                try {
                    withTimeout(5_000) { writeSawPrepare.await() }
                    assertFalse(closing.isCompleted, "last-detach must wait while the upstream write owns its fd")
                    assertTrue(up.closePrepared, "teardown first asks the child/slave to unblock the write")
                    assertFalse(up.closed, "the in-flight write's raw fd must not be closed or reusable")
                } finally {
                    // This releases only the post-prepare inspection barrier. It cannot make the test
                    // pass when prepareClose failed to unblock the first barrier above.
                    allowWriteReturn.complete(Unit)
                }
                assertTrue(writing.await(), "the write completed before teardown closed the handle")
                closing.await()
                assertTrue(up.closed, "last-detach closes the handle after the write returns")
            }
        } finally {
            allowWriteReturn.complete(Unit)
            writerContext.close()
        }
    }

    @Test
    fun resizeUsesTheLastActivePolicy() = bridgeTest { bridge, factory ->
        val a = bridge.subscribe()
        val b = bridge.subscribe()

        a.resize(100, 40)
        b.resize(120, 50) // most-recent client wins

        val up = factory.current
        assertEquals(listOf(100 to 40, 120 to 50), up.resizes)
        assertEquals(120 to 50, up.resizes.last(), "the last active resize is the upstream's current size")

        a.close()
        b.close()
    }

    @Test
    fun aSubscriberThatDeclaresItsSizeGetsTheUpstreamOpenedAtThatGeometry() = bridgeTest { bridge, factory ->
        // The declared size must be applied to the upstream BEFORE anything else — `tmux attach` reads
        // its geometry from TIOCGWINSZ at startup, so a size that lands after that startup is a reflow
        // (or, when SIGWINCH cannot be delivered, no resize at all).
        val a = bridge.subscribe(cols = 143, rows = 53)
        assertEquals(
            listOf(143 to 53),
            factory.current.resizes,
            "the declared geometry is applied to the upstream at open, not after the first frame",
        )

        // It also becomes the remembered last-active size, so a re-open reuses it.
        a.close()
        val b = bridge.subscribe()
        assertEquals(listOf(143 to 53), factory.current.resizes)
        b.close()
    }

    @Test
    fun aSubscriberWithoutAUsableDeclaredSizeLeavesTheUpstreamAtItsDefault() = bridgeTest { bridge, factory ->
        // A client that cannot know its size up front (or reports a bogus one) must not push a garbage
        // geometry onto the upstream; it corrects itself with a resize frame instead.
        val a = bridge.subscribe(cols = 0, rows = 53)
        val b = bridge.subscribe(cols = 100, rows = null)
        assertEquals(emptyList(), factory.current.resizes)
        a.close()
        b.close()
    }

    @Test
    fun theLastActiveSizeIsReappliedWhenTheUpstreamReopens() = bridgeTest { bridge, factory ->
        val a = bridge.subscribe()
        a.resize(133, 42)
        assertEquals(listOf(133 to 42), factory.current.resizes)
        a.close() // last subscriber leaves -> upstream closed, but the size is remembered

        val b = bridge.subscribe()
        assertEquals(2, factory.openCount, "re-subscribe opens a fresh upstream")
        assertEquals(
            133 to 42,
            factory.current.resizes.first(),
            "the re-opened upstream is sized to the remembered last-active geometry on open",
        )
        b.close()
    }

    @Test
    fun lastSubscriberClosesTheUpstreamButTheSessionStaysAliveAndReattachReopens() =
        bridgeTest { bridge, factory ->
            val a = bridge.subscribe()
            val upstream1 = factory.current
            upstream1.emit("live".encodeToByteArray())
            assertEquals("live", a.output.receiveText())

            // The LAST subscriber leaving tears the `tmux attach` upstream down …
            a.close()
            assertTrue(upstream1.closed, "closing the last subscriber closes the upstream (the attach)")
            assertEquals(0, bridge.subscriberCount())
            assertEquals(1, factory.openCount, "no new upstream is opened just because everyone left")

            // … but the session lives on (Detach). A new subscriber re-attaches over a FRESH upstream
            // (the factory can still mint one — that is what "the session stays alive" means here).
            val b = bridge.subscribe()
            assertEquals(2, factory.openCount, "a new subscriber re-opens a fresh upstream (re-attach)")
            val upstream2 = factory.current
            assertTrue(upstream1 !== upstream2, "the re-attach is a brand-new upstream, not the closed one")
            assertFalse(upstream2.closed)

            upstream2.emit("again".encodeToByteArray())
            assertEquals("again", b.output.receiveText())

            b.close()
            assertTrue(upstream2.closed, "and the fresh upstream closes when its last subscriber leaves")
        }

    @Test
    fun aNewSubscriberGetsTheCapturePaneSeedThenLiveDeltas() =
        bridgeTest(seed = { "SEED".encodeToByteArray() }) { bridge, factory ->
            val a = bridge.subscribe()
            // The capture-pane seed is delivered first …
            assertEquals("SEED", a.output.receiveText())
            // … then live deltas.
            factory.current.emit("DELTA".encodeToByteArray())
            assertEquals("DELTA", a.output.receiveText())

            // A subscriber joining later is ALSO seeded (a fresh snapshot) before it sees new deltas.
            val b = bridge.subscribe()
            assertEquals("SEED", b.output.receiveText())
            factory.current.emit("DELTA2".encodeToByteArray())
            assertEquals("DELTA2", a.output.receiveText())
            assertEquals("DELTA2", b.output.receiveText())

            a.close()
            b.close()
        }

    @Test
    fun aStalledSubscriberIsDisconnectedWhenItsBoundedBufferOverflows() = bridgeTest { bridge, factory ->
        val stalled = bridge.subscribe() // never drained
        val up = factory.current

        // Flood past the bounded per-subscriber buffer WITHOUT draining. The subscriber must be
        // disconnected rather than the daemon buffering unboundedly (OOM) or dropping bytes mid-stream
        // (which would corrupt its terminal).
        repeat(Broadcaster.SUBSCRIBER_BUFFER + 5) { up.emit(byteArrayOf((it and 0xff).toByte())) }

        // The reader fans the flood out; once the buffer is full the stalled subscriber is dropped.
        withTimeout(10_000) { while (bridge.subscriberCount() != 0) yield() }
        assertEquals(0, bridge.subscriberCount(), "the stalled subscriber was disconnected on sustained overflow")

        // Draining it yields the buffered prefix, then the CLOSED signal (it was disconnected cleanly).
        assertFailsWith<ClosedReceiveChannelException> {
            withTimeout(10_000) { while (true) stalled.output.receive() }
        }

        // The disconnect emptied the subscriber set, so the 1→0 teardown runs RIGHT THERE — it must not
        // wait for the stalled client's detach, which may never come (its WS is wedged; that is why it
        // stalled). Otherwise the upstream pty + reader thread leak with nobody watching.
        assertTrue(up.closed, "an overflow disconnect of the LAST subscriber closes the upstream itself")

        // And the (late, or never-arriving) detach of that same subscriber is a clean no-op — no second
        // close of the same handle.
        stalled.close()
        assertEquals(0, bridge.subscriberCount())
    }

    @Test
    fun anOverflowDisconnectOfTheLastSubscriberLetsAReattachOpenAFreshUpstream() = bridgeTest { bridge, factory ->
        val stalled = bridge.subscribe() // never drained -> will overflow and be dropped
        val upstream1 = factory.current

        repeat(Broadcaster.SUBSCRIBER_BUFFER + 5) { upstream1.emit(byteArrayOf((it and 0xff).toByte())) }
        withTimeout(10_000) { while (bridge.subscriberCount() != 0) yield() }
        assertTrue(upstream1.closed, "the overflow disconnect tore the idle upstream down")

        // A later attach must get a BRAND-NEW upstream. Before the fix the dead-but-open upstream was
        // still referenced, so this attach silently overwrote it: an orphaned pty + reader kept running
        // and two upstreams fed the same session.
        val fresh = bridge.subscribe()
        assertEquals(2, factory.openCount, "the re-attach opens a fresh upstream")
        val upstream2 = factory.current
        assertTrue(upstream1 !== upstream2, "the re-attach is a new upstream, not the closed one")
        assertFalse(upstream2.closed)

        upstream2.emit("after".encodeToByteArray())
        assertEquals("after", fresh.output.receiveText())
        fresh.close()
        assertTrue(upstream2.closed, "and the fresh upstream still closes on its last detach")
    }

    @Test
    fun upstreamEofDetachesSubscribersAndAllowsReopen() = bridgeTest { bridge, factory ->
        val a = bridge.subscribe()
        val upstream1 = factory.current
        upstream1.emit("x".encodeToByteArray())
        assertEquals("x", a.output.receiveText())

        // The pane/session dies on its own -> the master EOFs (distinct from us closing on detach).
        upstream1.eof()

        // Draining forces the reader to observe EOF; the subscriber's channel is then closed.
        assertFailsWith<ClosedReceiveChannelException> {
            withTimeout(5_000) { while (true) a.output.receive() }
        }
        assertEquals(0, bridge.subscriberCount(), "a natural EOF detaches the remaining subscribers")

        // A new subscriber re-opens a fresh upstream.
        val b = bridge.subscribe()
        assertEquals(2, factory.openCount)
        b.close()
    }

    // The real end-to-end fan-out (a live `Pty.open("tmux … attach …")` driving real bytes to two
    // subscribers) used to sit here @Ignore'd, because our cinterop cannot be called from a test
    // binary (KT-78062). It now runs for real in `ptycheck/src/Main.kt` — a MAIN binary, where the
    // cinterop does link — and [PtyTest] execs that binary as part of this suite.
}
