package io.kotgent.pty

import io.kotgent.tmux.Tmux
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import platform.posix.getenv

/**
 * The production [PtyHandle]: a thin adapter over the cinterop-backed [Pty] (Task 2, `sysnative`).
 *
 * Kept as an adapter rather than making [Pty] implement [PtyHandle] directly so `sysnative` stays
 * free of any app-module dependency (the [PtyHandle] contract lives in the app), and so this — the
 * *only* app file that references [Pty], and therefore our custom cinterop — is a single, obvious
 * seam. Because touching [Pty] from the TEST binary throws `IrLinkageError` (KT-78062), this class
 * and [realPtyFactory] must never be constructed from a unit test; unit tests use a pure-Kotlin
 * fake factory. The real path runs in the `ptycheck` module's MAIN binary (which does link the
 * cinterop), driven from the suite by `PtyTest`.
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
 * Production [PtyFactory]: open a real [Pty] running [command] (with child [env]) on its slave side
 * and wrap it as a [PtyHandle]. `command[0]` must be an absolute executable path — [Pty] spawns via
 * `posix_spawn` (no `PATH` search), so callers pass e.g. `/opt/homebrew/bin/tmux` (which
 * [Tmux.tmuxPath] resolves).
 */
val realPtyFactory: PtyFactory = { command, env -> RealPtyHandle(Pty.open(command, env)) }

/**
 * The environment handed to the `tmux attach` upstream child. `tmux attach` needs at least `TERM`
 * to build a terminal description — with an empty environment it fails ("missing or unsuitable
 * terminal") and the child exits immediately, EOFing the upstream so the browser/CLI terminal shows
 * "[terminal disconnected]". The attach is a shared transport for xterm.js and CLI clients, not the
 * daemon's own terminal, so its `TERM` must be stable and portable: inheriting values such as
 * `xterm-ghostty` also requires a custom `TERMINFO` path that a launchd daemon may not have. Use the
 * system-provided `xterm-256color` entry and inherit only `HOME`/`PATH`/`LANG`. Identity is never
 * derived from inherited env.
 */
@OptIn(ExperimentalForeignApi::class)
fun terminalAttachEnv(): Map<String, String> {
    val env = LinkedHashMap<String, String>()
    env["TERM"] = "xterm-256color"
    getenv("HOME")?.toKString()?.ifBlank { null }?.let { env["HOME"] = it }
    env["PATH"] = getenv("PATH")?.toKString()?.ifBlank { null }
        ?: "/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
    getenv("LANG")?.toKString()?.ifBlank { null }?.let { env["LANG"] = it }
    return env
}

/**
 * Build a lazy [TerminalBridge] for the logical session [id] over [tmux]: the upstream is
 * `tmux -L <socket> attach -t kt-<id>` and the per-subscriber seed is `capture-pane -e` on that
 * session. This is the production wiring; unit tests construct [TerminalBridge] directly with a
 * fake factory and fake seed instead.
 *
 * Note: driving a real `tmux attach` through [Pty] also depends on the child acquiring a
 * *controlling* terminal — see the note in [Pty.open] (Task 2). Because of KT-78062 that end-to-end
 * path cannot run in a test binary; it is checked for real in the `ptycheck` module's main binary.
 */
fun terminalBridgeForSession(
    tmux: Tmux,
    id: String,
    scope: CoroutineScope,
    ptyFactory: PtyFactory = realPtyFactory,
    env: Map<String, String> = terminalAttachEnv(),
): TerminalBridge = TerminalBridge(
    upstreamCommand = listOf(tmux.tmuxPath, "-L", tmux.socket, "attach", "-t", tmux.sessionName(id)),
    seedProvider = { tmux.capturePane(id).encodeToByteArray() },
    ptyFactory = ptyFactory,
    scope = scope,
    env = env,
)
