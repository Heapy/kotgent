package io.kotgent.launchd

/**
 * The launchd `Label` for kotgent's per-user daemon agent — the reverse-DNS service id. This is the
 * plist file's basename (`io.kotgent.daemon.plist`) and the handle `launchctl` addresses the job by.
 */
const val DAEMON_LABEL: String = "io.kotgent.daemon"

/**
 * The `PATH` handed to the daemon under launchd. A launchd agent starts with a *minimal* environment
 * (no login shell has run), so the daemon would not otherwise find `tmux` or `claude`. This lists the
 * Apple-silicon Homebrew prefix (`/opt/homebrew/bin`, where tmux/claude typically live) plus the
 * standard system bins.
 */
const val DAEMON_DEFAULT_PATH: String = "/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"

/**
 * Default `ThrottleInterval` (seconds). `KeepAlive` relaunches the daemon if it exits; this floor keeps
 * a crash-loop from hammering — launchd waits at least this long between (re)starts.
 */
const val DAEMON_THROTTLE_INTERVAL: Int = 10

/**
 * Generate the LaunchAgent property-list XML for the kotgent daemon (plan Task 16).
 *
 * This is a **pure** function: given its arguments it returns a deterministic string with no I/O, so it
 * is unit-testable field-by-field. [io.kotgent.launchd.LaunchdInstaller] writes the result to
 * `~/Library/LaunchAgents/<label>.plist`.
 *
 * The emitted job:
 * - `ProgramArguments = [binaryPath, "daemon"]` — runs `kotgent daemon` (the blocking control-plane server).
 * - `RunAtLoad = true` + `KeepAlive = true` — start at login and keep alive (respawn on exit).
 * - `ThrottleInterval` — minimum seconds between (re)starts (crash-loop floor).
 * - `EnvironmentVariables.PATH = [path]` — so the daemon resolves `tmux`/`claude` under launchd's minimal env.
 * - `StandardOutPath` / `StandardErrorPath` — the daemon's stdout/stderr, under [logDir].
 *
 * @param binaryPath absolute path of the kotgent binary to launch (launchd does not expand `~` or `$PATH`).
 * @param logDir absolute directory for the daemon's log files (launchd does not expand `~`); required so the
 *   generator stays pure (no `$HOME` read here — the installer supplies the resolved path).
 */
fun launchAgentPlist(
    binaryPath: String,
    logDir: String,
    label: String = DAEMON_LABEL,
    path: String = DAEMON_DEFAULT_PATH,
    throttleInterval: Int = DAEMON_THROTTLE_INTERVAL,
): String {
    val logs = logDir.trimEnd('/')
    val outPath = "$logs/daemon.out.log"
    val errPath = "$logs/daemon.err.log"
    return buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
        appendLine("""<plist version="1.0">""")
        appendLine("<dict>")
        appendLine("    <key>Label</key>")
        appendLine("    <string>${esc(label)}</string>")
        appendLine("    <key>ProgramArguments</key>")
        appendLine("    <array>")
        appendLine("        <string>${esc(binaryPath)}</string>")
        appendLine("        <string>daemon</string>")
        appendLine("    </array>")
        appendLine("    <key>RunAtLoad</key>")
        appendLine("    <true/>")
        appendLine("    <key>KeepAlive</key>")
        appendLine("    <true/>")
        appendLine("    <key>ThrottleInterval</key>")
        appendLine("    <integer>$throttleInterval</integer>")
        appendLine("    <key>EnvironmentVariables</key>")
        appendLine("    <dict>")
        appendLine("        <key>PATH</key>")
        appendLine("        <string>${esc(path)}</string>")
        appendLine("    </dict>")
        appendLine("    <key>StandardOutPath</key>")
        appendLine("    <string>${esc(outPath)}</string>")
        appendLine("    <key>StandardErrorPath</key>")
        appendLine("    <string>${esc(errPath)}</string>")
        appendLine("</dict>")
        append("</plist>")
    }
}

/** Escape the three characters that are not legal literally in XML element text content. */
private fun esc(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
