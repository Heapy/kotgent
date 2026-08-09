package io.kotgent.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getcwd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO

/**
 * The task view-state module (`resources/webui/lib/tasks.js`), plan Task 23 — the browser's half of the
 * task protocol: the REST calls the board and the detail view make, and the newest-rev-wins appliers
 * that fold `tasks_snapshot` / `task_row` / `task_update` / `task_removed` into one list.
 *
 * ## Why these are source assertions served over HTTP
 * The module cannot run in the macosArm64 test binary — there is no JavaScript engine here, and the
 * repository deliberately ships no browser harness. What IS automatable is the same serving/source
 * contract every other Web UI test uses: fetch the file through the real static handler and assert the
 * text of the rules that must not silently disappear. Behaviour stays in the manual device checklist.
 *
 * The harness mounts [staticWebUi] alone rather than a whole [KotgentServer]: this file asserts nothing
 * about authentication or about the API-vs-static precedence (`WebUiServingTest` owns both), so the
 * lighter mount is the honest one — and it keeps this suite out of the shared harness three other
 * wave-2 agents are writing against at the same time.
 */
class WebUiTaskStateTest {

    /**
     * Every name a consumer imports. `app.js` already imports the four appliers by these exact names
     * (its `applyTasksBaseline` / `applyTaskRow` / `applyTaskPatch` / `applyTaskRemoved` callbacks), and
     * the board and detail views reach the daemon only through the ten calls below — so a rename here is
     * a load-time failure of the whole SPA that the 200-plus-content-type check could never see.
     */
    private val exportedNames = listOf(
        "applyTasksSnapshot", "upsertTaskIfNewer", "patchTaskIfNewer", "removeTask",
        "fetchTasks", "fetchTaskDetail", "createTask", "patchTask", "moveTask",
        "editTaskDependency", "commentOnTask", "deleteTask", "fetchProjects", "createProject",
    )

    @Test
    fun theTaskStateModuleIsServedAsJavaScript() = withStaticUi { ctx ->
        val resp = ctx.get("/lib/tasks.js")
        assertEquals(HttpStatusCode.OK, resp.status, "GET /lib/tasks.js is served")
        val ct = resp.headers[HttpHeaders.ContentType].orEmpty()
        assertTrue(ct.contains("javascript", ignoreCase = true), "content-type '$ct' should mention javascript")
        assertTrue(resp.bodyAsText().isNotEmpty(), "the module is not an empty file")
    }

    @Test
    fun everyExportTheBoardAndAppImportIsPresent() = withStaticUi { ctx ->
        val source = ctx.get("/lib/tasks.js").bodyAsText()
        for (name in exportedNames) {
            assertTrue(
                source.contains("export function $name(") || source.contains("export async function $name("),
                "lib/tasks.js exports $name",
            )
        }
        // The four appliers are reached from app.js, which this module may not touch. Reading its served
        // text is what pins the two halves together: an import of a name this module does not export is
        // a hard module-resolution failure at load, and every screen goes with it.
        val app = ctx.get("/app.js").bodyAsText()
        assertTrue(app.contains("from \"./lib/tasks.js\""), "app.js imports the task state module")
        for (name in listOf("applyTasksSnapshot", "upsertTaskIfNewer", "patchTaskIfNewer", "removeTask")) {
            assertTrue(app.contains(name), "app.js reaches the applier $name")
        }
    }

    @Test
    fun bothAppliersCompareRevsAndTheStampLandsOnTheStoredRow() = withStaticUi { ctx ->
        val source = ctx.get("/lib/tasks.js").bodyAsText()
        val upsert = functionOf(source, "upsertTaskIfNewer")
        assertTrue(
            upsert.contains("if (!(row.rev > list[index].rev)) return list;"),
            "a full row is applied only when its rev is newer, so a late stale response cannot roll a row back",
        )
        val patch = functionOf(source, "patchTaskIfNewer")
        assertTrue(
            patch.contains("if (index < 0) return list;"),
            "a patch for a ref the list does not carry leaves the list untouched",
        )
        assertTrue(
            patch.contains("if (!(msg.rev > prev.rev)) return list;"),
            "a patch is applied only when its rev is newer",
        )
        // The stamp: the applied row is the merge of the stored row and the FRAME, so the frame's `rev`
        // is what the row now carries. Drop that half and the next stale full row compares against the
        // pre-patch rev, wins, and rolls the patch back — the if-newer invariant self-destructs after the
        // very first patch. `lib/sessions.js` spells the same rule as `rev: msg.rev`.
        assertTrue(
            patch.contains("Object.assign({}, prev, msg)"),
            "the patch merges the frame over the stored row, which is what stamps the frame's rev onto it",
        )
    }

