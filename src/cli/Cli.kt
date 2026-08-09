package io.kotgent.cli

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef
import io.kotgent.task.MoveTarget
import io.kotgent.versionLine
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import platform.posix.STDIN_FILENO
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.read
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

    /**
     * `start <agent> [cwd] [--name N] [--tag T]... [--task R]` — start a session ([cwd] null = current
     * dir). A non-null [task] links the new session to that task in the SAME `POST /sessions`, so a
     * failed launch leaves no link behind; [runStart] routes it to [TaskCommands.startWithTask], which
     * also owns the "which cwd" rule when the task's project lives elsewhere.
     */
    data class Start(
        val agent: String,
        val cwd: String?,
        val name: String?,
        val tags: List<String>,
        val task: String? = null,
    ) : CliCommand

    /**
     * `import <agent> <session-id> [--cwd D] [--name N] [--tag T]... [--no-start]` — register a provider
     * session started OUTSIDE kotgent as a `resumable` row, then (unless [noStart]) resume it. Unlike
     * [Start], a null [cwd] does NOT mean the current dir: it stays absent so the daemon discovers the
     * project directory from the provider's on-disk store (see [runImportResolving]).
     */
    data class Import(
        val agent: String,
        val providerSessionId: String,
        val cwd: String?,
        val name: String?,
        val tags: List<String>,
        val noStart: Boolean,
    ) : CliCommand

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
      start <agent> [cwd]            start a session (agent: 'claude' | 'codex' | 'junie' | 'shell'; cwd defaults to .)
                 [--name N] [--tag T] [--task R]
      import <agent> <session-id>    register a session started outside kotgent, then resume it
                 [--cwd D] [--name N] [--tag T] [--no-start]
      list | ls                      list sessions
      stop <id>                      stop a session
      resume <id>                    resume a stopped/crashed/resumable session
      interrupt <id>                 send Ctrl-C to un-stick a session
      attach <id>                    attach a raw terminal to a session

      The task backlog (JSON on stdout — written for an agent to parse). Every subcommand takes
      [--session S] to name its session explicitly instead of resolving the calling tmux pane.

      task add <title>               create a task            [--body B] [--project P]
      task list                      the project's backlog, in rank order        [--project P]
      task show [<ref>]              one task in full
      task next                      take the next eligible task   [--project P] (exit 3: none)
      task claim <ref>               link this session to a task
      task comment [<ref>] -m TEXT   add a comment ('-m -' reads the text from stdin)
      task review [<ref>] [-m TEXT]  move the task to review
      task done [<ref>] [-m TEXT]    close the task and unlink every session holding it
      task unlink [<ref>]            drop this session's link; the task's state is untouched
      task move <ref>                --top | --bottom | --before <ref> | --after <ref>
      task dep add|rm <ref> --on R   add or remove "<ref> depends on R"
      task delete <ref>              remove the task, its dependencies and its feed
      project list                   every project the daemon knows
      project init [<path>]          write .kotgent.json for a project           [--name N]

      web [--print]                  open the Web UI in a browser (or print the login URL)
      token rotate                   re-mint the master token (old key stops authenticating)
      config get                     print the persisted config (public URL)
      config set public-url <url>    set the public URL published behind the tunnel
      install | uninstall           (un)install the launchd LaunchAgent
      --version                      print version
      --help                         print this help
