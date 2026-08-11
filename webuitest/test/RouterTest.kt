package io.kotgent.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.WaitUntilState
import java.util.regex.Pattern
import kotlin.test.Test

/**
 * The SPA router in a real browser: the four addresses `resources/webui/lib/router.js` owns, the screens
 * `app.js` mounts for them, and the History API underneath both.
 *
 *   /            the session view
 *   /tasks       the kanban board
 *   /tasks/{ref} one task's detail, mounted as the board's sibling
 *   /s/{id}      one session
 *
 * This replaces two source-reading files, `WebUiScreenRoutingTest` (deleted) and most of
 * `WebUiRouterTest` (shrunk to the two guards a browser cannot make): those could only read the served
 * text of `router.js` and `app.js` and argue that the two would meet at runtime. Here they actually do —
 * a route is entered the way an operator enters one, and what is asserted is the address bar, the history
 * stack and the screen that came up.
 *
 * **Three couplings this file settles by exercise rather than by grep.** The route grammar must be the one
 * `isSpaRoute` (`src/transport/WebUiAssets.kt`) answers with the shell, or a link dies on reload — which
 * is exactly what [everyScreenKeepsItsOwnAddressAcrossAReload] presses F5 to find out; the server's half
 * of that grammar keeps its own HTTP test (`test/transport/SpaRoutingTest.kt`), untouched. A selection has
 * to move the URL, or it happens behind whatever screen the route has put on — the reason
 * [linkNavigationCrossLinksTheScreensAndBackForwardRetracesIt] picks a session from *inside the board*.
 * And `?session=<id>`, the deep link `sw.js` builds for a notification tap, has to reach the same
 * `{screen, id}` as a path — [aNotificationDeepLinkSelectsTheSessionAndThePathOutranksIt].
 *
 * **The deep link does NOT survive a cold start, and that is recorded rather than fixed.** These tests
 * sign the CONTEXT in first (`loginWithTicket`), so the browser that opens `/s/{id}` already holds the
 * cookie and the address is honoured. An operator opening the same link with an empty cookie jar — the
 * installed PWA's first launch, an unpaired phone — gets the shell (the daemon serves every SPA route
 * unauthenticated), whose first `/preferences` answers 401 and does `location.replace(AUTH_PATH)`; the
 * auth page then finishes with its own `location.replace("/")` (`src/transport/AuthRoutes.kt`). Both
 * replacements throw the requested path away, so the operator lands on the session view with nothing
 * selected. Carrying the intended path through the exchange is a change to the auth bootstrap, not to the
 * router, so no assertion here pretends otherwise.
 *
 * **What deliberately is not here**, though `WebUiScreenRoutingTest` used to read it out of the same two
 * files: the sidebar's body swap and its row/version rendering (the sidebar's own browser test), the
 * geometry of the board/detail pair — this file only proves the two are MOUNTED together at
 * `/tasks/{ref}` (the board's style test owns whether the detail floats over the board or squeezes it),
 * the board's own `#board-status` announcer and its one-shot New-task counter (the board and palette
 * tests), and the mark-read gate, which needs a fixture with unread to observe at all.
 */
class RouterTest {

    /**
     * A pasted link, a reload, a ⌘-click into a new tab: `/s/{id}` names the session to attach.
     *
     * Before the router grew a consumer, nothing read this arm — the address parsed fine and the daemon
     * served it, and the app came up with nothing selected. WHICH session is on is read from the terminal
     * header's task badge rather than from a name in the sidebar: the badge exists only for a selected
     * session and its href carries that session's task, so it names the row without this test having to
     * know how the fixture spelled the session's title.
     *
     * The second half is the same route with an id no row carries. The app must still render its screen:
     * the id is HELD, not discarded (the first snapshot has not necessarily landed when the route is
     * parsed, and the effect re-runs on every list change), and a route the list cannot satisfy may not
     * blank the page or bounce the address bar somewhere else.
     */
    @Test
    fun aSessionPathOpensThatSessionOnFirstLoad() = routerTest("session-path") { base, page ->
        page.navigate("$base/s/$DEEP_SESSION")
        assertSessionView(page)
        assertThat(page.locator(TERMINAL_TASK)).hasAttribute("href", TASK_HREF)
        // Checked once the selection has LANDED, not straight after the navigation: honouring the route
        // runs the app's own `navigate`, and this is what says that call recognised the page was already
        // where it was asked to go instead of rewriting the address under a link the operator pasted.
        assertThat(page).hasURL("$base/s/$DEEP_SESSION")

        page.navigate("$base/s/no-such-session")
        assertSessionView(page)
        // The list really did arrive (the sidebar announces its size on the first snapshot only), so the
        // empty selection below is an answer and not a page that has not rendered yet.
        assertThat(page.locator("#status-line")).hasText(SNAPSHOT_ANNOUNCEMENT)
        assertThat(page.locator(TERMINAL_TASK)).hasCount(0)
        assertThat(page.locator("li.session-row[aria-current='true']")).hasCount(0)
        assertThat(page).hasURL("$base/s/no-such-session")
    }

