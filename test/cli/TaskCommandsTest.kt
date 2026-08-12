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

class TaskCommandsTest {


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
            resolveCwdProjectId = { error("a named project is never second-guessed against the cwd") },
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
            resolveCwdProjectId = { error("the named session answered; the cwd is not consulted") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf<String?>(PROJECT), asked, "the named session's project is what gets listed")
        assertEquals("[]", out.only())
    }

    @Test
    fun listRefusesAnUnknownSessionInsteadOfListingTheCallingPane() = runCommandTest {
        val out = Sinks()
        val exit = runTaskListCommand(
            project = null,
            session = "ghost001",
            findSession = { null },
            listTasks = { error("nothing may be listed for a session that does not exist") },
            resolveCwdProjectId = { error("an explicit --session is never answered from the cwd") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        assertTrue(out.stdout.isEmpty(), "a failure never prints on the success stream: ${out.stdout}")
        assertTrue("ghost001" in out.onlyErrorJson(), "the error names the session it could not find")
    }

    @Test
    fun listRefusesANamedSessionThatResolvesToNoProject() = runCommandTest {
        val out = Sinks()
        val exit = runTaskListCommand(
            project = null,
            session = "sess0001",
            findSession = { id -> session(id, projectId = null) },
            listTasks = { error("a project-less session must not fall through to the calling pane") },
            resolveCwdProjectId = { error("an explicit --session is never answered from the cwd") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        val err = out.onlyErrorJson()
        assertTrue("sess0001" in err, "the error names the session it asked about: $err")
        assertTrue("--project" in err, "the error says how to answer the question instead: $err")
    }


    @Test
    fun listAsksTheDaemonFirstAndNeverSecondGuessesAnAnswer() = runCommandTest {
        val out = Sinks()
        val asked = mutableListOf<String?>()
        val exit = runTaskListCommand(
            project = null,
            session = null,
            findSession = { error("no session lookup on the pane path") },
            listTasks = { p -> asked += p; listOf(entry("local:1")) },
            resolveCwdProjectId = { error("a successful pane answer is never re-asked from the cwd") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf<String?>(null), asked, "the pane stays the authority when it can answer")
    }

    @Test
    fun listRetriesWithTheCwdProjectWhenTheSessionRowResolvesToNone() = runCommandTest {
        val out = Sinks()
        val asked = mutableListOf<String?>()
        val exit = runTaskListCommand(
            project = null,
            session = null,
            findSession = { error("no session lookup on the pane path") },
            listTasks = { p ->
                asked += p
                if (p == null) throw ApiException(400, NO_PROJECT_BODY) else listOf(entry("local:1"))
            },
            resolveCwdProjectId = { PROJECT },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        out.assertNoErrors()
        assertEquals(listOf(null, PROJECT), asked, "asked the daemon first, then named the cwd's project")
        assertEquals("local:1", Json.parseToJsonElement(out.only()).jsonArray.single().jsonObject["ref"]?.jsonPrimitive?.content)
    }

    @Test
    fun listKeepsTheDaemonsRefusalWhenTheCwdResolvesToNoProject() = runCommandTest {
        val out = Sinks()
        val exit = runTaskListCommand(
            project = null,
            session = null,
            findSession = { error("no session lookup on the pane path") },
            listTasks = { throw ApiException(400, NO_PROJECT_BODY) },
            resolveCwdProjectId = { null },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        val err = Json.parseToJsonElement(out.stderr.single()).jsonObject
        assertEquals(400, err["status"]?.jsonPrimitive?.content?.toInt())
        assertTrue("--project" in (err["error"]?.jsonPrimitive?.content ?: ""), "the daemon's own text survives")
    }

    @Test
    fun listReportsTheOriginalRefusalWhenTheCwdProjectIsUnknownToTheDaemon() = runCommandTest {
        val out = Sinks()
        val exit = runTaskListCommand(
            project = null,
            session = null,
            findSession = { error("no session lookup on the pane path") },
            listTasks = { p ->
                if (p == null) throw ApiException(400, NO_PROJECT_BODY) else throw ApiException(404, "no project '$p'")
            },
            resolveCwdProjectId = { PROJECT },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        val err = Json.parseToJsonElement(out.stderr.single()).jsonObject
        assertEquals(400, err["status"]?.jsonPrimitive?.content?.toInt(), "the retry's 404 is not the caller's error")
        assertTrue("--project" in (err["error"]?.jsonPrimitive?.content ?: ""))
    }

    @Test
    fun listDoesNotRetryAFailureThatIsNotAboutTheProject() = runCommandTest {
        val out = Sinks()
        var calls = 0
        val exit = runTaskListCommand(
            project = null,
            session = null,
            findSession = { error("no session lookup on the pane path") },
            listTasks = { calls++; throw ApiException(401, "unauthorized") },
            resolveCwdProjectId = { error("only a 400 means the daemon could not resolve a project") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        assertEquals(1, calls, "a 401 is not a missing project and must not be retried")
    }


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
            whoami = { throw ApiException(400, NO_SESSION_BODY) },
            findSession = { error("no session lookup on the pane path") },
            taskDetail = { error("there is no subject to fetch") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        assertTrue(out.stdout.isEmpty(), "a failure prints nothing on stdout: ${out.stdout}")
        val err = Json.parseToJsonElement(out.stderr.single()).jsonObject
        assertEquals(400, err["status"]?.jsonPrimitive?.content?.toInt(), "the daemon's status reaches the caller")
        assertTrue(
            "--session" in (err["error"]?.jsonPrimitive?.content ?: ""),
            "the daemon's own text names the escape hatch: $err",
        )
    }

    @Test
    fun aRefLessCommandInAPaneWithNoTaskFailsCleanly() = runCommandTest {
        val out = Sinks()
        val exit = runTaskShowCommand(
            ref = null,
            session = null,
            whoami = { WhoamiDto(sessionId = "sess0001", projectId = PROJECT, taskRef = null) },
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


    @Test
    fun nextPrintsTheLinkedEntry() = runCommandTest {
        val out = Sinks()
        val exit = runTaskNextCommand(
            project = null,
            session = null,
            nextTask = { entry("local:4", state = "in_progress") },
            resolveCwdProjectId = { error("a daemon that answered is never re-asked from the cwd") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        val json = out.onlyJsonObject()
        assertEquals("local:4", json["ref"]?.jsonPrimitive?.content)
        assertEquals("in_progress", json["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun nextRetriesWithTheCwdProjectWhenTheSessionRowResolvesToNone() = runCommandTest {
        val out = Sinks()
        val asked = mutableListOf<String?>()
        val exit = runTaskNextCommand(
            project = null,
            session = null,
            nextTask = { p ->
                asked += p
                if (p == null) throw ApiException(400, NO_PROJECT_BODY) else entry("local:4", state = "in_progress")
            },
            resolveCwdProjectId = { PROJECT },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf(null, PROJECT), asked)
        assertEquals("local:4", out.onlyJsonObject()["ref"]?.jsonPrimitive?.content)
    }

    @Test
    fun nextWithAnExplicitSessionIsNeverAnsweredFromTheCwd() = runCommandTest {
        val out = Sinks()
        val asked = mutableListOf<String?>()
        val exit = runTaskNextCommand(
            project = null,
            session = "sess0001",
            nextTask = { p -> asked += p; throw ApiException(400, "session 'sess0001' resolves to no project") },
            resolveCwdProjectId = { error("an explicit --session is never answered from the cwd") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        assertEquals(listOf<String?>(null), asked, "one request, carrying the session in its body")
    }

    @Test
    fun nextExitsThreeOnAnEmptyBacklogAndStillPrintsJson() = runCommandTest {
        val out = Sinks()
        val exit = runTaskNextCommand(
            project = PROJECT,
            session = null,
            nextTask = { null },
            resolveCwdProjectId = { error("a named project is never second-guessed against the cwd") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(TASK_NEXT_NOTHING_ELIGIBLE, exit)
        assertEquals(3, exit, "nothing eligible is exit 3, and only 3 — a script must tell it from a failure")
        out.assertNoErrors()
        assertEquals(JsonNull, out.onlyJsonObject()["task"], "the answer is parseable, not an empty stream")
    }


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
    fun commentResolvesARefLessSubjectThroughWhoami() = runCommandTest {
        val out = Sinks()
        var whoamiCalls = 0
        var seen: List<String?>? = null
        val exit = runTaskCommentCommand(
            ref = null,
            message = "picked this up",
            session = null,
            whoami = {
                whoamiCalls++
                WhoamiDto(sessionId = "sess0001", projectId = PROJECT, taskRef = "local:11")
            },
            findSession = { error("no session lookup on the pane path") },
            commentOnTask = { r, t, s ->
                seen = listOf(r, t, s)
                ActivityEntryDto(id = 13, ref = r, ts = 6, kind = "comment", author = "sess0001", text = t)
            },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        out.assertNoErrors()
        assertEquals(1, whoamiCalls)
        assertEquals(listOf("local:11", "picked this up", null), seen, "the pane's own task is the subject")
        assertEquals("local:11", out.onlyJsonObject()["ref"]?.jsonPrimitive?.content)
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
            editTaskDependency = { r, a, o -> edits += listOf(r, a, o); entry(r, blocked = true) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        val removed = runTaskDepCommand(
            ref = "local:1",
            on = "local:2",
            remove = true,
            editTaskDependency = { r, a, o -> edits += listOf(r, a, o); entry(r, blocked = false) },
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
    }

    @Test
    fun aDependencyEditPrintsTheUpdatedEntryNotTheRequest() = runCommandTest {
        val out = Sinks()
        val exit = runTaskDepCommand(
            ref = "local:1",
            on = "local:2",
            remove = false,
            editTaskDependency = { r, _, _ -> entry(r, blocked = true) },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        val json = out.onlyJsonObject()
        assertEquals("local:1", json["ref"]?.jsonPrimitive?.content)
        assertEquals(true, json["blocked"]?.jsonPrimitive?.content?.toBoolean(), "the answer carries `blocked`")
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
    fun deletingAProjectPrintsTheTombstonedRowItself() = runCommandTest {
        val out = Sinks()
        val asked = mutableListOf<String>()
        val exit = runProjectDeleteCommand(
            id = PROJECT,
            deleteProject = { id ->
                asked += id
                ProjectDto(id = id, name = "kotgent", path = "/repo", updatedAt = 3, archived = true)
            },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        out.assertNoErrors()
        assertEquals(listOf(PROJECT), asked)
        val json = out.onlyJsonObject()
        assertEquals(PROJECT, json["id"]?.jsonPrimitive?.content)
        assertEquals(
            true,
            json["archived"]?.jsonPrimitive?.content?.toBoolean(),
            "a script reads the mark back off the row rather than trusting the verb",
        )
    }

    @Test
    fun restoringAProjectPrintsTheRowWithTheMarkCleared() = runCommandTest {
        val out = Sinks()
        val asked = mutableListOf<String>()
        val exit = runProjectRestoreCommand(
            id = PROJECT,
            restoreProject = { id ->
                asked += id
                ProjectDto(id = id, name = "kotgent", path = "/repo", updatedAt = 4, archived = false)
            },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf(PROJECT), asked)
        assertEquals(false, out.onlyJsonObject()["archived"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun aProjectTheDaemonNeverSawIsOneErrorObjectCarryingIts404() = runCommandTest {
        for (call in listOf<suspend (Sinks) -> Int>(
            { out ->
                runProjectDeleteCommand(
                    id = OTHER_PROJECT,
                    deleteProject = { throw ApiException(404, "no project '$OTHER_PROJECT'") },
                    stdout = out.stdout::add,
                    stderr = out.stderr::add,
                )
            },
            { out ->
                runProjectRestoreCommand(
                    id = OTHER_PROJECT,
                    restoreProject = { throw ApiException(404, "no project '$OTHER_PROJECT'") },
                    stdout = out.stdout::add,
                    stderr = out.stderr::add,
                )
            },
        )) {
            val out = Sinks()
            assertEquals(1, call(out), "exit 3 stays reserved for `task next` finding nothing")
            assertTrue(out.stdout.isEmpty(), "a failure never prints on the success stream: ${out.stdout}")
            val err = Json.parseToJsonElement(out.onlyErrorJson()).jsonObject
            assertEquals(404, err["status"]?.jsonPrimitive?.content?.toInt())
            assertTrue(OTHER_PROJECT in (err["error"]?.jsonPrimitive?.content ?: ""))
        }
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
        assertEquals(listOf("/repo/nested"), created)
    }

    @Test
    fun projectInitRefusesARelativePathItCannotAnchor() = runCommandTest {
        val out = Sinks()
        val exit = runProjectInitCommand(
            path = "nested",
            name = null,
            callerCwd = ".",
            createProject = { _, _ -> error("nothing may be created from an unresolvable path") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(2, exit, "an unresolvable directory is a usage error, like start's")
        assertTrue(out.stdout.isEmpty(), "a failure never prints on the success stream: ${out.stdout}")
        assertTrue(out.onlyErrorJson().isNotEmpty())
    }


    @Test
    fun startWithTaskPrefersTheCallerCwdWhenItResolvesToTheTaskProject() = runCommandTest {
        val out = Sinks()
        val started = mutableListOf<List<Any?>>()
        val exit = runStartWithTaskCommand(
            agent = "claude",
            callerCwd = "/repo-wt/feature",
            cwdExplicit = false,
            taskRef = "local:1",
            name = "wt",
            tags = listOf("a"),
            taskDetail = { r -> detail(entry(r), projectPath = "/repo") },
            startSession = { a, c, n, t, r -> started += listOf(a, c, n, t, r); startedSession(c) },
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
            cwdExplicit = false,
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
            cwdExplicit = false,
            taskRef = "local:1",
            name = null,
            tags = emptyList(),
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
            cwdExplicit = false,
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
    fun startWithTaskStartsWhereTheOperatorSaidEvenWhenTheProjectLivesElsewhere() = runCommandTest {
        val out = Sinks()
        val started = mutableListOf<String>()
        val exit = runStartWithTaskCommand(
            agent = "claude",
            callerCwd = "/Users/me/scratch",
            cwdExplicit = true,
            taskRef = "local:1",
            name = null,
            tags = emptyList(),
            taskDetail = { r -> detail(entry(r), projectPath = "/repo") },
            startSession = { _, c, _, _, _ -> started += c; startedSession(c) },
            resolveProjectId = { error("a named directory is never weighed against the task's project") },
            isDirectory = { error("a named directory is never traded for the stored project path") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(0, exit)
        assertEquals(listOf("/Users/me/scratch"), started)
        val json = out.onlyJsonObject()
        assertEquals("/Users/me/scratch", json["cwd"]?.jsonPrimitive?.content)
        assertEquals("explicit-cwd", json["cwdSource"]?.jsonPrimitive?.content)
    }

    @Test
    fun startWithTaskStillFetchesTheTaskForAnExplicitCwd() = runCommandTest {
        val out = Sinks()
        val exit = runStartWithTaskCommand(
            agent = "claude",
            callerCwd = "/Users/me/scratch",
            cwdExplicit = true,
            taskRef = "local:404",
            name = null,
            tags = emptyList(),
            taskDetail = { throw ApiException(404, "no task 'local:404'") },
            startSession = { _, _, _, _, _ -> error("nothing may be launched for an unknown task") },
            resolveProjectId = { error("a named directory is never weighed against the task's project") },
            isDirectory = { error("a named directory is never traded for the stored project path") },
            stdout = out.stdout::add,
            stderr = out.stderr::add,
        )
        assertEquals(1, exit)
        assertTrue("local:404" in out.onlyErrorJson())
    }

    @Test
    fun startWithTaskFailsBeforeLaunchingWhenTheRefIsUnknown() = runCommandTest {
        val out = Sinks()
        val exit = runStartWithTaskCommand(
            agent = "claude",
            callerCwd = "/repo",
            cwdExplicit = false,
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


    private class Sinks {
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()

        fun only(): String = stdout.single()

        fun onlyJsonObject() = Json.parseToJsonElement(only()).jsonObject

        fun onlyErrorJson(): String = stderr.single()

        fun assertNoErrors() = assertTrue(stderr.isEmpty(), "nothing was expected on stderr: $stderr")
    }

    private fun runCommandTest(block: suspend () -> Unit) = runBlocking {
        withTimeout(30_000) { block() }
    }

    private fun entry(
        ref: String,
        title: String = "a task",
        state: String = "todo",
        position: Double = 1.0,
        blocked: Boolean = false,
    ) = BacklogEntryDto(
        ref = ref,
        project = PROJECT,
        title = title,
        body = "",
        position = position,
        state = state,
        blocked = blocked,
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

        const val NO_PROJECT_BODY: String =
            "no project: the request named none and the calling session resolves to none — pass --project <uuid>"

        const val NO_SESSION_BODY: String =
            "cannot resolve the calling session: no X-Kotgent-Tmux-Pane header, or the pane it names is " +
                "not a kotgent session — pass --session <id> from outside a kotgent pane"
    }
}
