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
 * Task commands emit JSON on stdout and JSON errors on stderr. An explicit session must bypass `/whoami`
 * and never fall through to the calling pane. Exit 3 is reserved for an empty eligible-task queue; other
 * daemon failures exit 1 and usage failures exit 2.
 */
object TaskCommands {

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

    fun claim(ref: String, session: String?): Int = withTaskApi { api ->
        runTaskClaimCommand(
            ref = ref,
            session = session,
            linkTask = { r, s -> api.linkTask(r, s) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

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

    fun move(ref: String, target: MoveTarget, session: String?): Int = withTaskApi { api ->
        runTaskMoveCommand(
            ref = ref,
            target = target,
            moveTask = { r, t -> api.moveTask(r, t) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

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

    fun delete(ref: String, session: String?): Int = withTaskApi { api ->
        runTaskDeleteCommand(
            ref = ref,
            deleteTask = { r -> api.deleteTask(r) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    fun projectList(archived: Boolean = false): Int = withTaskApi { api ->
        runProjectListCommand(
            listProjects = { api.listProjects(archived) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    fun projectDelete(id: String): Int = withTaskApi { api ->
        runProjectDeleteCommand(
            id = id,
            deleteProject = { api.deleteProject(it) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    fun projectRestore(id: String): Int = withTaskApi { api ->
        runProjectRestoreCommand(
            id = id,
            restoreProject = { api.restoreProject(it) },
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

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
     * Starts and links in one request. [cwdExplicit] distinguishes an operator-supplied path from the same
     * string chosen as a default, because a task project may override only the default.
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
            // Use the daemon's project walk; stale stored paths fall back to the caller safely.
            resolveProjectId = { dir -> resolveProject(fs, dir)?.id?.value },
            isDirectory = fs::isDirectory,
            stdout = ::println,
            stderr = ::eprintln,
        )
    }

    private fun withTaskApi(block: suspend (ApiClient) -> Int): Int = runBlocking {
        ApiClient(paneId = TmuxSelf.currentPane()).use { block(it) }
    }
}

/**
 * A ref-less command has no resolvable pane, session, or linked task.
 */
class TaskSubjectException(message: String) : RuntimeException(message)

private data class TaskOutput(val json: String, val exitCode: Int = 0)

/** Produces the task family's single-line machine-readable stderr format. */
fun taskErrorJson(message: String, status: Int? = null): String = TRANSPORT_JSON.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("error", message)
        if (status != null) put("status", status)
    },
)

/**
 * Converts expected failures to one JSON line without stack traces because callers parse this stream.
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
 * Precedence is explicit [ref], then explicit [session], then the calling pane. A named session never
 * consults `/whoami`.
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
 * Explicit project wins, then explicit session, then daemon pane resolution. Because `GET /tasks` has no
 * body, failure to resolve a named session must stop here or the daemon would silently use the calling
 * pane's project.
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
 * After a no-project refusal, retries with the project resolved from the CLI cwd. This covers sessions
 * created before `.kotgent.json` without overriding a project the daemon already resolved. A failed retry
 * rethrows the original refusal because the caller never named the fallback project.
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
 * Evaluated lazily so environment and filesystem access occur only on the fallback path.
 */
private fun currentCwdProjectId(): String? =
    resolveProject(PosixProjectFs(), currentWorkingDir())?.id?.value

private const val HTTP_BAD_REQUEST: Int = 400

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
 * A named session supplies its own project because `GET /tasks` cannot carry session identity in a body.
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
 * A null task is a successful empty result rendered as JSON with exit 3. Unlike list, this POST carries
 * session identity in its body, so the daemon resolves the session itself.
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

/** Reserved exclusively for an empty eligible-task queue. */
const val TASK_NEXT_NOTHING_ELIGIBLE: Int = 3

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
 * State and explanation share one request so the transition and activity row commit atomically.
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
 * Returns the updated entry because `blocked` is derived from all dependencies and cannot be inferred
 * from the edit request.
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
 * A missing task is a failure rather than a successful no-op, keeping failures off stdout.
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

suspend fun runProjectListCommand(
    listProjects: suspend () -> List<ProjectDto>,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    TaskOutput(TRANSPORT_JSON.encodeToString(ListSerializer(ProjectDto.serializer()), listProjects()))
}

/**
 * Both sides of the tombstone print the project row itself rather than an acknowledgement, because
 * `archived` is the answer a script wants and the daemon is idempotent — a repeat says the same thing
 * instead of failing. A uuid the daemon never saw is a 404, and that reaches stderr as exit 1.
 */
suspend fun runProjectDeleteCommand(
    id: String,
    deleteProject: suspend (String) -> ProjectDto,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runProjectRowCommand(id, deleteProject, stdout, stderr)

/** The counterpart of [runProjectDeleteCommand]; see it for why both answer the row. */
suspend fun runProjectRestoreCommand(
    id: String,
    restoreProject: suspend (String) -> ProjectDto,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runProjectRowCommand(id, restoreProject, stdout, stderr)

private suspend fun runProjectRowCommand(
    id: String,
    call: suspend (String) -> ProjectDto,
    stdout: (String) -> Unit,
    stderr: (String) -> Unit,
): Int = runTaskCommand(stdout, stderr) {
    TaskOutput(TRANSPORT_JSON.encodeToString(ProjectDto.serializer(), call(id)))
}

/**
 * Resolves paths against [callerCwd] before sending them to the launchd daemon, whose cwd is `/`.
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
 * Cwd precedence is explicit path, matching caller checkout, valid stored project path, then caller
 * fallback. The selected source is returned to make fallback visible. Task detail is fetched first so an
 * invalid ref fails before any tmux side effect, even when cwd is explicit.
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

private fun entryJson(entry: BacklogEntryDto): String =
    TRANSPORT_JSON.encodeToString(BacklogEntryDto.serializer(), entry)

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
 * There is no single-session endpoint, so explicit-session resolution searches the session list once.
 */
private fun ApiClient.sessionFinder(): suspend (String) -> SessionDto? =
    { id -> listSessions().firstOrNull { it.id == id } }
