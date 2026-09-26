package io.kotgent.systemd

import io.kotgent.service.DaemonService
import io.kotgent.sys.mergedExecutablePath
import io.kotgent.sys.utf8LocaleOrDefault
import io.kotgent.tmux.ProcessResult
import io.kotgent.tmux.ProcessRunner
import io.kotgent.transport.writePrivateFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.F_OK
import platform.posix.access
import platform.posix.errno
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.strerror
import platform.posix.unlink

class SystemdException(message: String) : RuntimeException(message)

fun systemdUserDirectory(home: String?, configHome: String?): String {
    val config = configHome?.takeIf { it.startsWith('/') }?.trimEnd('/')
        ?: home?.takeIf { it.startsWith('/') }?.trimEnd('/')?.let { "$it/.config" }
        ?: throw SystemdException("HOME or XDG_CONFIG_HOME must be an absolute path")
    return "$config/systemd/user"
}

@OptIn(ExperimentalForeignApi::class)
private fun environment(name: String): String? = getenv(name)?.toKString()?.ifEmpty { null }

@OptIn(ExperimentalForeignApi::class)
class SystemdInstaller(
    private val runner: (List<String>) -> ProcessResult = ProcessRunner::run,
    private val unitDirectory: String = systemdUserDirectory(environment("HOME"), environment("XDG_CONFIG_HOME")),
    private val pathProvider: () -> String? = { environment("PATH") },
    private val langProvider: () -> String? = { environment("LANG") },
) : DaemonService {
    override val description: String = "systemd user service"
    override val definitionPath: String get() = "${unitDirectory.trimEnd('/')}/$DAEMON_UNIT"

    override fun install(binaryPath: String): String {
        // Validate before touching either the filesystem or an existing service.
        val unit = daemonUnit(binaryPath, mergedExecutablePath(pathProvider()), utf8LocaleOrDefault(langProvider()))
        checkManager()
        mkdirs(unitDirectory)
        writePrivateFile(definitionPath, unit.encodeToByteArray())
        systemctl("daemon-reload")
        systemctl("enable", DAEMON_UNIT)
        systemctl("restart", DAEMON_UNIT)
        return definitionPath
    }

    override fun uninstall() {
        if (access(definitionPath, F_OK) != 0 && errno == ENOENT) return
        checkManager()
        // Do not delete the unit when stop/disable failed: keep the managed service recoverable.
        systemctl("disable", "--now", DAEMON_UNIT)
        if (unlink(definitionPath) != 0 && errno != ENOENT) {
            throw SystemdException("cannot remove $definitionPath: ${strerror(errno)?.toKString()}")
        }
        systemctl("daemon-reload")
    }

    private fun checkManager() {
        val result = runner(listOf("systemctl", "--user", "show-environment"))
        if (!result.isSuccess) throw SystemdException(
            "systemd user manager unavailable; run from a login session, or use `kotgent daemon` in the foreground. " +
                result.stderr.trim(),
        )
    }

    private fun systemctl(vararg args: String) {
        val result = runner(listOf("systemctl", "--user") + args)
        if (!result.isSuccess) throw SystemdException(
            "systemctl --user ${args.joinToString(" ")} failed (exit ${result.exitCode}): ${result.stderr.trim()}",
        )
    }

    private fun mkdirs(path: String) {
        require(path.startsWith('/')) { "service directory must be absolute" }
        var prefix = ""
        for (segment in path.split('/').filter(String::isNotEmpty)) {
            prefix += "/$segment"
            if (mkdir(prefix, 0b111000000.convert()) != 0 && errno != EEXIST) {
                throw SystemdException("cannot create $prefix: ${strerror(errno)?.toKString()}")
            }
        }
    }
}
