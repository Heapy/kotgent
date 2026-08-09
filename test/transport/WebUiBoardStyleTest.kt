package io.kotgent.transport

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.Projection
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.daemon.AgentFactory
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.VendorSessionLocator
import io.kotgent.daemon.VendorStoreProbe
import io.kotgent.store.EventStore
import io.kotgent.store.PreferencesStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StoredEvent
import io.kotgent.store.UiPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getcwd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stylesheet half of the task board (plan Task 28).
 *
 * ## Why this test exists at all
 * Three components — `Board.js` + `TaskCard.js`, `TaskDetail.js`, and the session badges in
 * `Sidebar.js` / `TerminalPane.js` — were written in parallel with `style.css`, in separate worktrees,
 * by agents who could not read each other's files. Nothing in the code connects a class name in the
 * markup to a rule in the stylesheet, and there is no build step that could notice a typo: a misspelt
 * class is a rule that matches nothing, and the board silently renders as unstyled boxes.
 *
 * So the plan froze the class list ("Board CSS vocabulary") and had the two ends assert it from
 * opposite sides. This file is the stylesheet end: **every** name on that list must be a real selector
 * here. The component end lives in each component's own serving test, which asserts the markup emits
 * the names it owns. Neither half can catch a mistake the other half made — together they can.
 *
 * ## What it can and cannot check
 * There is no browser here, so nothing below proves a single pixel. What it proves is that the
 * contracts a reader would otherwise have to re-derive are still written down: the vocabulary is
 * complete, the drag reservation is on the handle and nowhere else, the phone branch exists inside the
 * one width breakpoint the rest of the sheet uses, and the two `data-` attributes the components drive
 * (`data-state` on a column and a session dot, `data-kind` on a feed row) have a rule for every value
 * the domain can produce. Real layout stays in the manual checklist.
 */
class WebUiBoardStyleTest {

    private val token = "webui-board-style-token-abc123"
    private val currentVersion = "9.8.7+deadbee"

    /**
     * The plan's "Board CSS vocabulary" table, transcribed. Order follows the table so a diff against
     * the plan reads straight down. Every entry is asserted as a COMPLETE selector token, not a
     * substring: `.task-card` must exist in its own right and must not be satisfied by
     * `.task-card-title` happening to start with it.
     */
    private val vocabulary = listOf(
        "board",
        "board-head",
        "board-project",
        "board-new-task",
        "board-new-project",
        "board-columns",
        "board-column",
        "board-column-head",
        "board-column-switch",
        "board-show-all-done",
        "board-drop-target",
        "task-card",
        "task-card-handle",
        "task-card-title",
        "task-card-meta",
        "task-blocked",
        "task-dep-count",
        "task-sessions",
        "task-session-dot",
        "task-card-menu",
        "task-detail",
        "task-detail-head",
        "task-detail-body",
        "task-deps",
        "task-activity",
        "task-activity-row",
        "task-badge",
        "task-badge-unknown",
    )

    @Test
    fun everyClassInTheBoardVocabularyIsStyled() = withServer { ctx ->
        val resp = ctx.get("/style.css")
        assertEquals(HttpStatusCode.OK, resp.status, "the stylesheet is served")
        // Comments are stripped FIRST, so a class that survives only because this file's own prose
        // mentions it — and several of the comments do name neighbouring rules on purpose — cannot be
        // mistaken for a rule. After the strip, every remaining occurrence is inside a selector.
        val css = withoutComments(resp.bodyAsText())
        for (name in vocabulary) {
            assertTrue(
                declaresClass(css, name),
                "`.$name` is in the plan's Board CSS vocabulary but nothing in style.css selects it — " +
                    "the component emitting it would render unstyled",
            )
        }
    }

