package io.kotgent.pty

import io.kotgent.tmux.Tmux
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Ignore
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
 * Toolchain 0.11.0 (KT-78062). So the fan-out depends only on the pure-Kotlin [PtyHandle] +
 * [PtyFactory], and every test below drives it through a [FakePtyFactory] / [FakePtyHandle] — no
 * cinterop, so they execute the *actual* fan-out and lifecycle LOGIC in the test binary. The one
 * genuine end-to-end path (a real `Pty.open("tmux … attach …")`) is the `@Ignore`d
 * [realTmuxAttachFanOutEndToEnd] below; its coverage is the Task 18 acceptance test.
 *
 * Each test runs on the `runBlocking` event loop with the bridge's reader launched on that same
 * dispatcher (deterministic, single-threaded), bounded by [withTimeout] (anti-flaky), and tears the
 * bridge + reader scope down in a `finally`.
 */
class TerminalBridgeTest {

    /** Receive one chunk from a subscriber, decoded, bounded so a broken fan-out fails fast. */
    private suspend fun ReceiveChannel<ByteArray>.receiveText(timeoutMs: Long = 5_000): String =
        withTimeout(timeoutMs) { receive().decodeToString() }

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

    /**
     * PARKED (@Ignore) — the ONE real end-to-end fan-out: a live `Pty.open("tmux … attach …")`
     * driving real bytes through the real custom cinterop.
     *
     * Ignored because calling our own cinterop from the TEST binary throws `IrLinkageError` on
     * Kotlin Toolchain 0.11.0 (KT-78062) — the same reason [PtyTest] is `@Ignore`d. (Additionally,
     * a real `tmux attach` needs the child to acquire a *controlling* terminal — see the note in
     * [Pty.open], Task 2 — before this would pass even with the cinterop linked.) The real fan-out
     * is validated executably by the Task 18 acceptance test; every other test here covers the
     * fan-out/lifecycle logic through the pure-Kotlin fake. Re-enable once the toolchain links
     * cinterop into test binaries.
     */
    @Test
    @Ignore
    fun realTmuxAttachFanOutEndToEnd() = runBlocking {
        val tmux = Tmux(socket = "kotgent-test")
        if (!tmux.isAvailable()) return@runBlocking
        val id = "bridge-e2e"
        val readerScope = CoroutineScope(coroutineContext + Job())
        try {
            tmux.killSession(id)
            tmux.newSession(id = id, cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)

            val bridge = terminalBridgeForSession(tmux, id, readerScope, realPtyFactory)

            // First subscriber opens the real upstream `tmux -L kotgent-test attach -t kt-bridge-e2e`.
            val a = bridge.subscribe()
            // Its seed is the capture-pane snapshot of the pane (may be blank for a fresh `cat`).
            withTimeout(5_000) { a.output.receive() }

            // Input from the subscriber reaches `cat`, which echoes it back over the fan-out.
            a.write("hello-fanout\n".encodeToByteArray())
            val sb = StringBuilder()
            withTimeout(10_000) {
                while ("hello-fanout" !in sb) sb.append(a.output.receive().decodeToString())
            }
            assertTrue("hello-fanout" in sb.toString())

            // Last subscriber leaving closes the attach; the tmux session (and `cat`) survives.
            a.close()
            assertTrue(tmux.listPanes().any { it.session == "kt-$id" }, "the session outlives the attach")
        } finally {
            readerScope.cancel()
            tmux.killSession(id)
        }
    }
}
