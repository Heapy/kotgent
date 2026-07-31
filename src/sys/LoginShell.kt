package io.kotgent.sys

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.X_OK
import platform.posix.access
import platform.posix.getenv
import platform.posix.getpwuid
import platform.posix.getuid

/** The login shell used when neither the environment nor the passwd database has a usable value. */
const val DEFAULT_LOGIN_SHELL: String = "/bin/zsh"

/**
 * Returns the first usable login-shell candidate from [shellEnv] and [pwShell], or
 * [DEFAULT_LOGIN_SHELL] when neither qualifies.
 *
 * A candidate must be non-blank because launchd normally supplies no `SHELL`; absolute because tmux
 * changes to the requested session cwd before exec and a relative program would resolve there; and
 * executable because a stale `SHELL` (for example, a removed Homebrew shell) would otherwise let the
 * daemon persist a phantom `running` row after the pane immediately fails. That is the same failure
 * shape `AgentBinaryNotFoundException` prevents for vendor agents.
 *
 * This function is pure: callers provide [isExecutable] so the selection rule can be tested without
 * consulting the host filesystem.
 */
fun resolveLoginShell(
    shellEnv: String?,
    pwShell: String?,
    isExecutable: (String) -> Boolean,
): String =
    sequenceOf(shellEnv, pwShell)
        .mapNotNull { candidate -> candidate?.takeIf(String::isNotBlank) }
        .firstOrNull { candidate -> candidate.startsWith('/') && isExecutable(candidate) }
        ?: DEFAULT_LOGIN_SHELL

/**
 * Resolves the current user's login shell from `SHELL`, then the passwd database, using only stock
 * `platform.posix` bindings so this remains callable from the Kotlin/Native test binary (KT-78062 does
 * not affect stock bindings).
 */
@OptIn(ExperimentalForeignApi::class)
fun currentLoginShell(): String {
    val shellEnv = getenv("SHELL")?.toKString()
    val pwShell = getpwuid(getuid())?.pointed?.pw_shell?.toKString()
    return resolveLoginShell(shellEnv, pwShell) { candidate -> access(candidate, X_OK) == 0 }
}