    /**
     * The two screens the router can put where the terminal pane goes have to behave like the pane
     * they replace, or switching to the board rearranges the whole shell around it.
     */
    @Test
    fun theBoardAndTheDetailViewWearTheTerminalPanesCardGeometry() = withServer { ctx ->
        val css = ctx.get("/style.css").bodyAsText()
        val pane = cssRuleOf(css, "#terminal-pane")
        for (selector in listOf(".board", ".task-detail")) {
            val rule = cssRuleOf(css, selector)
            for (declaration in listOf("flex: 1 1 auto", "min-width: 0", "margin: 12px")) {
                assertTrue(rule.contains(declaration), "`$selector` keeps `$declaration`, like the pane it replaces")
            }
            for (property in listOf("border-radius:", "box-shadow:", "background:")) {
                assertTrue(rule.contains(property), "`$selector` floats as a card, with `$property`")
                assertTrue(pane.contains(property), "the terminal pane it borrows `$property` from still declares it")
            }
        }
        // The board clips and each column scrolls; the detail view is one document and scrolls itself.
        assertTrue(cssRuleOf(css, ".board").contains("overflow: hidden"), "the board itself never scrolls")
        assertTrue(
            cssRuleOf(css, ".board-column").contains("overflow-y: auto"),
            "a long todo column scrolls inside its own track rather than pushing done off the screen",
        )
        assertTrue(cssRuleOf(css, ".task-detail").contains("overflow-y: auto"), "the detail view scrolls as one page")
    }

    /**
     * A column's identity is one custom property, and the drop highlight is derived from it. Written as
     * two rules that must agree: if the accent were spelled per-rule, a fifth state (or a recoloured
     * one) would light the head and leave the drag feedback on the old colour.
     */
    @Test
    fun eachColumnStateOwnsOneAccentThatTheDropTargetReuses() = withServer { ctx ->
        val css = ctx.get("/style.css").bodyAsText()
        val column = cssRuleOf(css, ".board-column")
        assertTrue(column.contains("--column-accent:"), "the column declares the accent fallback the state rules override")
        assertTrue(
            column.contains("--column-fill:") && column.contains("background: var(--column-fill)"),
            "and publishes its fill as a property, which is what lets the drop tint reach the sticky head",
        )
        // Exactly the four workflow states of `TaskState`, which is what `data-state` carries.
        for (state in listOf("todo", "in_progress", "review", "done")) {
            assertTrue(
                css.contains(".board-column[data-state=\"$state\"] { --column-accent:"),
                "the `$state` column sets its own accent",
            )
        }
        val head = cssRuleOf(css, ".board-column-head")
        assertTrue(head.contains("color: var(--column-accent)"), "the column head is tinted from that one property")
        assertTrue(
            head.contains("background: var(--column-fill)") && head.contains("position: sticky"),
            "and the sticky head paints the column's own fill, so cards never scroll through it",
        )
        val drop = cssRuleOf(css, ".board-drop-target")
        assertTrue(
            drop.contains("--column-fill:") && drop.contains("var(--column-accent)") &&
                drop.contains("border-color:"),
            "the drop highlight derives from the same accent and rides the same fill instead of " +
                "hard-coding a second colour the head would then miss",
        )
    }

    /**
     * The drag handle is the only board element allowed to reserve the vertical gesture, and the
     * reservation must live outside the width breakpoint.
     *
     * Both halves are load-bearing and each has already been paid for elsewhere in this sheet.
     * `touch-action: none` on the column (or the card) would take away the finger-scroll that reaches
     * the rest of the backlog — measured on the terminal, where the opposite mistake stopped scrolling
     * entirely. And scoping it to `max-width: 720px` is the mistake `#terminal-host .xterm` records in
     * prose: a tablet is wider than the breakpoint and drags with a finger all the same.
     */
    @Test
    fun onlyTheCardHandleReservesTheDragGesture() = withServer { ctx ->
        val css = ctx.get("/style.css").bodyAsText()
        assertTrue(
            cssRuleOf(css, ".task-card-handle").contains("touch-action: none"),
            "the handle reserves the vertical gesture, without which a pointer drag never starts",
        )
        for (selector in listOf(".task-card", ".board-column", ".board-columns", ".board")) {
            assertFalse(
                cssRuleOf(css, selector).contains("touch-action"),
                "`$selector` leaves the finger its scroll — the reservation belongs to the handle alone",
            )
        }
        val breakpoint = css.indexOf("@media (max-width: 720px)")
        assertTrue(breakpoint > 0, "the mobile breakpoint exists")
        assertTrue(
            css.indexOf(".task-card-handle {") in 1 until breakpoint,
            "the reservation is unconditional: a touch tablet is wider than the phone breakpoint",
        )
    }

