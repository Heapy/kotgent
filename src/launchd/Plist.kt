package io.kotgent.launchd

import io.kotgent.sys.DEFAULT_UTF8_LOCALE

/**
 * The launchd `Label` for kotgent's per-user daemon agent — the reverse-DNS service id. This is the
 * plist file's basename (`io.kotgent.daemon.plist`) and the handle `launchctl` addresses the job by.
 */
const val DAEMON_LABEL: String = "io.kotgent.daemon"

/**
 * The **fallback minimum** `PATH` for the daemon under launchd — the Apple-silicon Homebrew prefix
 * (`/opt/homebrew/bin`) plus the standard system bins. A launchd agent starts with a *minimal*
 * environment (no login shell has run), so without an explicit `PATH` the daemon would find almost
 * nothing.
 *
 * This is **not** the PATH normally shipped: `kotgent install` snapshots the caller's real login `PATH`
 * and merges it in via [mergedDaemonPath], so kotgent-launched agents inherit the same environment a
 * terminal has (finding `~/.local/bin`, an nvm dir, `~/go/bin`, etc.). This constant is only the floor
 * used when the captured PATH is null or empty.
 */
const val DAEMON_DEFAULT_PATH: String = "/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"

/**
 * Merge the caller's captured `PATH` with [DAEMON_DEFAULT_PATH] for use in the daemon's launchd plist.
 *
 * Pure, no I/O. The captured entries win position (they come first, in their captured order); the
 * default entries are appended; duplicates are dropped preserving first-seen order. Only **absolute**
 * segments survive: empty/blank segments (from `a::b`, leading/trailing `:`) and any non-absolute entry
 * (a relative dir or a `.` — never useful, and a mild risk, in a daemon's PATH) are discarded.
 *
 * If [captured] is `null` or contributes no usable segment, [DAEMON_DEFAULT_PATH] is returned verbatim
 * (the backward-compatible fallback).
 */
fun mergedDaemonPath(captured: String?): String {
    val capturedEntries = (captured ?: "").split(':').filter { it.startsWith("/") }
    if (capturedEntries.isEmpty()) return DAEMON_DEFAULT_PATH
    val merged = LinkedHashSet<String>()
    merged.addAll(capturedEntries)
    merged.addAll(DAEMON_DEFAULT_PATH.split(':'))
    return merged.joinToString(":")
}

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
 * - `EnvironmentVariables.LANG = [lang]` — launchd supplies **no** locale (on macOS the terminal emulator
 *   sets one, and no shell runs here), and everything the daemon spawns inherits that emptiness: a tmux
 *   client without a UTF-8 locale rewrites every non-ASCII cell as `_`, so an agent TUI would render as
 *   underscores. See [io.kotgent.sys.utf8LocaleOrDefault].
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
    lang: String = DEFAULT_UTF8_LOCALE,
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
        appendLine("        <key>LANG</key>")
        appendLine("        <string>${esc(lang)}</string>")
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
