package io.kotgent.cli

import io.kotgent.task.MoveTarget
import io.kotgent.task.PosixProjectFs
import io.kotgent.task.TaskState
import io.kotgent.task.resolveProject
import io.kotgent.transport.ActivityEntryDto
import io.kotgent.transport.BacklogEntryDto
import io.kotgent.transport.ProjectDto
import io.kotgent.transport.SessionDto
import io.kotgent.transport.TRANSPORT_JSON
import io.kotgent.transport.TaskDetailDto
import io.kotgent.transport.WhoamiDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The side-effecting half of the `task` / `project` CLI families — the [Commands] shape, one function per
 * [CliCommand] variant, each returning a process exit code.
 *
 * ## Two rules the whole family obeys
 *  1. **JSON only, on stdout.** These commands exist for an agent inside a pane to parse. A human-readable
 *     table would be a second format to keep in step with the DTOs for no reader. Failures are JSON too,
 *     on **stderr** ([taskErrorJson]), so everything this family emits is machine-readable and the two
 *     streams stay separable: stdout is the answer, stderr is why there is none.
 *  2. **`--session` short-circuits `/whoami`, and never falls through to the pane.** A ref-less
 *     subcommand resolves its subject through `GET /whoami` (pane → session), but when `--session <id>`
 *     was given the CLI already knows the id and **must not** make that call: `/whoami` is pane
 *     resolution, and asking it from outside a kotgent pane would fail for a request that has everything
 *     it needs. The named session's own row answers the two remaining questions instead — its `taskRef`
 *     for a ref-less subject ([resolveSubjectRef]) and its `projectId` for a project-less `task list`
 *     ([resolveSubjectProject]) — because `GET /tasks` carries no body the daemon could resolve a session
 *     from. Both resolutions **fail loudly** when the named session is unknown or answers nothing, rather
 *     than sending the request without the answer: the daemon would then resolve the CALLING PANE, and
 *     `--session <other>` would silently return this pane's own backlog.
 *
 * ## Exit codes
 *  - `0` success, `1` a daemon/API failure, `2` a usage error ([CliCommand.Invalid], handled in `Cli.kt`,
 *    plus the one runtime usage error this file can raise: a relative `project init` path the CLI cannot
 *    anchor, exactly as `runStart` treats an unresolvable `start` cwd).
 *  - **`3` and only `3` means "nothing eligible"**, from [next]. It is a distinct code because a script
 *    that stops on an empty backlog must not also stop on a network error. It still prints parseable JSON
 *    (`{"task":null}`), so a caller may read the answer instead of the code.
 *
 * ## `start --task`'s cwd
 * [startWithTask] chooses, in order: a cwd the operator NAMED on the command line; else the caller's cwd
 * when it resolves to the task's project; else the project's stored `path`; else the caller's cwd again
 * when that stored path no longer exists. Whichever it used is named in the JSON output (`cwdSource`),
 * because "kotgent started this somewhere else" is exactly the kind of surprise a silent fallback creates.
 *
 * ## Why every body is a thin wrapper around a `run…Command` free function
 * Kotlin/Native gives a test no way to capture `println`, and these commands' interesting behaviour — ref
 * resolution, the `next` exit code, the cwd rule — must be provable without a daemon. So each entry point
 * only builds the [ApiClient] (with the pane resolved ONCE, at the edge, by [TmuxSelf.currentPane]) and
 * delegates to a free function taking suspending lambdas plus `stdout`/`stderr` sinks — the
 * [runWebCommand] / [runImportCommand] pattern. The pane is passed IN rather than resolved inside, so no
 * tested path ever touches the environment.
 */
object TaskCommands {

