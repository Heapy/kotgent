package io.kotgent.cli

import io.kotgent.core.PaneId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.getuid

/**
 * Resolves the current pane only when `$TMUX` names kotgent's server. Pane ids are server-local, so
 * accepting one from another tmux server could attribute a task to an unrelated session. On macOS tmux
 * canonicalizes `/tmp` and `/var` through `/private`; [foldPrivatePrefix] equates only those known
 * spellings. Other symlink aliases fail closed with no automatic pane attribution.
 */
object TmuxSelf {

    /** Empty `$TMUX_TMPDIR` follows tmux's unset behavior and falls back to `/tmp`. */
    fun kotgentSocketPath(env: (String) -> String?, uid: Int): String {
        val tmpdir = env("TMUX_TMPDIR")?.takeIf { it.isNotEmpty() } ?: DEFAULT_TMUX_TMPDIR
        return "${tmpdir.trimEnd('/')}/tmux-$uid/$TMUX_SOCKET"
    }

    /** Returns null for an absent, foreign, or malformed tmux context. */
    fun currentPane(env: (String) -> String? = ::processEnv, uid: Int = currentUid()): PaneId? {
        val tmux = env("TMUX")?.takeIf { it.isNotEmpty() } ?: return null
        val socket = tmux.substringBefore(',')
        if (foldPrivatePrefix(socket) != foldPrivatePrefix(kotgentSocketPath(env, uid))) return null
        val pane = env("TMUX_PANE")?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { PaneId(pane) }.getOrNull()
    }

    private const val DEFAULT_TMUX_TMPDIR: String = "/tmp"

    /** Do not fold arbitrary `/private` paths: only these roots are known macOS aliases. */
    private val PRIVATE_FIRMLINKS: List<String> = listOf("/tmp", "/var")

    private fun foldPrivatePrefix(path: String): String {
        if (!path.startsWith(PRIVATE_PREFIX)) return path
        val rest = path.removePrefix(PRIVATE_PREFIX)
        return if (PRIVATE_FIRMLINKS.any { rest == it || rest.startsWith("$it/") }) rest else path
    }

    private const val PRIVATE_PREFIX: String = "/private"
}

@OptIn(ExperimentalForeignApi::class)
fun processEnv(name: String): String? = getenv(name)?.toKString()

@OptIn(ExperimentalForeignApi::class)
fun currentUid(): Int = getuid().toInt()
