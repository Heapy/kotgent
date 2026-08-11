package io.kotgent.pty

import io.kotgent.tmux.Tmux
import io.kotgent.tmux.forcesMouseOn
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import platform.posix.getenv

/**
 * App-side adapter keeps the sysnative PTY free of a dependency on this module's fan-out contract.
 */
class RealPtyHandle(private val pty: Pty) : PtyHandle {
    override val output: ReceiveChannel<ByteArray> get() = pty.output
    override fun write(bytes: ByteArray) = pty.write(bytes)
    override fun resize(cols: Int, rows: Int) = pty.resize(cols, rows)
    override fun prepareClose() = pty.prepareClose()
    override fun close() {
        pty.close()
    }
}

/**
 * Command zero must be absolute because the underlying `posix_spawn` does not search PATH.
 */
val realPtyFactory: PtyFactory = { command, env -> RealPtyHandle(Pty.open(command, env)) }

@OptIn(ExperimentalForeignApi::class)
fun terminalAttachEnv(): Map<String, String> = terminalAttachEnv(
    lang = getenv("LANG")?.toKString(),
    home = getenv("HOME")?.toKString(),
    path = getenv("PATH")?.toKString(),
)

/**
 * Production wiring for one lazy tmux attach. The seed restores tmux-owned client modes that
 * capture-pane omits; UTF-8 is forced independently in argv and environment.
 */
fun terminalBridgeForSession(
    tmux: Tmux,
    id: String,
    scope: CoroutineScope,
    ptyFactory: PtyFactory = realPtyFactory,
    env: Map<String, String> = terminalAttachEnv(),
): TerminalBridge = TerminalBridge(
    upstreamCommand = attachUpstreamCommand(tmux.tmuxPath, tmux.socket, tmux.sessionName(id)),
    seedProvider = { terminalSeed(tmux.capturePane(id), mouseForced = forcesMouseOn(tmux.serverOptions)) },
    ptyFactory = ptyFactory,
    scope = scope,
    env = env,
)
