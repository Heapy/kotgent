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

/** `0x6b74` is the mnemonic ASCII spelling "kt". */
const val DEFAULT_PORT: Int = 0x6b74

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

/** A parsed CLI invocation. */
sealed interface CliCommand {
    data object Version : CliCommand

    data object Help : CliCommand

    data class Daemon(val port: Int) : CliCommand

    /**
     * A null [cwd] means the current directory. [task] is linked in the same request as session creation.
     */
    data class Start(
        val agent: String,
        val cwd: String?,
        val name: String?,
        val tags: List<String>,
        val task: String? = null,
    ) : CliCommand

    /**
     * Unlike [Start], a null [cwd] stays absent so the daemon discovers it from the provider's store.
     */
    data class Import(
        val agent: String,
        val providerSessionId: String,
        val cwd: String?,
        val name: String?,
        val tags: List<String>,
        val noStart: Boolean,
    ) : CliCommand

    data object ListSessions : CliCommand

    data class Stop(val id: String) : CliCommand

    data class Resume(val id: String) : CliCommand

    data class Interrupt(val id: String) : CliCommand

    data class Attach(val id: String) : CliCommand

    data object Install : CliCommand

    data object Uninstall : CliCommand

    data class Web(val print: Boolean) : CliCommand

    data object TokenRotate : CliCommand

    data object ConfigGet : CliCommand

    data class ConfigSet(val key: String, val value: String) : CliCommand

    /** A usage error (unknown command / missing argument) — printed to stderr with [message], exit 2. */
    data class Invalid(val message: String) : CliCommand
}

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
      project list                   the live projects, or the deleted ones      [--archived]
      project init [<path>]          write .kotgent.json for a project           [--name N]
      project delete <uuid>          hide a project everywhere; its file, tasks and sessions stay
      project restore <uuid>         bring a deleted project and its backlog back

      web [--print]                  open the Web UI in a browser (or print the login URL)
      token rotate                   re-mint the master token (old key stops authenticating)
      config get                     print the persisted config (public URL)
      config set public-url <url>    set the public URL published behind the tunnel
      install | uninstall           (un)install the launchd LaunchAgent
      --version                      print version
      --help                         print this help