    /**
     * `/tasks/{ref}` opens one task, over the board rather than instead of it: the card keeps its
     * `aria-current` highlight and the project selector stays live while the detail is read.
     *
     * Both spellings of the ref are the same route. A `TaskRef` is `<tracker>:<key>`, the mandatory `:`
     * goes through `encodeURIComponent` as `%3A` whenever the app builds the link itself, and a
     * hand-typed or copied `/tasks/local:7` must resolve to the same task — which is why the segment is
     * decoded on the way in instead of compared raw.
     */
    @Test
    fun aTaskPathOpensThatTasksDetailOnFirstLoad() = routerTest("task-path") { base, page ->
        page.navigate("$base/tasks/$ENCODED_TASK")
        assertThat(page).hasURL(taskUrl(base))
        assertTaskDetail(page)

        page.navigate("$base/tasks/$DEEP_TASK")
        assertThat(page).hasURL(taskUrl(base))
        assertTaskDetail(page)
    }

    /**
     * The notification deep link. `sw.js` is a classic script with no module graph — it cannot import the
     * router — so it hand-builds `/?session=<id>` in `openWindow`, and the two spellings of that
     * parameter are kept in step by the one source guard left in `WebUiRouterTest`. What that guard
     * cannot show is that the parameter arrives somewhere: here the tap's URL really does select the
     * session, and the address is rewritten to the session's own path, so a later reload re-selects it
     * from the path instead of re-honouring a query the operator has already spent.
     *
     * The second half is the precedence rule: a path that names a session outranks a leftover query. A
     * window opened by a tap and then navigated in-app can carry both, and the path is the newer
     * statement of intent — the query is a leftover from the tap that opened the window.
     */
    @Test
    fun aNotificationDeepLinkSelectsTheSessionAndThePathOutranksIt() = routerTest("deep-link") { base, page ->
        page.navigate("$base/?session=$DEEP_SESSION")
        assertSessionView(page)
        assertThat(page.locator(TERMINAL_TASK)).hasAttribute("href", TASK_HREF)
        // Exact, so the honoured `?session=` is provably gone from the address bar.
        assertThat(page).hasURL("$base/s/$DEEP_SESSION")

        page.navigate("$base/s/$DEEP_SESSION?session=no-such-session")
        assertSessionView(page)
        assertThat(page.locator(TERMINAL_TASK)).hasAttribute("href", TASK_HREF)
        assertThat(page).hasURL("$base/s/$DEEP_SESSION")
    }

    /**
     * The sidebar's two links are the app's navigation, and the sidebar is on every screen.
     *
     * The board used to be a one-way door in the surface this app is built for — an installed PWA draws
     * no browser chrome, and both shell controls that could navigate lived in the terminal pane, which
     * the board's branch unmounts — so it carried a "Sessions" link of its own. The pair now sits in the
     * sidebar's head, points both ways, and no screen owns an exit.
     *
     * The Sessions link names the SELECTED session rather than always going to `/`: the two are the same
     * screen, but the URL is what a reload, a bookmark and a shared link resolve, so the address bar
     * should describe the terminal that is actually on it.
     */
    @Test
    fun theSidebarsTwoLinksMoveBetweenTheScreensFromEitherOne() = routerTest("nav-switch") { base, page ->
        page.navigate("$base/s/$DEEP_SESSION")
        assertSessionView(page)
        // Wait for the deep link to be HONOURED before leaving: the Sessions link names the selection,
        // and nothing on the board can make a selection the session view never made.
        assertThat(page.locator(TERMINAL_TASK)).hasAttribute("href", TASK_HREF)
        val sessions = page.locator(".nav-switch a:text-is('Sessions')")
        val tasks = page.locator(".nav-switch a:text-is('Tasks')")
        assertThat(sessions).hasAttribute("aria-current", "page")

        tasks.click()
        assertThat(page).hasURL("$base/tasks")
        assertBoard(page)
        assertThat(tasks).hasAttribute("aria-current", "page")
        assertThat(sessions).not().hasAttribute("aria-current", "page")
        assertThat(sessions).hasAttribute("href", "/s/$DEEP_SESSION")

        sessions.click()
        assertThat(page).hasURL("$base/s/$DEEP_SESSION")
        assertSessionView(page)
        assertThat(page.locator(TERMINAL_TASK)).hasAttribute("href", TASK_HREF)
    }