    @Test
    fun theSnapshotReplacesTheListRatherThanMergingIntoIt() = withStaticUi { ctx ->
        val snapshot = functionOf(ctx.get("/lib/tasks.js").bodyAsText(), "applyTasksSnapshot")
        assertTrue(
            snapshot.contains("return rows ? rows.slice() : [];"),
            "a connect/reconnect baseline replaces the list — merging would resurrect a row deleted " +
                "while the socket was down",
        )
        assertFalseThat(
            snapshot.contains("upsertTaskIfNewer"),
            "the baseline does not fold rows in one by one",
        )
    }

    @Test
    fun theRemovalPathDropsTheRowAndKeepsIdentityWhenThereIsNothingToDrop() = withStaticUi { ctx ->
        val remove = functionOf(ctx.get("/lib/tasks.js").bodyAsText(), "removeTask")
        assertTrue(
            remove.contains("const index = list.findIndex((t) => t.ref === ref);"),
            "the row to drop is found by ref",
        )
        assertTrue(
            remove.contains("if (index < 0) return list;"),
            "an unknown ref returns the SAME array, so a setTasks caller keeps identity and does not re-render",
        )
        assertTrue(
            remove.contains("next.splice(index, 1);") && remove.contains("return next;"),
            "a known ref is dropped from a copy of the list",
        )
        // A removal is unconditional on purpose: `task_removed` carries no rev, and there can be no later,
        // newer observation of a row the daemon has deleted. Gating it on a rev comparison would leave a
        // deleted task on the board until a reload.
        assertFalseThat(remove.contains(".rev"), "the removal is not gated on a rev comparison")
    }

    @Test
    fun everyCallWritesTheBarePathAndTheDeclaredVerb() = withStaticUi { ctx ->
        val source = ctx.get("/lib/tasks.js").bodyAsText()
        // Bare paths only: `lib/api.js` is the one place the browser learns `/api/v1`, which is what keeps
        // every call site here spelling the route the way the daemon's own docs do.
        assertFalseThat(source.contains("/api/v1"), "the API prefix is api.js's job, not this module's")
        assertTrue(
            functionOf(source, "fetchTasks").contains("\"?project=\" + encodeURIComponent(projectId)"),
            "the list is scoped to one project — the board has no all-projects mode",
        )
        assertTrue(
            functionOf(source, "createTask").contains("jsonBody(\"POST\", {") &&
                functionOf(source, "createTask").contains("project: projectId"),
            "create always sends the SELECTED project id; the browser has no session to infer one from",
        )
        assertTrue(
            functionOf(source, "patchTask").contains("jsonBody(\"PATCH\", patch"),
            "title / body / state ride a PATCH",
        )
        assertTrue(
            functionOf(source, "deleteTask").contains("{ method: \"DELETE\" }"),
            "delete is a DELETE with no body",
        )
        assertTrue(
            functionOf(source, "editTaskDependency").contains("taskPath(ref, \"/deps\")") &&
                functionOf(source, "editTaskDependency").contains("action: action") &&
                functionOf(source, "editTaskDependency").contains("on: on"),
            "a dependency edit posts { action, on } to the ref's /deps",
        )
        assertTrue(
            functionOf(source, "commentOnTask").contains("taskPath(ref, \"/comment\")"),
            "a comment posts to the ref's /comment",
        )
        assertTrue(
            functionOf(source, "createProject").contains("apiRequest(\"/projects\", jsonBody(\"POST\""),
            "a new project is a POST to /projects",
        )
    }

