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
 * ## The two spellings of one socket, and why the comparison folds them
 * tmux `realpath(3)`s its socket DIRECTORY before it appends the label, and on macOS `/tmp` and `/var`
 * are symlinks into `/private`. Measured on tmux 3.7b: a pane on a `-L kotgent-probe18` server reports
 * `$TMUX = /private/tmp/tmux-501/kotgent-probe18,75453,0`, while the lexical reconstruction above spells
 * the same file `/tmp/tmux-501/kotgent-probe18`. Comparing the two literally would reject **every** real
 * kotgent pane and silently kill the feature on the only supported platform, so both sides go through
 * [foldPrivatePrefix] first. That fold is lexical on purpose — it keeps this object pure and its tests
 * hermetic — which means the one thing it cannot see is a symlink somewhere else in a custom
 * `$TMUX_TMPDIR`. That case fails CLOSED (no pane id, so no auto-link), which is the safe direction: a
 * missing link is an inconvenience, a link attributed to a stranger's pane is a bug.
 */
object TmuxSelf {

    /**
     * The socket path kotgent's tmux server binds for this [uid]:
     * `${TMUX_TMPDIR:-/tmp}/tmux-<uid>/<label>`, with any trailing slash on `$TMUX_TMPDIR` collapsed.
     *
     * An EMPTY `$TMUX_TMPDIR` reads as unset, matching both the `${TMUX_TMPDIR:-/tmp}` shell form above
     * and tmux's own `*s != '\0'` guard.
     */
    fun kotgentSocketPath(env: (String) -> String?, uid: Int): String {
        val tmpdir = env("TMUX_TMPDIR")?.takeIf { it.isNotEmpty() } ?: DEFAULT_TMUX_TMPDIR
        return "${tmpdir.trimEnd('/')}/tmux-$uid/$TMUX_SOCKET"
    }

    /**
     * This process's kotgent pane id, or `null` when it is not inside one — no `$TMUX`, a `$TMUX` naming
     * a different server's socket, a missing or malformed `$TMUX_PANE`.
     *
     * The socket path is everything before the FIRST comma of `$TMUX`; a value that carries no comma at
     * all is compared whole, and a path that itself contains one simply stops matching — both fail closed.
     */
    fun currentPane(env: (String) -> String? = ::processEnv, uid: Int = currentUid()): PaneId? {
        val tmux = env("TMUX")?.takeIf { it.isNotEmpty() } ?: return null
        val socket = tmux.substringBefore(',')
        if (foldPrivatePrefix(socket) != foldPrivatePrefix(kotgentSocketPath(env, uid))) return null
        val pane = env("TMUX_PANE")?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { PaneId(pane) }.getOrNull()
    }

    /** tmux's own fallback when `$TMUX_TMPDIR` is unset or empty (`_PATH_TMP`). */
    private const val DEFAULT_TMUX_TMPDIR: String = "/tmp"

    /**
     * The root-level symlinks into `/private` that can plausibly hold a tmux socket directory: `/tmp`
     * (tmux's default) and `/var` (where macOS's per-user `$TMPDIR` lives, so `TMUX_TMPDIR=$TMPDIR`
     * lands there). Deliberately not "strip any `/private` prefix" — `/private/foo` is an ordinary
     * directory, not a second spelling of `/foo`.
     */
    private val PRIVATE_FIRMLINKS: List<String> = listOf("/tmp", "/var")

    /** `/private/tmp/tmux-501/kotgent` → `/tmp/tmux-501/kotgent`; anything else is returned unchanged. */
    private fun foldPrivatePrefix(path: String): String {
        if (!path.startsWith(PRIVATE_PREFIX)) return path
        val rest = path.removePrefix(PRIVATE_PREFIX)
        return if (PRIVATE_FIRMLINKS.any { rest == it || rest.startsWith("$it/") }) rest else path
    }

    private const val PRIVATE_PREFIX: String = "/private"
}

/** `getenv`, as the injectable lookup [TmuxSelf] takes. */
@OptIn(ExperimentalForeignApi::class)
fun processEnv(name: String): String? = getenv(name)?.toKString()

/** This process's real uid — the `<uid>` in tmux's socket directory. */
@OptIn(ExperimentalForeignApi::class)
fun currentUid(): Int = getuid().toInt()