    /**
     * The fixture's session and task point at each other, so the walk can be made in both directions
     * through the app's own links — and then retraced with the browser's Back and Forward.
     *
     * The return leg is the half with the most victims. A selection that did not move the URL was
     * invisible while the board owned the screen, because which screen is on is computed from the route
     * alone: a palette row, a notification tap and the task detail's own "Start session" all ran to
     * completion behind a kanban board, opening a terminal socket and clearing an unread badge nobody
     * could see. Picking the linked session from the detail is exactly that path, and what proves it now
     * is the session view coming up.
     *
     * Back and Forward reach the app through `popstate`, the same subscription `navigate()` notifies, so
     * the assertions on the way back are the same ones as on the way out. Each hop is committed rather
     * than waited on for a `load`: every one of them is a same-document navigation, and no document
     * loads again.
     */
    @Test
    fun linkNavigationCrossLinksTheScreensAndBackForwardRetracesIt() = routerTest("cross-link") { base, page ->
        page.navigate("$base/s/$DEEP_SESSION")
        assertSessionView(page)

        page.locator(TERMINAL_TASK).click()
        assertThat(page).hasURL(taskUrl(base))
        assertTaskDetail(page)

        // Scoped to the DETAIL. `.task-sessions` is shared with `TaskCard`, which lists the same linked
        // session on the card still rendered behind this panel (the detail is the board's sibling, which
        // is the very thing `assertTaskDetail` asserts), so the bare class matches two anchors with the
        // same href and Playwright's strict mode refuses to guess. The detail's link is the one this
        // walk is about — the card's is the board's own affordance and has a test of its own.
        page.locator("section.task-detail .task-sessions a[href='/s/$DEEP_SESSION']").click()
        assertThat(page).hasURL("$base/s/$DEEP_SESSION")
        assertSessionView(page)

        page.goBack(Page.GoBackOptions().setWaitUntil(WaitUntilState.COMMIT))
        assertThat(page).hasURL(taskUrl(base))
        assertTaskDetail(page)

        page.goBack(Page.GoBackOptions().setWaitUntil(WaitUntilState.COMMIT))
        assertThat(page).hasURL("$base/s/$DEEP_SESSION")
        assertSessionView(page)

        page.goForward(Page.GoForwardOptions().setWaitUntil(WaitUntilState.COMMIT))
        assertThat(page).hasURL(taskUrl(base))
        assertTaskDetail(page)

        page.goForward(Page.GoForwardOptions().setWaitUntil(WaitUntilState.COMMIT))
        assertThat(page).hasURL("$base/s/$DEEP_SESSION")
        assertSessionView(page)
        assertThat(page.locator(TERMINAL_TASK)).hasAttribute("href", TASK_HREF)
    }

    /**
     * Every screen has an address of its own, and every one of those addresses is reloadable.
     *
     * This is the router's and the daemon's halves of one grammar meeting: `parseRoute` understands these
     * four paths and `isSpaRoute` serves the shell for exactly them, so F5 has to come back on the same
     * screen. A path either side got wrong is a link that dies on reload — the browser would land on the
     * daemon's 404 instead of the app, and no assertion below would find its screen.
     */
    @Test
    fun everyScreenKeepsItsOwnAddressAcrossAReload() = routerTest("reload") { base, page ->
        roundTrip(page, "$base/") {
            assertSessionView(page)
            assertThat(page.locator("#status-line")).hasText(SNAPSHOT_ANNOUNCEMENT)
            assertThat(page.locator(TERMINAL_TASK)).hasCount(0)   // `/` names no session
            assertThat(page).hasURL("$base/")
        }
        roundTrip(page, "$base/s/$DEEP_SESSION") {
            assertSessionView(page)
            assertThat(page.locator(TERMINAL_TASK)).hasAttribute("href", TASK_HREF)
            assertThat(page).hasURL("$base/s/$DEEP_SESSION")
        }
        roundTrip(page, "$base/tasks") {
            assertBoard(page)
            assertThat(page.locator("section.task-detail")).hasCount(0)   // the board alone
            assertThat(page).hasURL("$base/tasks")
        }
        roundTrip(page, "$base/tasks/$ENCODED_TASK") {
            assertTaskDetail(page)
            assertThat(page).hasURL(taskUrl(base))
        }
    }

    // --- harness -------------------------------------------------------------------------------------