    @Test
    fun aMoveCarriesNoStateAndAPatchCarriesNoPosition() = withStaticUi { ctx ->
        val source = ctx.get("/lib/tasks.js").bodyAsText()
        val move = functionOf(source, "moveTask")
        assertTrue(
            move.contains("taskPath(ref, \"/move\")") && move.contains("jsonBody(\"POST\", target"),
            "a move posts the { before | after | top | bottom } target to the ref's /move",
        )
        // The board's two-call rule: a drop that changes both column and rank is the PATCH and then the
        // move. Either endpoint quietly accepting the other's field would make that a coin toss.
        assertFalseThat(move.contains("state"), "/move never carries a state")
        assertFalseThat(functionOf(source, "patchTask").contains("position"), "PATCH never carries a position")
    }

    @Test
    fun aRefIsPercentEncodedIntoThePath() = withStaticUi { ctx ->
        val source = ctx.get("/lib/tasks.js").bodyAsText()
        // A TaskRef always contains a ':' (`local:42`, `io.kotgent.core.TaskRef`), so every ref-bearing URL
        // is built through the one encoder rather than concatenated raw.
        assertTrue(
            source.contains("return \"/tasks/\" + encodeURIComponent(ref) + (suffix || \"\");"),
            "the ref-bearing path is built once, with the ref percent-encoded",
        )
        for (name in listOf(
            "fetchTaskDetail", "patchTask", "moveTask", "editTaskDependency", "commentOnTask", "deleteTask",
        )) {
            assertTrue(functionOf(source, name).contains("taskPath(ref"), "$name builds its URL through taskPath")
        }
    }

    // --- harness -------------------------------------------------------------------------------------

    private inner class Ctx(val port: Int, val client: HttpClient) {
        suspend fun get(path: String): HttpResponse = client.get("http://127.0.0.1:$port$path")
    }

    /**
     * The static Web UI alone, on an ephemeral port. No auth and no API routes: the catch-all serves the
     * real files from `resources/webui` through the production handler, which is all this suite reads.
     */
    private fun withStaticUi(block: suspend (Ctx) -> Unit) = runBlocking {
        withTimeout(30_000) {
            val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
                routing { staticWebUi(webUiDir()) }
            }
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                block(Ctx(port, client))
            } finally {
                client.close()
                server.stop()
            }
        }
    }

    /**
     * The text of one exported function: from its `export …function <name>(` header to whichever comes
     * first — the next top-level `export` or the doc comment introducing it. Both bounds matter: an
     * assertion about one call must not be satisfiable by its neighbour's code, and a negative assertion
     * ("a move carries no state") must not be defeated by prose in the next function's comment. Fails
     * loudly rather than returning an empty slice, because an empty haystack makes every `contains`
     * below fail with a misleading message.
     */
    private fun functionOf(source: String, name: String): String {
        val header = listOf("export async function $name(", "export function $name(")
            .firstOrNull { source.contains(it) }
        assertTrue(header != null, "lib/tasks.js declares $name")
        val start = source.indexOf(header!!)
        val rest = source.substring(start + header.length)
        val end = listOf(rest.indexOf("\nexport "), rest.indexOf("\n/**"))
            .filter { it >= 0 }
            .minOrNull()
        return if (end == null) rest else rest.substring(0, end)
    }

    private fun assertFalseThat(condition: Boolean, message: String) = assertTrue(!condition, message)

    @OptIn(ExperimentalForeignApi::class)
    private fun currentDirectory(): String = memScoped {
        val size = 4096
        val buf = allocArray<ByteVar>(size)
        getcwd(buf, size.convert())
        buf.toKString()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun exists(path: String): Boolean = access(path, F_OK) == 0

    /**
     * `resources/webui`, found by walking up from the cwd — `./kotlin test` runs from the module root, but
     * the walk keeps this suite from depending on where the runner happens to start.
     */
    private fun webUiDir(): String {
        var dir = currentDirectory()
        repeat(6) {
            val candidate = "$dir/resources/webui"
            if (exists("$candidate/index.html")) return candidate
            val parent = dir.substringBeforeLast('/', "")
            if (parent.isEmpty() || parent == dir) return "resources/webui"
            dir = parent
        }
        return "resources/webui"
    }
}