    /**
     * `data-state` on a session dot is accepted in both spellings the vocabulary line can be read as —
     * the raw `SessionState` and the `cls` half of `stateBadge`'s return value. The components that
     * emit it were written in parallel with this sheet and could not be consulted; a dot whose value
     * matched no rule would silently render in the muted fallback, which reads as a real state.
     */
    @Test
    fun aLinkedSessionDotIsColouredForEveryStateInBothSpellings() = withServer { ctx ->
        val css = ctx.get("/style.css").bodyAsText()
        assertTrue(
            cssRuleOf(css, ".task-session-dot").contains("--dot:"),
            "the dot declares the fallback colour its state rules override",
        )
        val states = listOf(
            "running", "ready", "needs_approval", "needs_answer", "stopped", "crashed", "resumable",
        )
        for (state in states) {
            assertTrue(
                css.contains(".task-session-dot[data-state=\"$state\"]"),
                "a dot carrying the raw session state `$state` has a colour",
            )
        }
        // `stateBadge` collapses the two attention states onto one class, so its six values are the
        // other spelling this must answer.
        for (cls in listOf(
            "badge-running", "badge-ready", "badge-attention", "badge-dead", "badge-crashed", "badge-resumable",
        )) {
            assertTrue(
                css.contains(".task-session-dot[data-state=\"$cls\"]"),
                "a dot carrying stateBadge's `$cls` has the same colour as the states behind it",
            )
        }
    }

    /** One rule per `ActivityKind`; a feed row whose kind matched nothing would lose its left rule. */
    @Test
    fun theActivityFeedHasARuleForEveryActivityKind() = withServer { ctx ->
        val css = ctx.get("/style.css").bodyAsText()
        val row = cssRuleOf(css, ".task-activity-row")
        assertTrue(row.contains("--activity-accent:"), "the feed row declares the accent its kinds override")
        assertTrue(
            row.indexOf("border-left:") > row.indexOf("border:"),
            "the left rule is written after the border shorthand that would otherwise erase it",
        )
        for (kind in listOf("created", "comment", "transition", "linked", "unlinked")) {
            assertTrue(
                css.contains(".task-activity-row[data-kind=\"$kind\"] { --activity-accent:"),
                "the `$kind` feed row is distinguishable",
            )
        }
    }

