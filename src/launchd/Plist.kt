package io.kotgent.launchd

import io.kotgent.sys.DEFAULT_UTF8_LOCALE

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
fun mergedDaemonPath(captured: String?): String {
    val capturedEntries = (captured ?: "").split(':').filter { it.startsWith("/") }
    if (capturedEntries.isEmpty()) return DAEMON_DEFAULT_PATH
    val merged = LinkedHashSet<String>()
    merged.addAll(capturedEntries)
    merged.addAll(DAEMON_DEFAULT_PATH.split(':'))
    return merged.joinToString(":")
}

/** Crash-loop floor for the KeepAlive job. */
const val DAEMON_THROTTLE_INTERVAL: Int = 10

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

private fun esc(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