""".trimIndent()

/**
 * Parsing reads stdin only for `-m -`; doing so here lets an empty pipe become [CliCommand.Invalid].
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

private fun parseWeb(rest: List<String>): CliCommand {
    val unknown = rest.firstOrNull { it != "--print" }
    if (unknown != null) return CliCommand.Invalid("web: unexpected argument '$unknown' (usage: kotgent web [--print])")
    return CliCommand.Web(print = rest.contains("--print"))
}

private fun parseToken(rest: List<String>): CliCommand = when (val sub = rest.firstOrNull()) {
    "rotate" -> CliCommand.TokenRotate
    null -> CliCommand.Invalid("token requires a subcommand: kotgent token rotate")
    else -> CliCommand.Invalid("token: unknown subcommand '$sub' (did you mean: kotgent token rotate?)")
}

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
 * `--task` is stricter than the legacy `--name` and `--tag` parsing: losing its value would silently
 * launch an unlinked session. The lenient legacy flags remain unchanged for compatibility.
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
    val cwd = positionals.getOrNull(1)
    return CliCommand.Start(agent, cwd, name, tags, task)
}

/**
 * Import value flags require non-blank values and extra positionals are rejected. Otherwise a swallowed
 * flag or mistaken start-style cwd could change discovery, resumption, or the imported project.
 */
private fun parseImport(rest: List<String>): CliCommand {
    val positionals = mutableListOf<String>()
    val tags = mutableListOf<String>()
    var cwd: String? = null
    var name: String? = null
    var noStart = false
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

private const val SESSION_FLAG = "--session"

private const val PROJECT_FLAG = "--project"

private const val MESSAGE_FLAG = "--message"

private const val BODY_FLAG = "--body"
private const val NAME_FLAG = "--name"
private const val ON_FLAG = "--on"
private const val BEFORE_FLAG = "--before"
private const val AFTER_FLAG = "--after"
private const val TOP_FLAG = "--top"
private const val BOTTOM_FLAG = "--bottom"
private const val ARCHIVED_FLAG = "--archived"

private const val STDIN_MESSAGE = "-"

private const val END_OF_FLAGS = "--"

private const val TASK_SUBCOMMANDS =
    "add | list | show | next | claim | comment | review | done | unlink | move | dep | delete"

private const val PROJECT_SUBCOMMANDS = "list | init | delete | restore"

private sealed interface Scan {
    data class Ok(
        val positionals: List<String>,
        val values: Map<String, String>,
        val switches: Set<String>,
    ) : Scan

    data class Bad(val message: String) : Scan
}

/**
 * Value flags require real values, repeats and unknown flags are rejected, and [END_OF_FLAGS] permits
 * dash-prefixed positionals. Strict parsing prevents unattended agents from mutating the wrong task.
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

private fun malformedRef(command: String, value: String): String =
    "$command: '$value' is not a task ref — a ref is '<tracker>:<key>', e.g. 'local:42'"

private fun malformedProject(command: String, value: String): String =
    "$command: '$value' is not a project id — a project id is the uuid in its .kotgent.json " +
        "(see: kotgent project list)"

private class UsageError(override val message: String) : IllegalArgumentException(message)

private fun Result<*>.asInvalid(): CliCommand =
    CliCommand.Invalid(exceptionOrNull()?.message ?: "invalid arguments")

/**
 * Piped messages lose only trailing whitespace; leading content is preserved. An empty pipe is a usage
 * error rather than a permanent empty activity entry.
 */
private fun parseMessage(command: String, scan: Scan.Ok, readStdin: () -> String): Result<String?> {
    val raw = scan.values[MESSAGE_FLAG] ?: return Result.success(null)
    if (raw != STDIN_MESSAGE) return Result.success(raw)
    val piped = readStdin().trimEnd()
    if (piped.isBlank()) return Result.failure(UsageError("$command: '-m -' read an empty message from stdin"))
    return Result.success(piped)
}

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

private fun parseTaskList(rest: List<String>): CliCommand =
    parseProjectScoped("task list", rest) { project, session -> TaskList(project, session) }

private fun parseTaskNext(rest: List<String>): CliCommand =
    parseProjectScoped("task next", rest) { project, session -> TaskNext(project, session) }

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
 * A missing ref resolves through the current pane rather than being a usage error.
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

    val given = scan.switches.toList() + scan.values.keys.filter { it == BEFORE_FLAG || it == AFTER_FLAG }
    if (given.isEmpty()) return CliCommand.Invalid("$command requires a target: $targets")
    if (given.size > 1) {
        return CliCommand.Invalid("$command: give exactly one of $targets (got ${given.sorted().joinToString(", ")})")
    }
    val target = when {
        TOP_FLAG in scan.switches -> MoveTarget.Top
        BOTTOM_FLAG in scan.switches -> MoveTarget.Bottom
        else -> {
            val before = scan.values[BEFORE_FLAG]
            val neighbour = before ?: scan.values.getValue(AFTER_FLAG)
            val parsed = TaskRef.parseOrNull(neighbour)
                ?: return CliCommand.Invalid(malformedRef(command, neighbour))
            if (before != null) MoveTarget.Before(parsed) else MoveTarget.After(parsed)
        }
    }
    return TaskMove(ref, target, scan.values[SESSION_FLAG])
}

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

private fun parseProject(rest: List<String>): CliCommand {
    val sub = rest.firstOrNull()
        ?: return CliCommand.Invalid("project requires a subcommand: kotgent project $PROJECT_SUBCOMMANDS")
    val args = rest.drop(1)
    return when (sub) {
        "list" -> parseProjectList(args)
        "init" -> parseProjectInit(args)
        "delete" -> parseProjectId("project delete", args) { ProjectArchive(it, archived = true) }
        "restore" -> parseProjectId("project restore", args) { ProjectArchive(it, archived = false) }
        else -> CliCommand.Invalid("project: unknown subcommand '$sub' (use: kotgent project $PROJECT_SUBCOMMANDS)")
    }
}

private fun parseProjectList(rest: List<String>): CliCommand {
    val command = "project list"
    val scan = when (val s = scanFlags(command, rest, switchFlags = setOf(ARCHIVED_FLAG))) {
        is Scan.Bad -> return CliCommand.Invalid(s.message)
        is Scan.Ok -> s
    }
    scan.positionals.firstOrNull()?.let {
        return CliCommand.Invalid("$command: unexpected argument '$it' (usage: kotgent $command [--archived])")
    }
    return ProjectList(archived = ARCHIVED_FLAG in scan.switches)
}

private fun parseProjectId(command: String, rest: List<String>, make: (String) -> CliCommand): CliCommand {
    val scan = when (val s = scanFlags(command, rest)) {
        is Scan.Bad -> return CliCommand.Invalid(s.message)
        is Scan.Ok -> s
    }
    val id = scan.positionals.getOrNull(0)?.takeUnless { it.isBlank() }
        ?: return CliCommand.Invalid(
            "$command requires a project id: kotgent $command <uuid> (see: kotgent project list)",
        )
    scan.positionals.getOrNull(1)?.let { return CliCommand.Invalid("$command: unexpected argument '$it'") }
    if (ProjectId.parseOrNull(id) == null) return CliCommand.Invalid(malformedProject(command, id))
    return make(id)
}

private fun parseProjectInit(rest: List<String>): CliCommand {
    val command = "project init"
    val scan = when (val s = scanFlags(command, rest, valueFlags(NAME_FLAG))) {
        is Scan.Bad -> return CliCommand.Invalid(s.message)
        is Scan.Ok -> s
    }
    scan.positionals.getOrNull(1)?.let { return CliCommand.Invalid("$command: unexpected argument '$it'") }
    return ProjectInit(scan.positionals.getOrNull(0), scan.values[NAME_FLAG])
}

private fun valueFlags(vararg names: String): Map<String, String> = names.associateWith { it }

private fun messageSpellings(): Map<String, String> = mapOf("-m" to MESSAGE_FLAG, MESSAGE_FLAG to MESSAGE_FLAG)

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
    is ProjectList -> TaskCommands.projectList(command.archived)
    is ProjectInit -> TaskCommands.projectInit(command.path, command.name)
    is ProjectArchive -> TaskCommands.projectArchive(command.id, command.archived)
}

private fun runStart(command: CliCommand.Start): Int = runStartResolving(
    command,
    currentWorkingDir(),
    startWithTask = TaskCommands::startWithTask,
    start = Commands::start,
)

/**
 * Resolves cwd before sending it to a daemon launched from `/`. [startWithTask] also receives whether cwd
 * was explicit, because a task's project may override only the default—not an operator-supplied path.
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
    if (task != null) startWithTask(command.agent, cwd, command.cwd != null, task, command.name, command.tags)
    else start(command.agent, cwd, command.name, command.tags)
} catch (e: UnresolvableCwdException) {
    eprintln(e.message ?: "cannot resolve the working directory")
    2
}

private fun runImport(command: CliCommand.Import): Int =
    runImportResolving(command, currentWorkingDir()) { cwd ->
        Commands.importSession(command.agent, command.providerSessionId, cwd, command.name, command.tags, command.noStart)
    }

/**
 * Resolves an explicit import cwd against [base], but preserves absence so provider-store discovery is
 * not silently replaced by the caller's cwd.
 */
fun runImportResolving(command: CliCommand.Import, base: String, importCommand: (resolvedCwd: String?) -> Int): Int = try {
    importCommand(command.cwd?.let { resolveCwdAgainst(base, it) })
} catch (e: UnresolvableCwdException) {
    eprintln(e.message ?: "cannot resolve the working directory")
    2
}

/**
 * Reads stdin to EOF for `-m -`; joining chunks once avoids quadratic copying for long messages.
 */
@OptIn(ExperimentalForeignApi::class)
fun readStdinText(): String {
    val chunks = mutableListOf<ByteArray>()
    memScoped {
        val bufSize = 4096
        val buf = allocArray<ByteVar>(bufSize)
        while (true) {
            val n = read(STDIN_FILENO, buf, bufSize.convert())
            if (n <= 0) break
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

@OptIn(ExperimentalForeignApi::class)
fun eprintln(line: String) {
    fputs(line + "\n", stderr)
    fflush(stderr)
}

/**
 * Refuses a relative cwd that cannot be anchored safely; launchd would otherwise resolve it from `/`.
 */
class UnresolvableCwdException(message: String) : IllegalStateException(message)

/**
 * Returns an absolute cwd or throws. Dot segments and duplicate slashes are lexical spelling only, but
 * `..` is preserved because resolving it across symlinks requires the filesystem; tmux or the daemon's
 * `realpath(3)` handles it later.
 */
fun resolveCwdAgainst(base: String, cwd: String?): String {
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
 * Collapses spelling-only `.` and duplicate slashes but preserves `..`, whose meaning can change across
 * symlinked prefixes such as macOS `/tmp`.
 */
private fun normalizeAbsolutePath(path: String): String =
    "/" + path.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

/**
 * Accepts only an absolute `getcwd` or `$PWD`; `"."` deliberately makes later resolution fail closed.
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
