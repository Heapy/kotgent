package io.kotgent.pty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * One lazy tmux attach fanned out to all session viewers. The first subscriber opens it, the last
 * closes only the client (not the tmux session), and a later subscriber reopens it.
 */
class TerminalBridge(
    private val upstreamCommand: List<String>,
    private val seedProvider: () -> ByteArray,
    private val ptyFactory: PtyFactory,
    private val scope: CoroutineScope,
    private val env: Map<String, String> = emptyMap(),
) {
    private var readerJob: Job? = null

    private val broadcaster = Broadcaster(
        openUpstream = {
            val up = ptyFactory(upstreamCommand, env)
            readerJob = scope.launch { readerLoop(up) }
            up
        },
        closeUpstream = { up ->
            readerJob?.cancel()
            readerJob = null
            up.close()
        },
        seedProvider = seedProvider,
    )

    /**
     * Known geometry is applied at open so tmux starts at the correct size rather than reflowing after
     * the first resize frame.
     */
    suspend fun subscribe(cols: Int? = null, rows: Int? = null): Subscriber =
        broadcaster.attach(if (cols != null && rows != null && cols > 0 && rows > 0) cols to rows else null)

    suspend fun subscriberCount(): Int = broadcaster.subscriberCount()

    /**
     * False means full pty acceptance was not observed; a prefix may already have landed, so callers
     * must not promise whole-body retry safety. Copy-mode clearance is the caller's responsibility.
     */
    suspend fun write(bytes: ByteArray): Boolean = broadcaster.writeInput(bytes)

    suspend fun shutdown(): Unit = broadcaster.shutdown()

    /**
     * Cursor state spans reads because a size-bounded flush may leave the same repaint unfinished.
     */
    private suspend fun readerLoop(up: PtyHandle) {
        val cursor = CursorVisibilityScanner()
        while (true) {
            val first = up.output.receiveCatching().getOrNull() ?: break
            broadcaster.broadcast(coalesceUnfinishedRepaint(first, up.output, cursor))
        }
        broadcaster.onUpstreamEof(up)
    }

    /**
     * Holds only a visible-to-hidden transition until the cursor returns. Already-hidden full-screen
     * apps must pass through immediately or every read incurs the timeout. Time and size bounds both
     * fail toward sending, preventing a hidden cursor from stalling or growing memory without bound.
     */
    private suspend fun coalesceUnfinishedRepaint(
        first: ByteArray,
        source: ReceiveChannel<ByteArray>,
        cursor: CursorVisibilityScanner,
    ): ByteArray {
        val wasVisible = !cursor.hidden
        cursor.accept(first)
        if (!cursor.hidden || !wasVisible) return first

        val parts = mutableListOf(first)
        var size = first.size
        val started = TimeSource.Monotonic.markNow()
        while (cursor.hidden && size < MAX_COALESCED_FRAME) {
            val remaining = HIDDEN_CURSOR_HOLD - started.elapsedNow()
            if (remaining <= Duration.ZERO) break
            // EOF flushes pending bytes here; the next receive drives upstream teardown.
            val next = withTimeoutOrNull(remaining) { source.receiveCatching().getOrNull() } ?: break
            cursor.accept(next)
            parts.add(next)
            size += next.size
        }
        return joinParts(parts, size)
    }

    private fun joinParts(parts: List<ByteArray>, total: Int): ByteArray {
        if (parts.size == 1) return parts[0]
        val joined = ByteArray(total)
        var at = 0
        for (part in parts) {
            part.copyInto(joined, at)
            at += part.size
        }
        return joined
    }

    companion object {
        val HIDDEN_CURSOR_HOLD: Duration = 50.milliseconds

        const val MAX_COALESCED_FRAME: Int = 256 * 1024
    }
}
