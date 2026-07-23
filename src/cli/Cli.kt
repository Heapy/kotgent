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

    /** `web [--print]` — open the Web UI in a browser; [print] prints the one-shot login URL instead. */
    data class Web(val print: Boolean) : CliCommand

    /** `token rotate` — re-mint the master token; the old key stops authenticating new requests. */
    data object TokenRotate : CliCommand

    /** `config get` — print the persisted config (currently just the public URL). */
    data object ConfigGet : CliCommand

    /** `config set <key> <value>` — persist a config value (currently only `public-url`). */
    data class ConfigSet(val key: String, val value: String) : CliCommand

    /** A usage error (unknown command / missing argument) — printed to stderr with [message], exit 2. */
    data class Invalid(val message: String) : CliCommand
}

/** Usage help. */
val USAGE: String = """
    kotgent — local-first dispatcher for coding-agent sessions

    Usage: kotgent <command> [args]

    Commands:
      daemon [--port N]              run the control-plane server (default port $DEFAULT_PORT)
      start <agent> [cwd]            start a session (agent: 'claude' | 'codex'; cwd defaults to .)
                 [--name N] [--tag T]
      list | ls                      list sessions
      stop <id>                      stop a session
      resume <id>                    resume a stopped/crashed session
      interrupt <id>                 send Ctrl-C to un-stick a session
      attach <id>                    attach a raw terminal to a session
      web [--print]                  open the Web UI in a browser (or print the login URL)
      token rotate                   re-mint the master token (old key stops authenticating)
      config get                     print the persisted config (public URL)
      config set public-url <url>    set the public URL published behind the tunnel
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
        "web" -> parseWeb(rest)
        "token" -> parseToken(rest)
        "config" -> parseConfig(rest)
        else -> CliCommand.Invalid("unknown command: ${args[0]}")
    }
}

/** `web [--print]` — the only flag is `--print`; anything else is a usage error. */
private fun parseWeb(rest: List<String>): CliCommand {
    val unknown = rest.firstOrNull { it != "--print" }
    if (unknown != null) return CliCommand.Invalid("web: unexpected argument '$unknown' (usage: kotgent web [--print])")
    return CliCommand.Web(print = rest.contains("--print"))
}

/** `token <sub>` — only `rotate` today; a bare `token` is NOT `cat ~/.kotgent/token`, so it is an error. */
private fun parseToken(rest: List<String>): CliCommand = when (val sub = rest.firstOrNull()) {
    "rotate" -> CliCommand.TokenRotate
    null -> CliCommand.Invalid("token requires a subcommand: kotgent token rotate")
    else -> CliCommand.Invalid("token: unknown subcommand '$sub' (did you mean: kotgent token rotate?)")
}

/** `config get` / `config set <key> <value>`. */
private fun parseConfig(rest: List<String>): CliCommand = when (val sub = rest.firstOrNull()) {
    "get" -> CliCommand.ConfigGet
    "set" -> parseConfigSet(rest.drop(1))
    null -> CliCommand.Invalid("config requires a subcommand: kotgent config get | config set <key> <value>")
    else -> CliCommand.Invalid("config: unknown subcommand '$sub' (use: config get | config set <key> <value>)")
}

private fun parseConfigSet(rest: List<String>): CliCommand {
    val key = rest.getOrNull(0)
    if (key.isNullOrBlank()) return CliCommand.Invalid("config set requires a key: kotgent config set public-url <url>")
    val value = rest.getOrNull(1)
    if (value.isNullOrBlank()) return CliCommand.Invalid("config set $key requires a value: kotgent config set $key <value>")
    return CliCommand.ConfigSet(key, value)
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
    is CliCommand.Start -> runStart(command)
    is CliCommand.ListSessions -> Commands.list()
    is CliCommand.Stop -> Commands.stop(command.id)
    is CliCommand.Resume -> Commands.resume(command.id)
    is CliCommand.Interrupt -> Commands.interrupt(command.id)
    is CliCommand.Attach -> Commands.attach(command.id)
    is CliCommand.Install -> Commands.install()
    is CliCommand.Uninstall -> Commands.uninstall()
    is CliCommand.Web -> Commands.web(command.print)
    is CliCommand.TokenRotate -> Commands.tokenRotate()
    is CliCommand.ConfigGet -> Commands.configGet()
    is CliCommand.ConfigSet -> Commands.configSet(command.key, command.value)
}

/**
 * `start` — resolve the requested cwd to an ABSOLUTE path against the CLI's own working directory, then
 * hand it to [Commands.start]. If that cannot be done (the CLI cannot determine its own working
 * directory and the caller gave a relative path), it fails LOUDLY with exit code 2 instead of sending the
 * daemon a relative path it would resolve against its own launchd cwd `/` — i.e. the wrong directory.
 */
private fun runStart(command: CliCommand.Start): Int = try {
    Commands.start(command.agent, resolveCwdAgainst(currentWorkingDir(), command.cwd), command.name, command.tags)
} catch (e: UnresolvableCwdException) {
    eprintln(e.message ?: "cannot resolve the working directory")
    2
}

// --- small native helpers ------------------------------------------------------------------------

/** Print [line] to stderr (usage errors / diagnostics — keeps stdout clean for command output). */
@OptIn(ExperimentalForeignApi::class)
fun eprintln(line: String) {
    fputs(line + "\n", stderr)
    fflush(stderr)
}

/**
 * Raised when a relative cwd cannot be made absolute because [base] — the CLI's own working directory —
 * is not itself absolute. Fatal on purpose: sending the daemon a relative path would have it resolved
 * against launchd's cwd `/`, silently launching the agent in the wrong directory.
 */
class UnresolvableCwdException(message: String) : IllegalStateException(message)

/**
 * Resolve a possibly-relative [cwd] against [base] (the CLI's own working directory) to an ABSOLUTE
 * path. The daemon runs under launchd with cwd `/`, so a relative `cwd` sent verbatim (e.g.
 * `kotgent start claude .` or `… start claude sub/dir`) would be resolved by the daemon against `/` —
 * the wrong directory. The CLI resolves it here, against its own cwd, before sending. Pure (no IO), so
 * it is unit-tested directly; an already-absolute cwd and the omitted (`null`) case pass through as the
 * base. tmux `new-session -c` canonicalizes any remaining `..` segments at launch.
 *
 * The result is ABSOLUTE in every branch, or it throws:
 * - an absolute [cwd] passes straight through and never consults [base];
 * - otherwise [base] must itself be absolute — a relative one (`"sub"`), the `"."` [currentWorkingDir]
 *   falls back to when `getcwd` fails, or an empty string raise [UnresolvableCwdException]. Joining them
 *   would produce `"./sub"` / `"sub"` (still relative, the exact bug this function exists to prevent), and
 *   reading `""` as root would launch the agent in `/` — both silently wrong, so neither is guessed at.
 *
 * Root is the edge case that must not collapse: `"/"` trims to the EMPTY string, and `"./"` strips to an
 * empty relative part, so a naive join yields `""` — not a path at all. Both are normalized back to `/`.
 */
fun resolveCwdAgainst(base: String, cwd: String?): String {
    // An absolute target needs no base at all — resolve it even if the CLI cannot name its own cwd.
    if (cwd != null && cwd.startsWith("/")) return cwd
    if (!base.startsWith("/")) {
        throw UnresolvableCwdException(
            "cannot resolve a relative directory: the current working directory is not absolute " +
                "('$base') — pass an absolute path to `kotgent start`",
        )
    }
    val b = base.trimEnd('/').ifEmpty { "/" } // "/" stays root, never ""
    if (cwd.isNullOrEmpty()) return b
    // Strip leading "./" segments; "." / "./" / "././" all name the base directory itself.
    var rel: String = cwd
    while (rel.startsWith("./")) rel = rel.substring(2)
    if (rel.isEmpty() || rel == ".") return b
    return if (b == "/") "/$rel" else "$b/$rel"
}

/**
 * The process's current working directory (`start`'s default cwd): `getcwd`, else `$PWD`, else `"."`.
 * Only an ABSOLUTE answer is accepted from either source — a relative `$PWD` (or a relative `getcwd`,
 * which POSIX does not produce but a stub could) is no more usable than none at all. The `"."` fallback
 * is deliberately not a path: it makes [resolveCwdAgainst] fail loudly rather than quietly resolving a
 * relative cwd into another relative one.
 */
@OptIn(ExperimentalForeignApi::class)
fun currentWorkingDir(): String = memScoped {
    val size = 4096
    val buf = allocArray<ByteVar>(size)
    val fromGetcwd = getcwd(buf, size.convert())?.toKString()
    if (fromGetcwd != null && fromGetcwd.startsWith("/")) return@memScoped fromGetcwd
    getenv("PWD")?.toKString()?.takeIf { it.startsWith("/") } ?: "."
}

/** `~/.kotgent` (the token, hook-settings, and DB live here); `.kotgent` if `$HOME` is unset. */
@OptIn(ExperimentalForeignApi::class)
fun kotgentHome(): String {
    val home = getenv("HOME")?.toKString()?.trimEnd('/')
    return if (home.isNullOrEmpty()) ".kotgent" else "$home/.kotgent"
}
