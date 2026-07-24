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

/** Thrown when installing/uninstalling the LaunchAgent fails at the launchd layer (not for I/O EEXIST etc.). */
class LaunchdException(message: String) : RuntimeException(message)

/** The current process's real user id, as a launchd `gui/<uid>` domain uses it. */
@OptIn(ExperimentalForeignApi::class)
fun currentUid(): UInt = getuid()

/**
 * The caller's current `PATH` (`$PATH`), or `null` when unset/empty. `kotgent install` runs in the user's
 * login shell, so this is the full interactive PATH — snapshotted into the daemon's plist so launched
 * agents inherit the same environment a terminal has. Top-level (like [currentUid]/[defaultLogDir]) so the
 * `getenv` opt-in stays off the class surface.
 */
@OptIn(ExperimentalForeignApi::class)
fun currentPath(): String? = getenv("PATH")?.toKString()?.ifEmpty { null }

/**
 * The caller's current `LANG` (`$LANG`), or `null` when unset/empty — snapshotted into the daemon's plist
 * (through [io.kotgent.sys.utf8LocaleOrDefault], which substitutes a UTF-8 default for a missing or
 * non-UTF-8 value) so the daemon and everything it spawns run in a UTF-8 locale. launchd sets none, and a
 * tmux client without one renders every non-ASCII cell as `_`.
 */
@OptIn(ExperimentalForeignApi::class)
fun currentLang(): String? = getenv("LANG")?.toKString()?.ifEmpty { null }

/** `~/Library/LaunchAgents` (per-user launchd agents live here); cwd-relative if `$HOME` is unset. */
@OptIn(ExperimentalForeignApi::class)
fun defaultLaunchAgentsDir(): String {
    val home = homeDir()
    return if (home.isNullOrEmpty()) "Library/LaunchAgents" else "$home/Library/LaunchAgents"
}

/** `~/Library/Logs/kotgent` (the daemon's stdout/stderr under launchd land here). */
@OptIn(ExperimentalForeignApi::class)
fun defaultLogDir(): String {
    val home = homeDir()
    return if (home.isNullOrEmpty()) "Library/Logs/kotgent" else "$home/Library/Logs/kotgent"
}

@OptIn(ExperimentalForeignApi::class)
private fun homeDir(): String? = getenv("HOME")?.toKString()?.trimEnd('/')

/**
 * Installs / removes the kotgent daemon as a per-user launchd LaunchAgent (plan Task 16).
 *
 * ## What install does
 * 1. Ensure `~/Library/LaunchAgents/` and `~/Library/Logs/kotgent/` exist.
 * 2. Write `~/Library/LaunchAgents/io.kotgent.daemon.plist` (from [launchAgentPlist]) — **overwriting**
 *    any existing file, which makes install idempotent (re-running just refreshes the plist).
 * 3. `launchctl bootout gui/<uid> <plist>` — best-effort; a "not loaded" error is ignored so a fresh
 *    install and a reinstall-over-a-loaded-agent both work.
 * 4. `launchctl bootstrap gui/<uid> <plist>` — load (and, per `RunAtLoad`, start) the agent. A non-zero
 *    bootstrap exit is surfaced as a [LaunchdException].
 *
 * ## Injection (so it is unit-testable, and so the daemon is never really started in a test)
 * The `launchctl` invocations go through the injected [runner] (default [ProcessRunner], the stock
 * `platform.posix` `popen` runner). Tests inject a fake runner to assert the exact argv without
 * executing, and inject [launchAgentsDir] / [logDir] / [uid] to write under a throwaway temp path. The
 * `pathProvider` seam (default [currentPath]) lets a test supply a deterministic captured `PATH` that
 * `install` merges (via [mergedDaemonPath]) into the plist's `EnvironmentVariables.PATH`.
 * The `langProvider` seam (default [currentLang]) does the same for `EnvironmentVariables.LANG`.
 * `install` returns after bootstrapping — it does **not** run the daemon in-process (that is the
 * separate `daemon` subcommand the plist's `ProgramArguments` points at).
 */
class LaunchdInstaller(
    private val runner: (List<String>) -> ProcessResult = { ProcessRunner.run(it) },
    private val launchAgentsDir: String = defaultLaunchAgentsDir(),
    private val logDir: String = defaultLogDir(),
    private val label: String = DAEMON_LABEL,
    private val uid: UInt = currentUid(),
    private val pathProvider: () -> String? = ::currentPath,
    private val langProvider: () -> String? = ::currentLang,
) {
    /** Absolute path of the LaunchAgent plist this installer manages. */
    val plistPath: String get() = "${launchAgentsDir.trimEnd('/')}/$label.plist"

    private val domainTarget: String get() = "gui/$uid"

    /**
     * Write the plist for [binaryPath] and (re)bootstrap the agent. Idempotent — overwrites any existing
     * plist and boots out any already-loaded instance first. Returns the written [plistPath].
     */
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

        // Best-effort unload of any prior instance so bootstrap does not fail with "already bootstrapped".
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

    /** Boot out the agent (best-effort) and remove its plist. Idempotent — safe when nothing is installed. */
    fun uninstall() {
        runner(listOf("launchctl", "bootout", domainTarget, plistPath))
        unlink(plistPath) // ignore ENOENT — a missing plist is a fine end state
    }

    // --- filesystem helpers (stock platform.posix, same style as Auth.kt) ----------------------------

    /** `mkdir -p`: create every missing component of [path], ignoring EEXIST. Dirs are `0700` (user-only). */
    @OptIn(ExperimentalForeignApi::class)
    private fun mkdirs(path: String) {
        val mode0700 = S_IRUSR or S_IWUSR or S_IXUSR // Int; .convert() below narrows to mode_t (UShort)
        val segments = path.split('/').filter { it.isNotEmpty() }
        var prefix = if (path.startsWith("/")) "" else "."
        for (seg in segments) {
            prefix = "$prefix/$seg"
            mkdir(prefix, mode0700.convert()) // ignore EEXIST — a pre-existing dir is fine
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