    /**
     * One `deep-link` daemon, one signed-in context, one page.
     *
     * A DESKTOP viewport, deliberately, though the fixture's default context is a phone: the sidebar is
     * an overlay drawer below 721px (its two navigation links would need the drawer opened first) and the
     * board is `display: none` while a task detail is open there. Both of those are the phone layout's
     * own contracts and belong to the tests that own those screens; what this file navigates is routes.
     *
     * A fresh context per test is mandatory — the session cookie is not bound to the harness port, so a
     * reused context would carry a stale one — and the context is closed before the harness so no page is
     * still fetching while the daemon shuts down (`Harness.close()` asserts a clean exit code).
     */
    private fun routerTest(name: String, block: (String, Page) -> Unit) {
        Harness(DEEP_LINK_SCENARIO).use { harness ->
            Playwright.create().use { playwright ->
                val context = touchChromium(playwright).touchContext(
                    width = 1280,
                    height = 900,
                    deviceScaleFactor = 1.0,
                    mobile = false,
                )
                context.loginWithTicket(harness.ticket, harness.baseUrl)
                try {
                    context.traced(name) { block(harness.baseUrl, context.newPage()) }
                } finally {
                    context.close()
                }
            }
        }
    }

    /** Enter [url], check the screen, press F5, check the same screen again. */
    private fun roundTrip(page: Page, url: String, expect: () -> Unit) {
        page.navigate(url)
        expect()
        page.reload()
        expect()
    }

    /**
     * The session view is the screen on.
     *
     * Both halves matter: the two branches are exclusive, so the board's absence is what says the route
     * put THIS screen on rather than the app having rendered both. The pane's own visibility is the
     * positive that keeps the negative from passing against a page that has not rendered at all.
     */
    private fun assertSessionView(page: Page) {
        assertThat(page.locator("#terminal-pane")).isVisible()
        assertThat(page.locator("main.board")).hasCount(0)
    }

    /** The board is the screen on — the mirror of [assertSessionView], and exclusive with it. */
    private fun assertBoard(page: Page) {
        assertThat(page.locator("main.board")).isVisible()
        assertThat(page.locator("#terminal-pane")).hasCount(0)
    }

    /**
     * One task's detail, mounted as the board's SIBLING: `/tasks/{ref}` is still the board's screen, with
     * the detail over it. The title carries the ref in every state of the panel (loading, loaded, gone),
     * so this identifies the task without waiting on the detail's own fetch.
     */
    private fun assertTaskDetail(page: Page) {
        assertBoard(page)
        assertThat(page.locator("section.task-detail")).isVisible()
        assertThat(page.locator("#task-detail-title")).hasText(DEEP_TASK)
    }
}

/** The scenario whose session and task reference each other, so either one deep-links to the other. */
private const val DEEP_LINK_SCENARIO = "deep-link"

private const val DEEP_SESSION = "deep-session"
private const val DEEP_TASK = "local:7"

/** The ref as `encodeURIComponent` writes it — the spelling every link the app builds carries. */
private const val ENCODED_TASK = "local%3A7"

/** The terminal header's task badge: present only for a selected session, and named after ITS task. */
private const val TERMINAL_TASK = "#terminal-task"

/** That badge's target. An `href` attribute is the literal the app wrote, never a normalised URL. */
private const val TASK_HREF = "/tasks/$ENCODED_TASK"

/** The sidebar's first-snapshot line — proof that the session list arrived, whatever it contained. */
private val SNAPSHOT_ANNOUNCEMENT: Pattern = Pattern.compile("""\d+ session\(s\)\.""")

/**
 * `/tasks/local:7` as the address bar may spell it. The app's own links go through
 * `encodeURIComponent` (`%3A`) and a hand-typed one keeps the bare `:`; the browser preserves whichever
 * it was given, and both are the same route.
 */
private fun taskUrl(base: String): Pattern =
    Pattern.compile("^" + regexLiteral("$base/tasks/local") + "(%3A|:)7$")

/**
 * [text] as one literal inside a regular expression — and deliberately NOT `Pattern.quote`.
 *
 * A `java.util.regex.Pattern` handed to a Playwright assertion is not evaluated in Java at all: the
 * driver ships its SOURCE TEXT to Node and matches it there with a JavaScript `RegExp`, which has no
 * `\Q…\E` quoting. `\Q` is simply an escaped `Q`, so `Pattern.quote("http://…")` compiles, on the far
 * side, to a pattern that must begin with a literal `Q` and can therefore never match anything. The
 * failure it produces is unusually cruel — it prints the Java spelling of the pattern next to a URL that
 * plainly satisfies it — so escape by hand and keep the pattern portable to both engines.
 */
private fun regexLiteral(text: String): String = buildString {
    for (ch in text) {
        if (ch in REGEX_METACHARACTERS) append('\\')
        append(ch)
    }
}

private const val REGEX_METACHARACTERS = "\\^$.|?*+()[]{}"