    /**
     * The phone branch: one column, the switcher that picks it, and the cards' full-bleed geometry —
     * all inside the ONE width breakpoint the rest of the sheet already uses, because a second
     * breakpoint is how two screens start disagreeing about what "mobile" means.
     */
    @Test
    fun thePhoneBreakpointCollapsesTheBoardAndRevealsItsColumnSwitcher() = withServer { ctx ->
        val css = ctx.get("/style.css").bodyAsText()
        assertTrue(
            css.contains(".board-column-switch { display: none; }"),
            "the switcher is laid out from nothing on every screen wider than a phone",
        )
        val breakpoint = css.indexOf("@media (max-width: 720px)")
        assertTrue(breakpoint > 0, "the mobile breakpoint exists")
        // The slice stops at the next media query, so a rule written into the coarse-pointer block
        // below cannot be mistaken for one inside the width breakpoint.
        val nextMedia = css.indexOf("@media ", breakpoint + 1)
        assertTrue(nextMedia > breakpoint, "the mobile block is followed by another media query")
        val mobile = css.substring(breakpoint, nextMedia)
        assertTrue(
            mobile.contains("grid-template-columns: minmax(0, 1fr);"),
            "the four tracks collapse to one on a phone",
        )
        assertTrue(
            Regex("""(?s)\.board-column-switch\s*\{[^}]*display: flex""").containsMatchIn(mobile),
            "and the switcher that picks which state is on screen appears with them",
        )
        assertTrue(
            Regex("""(?s)\.board,\s*\.task-detail\s*\{[^}]*border-radius: 0[^}]*box-shadow: none[^}]*margin: 0""")
                .containsMatchIn(mobile),
            "both screens give up the card inset for the safe-area-sized phone shell",
        )
        assertTrue(
            css.indexOf(".board-columns {") in 1 until breakpoint,
            "the desktop grid is declared before the breakpoint that narrows it",
        )
    }

