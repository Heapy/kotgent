package io.kotgent.launchd

import io.kotgent.sys.DEFAULT_UTF8_LOCALE
import io.kotgent.sys.mergedExecutablePath

const val DAEMON_LABEL: String = "io.kotgent.daemon"

/**
 * Fallback PATH for launchd's minimal environment. Install normally prepends the caller's captured
 * login PATH through [mergedDaemonPath].
 */
const val DAEMON_DEFAULT_PATH: String = "/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"

/**
 * Captured absolute entries come first; defaults fill missing entries. Relative and empty segments are
 * rejected because a daemon PATH must not depend on its cwd.
 */
fun mergedDaemonPath(captured: String?): String = mergedExecutablePath(captured, DAEMON_DEFAULT_PATH)

/** Crash-loop floor for the KeepAlive job. */
const val DAEMON_THROTTLE_INTERVAL: Int = 10

/**
 * Allow concurrent viewers, PTYs and hook bursts while keeping the default within FD_SETSIZE
 * for select-based consumers.
 */
const val DAEMON_OPEN_FILE_LIMIT: Int = 1024

fun launchAgentPlist(
    binaryPath: String,
    logDir: String,
    label: String = DAEMON_LABEL,
    path: String = DAEMON_DEFAULT_PATH,
    lang: String = DEFAULT_UTF8_LOCALE,
    throttleInterval: Int = DAEMON_THROTTLE_INTERVAL,
    openFileLimit: Int = DAEMON_OPEN_FILE_LIMIT,
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
        appendLine("    <key>SoftResourceLimits</key>")
        appendLine("    <dict>")
        appendLine("        <key>NumberOfFiles</key>")
        appendLine("        <integer>$openFileLimit</integer>")
        appendLine("    </dict>")
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

private fun esc(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