""".trimIndent()

/**
 * `argv -> CliCommand`. Pure apart from ONE deliberate seam: [readMessageStdin], which is consulted only
 * for the `-m -` convention (`task comment`/`review`/`done`, see [parseMessage]). Everything else —
 * resolving the default cwd, every network call, the daemon — happens in [runCli].
 *
 * The seam is a parameter rather than a read inside [runCli] because `-m -` can *fail* (an empty pipe is a
 * usage error, exactly like a missing `-m` value), and a usage error has to become a [CliCommand.Invalid]
 * here — there is nowhere later to turn one into an exit code 2. Defaulted, so every existing call site
 * and every test that does not pipe anything stays a one-argument call.
 */
fun parseArgs(args: List<String>, readMessageStdin: () -> String = ::readStdinText): CliCommand {
    if (args.isEmpty()) return CliCommand.Help
    val rest = args.drop(1)
    return when (args[0]) {
        "--version", "-V", "version" -> CliCommand.Version
        "--help", "-h", "help" -> CliCommand.Help
        "daemon" -> CliCommand.Daemon(parsePortFlag(rest) ?: DEFAULT_PORT)
        "start" -> parseStart(rest)
        "import" -> parseImport(rest)
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
        "task" -> parseTask(rest, readMessageStdin)
        "project" -> parseProject(rest)
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

/**
 * `start <agent> [cwd] [--name N] [--tag T]... [--task R]`.
 *
 * `--task` is strict where `--name`/`--tag` are lenient, and the asymmetry is deliberate rather than
 * inherited: a swallowed `--name` value costs a label, while a swallowed `--task` value would start a
 * session that is silently NOT linked to the task the operator named — the launch succeeds, the link the
 * whole command was for is simply absent, and nothing says so. So a missing/`--`-prefixed value and a
 * value that is not a well-formed [TaskRef] are both usage errors here. The existing flags are left
 * exactly as they were: tightening them is a separate, behaviour-changing decision.
 */
private fun parseStart(rest: List<String>): CliCommand {
    val positionals = mutableListOf<String>()
    val tags = mutableListOf<String>()
    var name: String? = null
    var task: String? = null
    var i = 0
    while (i < rest.size) {
        when (val a = rest[i]) {
            "--name" -> { name = rest.getOrNull(i + 1); i += 2 }
            "--tag" -> { rest.getOrNull(i + 1)?.let { tags.add(it) }; i += 2 }
            "--task" -> {
                val value = rest.getOrNull(i + 1)?.takeUnless { it.isBlank() || it.startsWith("--") }
                    ?: return CliCommand.Invalid("start: --task requires a task ref")
                if (TaskRef.parseOrNull(value) == null) return CliCommand.Invalid(malformedRef("start", value))
                task = value
                i += 2
            }
            else -> {
                if (a.startsWith("--")) return CliCommand.Invalid("start: unknown flag '$a'")
                positionals.add(a); i += 1
            }
        }
    }
    val agent = positionals.getOrNull(0)
    if (agent.isNullOrBlank()) return CliCommand.Invalid("start requires an agent: kotgent start <agent> [cwd]")
    val cwd = positionals.getOrNull(1) // null → current dir, resolved in runCli
    return CliCommand.Start(agent, cwd, name, tags, task)
}

/**
 * `import <agent> <session-id> [--cwd D] [--name N] [--tag T]... [--no-start]`. Stricter than
 * [parseStart] on purpose, because every silently-tolerated slip changes what gets imported:
 *  - every value flag REQUIRES a real (non-blank, non-`--`) value. A forgotten `--cwd` value read as
 *    "discover" would contradict the operator's explicit override intent; a swallowed flag is worse —
 *    `--name --no-start` would name the session "--no-start" AND auto-resume it against the
 *    operator's stated intent; and an EMPTY value (`--cwd "$UNSET_VAR"` with the variable unset)
 *    would silently resolve to the CLI's own cwd and be sent as an explicit override of discovery —
 *    with the codex probe ignoring cwd, the session would be registered (and resumed) under the
 *    wrong project;
 *  - a third positional is rejected. `start <agent> [cwd]` trains `import claude <id> ~/proj`, and
 *    silently dropping the path would fall back to discovery — potentially registering a different
 *    cwd than the one the operator typed.
 */
private fun parseImport(rest: List<String>): CliCommand {
    val positionals = mutableListOf<String>()
    val tags = mutableListOf<String>()
    var cwd: String? = null
    var name: String? = null
    var noStart = false
    // The flag's value at [i + 1], or null when it is missing, blank, or itself a `--` flag (see the KDoc).
    fun flagValue(i: Int): String? = rest.getOrNull(i + 1)?.takeUnless { it.isBlank() || it.startsWith("--") }
    var i = 0
    while (i < rest.size) {
        when (val a = rest[i]) {
            "--cwd" -> {
                cwd = flagValue(i) ?: return CliCommand.Invalid("import: --cwd requires a directory")
                i += 2
            }
            "--name" -> {
                name = flagValue(i) ?: return CliCommand.Invalid("import: --name requires a value")
                i += 2
            }
            "--tag" -> {
                tags.add(flagValue(i) ?: return CliCommand.Invalid("import: --tag requires a value"))
                i += 2
            }
            "--no-start" -> { noStart = true; i += 1 }
            else -> {
                if (a.startsWith("--")) return CliCommand.Invalid("import: unknown flag '$a'")
                positionals.add(a); i += 1
            }
        }
    }
    val agent = positionals.getOrNull(0)
    if (agent.isNullOrBlank()) {
        return CliCommand.Invalid("import requires an agent: kotgent import <agent> <session-id>")
    }
    val id = positionals.getOrNull(1)
    if (id.isNullOrBlank()) {
        return CliCommand.Invalid("import requires a provider session id: kotgent import <agent> <session-id>")
    }
    if (positionals.size > 2) {
        return CliCommand.Invalid(
            "import: unexpected argument '${positionals[2]}' — the project directory is not positional here, use --cwd <dir>",
        )
    }
    return CliCommand.Import(agent, id, cwd, name, tags, noStart)
}

// --- the `task` / `project` families ---------------------------------------------------------------
//
// One scanner ([scanFlags]) plus one small parser per subcommand. The family is parsed as strictly as
// `import` and for the same reason: every one of these commands is run by an AGENT, unattended, so a
// silently-tolerated slip becomes a comment on the wrong task or a session linked to nothing at all.
// Refs and project ids are therefore validated HERE, against the very types the daemon will rebuild them
// with, rather than travelling to the daemon to come back as a 400 an agent has to interpret.

/** `--session <id>`: names the session explicitly, and is what lets the CLI skip `/whoami` entirely. */
private const val SESSION_FLAG = "--session"

/** `--project <uuid>`: names the project for the subcommands that are not about one task. */
private const val PROJECT_FLAG = "--project"

/** `-m` / `--message <text>`, canonicalized to this spelling by [scanFlags]. */
private const val MESSAGE_FLAG = "--message"

private const val BODY_FLAG = "--body"
private const val NAME_FLAG = "--name"
private const val ON_FLAG = "--on"
private const val BEFORE_FLAG = "--before"
private const val AFTER_FLAG = "--after"
private const val TOP_FLAG = "--top"
private const val BOTTOM_FLAG = "--bottom"

/** The `-m` value that means "read the message from stdin" instead of from argv. */
private const val STDIN_MESSAGE = "-"

/** Everything after this marker is a positional, however it is spelled (so a title may start with `-`). */
private const val END_OF_FLAGS = "--"

private const val TASK_SUBCOMMANDS =
    "add | list | show | next | claim | comment | review | done | unlink | move | dep | delete"

private const val PROJECT_SUBCOMMANDS = "list | init"

/** [scanFlags]'s answer: the argv split into positionals + flags, or the usage error that stopped it. */
private sealed interface Scan {
    data class Ok(
        val positionals: List<String>,
        val values: Map<String, String>,
        val switches: Set<String>,
    ) : Scan

    data class Bad(val message: String) : Scan
}

/**
 * Split [rest] into positionals and flags for [command].
 *
 * [valueFlags] maps each accepted SPELLING to its canonical name, which is how `-m` and `--message` fold
 * into one key; [switchFlags] are the valueless ones (`--top`, `--bottom`). Three rules, each of them a
 * failure mode this family cannot afford:
 *  - **a value flag REQUIRES a real value** — present, non-blank and not itself `--`-prefixed. The
 *    [parseImport] argument applies unchanged: `--message --session` would otherwise post the literal
 *    text "--session" as a comment AND drop the session the operator named;
 *  - **a repeated value flag is an error**, not last-wins. Nothing in this family is repeatable (unlike
 *    `start --tag`), so a second one is a mistake — usually a shell loop that appended twice — and
 *    silently keeping one of the two values is the worst possible answer;
 *  - **an unknown `-`-prefixed argument is an error**, single-dash included, so a typo'd `-s` can never
 *    be filed as a task title. A bare `-` is not a flag (it is [STDIN_MESSAGE]), and [END_OF_FLAGS] stops
 *    flag parsing for the one real case that needs it — a title that starts with `-`.
 */
private fun scanFlags(
    command: String,
    rest: List<String>,
    valueFlags: Map<String, String> = emptyMap(),
    switchFlags: Set<String> = emptySet(),
): Scan {
    val positionals = mutableListOf<String>()
    val values = mutableMapOf<String, String>()
    val switches = mutableSetOf<String>()
    var flagsEnded = false
    var i = 0
    while (i < rest.size) {
        val a = rest[i]
        val canonical = valueFlags[a]
        when {
            flagsEnded -> { positionals.add(a); i += 1 }
            a == END_OF_FLAGS -> { flagsEnded = true; i += 1 }
            canonical != null -> {
                val value = rest.getOrNull(i + 1)?.takeUnless { it.isBlank() || it.startsWith("--") }
                    ?: return Scan.Bad("$command: $a requires a value")
                if (values.containsKey(canonical)) return Scan.Bad("$command: $canonical was given more than once")
                values[canonical] = value
                i += 2
            }
            a in switchFlags -> { switches.add(a); i += 1 }
            a.length > 1 && a.startsWith("-") -> return Scan.Bad("$command: unknown flag '$a'")
            else -> { positionals.add(a); i += 1 }
        }
    }
    return Scan.Ok(positionals, values, switches)
}

/** The one wording for a ref that does not satisfy [TaskRef]'s invariant. */
private fun malformedRef(command: String, value: String): String =
    "$command: '$value' is not a task ref — a ref is '<tracker>:<key>', e.g. 'local:42'"

/** The one wording for a `--project` value that is not a canonical uuid. */
private fun malformedProject(command: String, value: String): String =
    "$command: '$value' is not a project id — a project id is the uuid in its .kotgent.json " +
        "(see: kotgent project list)"

/** Carries a usage message out of [parseMessage], which has three outcomes and no [CliCommand] to return. */
private class UsageError(override val message: String) : IllegalArgumentException(message)

/** The failure in [this] as a [CliCommand.Invalid] — every failure here is a [UsageError]. */
private fun Result<*>.asInvalid(): CliCommand =
    CliCommand.Invalid(exceptionOrNull()?.message ?: "invalid arguments")

/**
 * `-m/--message`: the argv value, `null` when the flag was absent, or the text piped on stdin when it was
 * the bare [STDIN_MESSAGE]. Only TRAILING whitespace is stripped from a piped message — that is the
 * newline a pipe or a heredoc adds, whereas the leading bytes are the operator's own content. An empty
 * pipe is a usage error for the same reason a missing `-m` value is: the operator asked to send a message
 * and there is none, and posting an empty comment would be a silent, permanent lie in the feed.
 */
private fun parseMessage(command: String, scan: Scan.Ok, readStdin: () -> String): Result<String?> {
    val raw = scan.values[MESSAGE_FLAG] ?: return Result.success(null)
    if (raw != STDIN_MESSAGE) return Result.success(raw)
    val piped = readStdin().trimEnd()
    if (piped.isBlank()) return Result.failure(UsageError("$command: '-m -' read an empty message from stdin"))
    return Result.success(piped)
}

/** `task <sub> …`. */
private fun parseTask(rest: List<String>, readStdin: () -> String): CliCommand {
    val sub = rest.firstOrNull()
        ?: return CliCommand.Invalid("task requires a subcommand: kotgent task $TASK_SUBCOMMANDS")
    val args = rest.drop(1)
    return when (sub) {
        "add" -> parseTaskAdd(args)
        "list" -> parseTaskList(args)
        "show" -> parseOptionalRef("task show", args) { ref, session -> TaskShow(ref, session) }
        "next" -> parseTaskNext(args)
        "claim" -> parseRequiredRef("task claim", args) { ref, session -> TaskClaim(ref, session) }
        "comment" -> parseTaskComment(args, readStdin)
        "review" -> parseTransition("task review", args, readStdin) { r, m, s -> TaskReview(r, m, s) }
        "done" -> parseTransition("task done", args, readStdin) { r, m, s -> TaskDone(r, m, s) }
        "unlink" -> parseOptionalRef("task unlink", args) { ref, session -> TaskUnlink(ref, session) }
        "move" -> parseTaskMove(args)
        "dep" -> parseTaskDep(args)
        "delete" -> parseRequiredRef("task delete", args) { ref, session -> TaskDelete(ref, session) }
        else -> CliCommand.Invalid("task: unknown subcommand '$sub' (use: kotgent task $TASK_SUBCOMMANDS)")
    }
}

/** `task add <title> [--body B] [--project P] [--session S]`. */
private fun parseTaskAdd(rest: List<String>): CliCommand {
    val command = "task add"
    val scan = when (val s = scanFlags(command, rest, valueFlags(BODY_FLAG, PROJECT_FLAG, SESSION_FLAG))) {
        is Scan.Bad -> return CliCommand.Invalid(s.message)
        is Scan.Ok -> s
    }
    val title = scan.positionals.getOrNull(0)
    if (title.isNullOrBlank()) {
        return CliCommand.Invalid("task add requires a title: kotgent task add <title> [--body B] [--project P]")
    }
    scan.positionals.getOrNull(1)?.let {
        return CliCommand.Invalid("$command: unexpected argument '$it' — quote the title if it contains spaces")
    }
    val project = scan.values[PROJECT_FLAG]
    if (project != null && ProjectId.parseOrNull(project) == null) {
        return CliCommand.Invalid(malformedProject(command, project))
    }
    return TaskAdd(title, scan.values[BODY_FLAG], project, scan.values[SESSION_FLAG])
}

/** `task list [--project P] [--session S]`. */
private fun parseTaskList(rest: List<String>): CliCommand =
    parseProjectScoped("task list", rest) { project, session -> TaskList(project, session) }

/** `task next [--project P] [--session S]`. */
private fun parseTaskNext(rest: List<String>): CliCommand =
    parseProjectScoped("task next", rest) { project, session -> TaskNext(project, session) }

/** The shape `task list` and `task next` share: no positionals, `--project` and `--session`. */
private fun parseProjectScoped(
    command: String,
    rest: List<String>,
    make: (project: String?, session: String?) -> CliCommand,
): CliCommand {
    val scan = when (val s = scanFlags(command, rest, valueFlags(PROJECT_FLAG, SESSION_FLAG))) {
        is Scan.Bad -> return CliCommand.Invalid(s.message)
        is Scan.Ok -> s
    }
    scan.positionals.firstOrNull()?.let {
        return CliCommand.Invalid("$command: unexpected argument '$it' (usage: kotgent $command [--project P] [--session S])")
    }
    val project = scan.values[PROJECT_FLAG]
    if (project != null && ProjectId.parseOrNull(project) == null) {
        return CliCommand.Invalid(malformedProject(command, project))
    }
    return make(project, scan.values[SESSION_FLAG])
}

/**
 * The shape `task show` and `task unlink` share: an OPTIONAL ref plus `--session`. A missing ref is not an
 * error — an agent inside a pane knows only its pane, so the subject is resolved through `GET /whoami`.
 */
private fun parseOptionalRef(
    command: String,
    rest: List<String>,
    make: (ref: String?, session: String?) -> CliCommand,
): CliCommand {
    val scan = when (val s = scanFlags(command, rest, valueFlags(SESSION_FLAG))) {
        is Scan.Bad -> return CliCommand.Invalid(s.message)
        is Scan.Ok -> s
    }
    val ref = scan.positionals.getOrNull(0)
    scan.positionals.getOrNull(1)?.let { return CliCommand.Invalid("$command: unexpected argument '$it'") }
    if (ref != null && TaskRef.parseOrNull(ref) == null) return CliCommand.Invalid(malformedRef(command, ref))
    return make(ref, scan.values[SESSION_FLAG])
}

/** The shape `task claim` and `task delete` share: a REQUIRED ref plus `--session`. */
private fun parseRequiredRef(
    command: String,
    rest: List<String>,
    make: (ref: String, session: String?) -> CliCommand,
): CliCommand {
    val scan = when (val s = scanFlags(command, rest, valueFlags(SESSION_FLAG))) {
        is Scan.Bad -> return CliCommand.Invalid(s.message)
        is Scan.Ok -> s
    }
    val ref = scan.positionals.getOrNull(0)
        ?: return CliCommand.Invalid("$command requires a task ref: kotgent $command <ref>")
    scan.positionals.getOrNull(1)?.let { return CliCommand.Invalid("$command: unexpected argument '$it'") }
    if (TaskRef.parseOrNull(ref) == null) return CliCommand.Invalid(malformedRef(command, ref))
    return make(ref, scan.values[SESSION_FLAG])
}

/** `task comment [<ref>] -m <text> [--session S]` — the one subcommand whose message is REQUIRED. */
private fun parseTaskComment(rest: List<String>, readStdin: () -> String): CliCommand {
    val command = "task comment"
    val scan = when (val s = scanFlags(command, rest, valueFlags(SESSION_FLAG) + messageSpellings())) {
        is Scan.Bad -> return CliCommand.Invalid(s.message)
        is Scan.Ok -> s
    }
    val ref = scan.positionals.getOrNull(0)
    scan.positionals.getOrNull(1)?.let { return CliCommand.Invalid("$command: unexpected argument '$it'") }
    if (ref != null && TaskRef.parseOrNull(ref) == null) return CliCommand.Invalid(malformedRef(command, ref))
    val message = parseMessage(command, scan, readStdin)
    val text = message.getOrElse { return message.asInvalid() }
        ?: return CliCommand.Invalid(
            "$command requires a message: kotgent task comment [<ref>] -m <text> (or '-m -' to read stdin)",
        )
    return TaskComment(ref, text, scan.values[SESSION_FLAG])
}

/** The shape `task review` and `task done` share: an optional ref, an optional `-m`, and `--session`. */
private fun parseTransition(
    command: String,
    rest: List<String>,
    readStdin: () -> String,
    make: (ref: String?, message: String?, session: String?) -> CliCommand,
): CliCommand {
    val scan = when (val s = scanFlags(command, rest, valueFlags(SESSION_FLAG) + messageSpellings())) {
        is Scan.Bad -> return CliCommand.Invalid(s.message)
        is Scan.Ok -> s
    }
    val ref = scan.positionals.getOrNull(0)
    scan.positionals.getOrNull(1)?.let { return CliCommand.Invalid("$command: unexpected argument '$it'") }
    if (ref != null && TaskRef.parseOrNull(ref) == null) return CliCommand.Invalid(malformedRef(command, ref))
    val message = parseMessage(command, scan, readStdin)
    return make(ref, message.getOrElse { return message.asInvalid() }, scan.values[SESSION_FLAG])
}

/** `task move <ref> (--top | --bottom | --before R | --after R) [--session S]`. */
private fun parseTaskMove(rest: List<String>): CliCommand {
    val command = "task move"
    val targets = "--top | --bottom | --before <ref> | --after <ref>"
    val scan = when (
        val s = scanFlags(
            command,
            rest,
            valueFlags(BEFORE_FLAG, AFTER_FLAG, SESSION_FLAG),
            switchFlags = setOf(TOP_FLAG, BOTTOM_FLAG),
        )
    ) {
        is Scan.Bad -> return CliCommand.Invalid(s.message)
        is Scan.Ok -> s
    }
    val ref = scan.positionals.getOrNull(0)
        ?: return CliCommand.Invalid("$command requires a task ref: kotgent task move <ref> ($targets)")
    scan.positionals.getOrNull(1)?.let { return CliCommand.Invalid("$command: unexpected argument '$it'") }
    if (TaskRef.parseOrNull(ref) == null) return CliCommand.Invalid(malformedRef(command, ref))

    // Exactly one target. Zero is a no-op the daemon would have to invent a meaning for; two is a
    // contradiction, and picking one of them silently re-ranks the backlog somewhere nobody asked for.
    val given = scan.switches.toList() + scan.values.keys.filter { it == BEFORE_FLAG || it == AFTER_FLAG }
    if (given.isEmpty()) return CliCommand.Invalid("$command requires a target: $targets")
    if (given.size > 1) {
        return CliCommand.Invalid("$command: give exactly one of $targets (got ${given.sorted().joinToString(", ")})")
    }
    val target = when {
        TOP_FLAG in scan.switches -> MoveTarget.Top
        BOTTOM_FLAG in scan.switches -> MoveTarget.Bottom
        else -> {
            // Exactly one of the two is present (the count above settled that), so the elvis cannot miss.
            val before = scan.values[BEFORE_FLAG]
            val neighbour = before ?: scan.values.getValue(AFTER_FLAG)
            val parsed = TaskRef.parseOrNull(neighbour)
                ?: return CliCommand.Invalid(malformedRef(command, neighbour))
            if (before != null) MoveTarget.Before(parsed) else MoveTarget.After(parsed)
        }
    }
    return TaskMove(ref, target, scan.values[SESSION_FLAG])
}

/** `task dep add|rm <ref> --on <other> [--session S]`. */
private fun parseTaskDep(rest: List<String>): CliCommand {
    val command = "task dep"
    val usage = "kotgent task dep add|rm <ref> --on <other>"
    val scan = when (val s = scanFlags(command, rest, valueFlags(ON_FLAG, SESSION_FLAG))) {
        is Scan.Bad -> return CliCommand.Invalid(s.message)
        is Scan.Ok -> s
    }
    val action = scan.positionals.getOrNull(0)
        ?: return CliCommand.Invalid("$command requires an action: $usage")
    val remove = when (action) {
        "add" -> false
        "rm" -> true
        else -> return CliCommand.Invalid("$command: unknown action '$action' (use: add | rm)")
    }
    val ref = scan.positionals.getOrNull(1)
        ?: return CliCommand.Invalid("$command $action requires a task ref: $usage")
    scan.positionals.getOrNull(2)?.let { return CliCommand.Invalid("$command: unexpected argument '$it'") }
    if (TaskRef.parseOrNull(ref) == null) return CliCommand.Invalid(malformedRef(command, ref))
    val on = scan.values[ON_FLAG]
        ?: return CliCommand.Invalid("$command $action requires --on <ref> — the task '$ref' depends on")
    if (TaskRef.parseOrNull(on) == null) return CliCommand.Invalid(malformedRef(command, on))
    return TaskDep(ref, on, remove, scan.values[SESSION_FLAG])
}

/** `project list` / `project init [<path>] [--name N]`. */
private fun parseProject(rest: List<String>): CliCommand {
    val sub = rest.firstOrNull()
        ?: return CliCommand.Invalid("project requires a subcommand: kotgent project $PROJECT_SUBCOMMANDS")
    val args = rest.drop(1)
    return when (sub) {
        "list" -> parseProjectList(args)
        "init" -> parseProjectInit(args)
        else -> CliCommand.Invalid("project: unknown subcommand '$sub' (use: kotgent project $PROJECT_SUBCOMMANDS)")
    }
}

/** `project list` — the daemon knows every project; there is nothing to scope it by. */
private fun parseProjectList(rest: List<String>): CliCommand {
    val scan = when (val s = scanFlags("project list", rest)) {
        is Scan.Bad -> return CliCommand.Invalid(s.message)
        is Scan.Ok -> s
    }
    scan.positionals.firstOrNull()?.let {
        return CliCommand.Invalid("project list: unexpected argument '$it' (usage: kotgent project list)")
    }
    return ProjectList
}

/** `project init [<path>] [--name N]` — a missing path means the caller's cwd, resolved by `TaskCommands`. */
private fun parseProjectInit(rest: List<String>): CliCommand {
    val command = "project init"
    val scan = when (val s = scanFlags(command, rest, valueFlags(NAME_FLAG))) {
        is Scan.Bad -> return CliCommand.Invalid(s.message)
        is Scan.Ok -> s
    }
    scan.positionals.getOrNull(1)?.let { return CliCommand.Invalid("$command: unexpected argument '$it'") }
    return ProjectInit(scan.positionals.getOrNull(0), scan.values[NAME_FLAG])
}

/** Flags whose spelling IS their canonical name (everything but `-m`). */
private fun valueFlags(vararg names: String): Map<String, String> = names.associateWith { it }

/** The two spellings of the message flag, both folding onto [MESSAGE_FLAG]. */
private fun messageSpellings(): Map<String, String> = mapOf("-m" to MESSAGE_FLAG, MESSAGE_FLAG to MESSAGE_FLAG)

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
    is CliCommand.Import -> runImport(command)
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
    // The task/backlog families. Parsing is Task 19's (in this file); execution is Task 21's, in
    // TaskCommands — so the dispatch is here and nothing else about these commands is.
    is TaskAdd -> TaskCommands.add(command.title, command.body, command.project, command.session)
    is TaskList -> TaskCommands.list(command.project, command.session)
    is TaskShow -> TaskCommands.show(command.ref, command.session)
    is TaskNext -> TaskCommands.next(command.project, command.session)
    is TaskClaim -> TaskCommands.claim(command.ref, command.session)
    is TaskComment -> TaskCommands.comment(command.ref, command.message, command.session)
    is TaskReview -> TaskCommands.review(command.ref, command.message, command.session)
    is TaskDone -> TaskCommands.done(command.ref, command.message, command.session)
    is TaskUnlink -> TaskCommands.unlink(command.ref, command.session)
    is TaskMove -> TaskCommands.move(command.ref, command.target, command.session)
    is TaskDep -> TaskCommands.dep(command.ref, command.on, command.remove, command.session)
    is TaskDelete -> TaskCommands.delete(command.ref, command.session)
    is ProjectList -> TaskCommands.projectList()
    is ProjectInit -> TaskCommands.projectInit(command.path, command.name)
}

/** `start` — the [runCli] dispatch wrapper: [runStartResolving] over the real cwd and the real commands. */
private fun runStart(command: CliCommand.Start): Int = runStartResolving(
    command,
    currentWorkingDir(),
    startWithTask = TaskCommands::startWithTask,
    start = Commands::start,
)

/**
 * `start` — resolve the requested cwd to an ABSOLUTE path against [base] (the CLI's own working
 * directory), then dispatch. If that cannot be done (the CLI cannot determine its own working directory
 * and the caller gave a relative path), it fails LOUDLY with exit code 2 instead of sending the daemon a
 * relative path it would resolve against its own launchd cwd `/` — i.e. the wrong directory. The two
 * commands arrive as parameters, the [runImportResolving] shape, so the rule is unit-testable with no
 * daemon and no tmux.
 *
 * Two things travel to [startWithTask], not one. The resolved cwd is always absolute, so it cannot say
 * whether the operator TYPED a directory or it was defaulted to the CLI's own — and `--task`'s cwd rule
 * may override only the default. `command.cwd != null` is that whole distinction; collapsing the two here
 * is what let a stale `projects.path` win over a positional argument the operator had spelled out.
 */
fun runStartResolving(
    command: CliCommand.Start,
    base: String,
    startWithTask: (
        agent: String,
        cwd: String,
        cwdExplicit: Boolean,
        taskRef: String,
        name: String?,
        tags: List<String>,
    ) -> Int,
    start: (agent: String, cwd: String, name: String?, tags: List<String>) -> Int,
): Int = try {
    val cwd = resolveCwdAgainst(base, command.cwd)
    val task = command.task
    // `--task` is one POST carrying the ref, not a start followed by a link — see TaskCommands.
    if (task != null) startWithTask(command.agent, cwd, command.cwd != null, task, command.name, command.tags)
    else start(command.agent, cwd, command.name, command.tags)
} catch (e: UnresolvableCwdException) {
    eprintln(e.message ?: "cannot resolve the working directory")
    2
}

/** `import` — the [runCli] dispatch wrapper (the [runStart] shape): [runImportResolving] over the real cwd + command. */
private fun runImport(command: CliCommand.Import): Int =
    runImportResolving(command, currentWorkingDir()) { cwd ->
        Commands.importSession(command.agent, command.providerSessionId, cwd, command.name, command.tags, command.noStart)
    }

/**
 * `import` — resolve the optional `--cwd` exactly the way [runStart] resolves its cwd (a relative path is
 * anchored at [base], the CLI's own working directory; an unresolvable one prints the error and exits 2 —
 * the daemon lives under launchd with cwd `/`, so a relative path must never reach it), with ONE
 * deliberate difference: an ABSENT `--cwd` stays absent, because the daemon then discovers the project
 * directory from the provider's on-disk store — defaulting it to the CLI's cwd would silently defeat
 * discovery. [importCommand] receives the resolved (or absent) cwd; it is a seam so this rule is
 * unit-testable without a daemon (see `CliTest`).
 */
fun runImportResolving(command: CliCommand.Import, base: String, importCommand: (resolvedCwd: String?) -> Int): Int = try {
    importCommand(command.cwd?.let { resolveCwdAgainst(base, it) })
} catch (e: UnresolvableCwdException) {
    eprintln(e.message ?: "cannot resolve the working directory")
    2
}

// --- small native helpers ------------------------------------------------------------------------

/**
 * Read stdin to EOF and decode it as UTF-8 — the `-m -` convention's other half ([parseMessage]).
 *
 * It exists so an agent can pipe a long summary (`kotgent task review -m - <<'EOF'`) without quoting it
 * into argv, which is also why it reads to EOF rather than one line. Chunks are collected and joined
 * ONCE: appending to a growing `ByteArray` would copy the whole accumulated message per 4 KiB read, and
 * "a long summary" is exactly the input this is for. Blocking is the point — the caller asked for stdin.
 */
@OptIn(ExperimentalForeignApi::class)
fun readStdinText(): String {
    val chunks = mutableListOf<ByteArray>()
    memScoped {
        val bufSize = 4096
        val buf = allocArray<ByteVar>(bufSize)
        while (true) {
            val n = read(STDIN_FILENO, buf, bufSize.convert())
            if (n <= 0) break // EOF, or an unreadable stdin — either way there is no more message
            chunks.add(buf.readBytes(n.toInt()))
        }
    }
    val all = ByteArray(chunks.sumOf { it.size })
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(all, offset)
        offset += chunk.size
    }
    return all.decodeToString()
}

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
 * Resolve a possibly-relative [cwd] against [base] (the CLI's own working directory) to an ABSOLUTE,
 * lexically NORMALIZED path. The daemon runs under launchd with cwd `/`, so a relative `cwd` sent
 * verbatim (e.g. `kotgent start claude .` or `… start claude sub/dir`) would be resolved by the daemon
 * against `/` — the wrong directory. The CLI resolves it here, against its own cwd, before sending.
 * Pure (no IO), so it is unit-tested directly.
 *
 * `.` segments and duplicate/trailing slashes are collapsed HERE (pure spelling — the same directory
 * either way); `..` segments deliberately pass through UNRESOLVED, because collapsing `..` lexically
 * changes meaning across symlinks (macOS: `/tmp/../Users` is lexically `/Users`, but /tmp is a symlink
 * into /private, so the filesystem's answer is `/private/Users`). Downstream owns them with the real
 * tree in hand: `start` hands the path to `tmux new-session -c` (the kernel resolves it at launch), and
 * `import` is canonicalized by the DAEMON with `realpath(3)` before the vendor-store probe runs (see
 * SessionManager.importSession). See [normalizeAbsolutePath].
 *
 * The result is ABSOLUTE in every branch, or it throws:
 * - an absolute [cwd] passes through (normalized) and never consults [base];
 * - otherwise [base] must itself be absolute — a relative one (`"sub"`), the `"."` [currentWorkingDir]
 *   falls back to when `getcwd` fails, or an empty string raise [UnresolvableCwdException]. Joining them
 *   would produce `"./sub"` / `"sub"` (still relative, the exact bug this function exists to prevent), and
 *   reading `""` as root would launch the agent in `/` — both silently wrong, so neither is guessed at.
 */
fun resolveCwdAgainst(base: String, cwd: String?): String {
    // An absolute target needs no base at all — resolve it even if the CLI cannot name its own cwd.
    if (cwd != null && cwd.startsWith("/")) return normalizeAbsolutePath(cwd)
    if (!base.startsWith("/")) {
        throw UnresolvableCwdException(
            "cannot resolve a relative directory: the current working directory is not absolute " +
                "('$base') — pass an absolute path instead",
        )
    }
    return normalizeAbsolutePath(if (cwd.isNullOrEmpty()) base else "$base/$cwd")
}

/**
 * Collapse `.` segments, duplicate slashes and any trailing slash in an ABSOLUTE [path] — spelling-only
 * cleanup that can never change which directory is named (it is also what keeps the degenerate joins,
 * `"//sub"` / `"/base/./"`, from ever emitting `""` or a doubled slash). `..` segments are deliberately
 * KEPT, not collapsed: `..` crosses a directory boundary, and a lexical collapse resolves it against the
 * path's SPELLING while the kernel resolves it against the real (symlink-traversed) tree — the two
 * disagree whenever a prefix is a symlink (`/tmp/../Users` really names `/private/Users`). Only code
 * with the filesystem in hand may resolve `..`: tmux at launch for `start`, the daemon's `realpath(3)`
 * for `import`.
 */
private fun normalizeAbsolutePath(path: String): String =
    "/" + path.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

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
