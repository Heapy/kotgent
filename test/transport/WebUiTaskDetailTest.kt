package io.kotgent.transport

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getcwd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The task detail view's source contract (task-backlog plan, Task 25) — `components/TaskDetail.js` and
 * the half of `components/dialogs.js` that carries a task into a launch.
 *
 * ## Why this reads the files rather than standing a server up
 * That both modules are *served* — `200`, a JavaScript content-type, from the real `resources/webui`
 * tree — is already pinned centrally, in `WebUiServingTest.daemonServesTheComponentAndLibModules`, which
 * lists `/components/TaskDetail.js` and `/components/dialogs.js` by name. That suite is a shared
 * chokepoint this task may not edit, and re-binding a port here would re-prove exactly what it already
 * proves. What is NOT covered anywhere else, and what a browser-less test binary genuinely can check, is
 * the source contract: the feed comes over HTTP, the session dots come off the live `/events` list, the
 * launch goes through the one New-session dialog, and every class name this component emits is one of
 * the ones Task 28 is writing rules for at the same moment, in a file nobody here can read.
 *
 * That last check is the interesting one. The board's class vocabulary is frozen in the plan because
 * three agents write the markup and the stylesheet concurrently with no compiler between them; the
 * whitelist below is this component's end of that contract, and Task 28's serving test is the other.
 */
class WebUiTaskDetailTest {

    /**
     * Every `task-*` / `board-*` class this component is allowed to emit — the rows of the plan's
     * "Board CSS vocabulary" table that name TaskDetail. Anything else is a class Task 28 will never
     * style, so it would ship as invisible markup.
     */
    private val allowedBoardClasses = setOf(
        "task-detail", "task-detail-head", "task-detail-body",
        "task-deps", "task-dep-count", "task-blocked",
        "task-sessions", "task-session-dot",
        "task-activity", "task-activity-row",
    )

    private val detail: String by lazy { taskDetailSource("components/TaskDetail.js") }
    private val dialogs: String by lazy { taskDetailSource("components/dialogs.js") }

    /**
     * `app.js` is read here too, for the four tests below that check a WIRING rather than a component.
     * A prop the component reads and the app never passes is exactly the failure this suite exists to
     * catch, and only the two files together can show it — the component's own source would look
     * perfectly correct with the call site missing.
     */
    private val app: String by lazy { taskDetailSource("app.js") }

    @Test
    fun theActivityFeedIsFetchedWithTheTaskAndNeverArrivesOnTheSocket() {
        assertTrue(
            detail.contains("import { deleteTask, editTaskDependency, fetchTaskDetail, patchTask }"),
            "the detail view reaches the task API through lib/tasks.js, not a hand-built fetch",
        )
        assertTrue(
            detail.contains("await fetchTaskDetail(taskRef)"),
            "the whole detail — entry, deps, sessions AND activity — comes from the one GET",
        )
        // The feed rides HTTP on purpose: it is unbounded and only ever read on one open detail view,
        // so putting it on /events would make every connected tab pay for a comment nobody is reading.
        for (frame in listOf("tasks_snapshot", "task_row", "task_update", "task_removed", "WebSocket")) {
            assertTrue(
                !detail.contains(frame),
                "the detail view must not subscribe to the events socket (found '$frame')",
            )
        }
        assertTrue(
            detail.contains("if (reload) await load();"),
            "a mutation re-reads the detail, so the feed and the derived blocked marker stay the daemon's",
        )
    }

    @Test
    fun theFeedRendersOneRowPerEntryCarryingItsKind() {
        assertTrue(detail.contains("class=\"task-activity\""), "the feed has its container class")
        assertTrue(
            detail.contains("<div class=\"task-activity-row\" data-kind=\${row.kind}"),
            "each feed row carries its ActivityKind in data-kind, which is what Task 28 styles on",
        )
        assertTrue(
            detail.contains("activity.map((row)"),
            "the feed is rendered from the fetched activity list",
        )
        // A corrupt timestamp must not take the screen down: `toISOString` THROWS on an invalid date.
        assertTrue(
            detail.contains("export function activityTimestampAttr(ts)") &&
                detail.contains("datetime=\${activityTimestampAttr(row.ts)}"),
            "the machine-readable stamp is guarded rather than calling toISOString on raw input",
        )
        assertTrue(
            detail.contains("stateLabel(row.fromState) + \" → \" + stateLabel(row.toState)"),
            "a transition row names both ends",
        )
    }

