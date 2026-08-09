package io.kotgent.cli

import io.kotgent.core.PaneId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.getuid

/**
 * "Which kotgent pane am I running in?" — the CLI's half of the pane-header protocol.
 *
 * ## Why the socket check is the whole point
 * `$TMUX_PANE` is just `%<n>`, and **pane ids are unique per tmux SERVER, not globally**. An agent that
 * blindly sent its `%2` from the operator's own tmux would have the daemon resolve it against kotgent's
 * `-L kotgent` server and attribute the link to a completely unrelated session. So the pane id is
 * reported ONLY when `$TMUX`'s socket path is kotgent's:
 *
 * ```
 * $TMUX = <socket-path>,<server-pid>,<session-index>
 * kotgent's socket path = ${TMUX_TMPDIR:-/tmp}/tmux-<uid>/kotgent
 * ```
 *
 * [TMUX_SOCKET] is only the `-L` LABEL; the path above is what tmux actually binds, which is why this
 * reconstructs it rather than matching the label.
 *
 * The environment lookup and the uid are injected so every branch — kotgent's socket, a foreign socket,
 * `$TMUX_TMPDIR` honoured, `$TMUX` absent, a malformed `$TMUX_PANE`, both absent — is testable without a
 * tmux server.
 *
 * Bodies are [TODO] on purpose: Task 18 of the task-backlog plan implements this file.
 */
object TmuxSelf {

    /**
     * The socket path kotgent's tmux server binds for this [uid]:
     * `${TMUX_TMPDIR:-/tmp}/tmux-<uid>/<label>`, with any trailing slash on `$TMUX_TMPDIR` collapsed.
     */
    fun kotgentSocketPath(env: (String) -> String?, uid: Int): String =
        TODO("Task 18: reconstruct the -L kotgent socket path")

    /**
     * This process's kotgent pane id, or `null` when it is not inside one — no `$TMUX`, a `$TMUX` naming
     * a different server's socket, a missing or malformed `$TMUX_PANE`.
     */
    fun currentPane(env: (String) -> String? = ::processEnv, uid: Int = currentUid()): PaneId? =
        TODO("Task 18: pane id, gated on the socket path")
}

/** `getenv`, as the injectable lookup [TmuxSelf] takes. */
@OptIn(ExperimentalForeignApi::class)
fun processEnv(name: String): String? = getenv(name)?.toKString()

/** This process's real uid — the `<uid>` in tmux's socket directory. */
@OptIn(ExperimentalForeignApi::class)
fun currentUid(): Int = getuid().toInt()
