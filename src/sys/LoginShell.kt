package io.kotgent.sys

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.X_OK
import platform.posix.access
import platform.posix.getenv
import platform.posix.getpwuid
import platform.posix.getuid

const val DEFAULT_LOGIN_SHELL: String = "/bin/zsh"

/**
 * Candidates must be absolute because tmux changes cwd before exec, and executable so a stale SHELL
 * cannot create a phantom running session.
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

@OptIn(ExperimentalForeignApi::class)
fun currentLoginShell(): String {
    val shellEnv = getenv("SHELL")?.toKString()
    val pwShell = getpwuid(getuid())?.pointed?.pw_shell?.toKString()
    return resolveLoginShell(shellEnv, pwShell) { candidate -> access(candidate, X_OK) == 0 }
}