    /**
     * The unknown-ref badge is the post-delete fallback, so it must read as a bare ref rather than as a
     * title — and it must survive arriving on its own, because whether the component composes the two
     * classes is decided in a file this one cannot read.
     */
    @Test
    fun theUnknownTaskBadgeStandsAloneAndDropsTheAccent() = withServer { ctx ->
        val css = ctx.get("/style.css").bodyAsText()
        val badge = cssRuleOf(css, ".task-badge")
        assertTrue(
            badge.contains("var(--pill-active)") && badge.contains("text-overflow: ellipsis"),
            "the known badge is an accent pill that truncates instead of squeezing the session name",
        )
        val unknown = cssRuleOf(css, ".task-badge-unknown")
        assertTrue(unknown.contains("border: 1px dashed"), "the unknown badge drops the accent for a dashed outline")
        assertTrue(unknown.contains("font-family: Menlo"), "and shows the bare ref in the monospace face")
        for (geometry in listOf("border-radius:", "padding:", "font-size:", "text-overflow: ellipsis")) {
            assertTrue(
                unknown.contains(geometry),
                "`.task-badge-unknown` repeats `$geometry`, so it is a pill even when it arrives alone",
            )
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * Whether [css] selects `.name` as a COMPLETE class token. The negative lookahead is the whole
     * point: `.task-card` must not be reported as present merely because `.task-card-title` is.
     */
    private fun declaresClass(css: String, name: String): Boolean =
        Regex("""\.${Regex.escape(name)}(?![-\w])""").containsMatchIn(css)

    /**
     * [css] with every CSS comment removed, so prose ABOUT a rule can never stand in for the rule. The
     * stylesheet's comments name neighbouring selectors on purpose (that is how its invariants are
     * recorded), which would otherwise make the vocabulary check pass on a class that was only ever
     * discussed.
     */
    private fun withoutComments(css: String): String {
        val out = StringBuilder(css.length)
        var i = 0
        while (i < css.length) {
            val open = css.indexOf("/*", i)
            if (open < 0) {
                out.append(css, i, css.length)
                break
            }
            out.append(css, i, open)
            val close = css.indexOf("*/", open + 2)
            if (close < 0) break
            i = close + 2
        }
        return out.toString()
    }

    /** One top-level `selector { … }` rule body, so a declaration check cannot read a neighbouring rule. */
    private fun cssRuleOf(css: String, selector: String): String {
        val start = css.indexOf("\n$selector {")
        assertTrue(start >= 0, "the stylesheet declares `$selector`")
        val end = css.indexOf("}", start)
        assertTrue(end > start, "`$selector`'s rule closes")
        return css.substring(start, end)
    }

    // --- harness ---------------------------------------------------------------------------------

    private inner class Ctx(val port: Int, val client: HttpClient) {
        suspend fun get(path: String): HttpResponse = client.get("http://127.0.0.1:$port$path")
    }

    /**
     * The same shape `WebUiServingTest` uses, rebuilt here rather than shared: its `withServer` is
     * `private` to that file, and this task owns no other test file. The task subsystem is deliberately
     * absent — nothing below leaves the static-serving path.
     */
    private fun withServer(block: suspend (Ctx) -> Unit) = runBlocking {
        withTimeout(40_000) {
            val store = NoopEventStore()
            val idScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager = SessionManager(
                FakeTmux(),
                store,
                PaneRegistry(),
                AgentFactory { _, cwd ->
                    object : AgentAdapter {
                        override val events: Flow<AgentEvent> = emptyFlow()
                        override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec =
                            LaunchSpec(listOf("cat"), emptyMap(), cwd, null)
                    }
                },
                ProviderIdCapture(store, idScope),
                VendorStoreProbe { _, _, _ -> false },
                VendorSessionLocator { _, _ -> null },
                setOf("claude", "codex"),
                now = { 1L },
            )
            val server = KotgentServer(
                sessionManager = manager,
                store = store,
                preferencesStore = store,
                tokens = TokenHolder(token),
                terminalBridgeFactory = { _, _ -> error("terminal bridge is not used in a stylesheet test") },
                currentVersion = currentVersion,
                webUiDir = locateWebUiDir(),
                port = 0,
            ).start()
            val client = HttpClient(CIO)
            try {
                block(Ctx(server.port(), client))
            } finally {
                client.close()
                server.stop()
                idScope.cancel()
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun currentDir(): String = memScoped {
        val size = 4096
        val buf = allocArray<ByteVar>(size)
        getcwd(buf, size.convert())
        buf.toKString()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun fileExists(path: String): Boolean = access(path, F_OK) == 0

    /** `./kotlin test` runs from the module root, but walk up anyway so the runner's cwd is not a contract. */
    private fun locateWebUiDir(): String {
        var dir = currentDir()
        repeat(6) {
            val candidate = "$dir/resources/webui"
            if (fileExists("$candidate/index.html")) return candidate
            val parent = dir.substringBeforeLast('/', "")
            if (parent.isEmpty() || parent == dir) return "resources/webui"
            dir = parent
        }
        return "resources/webui"
    }

    /** A no-op [EventStore]: nothing here leaves the static-serving path, so every member is unused. */
    private class NoopEventStore : EventStore, PreferencesStore {
        override val sessionUpdates: SharedFlow<SessionUpdate> = MutableSharedFlow<SessionUpdate>()
        private val preferenceState = MutableStateFlow(UiPreferences("", 1, 0))
        override val preferences: StateFlow<UiPreferences> get() = preferenceState
        override suspend fun savePreferences(basePath: String, groupingLevel: Int): UiPreferences =
            UiPreferences(basePath, groupingLevel, preferenceState.value.revision + 1).also {
                preferenceState.value = it
            }
        override suspend fun upsertSession(meta: SessionMeta) {}
        override suspend fun updateSessionState(
            sessionId: SessionId,
            state: io.kotgent.core.SessionState,
            stateSource: EventSource,
            paneId: io.kotgent.core.PaneId?,
            updatedAt: Long,
        ) {}
        override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long) {}
        override suspend fun setModel(sessionId: SessionId, model: String?, updatedAt: Long) {}
        override suspend fun setModelForProvider(
            sessionId: SessionId,
            providerSessionId: io.kotgent.core.ProviderSessionId,
            model: String,
            updatedAt: Long,
        ): Boolean = false
        override suspend fun markRead(sessionId: SessionId, seq: Seq) {}
        override suspend fun getSession(sessionId: SessionId): SessionMeta? = null
        override suspend fun listSessions(): List<SessionMeta> = emptyList()
        override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq = Seq(0L)
        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = emptyList()
        override suspend fun projectionOf(sessionId: SessionId): Projection = Projection.EMPTY
        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = emptyFlow()
    }
}