    /** `task add`. */
    fun add(title: String, body: String?, project: String?, session: String?): Int = withTaskApi { api ->
        runTaskAddCommand(
            title = title,
            body = body,
            project = project,
            session = session,
            createTask = { t, b, p, s -> api.createTask(t, b, p, s) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /** `task list`. */
    fun list(project: String?, session: String?): Int = withTaskApi { api ->
        runTaskListCommand(
            project = project,
            session = session,
            findSession = api.sessionFinder(),
            listTasks = { p -> api.listTasks(p) },
            resolveCwdProjectId = ::currentCwdProjectId,
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /** `task show` — a null [ref] resolves through `/whoami`. */
    fun show(ref: String?, session: String?): Int = withTaskApi { api ->
        runTaskShowCommand(
            ref = ref,
            session = session,
            whoami = { api.whoami() },
            findSession = api.sessionFinder(),
            taskDetail = { r -> api.taskDetail(r) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /** `task next` — exits `3` when nothing is eligible. */
    fun next(project: String?, session: String?): Int = withTaskApi { api ->
        runTaskNextCommand(
            project = project,
            session = session,
            nextTask = { p -> api.nextTask(p, session) },
            resolveCwdProjectId = ::currentCwdProjectId,
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /** `task claim`. */
    fun claim(ref: String, session: String?): Int = withTaskApi { api ->
        runTaskClaimCommand(
            ref = ref,
            session = session,
            linkTask = { r, s -> api.linkTask(r, s) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /** `task comment`. */
    fun comment(ref: String?, message: String, session: String?): Int = withTaskApi { api ->
        runTaskCommentCommand(
            ref = ref,
            message = message,
            session = session,
            whoami = { api.whoami() },
            findSession = api.sessionFinder(),
            commentOnTask = { r, t, s -> api.commentOnTask(r, t, s) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /** `task review`. */
    fun review(ref: String?, message: String?, session: String?): Int = withTaskApi { api ->
        runTaskTransitionCommand(
            ref = ref,
            state = TaskState.review.name,
            message = message,
            session = session,
            whoami = { api.whoami() },
            findSession = api.sessionFinder(),
            patchTask = { r, st, m, s -> api.patchTask(ref = r, state = st, message = m, sessionId = s) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /** `task done` — closes the task and unlinks every holder; the sessions stay alive. */
    fun done(ref: String?, message: String?, session: String?): Int = withTaskApi { api ->
        runTaskTransitionCommand(
            ref = ref,
            state = TaskState.done.name,
            message = message,
            session = session,
            whoami = { api.whoami() },
            findSession = api.sessionFinder(),
            patchTask = { r, st, m, s -> api.patchTask(ref = r, state = st, message = m, sessionId = s) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /** `task unlink` — drops this session's link and leaves the task's state alone. */
    fun unlink(ref: String?, session: String?): Int = withTaskApi { api ->
        runTaskUnlinkCommand(
            ref = ref,
            session = session,
            whoami = { api.whoami() },
            findSession = api.sessionFinder(),
            unlinkTask = { r, s -> api.unlinkTask(r, s) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /** `task move`. */
    fun move(ref: String, target: MoveTarget, session: String?): Int = withTaskApi { api ->
        runTaskMoveCommand(
            ref = ref,
            target = target,
            moveTask = { r, t -> api.moveTask(r, t) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /** `task dep add|rm`. */
    fun dep(ref: String, on: String, remove: Boolean, session: String?): Int = withTaskApi { api ->
        runTaskDepCommand(
            ref = ref,
            on = on,
            remove = remove,
            editTaskDependency = { r, a, o -> api.editTaskDependency(r, a, o) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /** `task delete`. */
    fun delete(ref: String, session: String?): Int = withTaskApi { api ->
        runTaskDeleteCommand(
            ref = ref,
            deleteTask = { r -> api.deleteTask(r) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /** `project list`. */
    fun projectList(): Int = withTaskApi { api ->
        runProjectListCommand(
            listProjects = { api.listProjects() },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /** `project init` — a null [path] means the caller's cwd. */
    fun projectInit(path: String?, name: String?): Int = withTaskApi { api ->
        runProjectInitCommand(
            path = path,
            name = name,
            callerCwd = currentWorkingDir(),
            createProject = { p, n -> api.createProject(p, n) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    /**
     * `start <agent> [cwd] --task <ref>` — one `POST /api/v1/sessions` carrying the `taskRef`, so the
     * session row and its link are written by the same request and a failed launch leaves no link behind.
     *
     * [cwd] is always absolute (`runStart` resolved it); [cwdExplicit] says whether it came from the
     * command line or was defaulted to the caller's own working directory. The two are the same STRING
     * and only that flag tells them apart — see the cwd rule in this object's KDoc.
     */
    fun startWithTask(
        agent: String,
        cwd: String,
        cwdExplicit: Boolean,
        taskRef: String,
        name: String?,
        tags: List<String>,
    ): Int = withTaskApi { api ->
        val fs = PosixProjectFs()
        runStartWithTaskCommand(
            agent = agent,
            callerCwd = cwd,
            cwdExplicit = cwdExplicit,
            taskRef = taskRef,
            name = name,
            tags = tags,
            taskDetail = { r -> api.taskDetail(r) },
            startSession = { a, c, n, t, r -> api.startSession(a, c, n, t, r) },
            // Both seams are the FILESYSTEM's answer, injected so the three cwd branches are testable:
            // resolveProject is the same pure walk the daemon runs, and a stored path that no longer
            // exists is the "stale projects.path" case the fallback exists for.
            resolveProjectId = { dir -> resolveProject(fs, dir)?.id?.value },
            isDirectory = fs::isDirectory,
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    // --- the one edge where a real client is built -------------------------------------------------

    /**
     * Run [block] against a real [ApiClient] carrying this process's kotgent pane.
     *
     * [TmuxSelf.currentPane] is called HERE, once, and never inside a `run…Command`: it reads the
     * environment, so a tested path that resolved it itself could not be driven from a test binary. Every
     * failure mode below this point is handled by [runTaskCommand]'s catches, which turn it into one line
     * of JSON on stderr.
     */
    private fun withTaskApi(block: suspend (ApiClient) -> Int): Int = runBlocking {
        ApiClient(paneId = TmuxSelf.currentPane()).use { block(it) }
    }
}

/**
 * Raised when a ref-less subcommand cannot work out what it is about: no pane, a named session that does
 * not exist, or a session linked to no task. Distinct from [ApiException] because nothing failed — the
 * caller simply has to say which task it means, and the message says so.
 */
class TaskSubjectException(message: String) : RuntimeException(message)

/** One command's answer: the JSON that goes to stdout, and the exit code that goes with it. */
private data class TaskOutput(val json: String, val exitCode: Int = 0)

/**
 * The family's one error renderer: a single line of JSON on stderr, carrying the daemon's HTTP status
 * when there was one. An agent that captures both streams can parse either without a second format.
 */
fun taskErrorJson(message: String, status: Int? = null): String = TRANSPORT_JSON.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("error", message)
        if (status != null) put("status", status)
    },
)

/**
 * Run one command body, turning every failure into one line of JSON on stderr and a non-zero exit.
 *
 * The catches are ordered from most specific to least: a subject that cannot be resolved is the caller's
 * to fix, an [ApiException] carries the daemon's own message and status, a [MissingTokenException] means
 * the daemon was never started, and anything else is an unreachable daemon. None of them may print a
 * stack trace — this output is parsed.
 */
private suspend fun runTaskCommand(
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
    body: suspend () -> TaskOutput,
): Int = try {
    val out = body()
    stdout(out.json)
    out.exitCode
} catch (e: TaskSubjectException) {
    stderr(taskErrorJson(e.message ?: "cannot tell which task this is about"))
    1
} catch (e: ApiException) {
    stderr(taskErrorJson(e.body.trim().ifEmpty { e.message ?: "daemon error" }, e.status))
    1
} catch (e: MissingTokenException) {
    stderr(taskErrorJson(e.message ?: "no kotgent token"))
    1
} catch (e: Throwable) {
    stderr(taskErrorJson("cannot reach the kotgent daemon: ${e.message}"))
    1
}

/**
 * Which task a ref-less subcommand is about.
 *
 * Three branches, and the middle one is the whole `--session` rule: an explicit [ref] asks nobody, an
 * explicit [session] asks that session's own row (never `/whoami`, which answers about a PANE this
 * caller may not have), and only a caller with neither asks `/whoami`.
 */
private suspend fun resolveSubjectRef(
    ref: String?,
    session: String?,
    whoami: suspend () -> WhoamiDto,
    findSession: suspend (String) -> SessionDto?,
): String {
    if (ref != null) return ref
    if (session != null) {
        val row = findSession(session)
            ?: throw TaskSubjectException("no session '$session' — check `kotgent list`")
        return row.taskRef ?: throw TaskSubjectException(
            "session '$session' is not linked to a task — name one: kotgent task <command> <ref> --session $session",
        )
    }
    return whoami().taskRef ?: throw TaskSubjectException(
        "this session is not linked to a task — name one, or link one with `kotgent task claim <ref>`",
    )
}

/**
 * Which project a project-scoped subcommand is about, or `null` for "let the daemon answer".
 *
 * [resolveSubjectRef]'s counterpart, and it obeys the same `--session` rule: an explicit [project] asks
 * nobody, an explicit [session] is answered from that session's own row, and only a caller with neither
 * leaves the question to the daemon (which resolves the calling pane).
 *
 * The two throws are the whole point. `GET /tasks` carries no body, so a `--session` the CLI failed to
 * answer would go out as a request naming no project at all — and the daemon would then answer from the
 * CALLING PANE, i.e. `kotgent task list --session <other>` would silently print this pane's own backlog
 * with exit `0`. An unknown session and a session carrying no project therefore both fail here, the way
 * [resolveSubjectRef] already fails for the same two shapes.
 */
private suspend fun resolveSubjectProject(
    command: String,
    project: String?,
    session: String?,
    findSession: suspend (String) -> SessionDto?,
): String? {
    if (project != null) return project
    if (session == null) return null
    val row = findSession(session)
        ?: throw TaskSubjectException("no session '$session' — check `kotgent list`")
    return row.projectId ?: throw TaskSubjectException(
        "session '$session' resolves to no project — name one: kotgent $command --project <uuid>",
    )
}

/**
 * Run [call] with no project and, when the daemon answers that it could not resolve one, run it again
 * naming the project the CLI's OWN working directory resolves to.
 *
 * This closes the first-run loop. `kotgent task add` creates `.kotgent.json` when a session has no
 * project yet, but the file it writes is not the session ROW: the row's `project_id` is written when the
 * row is inserted, so a `task list` / `task next` in the very session that just created the project asks
 * a question the daemon's row still cannot answer. The CLI is standing IN the checkout and can — with
 * exactly the pure walk the daemon runs ([resolveProject]).
 *
 * Two properties keep it honest. It is a **fallback, not a precedence change**: a request the daemon
 * answered is never second-guessed, so a pane whose row does name a project keeps deciding — the same
 * project `task add` files into. And a failed retry rethrows the **original** refusal, because the
 * caller never named the fallback project and an error about it (a `404` for a uuid they have not seen)
 * would be an answer to a request they did not make; the original names `--project`, which is still the
 * fix. The retry costs one extra round trip only on a path that had already failed.
 */
private suspend fun <T> withCwdProjectFallback(
    resolveCwdProjectId: () -> String?,
    call: suspend (String?) -> T,
): T = try {
    call(null)
} catch (noProject: ApiException) {
    if (noProject.status != HTTP_BAD_REQUEST) throw noProject
    val local = resolveCwdProjectId() ?: throw noProject
    try {
        call(local)
    } catch (retryFailed: ApiException) {
        throw noProject.also { it.addSuppressed(retryFailed) }
    }
}

/**
 * The project the CLI's own working directory resolves to, by the pure walk in `src/task/ProjectFile.kt`
 * over the real filesystem — the same rule the daemon applies to a session's cwd.
 *
 * A function rather than a value: it reads the environment, so it must stay off every path that does not
 * need it (see [withCwdProjectFallback], its only caller).
 */
private fun currentCwdProjectId(): String? =
    resolveProject(PosixProjectFs(), currentWorkingDir())?.id?.value

/** The daemon's "I could not resolve a project" — the one status [withCwdProjectFallback] answers. */
private const val HTTP_BAD_REQUEST: Int = 400

/** `task add` — create and print the new entry. */
suspend fun runTaskAddCommand(
    title: String,
    body: String?,
    project: String?,
    session: String?,
    createTask: suspend (title: String, body: String, project: String?, sessionId: String?) -> BacklogEntryDto,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    TaskOutput(entryJson(createTask(title, body ?: "", project, session)))
}

/**
 * `task list` — the project's backlog in rank order.
 *
 * With `--session` and no `--project` the project comes from that session's own row: `GET /tasks` carries
 * no body, so the daemon has nothing to resolve a session from, and without this the one documented way
 * to run these commands from outside a pane would not work for `list`. That resolution never falls
 * through to the pane — see [resolveSubjectProject]. With neither flag the daemon answers from the
 * calling pane, and [withCwdProjectFallback] covers the one case it cannot.
 */
suspend fun runTaskListCommand(
    project: String?,
    session: String?,
    findSession: suspend (String) -> SessionDto?,
    listTasks: suspend (String?) -> List<BacklogEntryDto>,
    resolveCwdProjectId: () -> String?,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    val named = resolveSubjectProject("task list", project, session, findSession)
    val entries =
        if (named != null) listTasks(named) else withCwdProjectFallback(resolveCwdProjectId, listTasks)
    TaskOutput(TRANSPORT_JSON.encodeToString(ListSerializer(BacklogEntryDto.serializer()), entries))
}

/** `task show` — one task in full. */
suspend fun runTaskShowCommand(
    ref: String?,
    session: String?,
    whoami: suspend () -> WhoamiDto,
    findSession: suspend (String) -> SessionDto?,
    taskDetail: suspend (String) -> TaskDetailDto,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    val subject = resolveSubjectRef(ref, session, whoami, findSession)
    TaskOutput(TRANSPORT_JSON.encodeToString(TaskDetailDto.serializer(), taskDetail(subject)))
}

/**
 * `task next` — take the next eligible task, or exit `3`.
 *
 * A null answer is not a failure: the route says so with a `200` carrying a null task precisely so this
 * can be told apart from an error, and the printed `{"task":null}` keeps the JSON-only contract for a
 * caller that reads the answer rather than the code.
 *
 * Unlike `task list`, the session is resolved by the DAEMON here — `POST /tasks/next` carries a body, so
 * `--session` rides in it — and only the project is the CLI's problem. Hence no [findSession] seam: with
 * either flag the request goes as written, and with neither it takes [withCwdProjectFallback].
 */
suspend fun runTaskNextCommand(
    project: String?,
    session: String?,
    nextTask: suspend (String?) -> BacklogEntryDto?,
    resolveCwdProjectId: () -> String?,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    val entry = if (project != null || session != null) {
        nextTask(project)
    } else {
        withCwdProjectFallback(resolveCwdProjectId, nextTask)
    }
    if (entry == null) {
        TaskOutput(
            TRANSPORT_JSON.encodeToString(
                JsonObject.serializer(),
                buildJsonObject { put("task", JsonNull) },
            ),
            exitCode = TASK_NEXT_NOTHING_ELIGIBLE,
        )
    } else {
        TaskOutput(entryJson(entry))
    }
}

/** The exit code `task next` reserves for "nothing eligible" — never used for a failure. */
const val TASK_NEXT_NOTHING_ELIGIBLE: Int = 3

/** `task claim` — link this session to a named task. Unconditional; an `in_progress` task just gains one. */
suspend fun runTaskClaimCommand(
    ref: String,
    session: String?,
    linkTask: suspend (String, String?) -> Unit,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    linkTask(ref, session)
    TaskOutput(acknowledgement(ref, "linked", session))
}

/** `task comment` — append one activity row and print it. */
suspend fun runTaskCommentCommand(
    ref: String?,
    message: String,
    session: String?,
    whoami: suspend () -> WhoamiDto,
    findSession: suspend (String) -> SessionDto?,
    commentOnTask: suspend (ref: String, text: String, sessionId: String?) -> ActivityEntryDto,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    val subject = resolveSubjectRef(ref, session, whoami, findSession)
    TaskOutput(
        TRANSPORT_JSON.encodeToString(
            ActivityEntryDto.serializer(),
            commentOnTask(subject, message, session),
        ),
    )
}

/**
 * `task review` and `task done` — one `PATCH` carrying the state and, when given, its explanation.
 *
 * The two share a body because they differ only in [state]: the transition and its activity row commit in
 * one task-store transaction, which is what makes `-m` part of the same operation rather than a second
 * request that can be lost.
 */
suspend fun runTaskTransitionCommand(
    ref: String?,
    state: String,
    message: String?,
    session: String?,
    whoami: suspend () -> WhoamiDto,
    findSession: suspend (String) -> SessionDto?,
    patchTask: suspend (ref: String, state: String, message: String?, sessionId: String?) -> BacklogEntryDto,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    val subject = resolveSubjectRef(ref, session, whoami, findSession)
    TaskOutput(entryJson(patchTask(subject, state, message, session)))
}

/** `task unlink` — drop this session's link; the task's own state is untouched. */
suspend fun runTaskUnlinkCommand(
    ref: String?,
    session: String?,
    whoami: suspend () -> WhoamiDto,
    findSession: suspend (String) -> SessionDto?,
    unlinkTask: suspend (String, String?) -> Unit,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    val subject = resolveSubjectRef(ref, session, whoami, findSession)
    unlinkTask(subject, session)
    TaskOutput(acknowledgement(subject, "unlinked", session))
}

/** `task move` — re-rank within the project and print the moved entry. */
suspend fun runTaskMoveCommand(
    ref: String,
    target: MoveTarget,
    moveTask: suspend (String, MoveTarget) -> BacklogEntryDto,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    TaskOutput(entryJson(moveTask(ref, target)))
}

/**
 * `task dep add|rm` — add or remove "ref depends on other", printing the edited task.
 *
 * The route answers the updated entry, and that entry is the answer: what a dependency edit CHANGES is
 * `blocked`, which is derived (`state == todo` and some dependency is not `done`) and therefore cannot be
 * worked out from the request. Echoing the request instead — which is what this did — left the one
 * consumer with no socket having to issue a second `task show` to learn whether the ref it just made
 * dependent is still workable, after the skill told it to treat `blocked` as "not workable".
 *
 * The four refusals (unknown ref, cross-project, self, cycle) are `400`s and print as errors.
 */
suspend fun runTaskDepCommand(
    ref: String,
    on: String,
    remove: Boolean,
    editTaskDependency: suspend (ref: String, action: String, on: String) -> BacklogEntryDto,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    TaskOutput(entryJson(editTaskDependency(ref, if (remove) "remove" else "add", on)))
}

/**
 * `task delete` — unlink every holder, then remove the task.
 *
 * A `false` answer means the ref named no task. That is a **failure** for the caller (exit `1`), not a
 * quiet success: a script that deletes what it just created and is told "nothing there" has hit a real
 * problem, and printing `deleted: false` on stdout would put a failure on the success stream.
 */
suspend fun runTaskDeleteCommand(
    ref: String,
    deleteTask: suspend (String) -> Boolean,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    if (!deleteTask(ref)) throw TaskSubjectException("no task '$ref' — nothing was deleted")
    TaskOutput(acknowledgement(ref, "deleted", session = null))
}

/** `project list` — every project the daemon knows, as a JSON array. */
suspend fun runProjectListCommand(
    listProjects: suspend () -> List<ProjectDto>,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    TaskOutput(TRANSPORT_JSON.encodeToString(ListSerializer(ProjectDto.serializer()), listProjects()))
}

/**
 * `project init [<path>]` — write `.kotgent.json` at the main checkout root for [path].
 *
 * The path is resolved to an ABSOLUTE one against [callerCwd] first, the same way `start` resolves its
 * cwd and for the same reason: the daemon runs under launchd with cwd `/`, so a relative path sent
 * verbatim would create the project file in the wrong place entirely. An unresolvable one exits `2`
 * (a usage error) rather than guessing, matching `runStart`.
 */
suspend fun runProjectInitCommand(
    path: String?,
    name: String?,
    callerCwd: String,
    createProject: suspend (String, String?) -> ProjectDto,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int {
    val absolute = try {
        resolveCwdAgainst(callerCwd, path)
    } catch (e: UnresolvableCwdException) {
        stderr(taskErrorJson(e.message ?: "cannot resolve the project directory"))
        return 2
    }
    return runTaskCommand(stdout, stderr) {
        TaskOutput(TRANSPORT_JSON.encodeToString(ProjectDto.serializer(), createProject(absolute, name)))
    }
}

/**
 * `start --task` — pick the directory, then start the session and its link in ONE request.
 *
 * The rule, in order, with the chosen branch named in the output as `cwdSource`:
 *  0. `explicit-cwd` — the operator NAMED a directory (`start claude /some/dir --task <ref>`). Nothing
 *     below may override it: `USAGE` documents that positional as where the session starts, and
 *     `projects.path` is by definition "the checkout the daemon saw most recently", i.e. not authoritative
 *     about anything. Starting somewhere the operator did not type, on the strength of a stale row, is the
 *     one outcome no branch here may produce — and the caller's own cwd is the same STRING as a named one,
 *     so [cwdExplicit] is the only thing that can tell them apart.
 *  1. `caller-cwd` — no directory was named and the caller's own cwd resolves to the task's project. The
 *     operator is standing in the right checkout (and in a worktree that is the checkout they mean, which
 *     a stored path cannot know).
 *  2. `project-path` — it does not, but the project's last-seen path is still a directory.
 *  3. `caller-cwd-fallback` — that stored path is stale (a checkout that moved or was deleted), so the
 *     caller's cwd is used after all. Starting in a directory that no longer exists would fail the launch
 *     for a reason that has nothing to do with what the operator asked for.
 *
 * The detail fetch is not incidental: it is where the task's project comes from, and it fails a mistyped
 * ref with the daemon's `404` **before** any tmux side effect. It happens even for an explicit cwd, which
 * is why branch 0 does not short-circuit the whole body.
 */
suspend fun runStartWithTaskCommand(
    agent: String,
    callerCwd: String,
    cwdExplicit: Boolean,
    taskRef: String,
    name: String?,
    tags: List<String>,
    taskDetail: suspend (String) -> TaskDetailDto,
    startSession: suspend (
        agent: String,
        cwd: String,
        name: String?,
        tags: List<String>,
        taskRef: String?,
    ) -> SessionDto,
    resolveProjectId: (String) -> String?,
    isDirectory: (String) -> Boolean,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    val detail = taskDetail(taskRef)
    val storedPath = detail.projectPath
    val chosen = when {
        cwdExplicit -> callerCwd to "explicit-cwd"
        resolveProjectId(callerCwd) == detail.task.project -> callerCwd to "caller-cwd"
        storedPath != null && isDirectory(storedPath) -> storedPath to "project-path"
        else -> callerCwd to "caller-cwd-fallback"
    }
    val session = startSession(agent, chosen.first, name, tags, taskRef)
    TaskOutput(
        TRANSPORT_JSON.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("taskRef", taskRef)
                put("cwd", chosen.first)
                put("cwdSource", chosen.second)
                put("session", TRANSPORT_JSON.encodeToJsonElement(SessionDto.serializer(), session))
            },
        ),
    )
}

// --- small shared renderers ------------------------------------------------------------------------

/** One backlog entry, the shape every write that answers with a row prints. */
private fun entryJson(entry: BacklogEntryDto): String =
    TRANSPORT_JSON.encodeToString(BacklogEntryDto.serializer(), entry)

/** A route that answers no body, echoed: which ref, what happened to it, and on whose behalf. */
private fun acknowledgement(ref: String, verb: String, session: String?): String =
    TRANSPORT_JSON.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("ref", ref)
            put(verb, true)
            if (session != null) put("sessionId", session)
        },
    )

/**
 * "Find this session's row", the one seam the two `--session` resolutions share.
 *
 * There is no single-session GET on the control surface, so the row comes out of the list every other
 * command already uses. It is only ever reached when `--session` was given, i.e. once per invocation and
 * never on the pane path.
 */
private fun ApiClient.sessionFinder(): suspend (String) -> SessionDto? =
    { id -> listSessions().firstOrNull { it.id == id } }