    @Test
    fun theSessionListIsLiveFirstAndUnionsOnlySessionsTheBrowserDoesNotHold() {
        assertTrue(
            detail.contains("export function linkedSessions(ref, sessions, detail)"),
            "the rule is one named, exported function rather than an inline filter",
        )
        assertTrue(
            detail.contains("held.filter((s) => s && s.taskRef === ref)"),
            "the live list — the one the /events socket keeps current — is the primary source",
        )
        assertTrue(
            detail.contains("((detail && detail.sessions) || []).filter((s) => s && !ids.has(s.id))"),
            "a fetched row is unioned in only for a session this browser does not hold at all; one it " +
                "holds that no longer names this ref has been released and must not be shown",
        )
        assertTrue(detail.contains("class=\"task-sessions\""), "the list has its container class")
        assertTrue(
            detail.contains("class=\"task-session-dot\" data-state=\${stateBadge(s.state).cls}"),
            "the dot reuses stateBadge's own value in data-state instead of a second mapping, so the " +
                "sidebar badge and this dot cannot drift apart",
        )
        assertTrue(
            detail.contains("href=\${sessionPath(s.id)}"),
            "a linked session is a link to that session's route",
        )
    }

    @Test
    fun startingASessionGoesThroughTheOrdinaryNewSessionDialog() {
        assertTrue(
            detail.contains("onStartSession((detail && detail.projectPath) || null, taskRef)"),
            "\"Start session\" hands the project path and the ref to app.js's dialog opener",
        )
        // The whole point of the callback: there is exactly one launch path, and it is not here.
        assertTrue(
            !detail.contains("\"/sessions\"") && !detail.contains("apiRequest("),
            "the detail view must not POST a session itself — that would be the second launch path",
        )
        assertTrue(
            detail.contains("id=\"task-detail-start\""),
            "the action lives in the head, where the plan's vocabulary puts it",
        )
    }

    @Test
    fun theNewSessionDialogCarriesTheTaskIntoItsStartBodyAndOnlyThere() {
        assertTrue(
            dialogs.contains("initialTaskRef = null,"),
            "NewSessionDialog accepts the ref app.js already passes it",
        )
        assertTrue(
            dialogs.contains("if (taskRef) body.taskRef = taskRef;") &&
                dialogs.contains("await onStart(body);"),
            "the ref is added to the START body only when it is set — app.js POSTs that object verbatim",
        )
        // Import has no taskRef field at all, and a daemon with no task layer owes a 400 for a request
        // that carries one, so an ordinary launch must keep sending the shape it always did.
        val importCall = dialogs.substringAfter("await onImport({").substringBefore("}, registerOnly);")
        assertTrue(importCall.isNotEmpty(), "the import branch is still there to check")
        assertTrue(
            !importCall.contains("taskRef"),
            "the import body carries no taskRef — importing is registration, not a task launch",
        )
        assertTrue(
            dialogs.contains("id=\"new-session-task-ref\""),
            "the link is stated in the form: this dialog also opens from the sidebar and the palette",
        )
        assertTrue(
            dialogs.contains("\${mode === \"start\" && taskRef && html`"),
            "and only in start mode, where the ref is actually submitted",
        )
    }

    @Test
    fun everyMutationSurfacesItsRefusalThroughTheAnnouncementChannel() {
        assertTrue(
            detail.contains("onAnnounce(label + \": \" + errorMessage(e), true);"),
            "one wrapper reports every failed write as an error announcement rather than swallowing it",
        )
        for (call in listOf(
            "await patchTask(taskRef, patch);",
            "await patchTask(taskRef, { state: next });",
            "await editTaskDependency(taskRef, \"add\", on);",
            "await editTaskDependency(taskRef, \"remove\", on);",
            "await deleteTask(taskRef);",
        )) {
            assertTrue(detail.contains(call), "the detail view performs '$call' through that wrapper")
        }
        // A delete must not re-read the ref it just removed: the refresh would 404 and paint a load
        // error over a write that succeeded.
        assertTrue(
            detail.contains("backToBoard();\n    }, false);"),
            "delete leaves for the board without re-reading the deleted ref",
        )
        assertTrue(
            detail.contains("window.confirm("),
            "delete is confirmed first — it unlinks every session and drops the feed",
        )
    }

