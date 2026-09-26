package io.kotgent.systemd

const val DAEMON_UNIT: String = "kotgent.service"

/** Percent is a systemd specifier even inside quotes; values are never passed to a shell. */
internal fun unitQuote(value: String): String {
    require(value.none { it == '\u0000' || it == '\n' || it == '\r' }) { "service values must be single-line strings" }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%") + "\""
}

fun daemonUnit(binaryPath: String, path: String, lang: String): String {
    require(binaryPath.startsWith('/')) { "service executable must be an absolute path" }
    // systemd 249 forbids quotes in the executable token, even after correct unit escaping.
    // env passes the quoted absolute path as an argument and execs it without a shell or extra process.
    return """
        [Unit]
        Description=Kotgent coding session supervisor

        [Service]
        Type=simple
        ExecStart=:/usr/bin/env -- ${unitQuote(binaryPath)} daemon
        WorkingDirectory=%h
        Environment=${unitQuote("PATH=$path")}
        Environment=${unitQuote("LANG=$lang")}
        Restart=always
        RestartSec=10
        TimeoutStopSec=30
        # Only the daemon belongs to this lifecycle; tmux sessions survive its restart or removal.
        KillMode=process
        LimitNOFILE=1024
        StandardOutput=journal
        StandardError=journal
        SyslogIdentifier=kotgent

        [Install]
        WantedBy=default.target
    """.trimIndent() + "\n"
}
