package io.kotgent.launchd

import io.kotgent.sys.utf8LocaleOrDefault
import io.kotgent.tmux.ProcessResult
import io.kotgent.tmux.ProcessRunner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.getuid
import platform.posix.mkdir
import platform.posix.unlink

class LaunchdException(message: String) : RuntimeException(message)

@OptIn(ExperimentalForeignApi::class)
fun currentUid(): UInt = getuid()

/** Snapshotted during install because launchd does not inherit the login shell's PATH. */
@OptIn(ExperimentalForeignApi::class)
fun currentPath(): String? = getenv("PATH")?.toKString()?.ifEmpty { null }

/**
 * Snapshotted during install because launchd sets no locale and non-UTF-8 tmux clients replace Unicode
 * cells with `_`.
 */
@OptIn(ExperimentalForeignApi::class)
fun currentLang(): String? = getenv("LANG")?.toKString()?.ifEmpty { null }

@OptIn(ExperimentalForeignApi::class)
fun defaultLaunchAgentsDir(): String {
    val home = homeDir()
    return if (home.isNullOrEmpty()) "Library/LaunchAgents" else "$home/Library/LaunchAgents"
}

@OptIn(ExperimentalForeignApi::class)
fun defaultLogDir(): String {
    val home = homeDir()
    return if (home.isNullOrEmpty()) "Library/Logs/kotgent" else "$home/Library/Logs/kotgent"
}

@OptIn(ExperimentalForeignApi::class)
private fun homeDir(): String? = getenv("HOME")?.toKString()?.trimEnd('/')

class LaunchdInstaller(
    private val runner: (List<String>) -> ProcessResult = { ProcessRunner.run(it) },
    private val launchAgentsDir: String = defaultLaunchAgentsDir(),
    private val logDir: String = defaultLogDir(),
    private val label: String = DAEMON_LABEL,
    private val uid: UInt = currentUid(),
    private val pathProvider: () -> String? = ::currentPath,
    private val langProvider: () -> String? = ::currentLang,
) {
    val plistPath: String get() = "${launchAgentsDir.trimEnd('/')}/$label.plist"

    private val domainTarget: String get() = "gui/$uid"

    fun install(binaryPath: String): String {
        mkdirs(launchAgentsDir)
        mkdirs(logDir)
        writeFile(
            plistPath,
            launchAgentPlist(
                binaryPath = binaryPath,
                logDir = logDir,
                label = label,
                path = mergedDaemonPath(pathProvider()),
                lang = utf8LocaleOrDefault(langProvider()),
            ),
        )

        // A missing prior job is harmless; bootout also makes reinstall idempotent.
        runner(listOf("launchctl", "bootout", domainTarget, plistPath))
        val result = runner(listOf("launchctl", "bootstrap", domainTarget, plistPath))
        if (!result.isSuccess) {
            val detail = result.stderr.trim()
            throw LaunchdException(
                "launchctl bootstrap failed (exit ${result.exitCode})" + if (detail.isEmpty()) "" else ": $detail",
            )
        }
        return plistPath
    }

    fun uninstall() {
        runner(listOf("launchctl", "bootout", domainTarget, plistPath))
        unlink(plistPath)
    }

    /** Creates missing components as user-only directories. */
    @OptIn(ExperimentalForeignApi::class)
    private fun mkdirs(path: String) {
        val mode0700 = S_IRUSR or S_IWUSR or S_IXUSR
        val segments = path.split('/').filter { it.isNotEmpty() }
        var prefix = if (path.startsWith("/")) "" else "."
        for (seg in segments) {
            prefix = "$prefix/$seg"
            mkdir(prefix, mode0700.convert())
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeFile(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        val fp = fopen(path, "wb") ?: throw LaunchdException("cannot write $path")
        try {
            if (bytes.isNotEmpty()) {
                bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp) }
            }
        } finally {
            fclose(fp)
        }
    }
}