    @Test
    fun theHeadCarriesTheTitleTheStateAndTheBlockedMarker() {
        assertTrue(detail.contains("class=\"task-detail\""), "the screen's root class")
        assertTrue(detail.contains("class=\"task-detail-head\""), "the head's class")
        assertTrue(
            detail.contains("class=\"task-detail-body\""),
            "the editable body carries the class Task 28 styles",
        )
        assertTrue(
            detail.contains("id=\"task-detail-state\"") && detail.contains("onChange=\${changeState}"),
            "the state select is the head's one write — and the only way to move a task on a phone, " +
                "where the board has no dragging",
        )
        assertTrue(
            detail.contains("export const TASK_STATES = [\"todo\", \"in_progress\", \"review\", \"done\"]"),
            "the four workflow states, in board order",
        )
        assertTrue(
            detail.contains("class=\"task-blocked\""),
            "the blocked marker is rendered from the server-derived flag",
        )
        assertTrue(
            detail.contains("class=\"task-dep-count\"") && detail.contains("class=\"task-deps\""),
            "the dependency editor and its count",
        )
        assertTrue(
            detail.contains("editTaskDependency(taskRef, \"add\", on)") &&
                detail.contains("editTaskDependency(taskRef, \"remove\", on)"),
            "the editor drives both directions of /deps",
        )
    }

    @Test
    fun theComponentInventsNoBoardClassOutsideTheSharedVocabulary() {
        // Task 28 writes `style.css` at this very moment and cannot read this file, so the frozen list
        // in the plan is the entire contract between the two halves. A class outside it ships as markup
        // no rule will ever match.
        assertTrue(
            !detail.contains("class=\$"),
            "every class on this screen is a literal, so the scan below cannot be bypassed by a " +
                "computed value; add one and this check has to be taught how to read it",
        )
        val emitted = boardClassesIn(detail)
        assertTrue(emitted.isNotEmpty(), "the scan found the component's classes at all")
        assertEquals(
            emptySet<String>(), emitted - allowedBoardClasses,
            "classes emitted that are not in the plan's Board CSS vocabulary for TaskDetail",
        )
        // The other direction: the shared rows really are used, so Task 28's rules are not dead.
        for (shared in listOf("task-blocked", "task-dep-count", "task-sessions", "task-session-dot")) {
            assertTrue(emitted.contains(shared), "the shared class '$shared' is emitted here")
        }
    }

    @Test
    fun aSupersededReadCanNeverOverwriteANewerOne() {
        assertTrue(
            detail.contains("const generation = ++generationRef.current;") &&
                detail.contains("if (generationRef.current !== generation) return;"),
            "each read is stamped and only the newest may land",
        )
        assertTrue(
            detail.contains("generationRef.current += 1;"),
            "leaving the ref (or unmounting) retires the generation, so a late answer writes nothing",
        )
        assertTrue(
            detail.contains("}, [taskRef, load]);"),
            "the fetch effect re-runs for a new ref rather than showing the previous task's detail",
        )
    }

    /**
     * The panel and the card behind it must be ONE row.
     *
     * Before this, `app.js` passed the detail nothing but a ref and the component fetched on a ref change
     * or its own write — so another tab's edit reached `tasks` (the board card moved) and never reached
     * the open panel. On a phone that panel is the whole screen, so the stale copy was all there was.
     *
     * Each assertion below is one link of the chain, and the chain is what makes the test falsifiable:
     * delete the `entry=` prop and the third fails, keep the prop but read only the fetched copy and the
     * fourth and fifth fail. Verified against the pre-fix source — none of these strings was in it.
     */
    @Test
    fun theOpenDetailRendersTheRowTheAppHoldsAndNotAPrivateCopy() {
        assertTrue(
            app.contains("const openTaskEntry = route.screen === SCREEN_TASK && route.id"),
            "app.js resolves the open task's live row itself, from the one list it already merges",
        )
        assertTrue(
            app.contains("? tasks.find((task) => task.ref === route.id) || null"),
            "…by ref, out of `tasks` — the same list the board renders and the socket keeps current",
        )
        assertTrue(
            app.contains("<\${TaskDetail} taskRef=\${route.id} entry=\${openTaskEntry}"),
            "and hands it to the panel: without this prop the panel can only show its own fetched copy, " +
                "which is exactly the stale screen this fixes",
        )
        assertTrue(
            detail.contains("entry: liveEntry = null,"),
            "the component accepts that row (and defaults it, so a caller without one still renders)",
        )
        assertTrue(
            detail.contains("const entry = newerEntry(liveEntry, detail && detail.task);"),
            "and renders the newer of its TWO observations rather than either one alone",
        )
        assertTrue(
            detail.contains("export function newerEntry(live, fetched)") &&
                detail.contains("return fetched.rev > live.rev ? fetched : live;"),
            "the choice is the same rev comparison lib/tasks.js makes on the list — the GET and the " +
                "frame race, so neither may be assumed authoritative",
        )
        // An absent live row is ambiguous — a baseline that has not landed, or a deletion — so the pure
        // helper refuses to resolve it and the component, which knows whether it ever held one, decides.
        assertTrue(
            detail.contains("if (!live) return fetched || null;"),
            "an absent live row falls back to the fetched copy rather than blanking the screen",
        )
    }

