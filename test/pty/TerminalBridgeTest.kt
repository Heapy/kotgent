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

class TerminalBridgeTest {

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

    @Test
    fun outputWithAVisibleCursorIsForwardedReadForRead() = bridgeTest { bridge, factory ->
        val sub = bridge.subscribe()

        factory.current.emit("one".encodeToByteArray())
        assertEquals("one", sub.output.receiveText(), "a visible cursor means send now")
        factory.current.emit("two".encodeToByteArray())
        assertEquals("two", sub.output.receiveText(), "reads are not merged when nothing is mid-repaint")

        sub.close()
    }

    @Test
    fun anAppThatKeepsItsCursorHiddenIsForwardedWithoutHolding() = bridgeTest { bridge, factory ->
        val sub = bridge.subscribe()

        factory.current.emit("\u001b[?25l".encodeToByteArray())
        assertEquals("\u001b[?25l", sub.output.receiveText())

        factory.current.emit("rows one".encodeToByteArray())
        factory.current.emit("rows two".encodeToByteArray())
        assertEquals("rows one", sub.output.receiveText(), "an already-hidden cursor does not arm a hold")
        assertEquals("rows two", sub.output.receiveText(), "so the next read is its own frame, not merged")

        sub.close()
    }

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

        assertEquals(listOf("A", "B"), factory.current.written.map { it.decodeToString() })

        a.close()
        b.close()
    }

    @Test
    fun theRestWriteSeamReportsWhetherTheFullPtyWriteCompleted() = bridgeTest { bridge, factory ->
        assertFalse(bridge.write("nobody-home".encodeToByteArray()), "no subscriber = no upstream write")
        assertEquals(0, factory.openCount, "and a bare write never opens one — only a subscriber does")

        val a = bridge.subscribe()
        assertTrue(bridge.write("landed".encodeToByteArray()), "the full pty write returned normally")
        assertEquals(listOf("landed"), factory.current.written.map { it.decodeToString() })

        factory.current.failWrites = true
        assertFalse(bridge.write("thrown".encodeToByteArray()), "a thrown write did not confirm full completion")

        factory.current.failWrites = false
        factory.current.failWritesAfterBytes = 4
        assertFalse(bridge.write("prefix-tail".encodeToByteArray()), "a partial pty write did not complete")
        assertEquals(
            "pref",
            factory.current.written.last().decodeToString(),
            "the false result can coexist with a prefix already present upstream",
        )

        factory.current.failWritesAfterBytes = null
        assertTrue(bridge.write(ByteArray(0)), "an empty write has nothing to deliver and nothing to lose")

        a.close()
    }

    @Test
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    fun lastDetachWaitsForAnInFlightUpstreamWriteBeforeClosingItsHandle() = bridgeTest { bridge, factory ->
        // A dedicated writer and barriers force the fd-write/last-detach interleaving deterministically.
        val sub = bridge.subscribe()
        val up = factory.current
        val writeEntered = CompletableDeferred<Unit>()
        val prepareReleasedWrite = CompletableDeferred<Unit>()
        val writeSawPrepare = CompletableDeferred<Unit>()
        val allowWriteReturn = CompletableDeferred<Unit>()
        up.beforeWrite = {
            writeEntered.complete(Unit)
            runBlocking {
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
        b.resize(120, 50)

        val up = factory.current
        assertEquals(listOf(100 to 40, 120 to 50), up.resizes)
        assertEquals(120 to 50, up.resizes.last(), "the last active resize is the upstream's current size")

        a.close()
        b.close()
    }

    @Test
    fun aSubscriberThatDeclaresItsSizeGetsTheUpstreamOpenedAtThatGeometry() = bridgeTest { bridge, factory ->
        val a = bridge.subscribe(cols = 143, rows = 53)
        assertEquals(
            listOf(143 to 53),
            factory.current.resizes,
            "the declared geometry is applied to the upstream at open, not after the first frame",
        )

        a.close()
        val b = bridge.subscribe()
        assertEquals(listOf(143 to 53), factory.current.resizes)
        b.close()
    }

    @Test
    fun aSubscriberWithoutAUsableDeclaredSizeLeavesTheUpstreamAtItsDefault() = bridgeTest { bridge, factory ->
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
        a.close()

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

            a.close()
            assertTrue(upstream1.closed, "closing the last subscriber closes the upstream (the attach)")
            assertEquals(0, bridge.subscriberCount())
            assertEquals(1, factory.openCount, "no new upstream is opened just because everyone left")

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
            assertEquals("SEED", a.output.receiveText())
            factory.current.emit("DELTA".encodeToByteArray())
            assertEquals("DELTA", a.output.receiveText())

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
        val stalled = bridge.subscribe()
        val up = factory.current

        repeat(Broadcaster.SUBSCRIBER_BUFFER + 5) { up.emit(byteArrayOf((it and 0xff).toByte())) }

        withTimeout(10_000) { while (bridge.subscriberCount() != 0) yield() }
        assertEquals(0, bridge.subscriberCount(), "the stalled subscriber was disconnected on sustained overflow")

        assertFailsWith<ClosedReceiveChannelException> {
            withTimeout(10_000) { while (true) stalled.output.receive() }
        }

        assertTrue(up.closed, "an overflow disconnect of the LAST subscriber closes the upstream itself")

        stalled.close()
        assertEquals(0, bridge.subscriberCount())
    }

    @Test
    fun anOverflowDisconnectOfTheLastSubscriberLetsAReattachOpenAFreshUpstream() = bridgeTest { bridge, factory ->
        val stalled = bridge.subscribe()
        val upstream1 = factory.current

        repeat(Broadcaster.SUBSCRIBER_BUFFER + 5) { upstream1.emit(byteArrayOf((it and 0xff).toByte())) }
        withTimeout(10_000) { while (bridge.subscriberCount() != 0) yield() }
        assertTrue(upstream1.closed, "the overflow disconnect tore the idle upstream down")

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

        upstream1.eof()

        assertFailsWith<ClosedReceiveChannelException> {
            withTimeout(5_000) { while (true) a.output.receive() }
        }
        assertEquals(0, bridge.subscriberCount(), "a natural EOF detaches the remaining subscribers")

        val b = bridge.subscribe()
        assertEquals(2, factory.openCount)
        b.close()
    }

}
