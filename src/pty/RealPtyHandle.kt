package io.kotgent.pty

import io.kotgent.tmux.Tmux
import io.kotgent.tmux.forcesMouseOn
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
    override fun prepareClose() = pty.prepareClose()
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
 * The environment handed to the `tmux attach` upstream child: the daemon's own `HOME`/`PATH`/`LANG`
 * fed into the pure [terminalAttachEnv] (which pins `TERM` and forces a UTF-8 `LANG`). This is the
 * only I/O here — the `getenv` reads — so the shaping rules stay unit-testable.
 */
@OptIn(ExperimentalForeignApi::class)
fun terminalAttachEnv(): Map<String, String> = terminalAttachEnv(
    lang = getenv("LANG")?.toKString(),
    home = getenv("HOME")?.toKString(),
    path = getenv("PATH")?.toKString(),
)

/**
 * Build a lazy [TerminalBridge] for the logical session [id] over [tmux]: the upstream is
 * `tmux -f /dev/null -u -L <socket> attach -t kt-<id>` and the per-subscriber seed is
 * `capture-pane -p -e` on that session, composed by the pure [terminalSeed]. This is the production
 * wiring; unit tests construct [TerminalBridge] directly with a fake factory and fake seed instead.
 *
 * The seed is not the raw capture: it is prefixed with [TERMINAL_BRACKETED_PASTE_ENABLE] (tmux enables
 * bracketed paste for every client) and, when [tmux] forces `mouse on`, [TERMINAL_MOUSE_ENABLE].
 * `capture-pane` emits no private-mode sequences and the upstream's enables were broadcast when it
 * opened — a later same-size joiner would otherwise paste multiline input unsafely and lose mouse
 * access to pane history. See [terminalSeed].
 *
 * `-u` forces the attach client to emit UTF-8 regardless of what its locale says — belt to
 * [terminalAttachEnv]'s braces, and independent of whether the requested locale exists on the host.
 * Without a UTF-8 client, tmux replaces every non-ASCII cell with `_` (`tty_check_codeset`) and an
 * agent's box-drawing TUI arrives as underscores.
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
    upstreamCommand = attachUpstreamCommand(tmux.tmuxPath, tmux.socket, tmux.sessionName(id)),
    seedProvider = { terminalSeed(tmux.capturePane(id), mouseForced = forcesMouseOn(tmux.serverOptions)) },
    ptyFactory = ptyFactory,
    scope = scope,
    env = env,
)