    /**
     * The other direction of the same wiring: every row this screen OBSERVES goes into that one list.
     *
     * Both halves matter. The GET's copy, because a deep link can open before the socket's baseline and
     * the card behind the panel would otherwise be missing; and each write's committed entry, because
     * while `/events` is reconnecting REST still works and a save that changed nothing on screen is
     * indistinguishable from one that failed.
     */
    @Test
    fun everyRowThisScreenObservesGoesBackIntoTheAppsOneList() {
        assertTrue(
            app.contains("onTaskRow=\${applyTaskRow} onTaskRemoved=\${applyTaskRemoved}"),
            "the sinks handed down are the SAME appliers the events frames use — a response and a frame " +
                "must not take two different paths into the list",
        )
        assertTrue(
            app.contains("setTasks((current) => upsertTaskIfNewer(current, row));"),
            "and that applier is the newest-rev-wins upsert, so merging a response is never rev-blind",
        )
        assertTrue(
            detail.contains("if (row && row.ref && onTaskRowRef.current) onTaskRowRef.current(row);"),
            "one publisher, which ignores an answer that is not a row",
        )
        assertTrue(
            detail.contains("publishRow(next && next.task);"),
            "the fetched entry is published, so the card appears without a second request",
        )
        for (write in listOf(
            "const saved = await patchTask(taskRef, patch);\n      publishRow(saved);",
            "const moved = await patchTask(taskRef, { state: next });\n      publishRow(moved);",
            "const edited = await editTaskDependency(taskRef, \"add\", on);\n      publishRow(edited);",
            "const edited = await editTaskDependency(taskRef, \"remove\", on);\n      publishRow(edited);",
        )) {
            assertTrue(
                detail.contains(write),
                "a write merges the committed entry its own answer carries:\n$write",
            )
        }
        assertTrue(
            detail.contains("if (onTaskRemovedRef.current) onTaskRemovedRef.current(taskRef);"),
            "a delete answers no row at all, so the removal itself is what is applied — before this " +
                "screen leaves for a board rendered from that same list",
        )
        // The sinks are reached through refs deliberately: `load` is a DEPENDENCY of the fetch effect.
        assertTrue(
            detail.contains("onTaskRowRef.current = onTaskRow;") &&
                detail.contains("const publishRow = useCallback((row) => {") &&
                detail.contains("}, [taskRef, publishRow]);"),
            "the publisher is identity-stable, so a parent callback that is not memoized cannot make " +
                "the fetch effect rebuild and re-read forever",
        )
    }

