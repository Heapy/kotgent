package io.kotgent.cli

import io.kotgent.task.MoveTarget
import io.kotgent.core.TaskRef
import io.kotgent.transport.ActivityEntryDto
import io.kotgent.transport.BacklogEntryDto
import io.kotgent.transport.ProjectDto
import io.kotgent.transport.SessionDto
import io.kotgent.transport.TaskDetailDto
import io.kotgent.transport.WhoamiDto
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 21: the `task` / `project` command bodies.
 *
 * Every test drives a `run…Command` free function directly, with fakes for the daemon calls and lists for
 * the two output sinks — the `runImportCommand` shape at `CliTest`. That is not a stylistic choice: a test
 * that went through a live [ApiClient] would hit Task 20's `TODO()` bodies, and Kotlin/Native offers no way
 * to capture `println`, so the sinks are the only way to assert what is printed at all.
 *
 * What is deliberately NOT tested here: [TaskCommands]' own entry points. They exist to resolve the pane
 * ([TmuxSelf.currentPane], Task 18) and build a client, and that is exactly the environment-reading edge
 * the seam was introduced to keep out of a tested path.
 */
class TaskCommandsTest {

    // --- add / list ------------------------------------------------------------------------------

    @Test
    fun addPrintsTheCreatedEntryAsJson() = runCommandTest {
        val out = Sinks()
        var seen: List<Any?>? = null
        val exit = runTaskAddCommand(
            title = "wire the board",
            body = null,
            project = PROJECT,
            session = null,
            createTask = { t, b, p, s -> seen = listOf(t, b, p, s); entry("local:1", title = t) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        out.assertNoErrors()
        val json = out.onlyJsonObject()
        assertEquals("local:1", json["ref"]?.jsonPrimitive?.content)
        assertEquals("wire the board", json["title"]?.jsonPrimitive?.content)
        // A null --body reaches the daemon as "", never as JSON null: `body` is non-null on the wire.
        assertEquals(listOf("wire the board", "", PROJECT, null), seen)
    }

    @Test
    fun listPrintsTheBacklogAsAJsonArray() = runCommandTest {
        val out = Sinks()
        val asked = mutableListOf<String?>()
        val exit = runTaskListCommand(
            project = PROJECT,
            session = null,
            findSession = { error("no session lookup when --project was given") },
            listTasks = { p -> asked += p; listOf(entry("local:1"), entry("local:2")) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        out.assertNoErrors()
        val rows = Json.parseToJsonElement(out.only()).jsonArray
        assertEquals(listOf("local:1", "local:2"), rows.map { it.jsonObject["ref"]?.jsonPrimitive?.content })
        assertEquals(listOf<String?>(PROJECT), asked)
    }

    @Test
    fun listResolvesTheProjectFromTheNamedSessionWithoutWhoami() = runCommandTest {
        val out = Sinks()
        val asked = mutableListOf<String?>()
        val exit = runTaskListCommand(
            project = null,
            session = "sess0001",
            findSession = { id -> session(id, projectId = PROJECT) },
            listTasks = { p -> asked += p; emptyList() },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        // GET /tasks carries no body, so the daemon cannot resolve a session; the CLI reads the row itself.
        assertEquals(listOf<String?>(PROJECT), asked, "the named session's project is what gets listed")
        assertEquals("[]", out.only())
    }

    // --- the /whoami rule ------------------------------------------------------------------------

    @Test
    fun showResolvesARefLessSubjectThroughWhoami() = runCommandTest {
        val out = Sinks()
        var whoamiCalls = 0
        val fetched = mutableListOf<String>()
        val exit = runTaskShowCommand(
            ref = null,
            session = null,
            whoami = { whoamiCalls++; WhoamiDto(sessionId = "sess0001", projectId = PROJECT, taskRef = "local:7") },
            findSession = { error("no session lookup on the pane path") },
            taskDetail = { r -> fetched += r; detail(entry(r)) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(1, whoamiCalls)
        assertEquals(listOf("local:7"), fetched)
        assertEquals("local:7", out.onlyJsonObject()["task"]?.jsonObject?.get("ref")?.jsonPrimitive?.content)
    }

    @Test
    fun showWithAnExplicitSessionNeverCallsWhoami() = runCommandTest {
        val out = Sinks()
        val fetched = mutableListOf<String>()
        val exit = runTaskShowCommand(
            ref = null,
            session = "sess0001",
            whoami = { error("--session must skip /whoami entirely — it is pane resolution") },
            findSession = { id -> session(id, taskRef = "local:9") },
            taskDetail = { r -> fetched += r; detail(entry(r)) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        out.assertNoErrors()
        assertEquals(listOf("local:9"), fetched, "the named session's own row names the subject")
    }

    @Test
    fun showWithAnExplicitRefAsksNobodyAnything() = runCommandTest {
        val out = Sinks()
        val exit = runTaskShowCommand(
            ref = "local:3",
            session = "sess0001",
            whoami = { error("an explicit ref resolves nothing") },
            findSession = { error("an explicit ref resolves nothing") },
            taskDetail = { r -> detail(entry(r)) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals("local:3", out.onlyJsonObject()["task"]?.jsonObject?.get("ref")?.jsonPrimitive?.content)
    }

    @Test
    fun aRefLessCommandOutsideAPaneFailsCleanly() = runCommandTest {
        val out = Sinks()
        val exit = runTaskShowCommand(
            ref = null,
            session = null,
            // Outside a kotgent pane the daemon resolves nobody, so /whoami answers with a null ref.
            whoami = { WhoamiDto() },
            findSession = { error("no session lookup on the pane path") },
            taskDetail = { error("there is no subject to fetch") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        assertTrue(out.stdout.isEmpty(), "a failure prints nothing on stdout: ${out.stdout}")
        val err = out.onlyErrorJson()
        assertTrue("not linked to a task" in err, "the error says what is missing: $err")
        assertTrue("task claim" in err, "the error says how to fix it: $err")
    }

    @Test
    fun aRefLessCommandNamingASessionWithNoTaskFailsCleanly() = runCommandTest {
        val out = Sinks()
        val exit = runTaskShowCommand(
            ref = null,
            session = "sess0001",
            whoami = { error("--session must skip /whoami entirely") },
            findSession = { id -> session(id, taskRef = null) },
            taskDetail = { error("there is no subject to fetch") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        assertTrue("sess0001" in out.onlyErrorJson(), "the error names the session it asked about")
    }

    @Test
    fun aRefLessCommandNamingAnUnknownSessionFailsCleanly() = runCommandTest {
        val out = Sinks()
        val exit = runTaskShowCommand(
            ref = null,
            session = "ghost001",
            whoami = { error("--session must skip /whoami entirely") },
            findSession = { null },
            taskDetail = { error("there is no subject to fetch") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        assertTrue("ghost001" in out.onlyErrorJson(), "the error names the session it could not find")
    }

    // --- next ------------------------------------------------------------------------------------

    @Test
    fun nextPrintsTheLinkedEntry() = runCommandTest {
        val out = Sinks()
        val exit = runTaskNextCommand(
            nextTask = { entry("local:4", state = "in_progress") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        val json = out.onlyJsonObject()
        assertEquals("local:4", json["ref"]?.jsonPrimitive?.content)
        assertEquals("in_progress", json["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun nextExitsThreeOnAnEmptyBacklogAndStillPrintsJson() = runCommandTest {
        val out = Sinks()
        val exit = runTaskNextCommand(
            nextTask = { null },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(TASK_NEXT_NOTHING_ELIGIBLE, exit)
        assertEquals(3, exit, "nothing eligible is exit 3, and only 3 — a script must tell it from a failure")
        out.assertNoErrors()
        assertEquals(JsonNull, out.onlyJsonObject()["task"], "the answer is parseable, not an empty stream")
    }

    // --- claim / comment / review / done / unlink --------------------------------------------------

    @Test
    fun claimAcknowledgesTheLink() = runCommandTest {
        val out = Sinks()
        val linked = mutableListOf<Pair<String, String?>>()
        val exit = runTaskClaimCommand(
            ref = "local:5",
            session = "sess0001",
            linkTask = { r, s -> linked += r to s },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf<Pair<String, String?>>("local:5" to "sess0001"), linked)
        val json = out.onlyJsonObject()
        assertEquals("local:5", json["ref"]?.jsonPrimitive?.content)
        assertEquals(true, json["linked"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("sess0001", json["sessionId"]?.jsonPrimitive?.content)
    }

    @Test
    fun commentPrintsTheActivityRowItCreated() = runCommandTest {
        val out = Sinks()
        var seen: List<String?>? = null
        val exit = runTaskCommentCommand(
            ref = "local:6",
            message = "rebased onto main",
            session = null,
            whoami = { error("an explicit ref resolves nothing") },
            findSession = { error("an explicit ref resolves nothing") },
            commentOnTask = { r, t, s ->
                seen = listOf(r, t, s)
                ActivityEntryDto(id = 12, ref = r, ts = 5, kind = "comment", author = "sess0001", text = t)
            },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf("local:6", "rebased onto main", null), seen)
        assertEquals("comment", out.onlyJsonObject()["kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun reviewPatchesTheReviewStateCarryingItsMessage() = runCommandTest {
        val out = Sinks()
        var seen: List<String?>? = null
        val exit = runTaskTransitionCommand(
            ref = "local:8",
            state = "review",
            message = "ready for a look",
            session = "sess0001",
            whoami = { error("an explicit ref resolves nothing") },
            findSession = { error("an explicit ref resolves nothing") },
            patchTask = { r, st, m, s -> seen = listOf(r, st, m, s); entry(r, state = st) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        // One PATCH, not a transition plus a comment: the message commits with the state or not at all.
        assertEquals(listOf("local:8", "review", "ready for a look", "sess0001"), seen)
        assertEquals("review", out.onlyJsonObject()["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun doneResolvesItsSubjectThroughWhoamiAndPatchesTheDoneState() = runCommandTest {
        val out = Sinks()
        var seen: List<String?>? = null
        val exit = runTaskTransitionCommand(
            ref = null,
            state = "done",
            message = null,
            session = null,
            whoami = { WhoamiDto(sessionId = "sess0001", taskRef = "local:10") },
            findSession = { error("no session lookup on the pane path") },
            patchTask = { r, st, m, s -> seen = listOf(r, st, m, s); entry(r, state = st) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf("local:10", "done", null, null), seen)
        assertEquals("done", out.onlyJsonObject()["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun unlinkAcknowledgesTheDroppedLink() = runCommandTest {
        val out = Sinks()
        val dropped = mutableListOf<Pair<String, String?>>()
        val exit = runTaskUnlinkCommand(
            ref = null,
            session = null,
            whoami = { WhoamiDto(sessionId = "sess0001", taskRef = "local:11") },
            findSession = { error("no session lookup on the pane path") },
            unlinkTask = { r, s -> dropped += r to s },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf<Pair<String, String?>>("local:11" to null), dropped)
        assertEquals(true, out.onlyJsonObject()["unlinked"]?.jsonPrimitive?.content?.toBoolean())
    }

    // --- move / dep / delete -----------------------------------------------------------------------

    @Test
    fun movePassesItsTargetThroughUntouched() = runCommandTest {
        val out = Sinks()
        val moved = mutableListOf<Pair<String, MoveTarget>>()
        val target = MoveTarget.Before(TaskRef("local:2"))
        val exit = runTaskMoveCommand(
            ref = "local:1",
            target = target,
            moveTask = { r, t -> moved += r to t; entry(r, position = 1.5) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf<Pair<String, MoveTarget>>("local:1" to target), moved)
        assertEquals("1.5", out.onlyJsonObject()["position"]?.jsonPrimitive?.content)
    }

    @Test
    fun dependencyEditsNameTheirDirection() = runCommandTest {
        val out = Sinks()
        val edits = mutableListOf<List<String>>()
        val added = runTaskDepCommand(
            ref = "local:1",
            on = "local:2",
            remove = false,
            editTaskDependency = { r, a, o -> edits += listOf(r, a, o) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        val removed = runTaskDepCommand(
            ref = "local:1",
            on = "local:2",
            remove = true,
            editTaskDependency = { r, a, o -> edits += listOf(r, a, o) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, added)
        assertEquals(0, removed)
        assertEquals(
            listOf(listOf("local:1", "add", "local:2"), listOf("local:1", "remove", "local:2")),
            edits,
        )
        out.assertNoErrors()
        val second = Json.parseToJsonElement(out.stdout.last()).jsonObject
        assertEquals("remove", second["action"]?.jsonPrimitive?.content)
        assertEquals("local:2", second["on"]?.jsonPrimitive?.content)
    }

    @Test
    fun deleteAcknowledgesARemovedTask() = runCommandTest {
        val out = Sinks()
        val exit = runTaskDeleteCommand(
            ref = "local:1",
            deleteTask = { true },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(true, out.onlyJsonObject()["deleted"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun deletingNothingIsAFailureNotAQuietSuccess() = runCommandTest {
        val out = Sinks()
        val exit = runTaskDeleteCommand(
            ref = "local:404",
            deleteTask = { false },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        assertTrue(out.stdout.isEmpty(), "a failure never prints on the success stream: ${out.stdout}")
        assertTrue("local:404" in out.onlyErrorJson(), "the error names the ref that matched nothing")
    }

    // --- projects ----------------------------------------------------------------------------------

    @Test
    fun projectListPrintsEveryKnownProject() = runCommandTest {
        val out = Sinks()
        val exit = runProjectListCommand(
            listProjects = {
                listOf(
                    ProjectDto(id = PROJECT, name = "kotgent", path = "/repo", updatedAt = 3),
                    ProjectDto(id = OTHER_PROJECT, name = "other", path = null, updatedAt = 4),
                )
            },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        val rows = Json.parseToJsonElement(out.only()).jsonArray
        assertEquals(listOf("kotgent", "other"), rows.map { it.jsonObject["name"]?.jsonPrimitive?.content })
    }

    @Test
    fun projectInitDefaultsToTheCallerCwdAndSendsAnAbsolutePath() = runCommandTest {
        val out = Sinks()
        val created = mutableListOf<Pair<String, String?>>()
        val exit = runProjectInitCommand(
            path = null,
            name = "kotgent",
            callerCwd = "/repo/sub",
            createProject = { p, n -> created += p to n; ProjectDto(PROJECT, n ?: "kotgent", p, 1) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf<Pair<String, String?>>("/repo/sub" to "kotgent"), created)
        assertEquals("/repo/sub", out.onlyJsonObject()["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun projectInitAnchorsARelativePathAtTheCallerCwd() = runCommandTest {
        val out = Sinks()
        val created = mutableListOf<String>()
        val exit = runProjectInitCommand(
            path = "./nested",
            name = null,
            callerCwd = "/repo",
            createProject = { p, _ -> created += p; ProjectDto(PROJECT, "nested", p, 1) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        // The daemon runs under launchd with cwd `/`, so a relative path must never leave the CLI.
        assertEquals(listOf("/repo/nested"), created)
    }

    @Test
    fun projectInitRefusesARelativePathItCannotAnchor() = runCommandTest {
        val out = Sinks()
        val exit = runProjectInitCommand(
            path = "nested",
            name = null,
            // What currentWorkingDir() falls back to when getcwd fails — resolving against it would send
            // the daemon a relative path, which it would read against `/`.
            callerCwd = ".",
            createProject = { _, _ -> error("nothing may be created from an unresolvable path") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(2, exit, "an unresolvable directory is a usage error, like start's")
        assertTrue(out.stdout.isEmpty(), "a failure never prints on the success stream: ${out.stdout}")
        assertTrue(out.onlyErrorJson().isNotEmpty())
    }

    // --- start --task's cwd rule ---------------------------------------------------------------------

    @Test
    fun startWithTaskPrefersTheCallerCwdWhenItResolvesToTheTaskProject() = runCommandTest {
        val out = Sinks()
        val started = mutableListOf<List<Any?>>()
        val exit = runStartWithTaskCommand(
            agent = "claude",
            callerCwd = "/repo-wt/feature",
            taskRef = "local:1",
            name = "wt",
            tags = listOf("a"),
            taskDetail = { r -> detail(entry(r), projectPath = "/repo") },
            startSession = { a, c, n, t, r -> started += listOf(a, c, n, t, r); startedSession(c) },
            // A worktree of the same checkout: one uuid, a different directory — and the operator is
            // standing in the one they mean, which the stored path cannot know.
            resolveProjectId = { dir -> if (dir == "/repo-wt/feature") PROJECT else null },
            isDirectory = { true },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf<List<Any?>>(listOf("claude", "/repo-wt/feature", "wt", listOf("a"), "local:1")), started)
        val json = out.onlyJsonObject()
        assertEquals("/repo-wt/feature", json["cwd"]?.jsonPrimitive?.content)
        assertEquals("caller-cwd", json["cwdSource"]?.jsonPrimitive?.content)
        assertEquals("local:1", json["taskRef"]?.jsonPrimitive?.content)
        assertEquals("/repo-wt/feature", json["session"]?.jsonObject?.get("cwd")?.jsonPrimitive?.content)
    }

    @Test
    fun startWithTaskFallsBackToTheStoredProjectPath() = runCommandTest {
        val out = Sinks()
        val started = mutableListOf<String>()
        val exit = runStartWithTaskCommand(
            agent = "codex",
            callerCwd = "/elsewhere",
            taskRef = "local:1",
            name = null,
            tags = emptyList(),
            taskDetail = { r -> detail(entry(r), projectPath = "/repo") },
            startSession = { _, c, _, _, _ -> started += c; startedSession(c) },
            resolveProjectId = { OTHER_PROJECT },
            isDirectory = { it == "/repo" },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf("/repo"), started)
        val json = out.onlyJsonObject()
        assertEquals("/repo", json["cwd"]?.jsonPrimitive?.content)
        assertEquals("project-path", json["cwdSource"]?.jsonPrimitive?.content)
    }

    @Test
    fun startWithTaskFallsBackToTheCallerCwdWhenTheStoredPathIsStale() = runCommandTest {
        val out = Sinks()
        val started = mutableListOf<String>()
        val exit = runStartWithTaskCommand(
            agent = "claude",
            callerCwd = "/elsewhere",
            taskRef = "local:1",
            name = null,
            tags = emptyList(),
            // projects.path is "the checkout the daemon saw most recently" — it can name a directory that
            // has since moved or been deleted, and starting there would fail for an unrelated reason.
            taskDetail = { r -> detail(entry(r), projectPath = "/gone") },
            startSession = { _, c, _, _, _ -> started += c; startedSession(c) },
            resolveProjectId = { null },
            isDirectory = { false },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf("/elsewhere"), started)
        assertEquals("caller-cwd-fallback", out.onlyJsonObject()["cwdSource"]?.jsonPrimitive?.content)
    }

    @Test
    fun startWithTaskFallsBackToTheCallerCwdWhenTheProjectHasNoStoredPath() = runCommandTest {
        val out = Sinks()
        val exit = runStartWithTaskCommand(
            agent = "claude",
            callerCwd = "/elsewhere",
            taskRef = "local:1",
            name = null,
            tags = emptyList(),
            taskDetail = { r -> detail(entry(r), projectPath = null) },
            startSession = { _, c, _, _, _ -> startedSession(c) },
            resolveProjectId = { null },
            isDirectory = { error("a null path is never probed") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals("caller-cwd-fallback", out.onlyJsonObject()["cwdSource"]?.jsonPrimitive?.content)
    }

    @Test
    fun startWithTaskFailsBeforeLaunchingWhenTheRefIsUnknown() = runCommandTest {
        val out = Sinks()
        val exit = runStartWithTaskCommand(
            agent = "claude",
            callerCwd = "/repo",
            taskRef = "local:404",
            name = null,
            tags = emptyList(),
            taskDetail = { throw ApiException(404, "no task 'local:404'") },
            startSession = { _, _, _, _, _ -> error("nothing may be launched for an unknown task") },
            resolveProjectId = { PROJECT },
            isDirectory = { true },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        val err = Json.parseToJsonElement(out.stderr.single()).jsonObject
        assertEquals(404, err["status"]?.jsonPrimitive?.content?.toInt())
        assertTrue("local:404" in (err["error"]?.jsonPrimitive?.content ?: ""))
    }

    // --- failure rendering ---------------------------------------------------------------------------

    @Test
    fun aDaemonFailurePrintsOneLineOfJsonOnStderrCarryingTheStatus() = runCommandTest {
        val out = Sinks()
        val exit = runTaskAddCommand(
            title = "t",
            body = null,
            project = null,
            session = null,
            createTask = { _, _, _, _ -> throw ApiException(400, "no project for this session — pass --project") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        assertTrue(out.stdout.isEmpty())
        val err = Json.parseToJsonElement(out.stderr.single()).jsonObject
        assertEquals(400, err["status"]?.jsonPrimitive?.content?.toInt())
        assertTrue("--project" in (err["error"]?.jsonPrimitive?.content ?: ""), "the daemon's own text survives")
    }

    @Test
    fun anUnreachableDaemonIsStillJsonAndNeverAStackTrace() = runCommandTest {
        val out = Sinks()
        val exit = runProjectListCommand(
            listProjects = { throw RuntimeException("Connection refused") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        val err = Json.parseToJsonElement(out.stderr.single()).jsonObject
        assertTrue("Connection refused" in (err["error"]?.jsonPrimitive?.content ?: ""))
        assertNull(err["status"], "no HTTP status when there was no HTTP answer")
    }

    @Test
    fun aMissingTokenIsReportedAsJsonToo() = runCommandTest {
        val out = Sinks()
        val exit = runProjectListCommand(
            listProjects = { throw MissingTokenException("/home/u/.kotgent/token") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        assertTrue("kotgent daemon" in out.onlyErrorJson(), "the setup hint survives the JSON wrapper")
    }

    // --- fixtures ------------------------------------------------------------------------------------

    /** Both output streams, plus the assertions every test makes about them. */
    private class Sinks {
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()

        fun only(): String = stdout.single()

        fun onlyJsonObject() = Json.parseToJsonElement(only()).jsonObject

        fun onlyErrorJson(): String = stderr.single()

        fun assertNoErrors() = assertTrue(stderr.isEmpty(), "nothing was expected on stderr: $stderr")
    }

    /**
     * Every command body is a suspend function and every test bounds it, the suite-wide anti-hang rule —
     * a fake that never answers must fail the test rather than park the runner.
     */
    private fun runCommandTest(block: suspend () -> Unit) = runBlocking {
        withTimeout(30_000) { block() }
    }

    private fun entry(
        ref: String,
        title: String = "a task",
        state: String = "todo",
        position: Double = 1.0,
    ) = BacklogEntryDto(
        ref = ref,
        project = PROJECT,
        title = title,
        body = "",
        position = position,
        state = state,
        blocked = false,
        createdAt = 1,
        updatedAt = 2,
        rev = 3,
    )

    private fun detail(entry: BacklogEntryDto, projectPath: String? = "/repo") =
        TaskDetailDto(task = entry, projectName = "kotgent", projectPath = projectPath)

    private fun session(id: String, taskRef: String? = null, projectId: String? = null) = SessionDto(
        id = id,
        name = "kt-$id",
        tags = emptyList(),
        agent = "claude",
        providerSessionId = null,
        state = "running",
        needsAttention = false,
        alive = true,
        cwd = "/repo",
        tmuxSession = "kt-$id",
        paneId = "%1",
        lastSeq = 1,
        readCursor = 0,
        unread = 0,
        createdAt = 1,
        updatedAt = 2,
        taskRef = taskRef,
        projectId = projectId,
    )

    private fun startedSession(cwd: String) = session("new00001").copy(cwd = cwd)

    private companion object {
        const val PROJECT: String = "0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34"
        const val OTHER_PROJECT: String = "11111111-2222-4333-8444-555555555555"
    }
}
