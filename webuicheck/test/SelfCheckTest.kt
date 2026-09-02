package io.kotgent.webuicheck

import io.kotgent.pty.Subscriber
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

// Proves the harness's real-pty plumbing itself, so a browser-tier failure can be blamed on the Web UI.
class SelfCheckTest {

    @Test
    fun aRealPtyStreamsAndEchoesThroughTheHarnessOwnTerminalBridge() = runBlocking {
        withTimeout(SELF_CHECK_TIMEOUT_MS.milliseconds) {
            val harness = Harness(selfCheckScenario(), webUiDir = null)
            val context = harness.start()
            try {
                expect(context.port > 0) { "the harness server bound no port (reported ${context.port})" }
                val bridge = harness.terminalBridgeFactory(SELF_CHECK_SESSION_ID, harness.background)
                val subscriber = bridge.subscribe(cols = 80, rows = 24)
                try {
                    val banner = receiveUntil(subscriber, SELF_CHECK_BANNER)
                    expect(SELF_CHECK_BANNER in banner) {
                        "the upstream's own output never arrived, got: <$banner>"
                    }
                    subscriber.write("$SELF_CHECK_ECHO\n".encodeToByteArray())
                    val echoed = receiveUntil(subscriber, SELF_CHECK_ECHO)
                    expect(SELF_CHECK_ECHO in echoed) {
                        "input written to the bridge did not come back from the pty, got: <$echoed>"
                    }
                } finally {
                    subscriber.close()
                }
                expect(bridge.subscriberCount() == 0) {
                    "the last subscriber left, so the bridge should hold none"
                }
            } finally {
                harness.stop()
            }
        }
    }

    @Test
    fun theLastSubscriberClosesTheRealUpstreamAndTheNextOneRespawnsIt() = runBlocking {
        withTimeout(SELF_CHECK_TIMEOUT_MS.milliseconds) {
            val harness = Harness(selfCheckScenario(), webUiDir = null)
            try {
                val bridge = harness.terminalBridgeFactory(SELF_CHECK_SESSION_ID, harness.background)
                val first = bridge.subscribe(cols = 80, rows = 24)
                val opened = receiveUntil(first, SELF_CHECK_BANNER)
                expect(SELF_CHECK_BANNER in opened) { "the first attach never saw the upstream, got: <$opened>" }
                first.close()

                val second = bridge.subscribe(cols = 80, rows = 24)
                val reopened = receiveUntil(second, SELF_CHECK_BANNER)
                expect(SELF_CHECK_BANNER in reopened) {
                    "re-attaching did not respawn the upstream, got: <$reopened>"
                }
                second.close()
                bridge.shutdown()
            } finally {
                harness.stop()
            }
        }
    }

    private fun selfCheckScenario(): Scenario = Scenario(
        name = "self-check",
        seed = { _ -> },
        terminalUpstream = listOf("/bin/sh", "-c", "printf '$SELF_CHECK_BANNER\\n'; exec cat"),
    )

    private suspend fun receiveUntil(
        subscriber: Subscriber,
        needle: String,
        timeoutMs: Long = RECEIVE_TIMEOUT_MS,
    ): String {
        val received = StringBuilder()
        withTimeout(timeoutMs.milliseconds) {
            while (needle !in received) received.append(subscriber.output.receive().decodeToString())
        }
        return received.toString()
    }

    private inline fun expect(condition: Boolean, message: () -> String) {
        if (!condition) throw AssertionError(message())
    }

    private companion object {
        const val SELF_CHECK_SESSION_ID = "selfcheck"

        const val SELF_CHECK_BANNER = "kotgent-webuicheck-upstream"

        const val SELF_CHECK_ECHO = "kotgent-webuicheck-echo"

        const val SELF_CHECK_TIMEOUT_MS = 30_000L

        const val RECEIVE_TIMEOUT_MS = 10_000L
    }
}