    /**
     * An external update may move everything around the form and must not touch the form.
     *
     * The trick is which copy "untouched" is measured against. Against the CURRENT entry, every external
     * edit reads as unsaved work and the fields freeze for the rest of the visit; against what the entry
     * said LAST, only the operator's own typing counts — and a save, which leaves the drafts holding the
     * new value, re-synchronises on the next change instead of latching.
     */
    @Test
    fun anExternalUpdateNeverTakesAHalfTypedEditAway() {
        assertTrue(
            detail.contains("const seededRef = useRef({ ref: null, title: \"\", body: \"\" });"),
            "the screen remembers what the entry last said",
        )
        assertTrue(
            detail.contains(
                "draftRef.current.title === seeded.title && draftRef.current.body === seeded.body;",
            ),
            "and \"untouched\" is measured against THAT, not against the entry as it now reads",
        )
        assertTrue(
            detail.contains("if (seeded.ref !== taskRef || untouched) {"),
            "a new ref always re-seeds; an external change re-seeds only a form nobody has typed in",
        )
        assertTrue(
            detail.contains("}, [taskRef, entryTitle, entryBody]);"),
            "and it runs for a title/body that changed ANYWHERE — the socket's row included, which is " +
                "the case that has no fetch behind it",
        )
        // The old seeding site: `load` overwrote both drafts on every read. With the live row driving the
        // entry there is no read behind an external change at all, so this had to move out of the fetch.
        val loadBody = detail.substringAfter("const load = useCallback").substringBefore("}, [taskRef,")
        assertTrue(loadBody.isNotEmpty(), "the fetch callback is still there to check")
        assertFalse(
            loadBody.contains("setTitleDraft") || loadBody.contains("setBodyDraft"),
            "the fetch no longer seeds the drafts, so a refresh cannot clobber an edit in progress",
        )
    }

    /**
     * A task deleted somewhere else leaves a panel whose every control can now only answer 404.
     *
     * The list is the evidence: a row this screen HELD and then lost is a deletion (a removal frame and a
     * fresh baseline are the only two things that take one out of it, and both mean gone), while a row it
     * never held is a baseline that has not landed yet. Storing the ref rather than a flag is what stops
     * a second task, opened before its own row arrives, from inheriting the first one's answer.
     */
    @Test
    fun aTaskDeletedInAnotherTabStopsOfferingEditorsHere() {
        assertTrue(
            detail.contains("if (liveEntry) seenLiveRef.current = taskRef;"),
            "the screen records WHICH ref it has held, not merely that it held one",
        )
        assertTrue(
            detail.contains("const vanished = !busy && seenLiveRef.current === taskRef && !liveEntry;"),
            "…and reads a row it held and then lost as a deletion; `busy` keeps this screen's OWN " +
                "delete, whose removal lands just before it navigates, from flashing it",
        )
        assertTrue(
            detail.contains("id=\"task-detail-gone\""),
            "and it says so, rather than leaving live editors over a task that no longer exists",
        )
        val goneAt = detail.indexOf("if (vanished) {")
        val errorAt = detail.indexOf("if (loadError) {")
        assertTrue(goneAt > 0, "the deleted branch exists")
        assertTrue(
            goneAt < errorAt,
            "and it is checked BEFORE the load error, because the deletion is what causes that 404 and " +
                "\"it was deleted\" is the honest sentence for it",
        )
    }

    /** Every `task-*` / `board-*` token inside a literal `class="…"` attribute of [source]. */
    private fun boardClassesIn(source: String): Set<String> {
        val found = mutableSetOf<String>()
        var at = source.indexOf("class=\"")
        while (at >= 0) {
            val start = at + "class=\"".length
            val end = source.indexOf('"', start)
            if (end < 0) break
            for (token in source.substring(start, end).split(' ', '\n', '\t')) {
                val name = token.trim()
                if (name.startsWith("task-") || name.startsWith("board-")) found.add(name)
            }
            at = source.indexOf("class=\"", end)
        }
        return found
    }
}

/**
 * Read one file out of the real `resources/webui` tree. Named for this suite because a private top-level
 * declaration is file-scoped but the package is shared with every other `test/transport` file being
 * written right now.
 */
private fun taskDetailSource(relative: String): String {
    val path = taskDetailWebUiDir() + "/" + relative
    val text = readFileTextOrNull(path)
    checkNotNull(text) { "cannot read $path — is resources/webui where the test runner expects it?" }
    check(text.isNotBlank()) { "$path is empty" }
    return text
}

/**
 * Locate `resources/webui` the way `WebUiServingTest` does: `./kotlin test` runs from the module root,
 * but walking up from the cwd keeps the test independent of where the runner started.
 */
@OptIn(ExperimentalForeignApi::class)
private fun taskDetailWebUiDir(): String {
    var dir = memScoped {
        val size = 4096
        val buf = allocArray<ByteVar>(size)
        getcwd(buf, size.convert())
        buf.toKString()
    }
    repeat(6) {
        val candidate = "$dir/resources/webui"
        if (access("$candidate/index.html", F_OK) == 0) return candidate
        val parent = dir.substringBeforeLast('/', "")
        if (parent.isEmpty() || parent == dir) return "resources/webui"
        dir = parent
    }
    return "resources/webui"
}
