package io.kotgent.pty

import io.kotgent.tmux.Tmux
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * The production [PtyHandle]: a thin adapter over the cinterop-backed [Pty] (Task 2, `sysnative`).
 *
 * Kept as an adapter rather than making [Pty] implement [PtyHandle] directly so `sysnative` stays
 * free of any app-module dependency (the [PtyHandle] contract lives in the app), and so this — the
 * *only* app file that references [Pty], and therefore our custom cinterop — is a single, obvious
 * seam. Because touching [Pty] from the TEST binary throws `IrLinkageError` (KT-78062), this class
 * and [realPtyFactory] must never be constructed from a unit test; unit tests use a pure-Kotlin
 * fake factory. The real path is exercised by `./kotlin build`/`run` (main binary links the
 * cinterop) and by the Task 18 acceptance test.
 */
class RealPtyHandle(private val pty: Pty) : PtyHandle {
    override val output: ReceiveChannel<ByteArray> get() = pty.output
    override fun write(bytes: ByteArray) = pty.write(bytes)
    override fun resize(cols: Int, rows: Int) = pty.resize(cols, rows)
    override fun close() {
        pty.close()
    }
}

/**
 * Production [PtyFactory]: open a real [Pty] running [command] on its slave side and wrap it as a
 * [PtyHandle]. `command[0]` must be an absolute executable path — [Pty] spawns via `posix_spawn`
 * (no `PATH` search), so callers pass e.g. `/opt/homebrew/bin/tmux` (which [Tmux.tmuxPath] resolves).
 */
val realPtyFactory: PtyFactory = { command -> RealPtyHandle(Pty.open(command)) }

/**
 * Build a lazy [TerminalBridge] for the logical session [id] over [tmux]: the upstream is
 * `tmux -L <socket> attach -t kt-<id>` and the per-subscriber seed is `capture-pane -e` on that
 * session. This is the production wiring; unit tests construct [TerminalBridge] directly with a
 * fake factory and fake seed instead.
 *
 * Note: driving a real `tmux attach` through [Pty] also depends on the child acquiring a
 * *controlling* terminal — see the note in [Pty.open] (Task 2). That, together with KT-78062, is
 * why the real end-to-end bridge is validated by the Task 18 acceptance test rather than a unit test.
 */
fun terminalBridgeForSession(
    tmux: Tmux,
    id: String,
    scope: CoroutineScope,
    ptyFactory: PtyFactory = realPtyFactory,
): TerminalBridge = TerminalBridge(
    upstreamCommand = listOf(tmux.tmuxPath, "-L", tmux.socket, "attach", "-t", tmux.sessionName(id)),
    seedProvider = { tmux.capturePane(id).encodeToByteArray() },
    ptyFactory = ptyFactory,
    scope = scope,
)
