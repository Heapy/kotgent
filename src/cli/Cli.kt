package io.kotgent.cli

import io.kotgent.versionLine
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.stderr

/** The default local daemon port. `0x6b74` = ASCII "kt" (kotgent) — a stable, mnemonic high port. */
const val DEFAULT_PORT: Int = 0x6b74 // 27508

/** The tmux `-L` socket label the daemon and its sessions live on. */
const val TMUX_SOCKET: String = "kotgent"

/**
 * The daemon's control-plane base URL. Defaults to `http://127.0.0.1:<DEFAULT_PORT>`, overridable with
 * `$KOTGENT_PORT` (so a daemon bound elsewhere is still reachable without recompiling).
 */
@OptIn(ExperimentalForeignApi::class)
fun defaultBaseUrl(): String {
    val port = getenv("KOTGENT_PORT")?.toKString()?.toIntOrNull() ?: DEFAULT_PORT
    return "http://127.0.0.1:$port"
}

/**
 * A parsed CLI invocation (plan Task 15). Parsing is a pure `argv -> CliCommand` mapping ([parseArgs])
 * with no I/O, so the whole surface is unit-testable; [runCli] then performs the side effects.
 */
sealed interface CliCommand {
    /** `--version` / `version` — print the version line. */
    data object Version : CliCommand

    /** `--help` / `help` / no args — print usage. */
    data object Help : CliCommand

    /** `daemon [--port N]` — run the control-plane server (the launchd entry point). */
    data class Daemon(val port: Int) : CliCommand

    /** `start <agent> [cwd] [--name N] [--tag T]...` — start a session ([cwd] null = current dir). */
    data class Start(val agent: String, val cwd: String?, val name: String?, val tags: List<String>) : CliCommand

    /** `list` / `ls` — list sessions. */
    data object ListSessions : CliCommand

    /** `stop <id>`. */
    data class Stop(val id: String) : CliCommand

    /** `resume <id>`. */
    data class Resume(val id: String) : CliCommand

    /** `interrupt <id>`. */
    data class Interrupt(val id: String) : CliCommand

    /** `attach <id>` — raw terminal passthrough. */
    data class Attach(val id: String) : CliCommand

    /** `install` — install the launchd LaunchAgent (Task 16). */
    data object Install : CliCommand

    /** `uninstall` — remove the launchd LaunchAgent (Task 16). */
    data object Uninstall : CliCommand

    /** A usage error (unknown command / missing argument) — printed to stderr with [message], exit 2. */
    data class Invalid(val message: String) : CliCommand
}

/** Usage help. */
val USAGE: String = """
    kotgent — local-first dispatcher for coding-agent sessions

    Usage: kotgent <command> [args]

    Commands:
      daemon [--port N]              run the control-plane server (default port $DEFAULT_PORT)
      start <agent> [cwd]            start a session (agent e.g. 'claude'; cwd defaults to .)
                 [--name N] [--tag T]
      list | ls                      list sessions
      stop <id>                      stop a session
      resume <id>                    resume a stopped/crashed session
      interrupt <id>                 send Ctrl-C to un-stick a session
      attach <id>                    attach a raw terminal to a session
      install | uninstall           (un)install the launchd LaunchAgent
      --version                      print version
      --help                         print this help
""".trimIndent()

/** Pure `argv -> CliCommand`. No I/O — resolving defaults (cwd) and running happens in [runCli]. */
fun parseArgs(args: List<String>): CliCommand {
    if (args.isEmpty()) return CliCommand.Help
    val rest = args.drop(1)
    return when (args[0]) {
        "--version", "-V", "version" -> CliCommand.Version
        "--help", "-h", "help" -> CliCommand.Help
        "daemon" -> CliCommand.Daemon(parsePortFlag(rest) ?: DEFAULT_PORT)
        "start" -> parseStart(rest)
        "list", "ls" -> CliCommand.ListSessions
        "stop" -> requireId("stop", rest) { CliCommand.Stop(it) }
        "resume" -> requireId("resume", rest) { CliCommand.Resume(it) }
        "interrupt" -> requireId("interrupt", rest) { CliCommand.Interrupt(it) }
        "attach" -> requireId("attach", rest) { CliCommand.Attach(it) }
        "install" -> CliCommand.Install
        "uninstall" -> CliCommand.Uninstall
        else -> CliCommand.Invalid("unknown command: ${args[0]}")
    }
}

private fun requireId(command: String, rest: List<String>, make: (String) -> CliCommand): CliCommand {
    val id = rest.firstOrNull { !it.startsWith("-") }
    return if (id.isNullOrBlank()) CliCommand.Invalid("$command requires a session id: kotgent $command <id>")
    else make(id)
}

private fun parsePortFlag(rest: List<String>): Int? {
    val i = rest.indexOf("--port")
    if (i >= 0 && i + 1 < rest.size) return rest[i + 1].toIntOrNull()
    return null
}

private fun parseStart(rest: List<String>): CliCommand {
    val positionals = mutableListOf<String>()
    val tags = mutableListOf<String>()
    var name: String? = null
    var i = 0
    while (i < rest.size) {
        when (val a = rest[i]) {
            "--name" -> { name = rest.getOrNull(i + 1); i += 2 }
            "--tag" -> { rest.getOrNull(i + 1)?.let { tags.add(it) }; i += 2 }
            else -> {
                if (a.startsWith("--")) return CliCommand.Invalid("start: unknown flag '$a'")
                positionals.add(a); i += 1
            }
        }
    }
    val agent = positionals.getOrNull(0)
    if (agent.isNullOrBlank()) return CliCommand.Invalid("start requires an agent: kotgent start <agent> [cwd]")
    val cwd = positionals.getOrNull(1) // null → current dir, resolved in runCli
    return CliCommand.Start(agent, cwd, name, tags)
}

/**
 * Parse [args] and run the resulting command, returning the process exit code. This is the one place
 * that performs I/O (network calls, the daemon, the interactive attach); [parseArgs] stays pure.
 */
fun runCli(args: Array<String>): Int = when (val command = parseArgs(args.toList())) {
    is CliCommand.Version -> { println(versionLine()); 0 }
    is CliCommand.Help -> { println(USAGE); 0 }
    is CliCommand.Invalid -> { eprintln(command.message); eprintln(""); eprintln(USAGE); 2 }
    is CliCommand.Daemon -> Commands.daemon(command.port)
    is CliCommand.Start -> Commands.start(command.agent, command.cwd ?: currentWorkingDir(), command.name, command.tags)
    is CliCommand.ListSessions -> Commands.list()
    is CliCommand.Stop -> Commands.stop(command.id)
    is CliCommand.Resume -> Commands.resume(command.id)
    is CliCommand.Interrupt -> Commands.interrupt(command.id)
    is CliCommand.Attach -> Commands.attach(command.id)
    is CliCommand.Install -> Commands.install()
    is CliCommand.Uninstall -> Commands.uninstall()
}

// --- small native helpers ------------------------------------------------------------------------

/** Print [line] to stderr (usage errors / diagnostics — keeps stdout clean for command output). */
@OptIn(ExperimentalForeignApi::class)
fun eprintln(line: String) {
    fputs(line + "\n", stderr)
    fflush(stderr)
}

/** The process's current working directory (`start`'s default cwd), falling back to `$PWD` then `.`. */
@OptIn(ExperimentalForeignApi::class)
fun currentWorkingDir(): String = memScoped {
    val size = 4096
    val buf = allocArray<ByteVar>(size)
    getcwd(buf, size.convert())?.toKString()
        ?: getenv("PWD")?.toKString()
        ?: "."
}

/** `~/.kotgent` (the token, hook-settings, and DB live here); `.kotgent` if `$HOME` is unset. */
@OptIn(ExperimentalForeignApi::class)
fun kotgentHome(): String {
    val home = getenv("HOME")?.toKString()?.trimEnd('/')
    return if (home.isNullOrEmpty()) ".kotgent" else "$home/.kotgent"
}
