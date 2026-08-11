package io.kotgent.webuitest

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The sidebar the whole app is navigated from, and the address bar it is coupled to.
 *
 * Every assertion here reads the DOM a real Chromium built from `resources/webui` against a real daemon,
 * which is the point: the tests these replace read `components/Sidebar.js` and `lib/paths.js` as TEXT and
 * asserted that a particular expression appeared in them. That could prove `sessionCount` was written as
 * `node.sessions.length + children.reduce(...)`; it could never prove a folder actually reports the size
 * of its own subtree, and it went green for any refactor that spelled the same rule differently.
 *
 * ## What the sidebar is, and why the tree is not the default
 * The body is a flat list of every session until a BASE PATH is configured — `groupingEnabled` is exactly
 * `prefs.basePath.length > 0`, so a daemon nobody has configured shows one list and no folder chrome at
 * all. The base path then decides the shape of the tree and the tree depth decides how deep it folds;
 * neither hides anything, because a cwd outside the base path stays a standalone group of its own.
 *
 * ## The design record these tests carry over from the grep tier
 * - Folders are RECURSIVE and their counts are AGGREGATE: a folder reports the sessions of its whole
 *   subtree, not the ones parked directly in it. `/a` says 3 while holding none of them itself.
 * - A cwd outside the base path becomes a standalone group AFTER the in-base tree, labelled by its full
 *   path where an in-base folder is labelled by its segment.
 * - Each folder's `+` carries that folder's exact full path, not the base path and not its label — that
 *   is what makes "new session here" mean here.
 * - Collapse state is keyed by the folder's full path and each folder toggles independently; a collapsed
 *   folder that is hiding a session which needs attention says so with its own dot, because the whole
 *   point of the triage dot is that it survives being folded away.
 * - The footer carries the running daemon's version, which is how an operator on a phone knows which
 *   build they are talking to.
 *
 * Two rules of the same grouping helper are recorded here but NOT observable from this scenario, because
 * no seeded session sits directly in a directory that also has a subdirectory: direct sessions render
 * before nested child folders, and a session at the base path itself gets a base-labelled node of its own
 * ahead of the tree. Both would need a fifth session (one at `/a`) to become visible in the DOM.
 *
 * ## Why the routing assertions live here
 * Selecting a row and the address bar are ONE thing: `showSession` navigates to `/s/{id}`, and the route
 * effect selects the session a route names. Before that coupling existed the two were independent owners
 * and a selection made from the board changed state nobody could see. A test that clicks a row therefore
 * owes an assertion about `location.pathname`, and the operator's Back is the other half of it.
 *
 * Scenario data is the frozen wave-1/2 contract: `s-alpha` claude `/a/b` running, `s-beta` codex `/a/b`
 * ready, `s-gamma` junie `/a/c` needs_approval, `s-delta` shell `/d` resumable, in that seed order (the
 * daemon lists sessions `ORDER BY created_at, id` and the browser never re-sorts).
 */
class SidebarTest {

    @Test
    fun theSidebarIsOneFlatListOfEverySessionUntilABasePathIsConfigured() {
        signedIn(SESSIONS_SCENARIO, "sidebar-flat") { _, _, page ->
            assertThat(page.locator("#session-list .session-row")).hasCount(4)

            // No base path is configured, so there is no folder chrome anywhere: no `grouped` class on
            // the list, no folder rows inside it, and no base-path note in the section heading (the note
            // is the affordance that only exists once there is a base path to name).
            assertThat(page.locator("#session-list.grouped")).hasCount(0)
            assertThat(page.locator("#session-list .session-group")).hasCount(0)
            assertThat(page.locator("#base-path-note")).hasCount(0)

            assertSidebarTree(
                page,
                """
                s-alpha
                s-beta
                s-gamma
                s-delta
                """.trimIndent(),
                "the flat list is every session, in the order the daemon lists them",
            )

            // The one row that needs attention is ALSO in the triage list above — the flat list is every
            // session, not everything-except-the-urgent-ones — and the head's counter agrees with it.
            assertThat(page.locator("#attention-list .session-row")).hasCount(1)
            assertThat(page.locator("#attention-list .session-row[data-id='s-gamma']")).hasCount(1)
            assertThat(page.locator("#attention-num")).hasText("1")
        }
    }

    @Test
    fun aBasePathFoldsTheRowsIntoTheDirectoryTreeTheirCwdsDescribe() {
        signedIn(SESSIONS_SCENARIO, "sidebar-tree") { _, _, page ->
            assertThat(page.locator("#session-list .session-row")).hasCount(4)
            configureGrouping(page, basePath = "/", level = 2)

            awaitFoldedTree(page, deepestFolder = "/a/b")
            assertSidebarTree(
                page,
                """
                a [/a] (3)
                  b [/a/b] (2)
                    s-alpha
                    s-beta
                  c [/a/c] (1)
                    s-gamma
                d [/d] (1)
                  s-delta
                """.trimIndent(),
                "two levels below the base path, with every folder counting its whole subtree",
            )

            // The aggregate is the claim worth naming on its own: the tree above shows `/a` holding no
            // session of its own, and its head still reports 3 — the sum of `/a/b` and `/a/c`.
            assertThat(folderHead(page, "/a").locator(".group-count")).hasText("3")

            // Each folder's `+` starts a session in THAT folder. Its label is the observable form of the
            // full path being passed through, which is what a label of "b" or of the base path would lose.
            assertThat(folderHead(page, "/a/b").locator(".group-new"))
                .hasAttribute("aria-label", "New session in /a/b")
            assertThat(folderHead(page, "/d").locator(".group-new"))
                .hasAttribute("aria-label", "New session in /d")

            // The section heading now names the base path, and that button is how Preferences stays
            // reachable without the command palette.
            assertThat(page.locator("#base-path-note")).hasText("/")

            // A second base path, because the base path decides the SHAPE of the tree and hides nothing.
            // Under `/a` its two directories become the roots — and `/d`, outside the base entirely,
            // stays a group of its own AFTER them, labelled by its full path where an in-base folder is
            // labelled by a segment. A base path that dropped it would quietly lose a live session.
            page.locator("#base-path-note").click()
            assertThat(page.locator("#prefs-dialog")).isVisible()
            savePreferences(page, basePath = "/a", level = 1)

            assertThat(page.locator("#session-list .group-title[title='/a']")).hasCount(0)
            assertThat(page.locator("#session-list > li.session-group")).hasCount(3)
            assertSidebarTree(
                page,
                """
                b [/a/b] (2)
                  s-alpha
                  s-beta
                c [/a/c] (1)
                  s-gamma
                /d [/d] (1)
                  s-delta
                """.trimIndent(),
                "an out-of-base directory keeps its own group, spelled in full, after the in-base tree",
            )
            assertThat(page.locator("#base-path-note")).hasText("/a")
        }
    }

    @Test
    fun collapsingAFolderHidesItsSubtreeAndSurfacesTheAttentionItWouldHide() {
        signedIn(SESSIONS_SCENARIO, "sidebar-collapse") { _, _, page ->
            assertThat(page.locator("#session-list .session-row")).hasCount(4)
            configureGrouping(page, basePath = "/", level = 2)
            awaitFoldedTree(page, deepestFolder = "/a/b")

            // Expanded, `/a` shows no dot of its own: nothing is being hidden, and the row that needs
            // attention carries its own dot where it is.
            assertThat(folderHead(page, "/a").locator(".attn-dot")).hasCount(0)

            folderHead(page, "/a").locator(".group-toggle").click()

            assertThat(folderHead(page, "/a").locator(".group-toggle"))
                .hasAttribute("aria-expanded", "false")
            // `/a`'s subtree is GONE from the DOM rather than hidden by CSS, and `/d` — a sibling folder
            // whose collapse state is its own — is untouched. The count still speaks for the whole
            // subtree, because a folded folder that stopped counting would be a folder that lied.
            assertSidebarTree(
                page,
                """
                a [/a] (3)
                d [/d] (1)
                  s-delta
                """.trimIndent(),
                "a collapsed folder keeps its aggregate count and drops only its own contents",
            )
            // s-gamma needs approval and is now two levels inside a folded folder, so the folder says so.
            // Without this the one signal the sidebar exists to surface would be one click away from
            // invisible.
            assertThat(folderHead(page, "/a").locator(".attn-dot")).hasCount(1)

            folderHead(page, "/a").locator(".group-toggle").click()
            assertThat(folderHead(page, "/a").locator(".group-toggle"))
                .hasAttribute("aria-expanded", "true")
            assertThat(folderHead(page, "/a").locator(".attn-dot")).hasCount(0)
            assertSidebarTree(
                page,
                """
                a [/a] (3)
                  b [/a/b] (2)
                    s-alpha
                    s-beta
                  c [/a/c] (1)
                    s-gamma
                d [/d] (1)
                  s-delta
                """.trimIndent(),
                "expanding restores the same subtree",
            )
        }
    }

    @Test
    fun theTreeDepthDecidesHowManyFoldersDeepTheSidebarFolds() {
        signedIn(SESSIONS_SCENARIO, "sidebar-depth") { _, _, page ->
            assertThat(page.locator("#session-list .session-row")).hasCount(4)
            configureGrouping(page, basePath = "/", level = 2)
            awaitFoldedTree(page, deepestFolder = "/a/b")

            // Reopened from the base-path note this time: with a base path configured that button is the
            // operator's route back into Preferences, and it must reach the same dialog the palette does.
            page.locator("#base-path-note").click()
            assertThat(page.locator("#prefs-dialog")).isVisible()
            assertThat(page.locator("#prefs-base-path")).hasValue("/")
            savePreferences(page, basePath = null, level = 1)

            // One level below the base path: `/a/b` and `/a/c` fold into `/a`, which now holds all three
            // of their sessions directly. Nothing moved out of the sidebar and nothing was hidden — the
            // depth changes how far the tree is drawn, not which sessions it contains.
            assertThat(page.locator("#session-list .group-title[title='/a/b']")).hasCount(0)
            assertSidebarTree(
                page,
                """
                a [/a] (3)
                  s-alpha
                  s-beta
                  s-gamma
                d [/d] (1)
                  s-delta
                """.trimIndent(),
                "one level below the base path folds the two children into their parent",
            )
            assertThat(page.locator("#session-list .session-row")).hasCount(4)
        }
    }

    @Test
    fun theSidebarFooterCarriesTheVersionTheDaemonItselfReports() {
        signedIn(SESSIONS_SCENARIO, "sidebar-version") { harness, context, page ->
            // Compared against the daemon's own answer rather than a literal: the harness serves whatever
            // this build's `currentUiVersion()` produces (`VERSION` plus a short git hash on a source
            // build, `VERSION` alone on a release one), so a hard-coded string would either pin the
            // release rule or need editing on every bump. What the footer owes is agreement with the API.
            val reported = versionReportedByTheDaemon(context, harness.baseUrl)

            assertThat(page.locator("#sidebar-footer #current-version")).hasText(reported)
            assertThat(page.locator("#current-version")).hasAttribute("title", "Kotgent version")
        }
    }

    @Test
    fun selectingASessionRowMovesTheAddressToItsOwnPathAndBackReturns() {
        signedIn(SESSIONS_SCENARIO, "sidebar-route") { harness, _, page ->
            assertThat(page.locator("#session-list .session-row")).hasCount(4)
            assertThat(page).hasURL("${harness.baseUrl}/")

            page.locator("#session-list .session-row[data-id='s-alpha']").click()
            assertThat(page).hasURL("${harness.baseUrl}/s/s-alpha")
            assertThat(page.locator(".session-row[data-id='s-alpha']").first())
                .hasAttribute("aria-current", "true")

            // A second selection is a second history entry, not a replacement.
            page.locator("#session-list .session-row[data-id='s-delta']").click()
            assertThat(page).hasURL("${harness.baseUrl}/s/s-delta")
            assertThat(page.locator(".session-row[data-id='s-delta']").first())
                .hasAttribute("aria-current", "true")
            assertThat(page.locator("#session-list .session-row[data-id='s-alpha']"))
                .not().hasAttribute("aria-current", "true")

            // Back is the other half of the coupling: the route names a session, so the sidebar selects
            // it. Without that, Back out of a session (or a pasted link, or a reload) used to land on the
            // session view with nothing selected at all.
            page.goBack()
            assertThat(page).hasURL("${harness.baseUrl}/s/s-alpha")
            assertThat(page.locator(".session-row[data-id='s-alpha']").first())
                .hasAttribute("aria-current", "true")

            // And back to where the page started. The coupling is deliberately ONE-directional here: `/`
            // names no session, and clearing the selection there would tear down a live terminal for a
            // navigation the operator made to reach exactly that terminal.
            page.goBack()
            assertThat(page).hasURL("${harness.baseUrl}/")
            assertThat(page.locator(".session-row[data-id='s-alpha']").first())
                .hasAttribute("aria-current", "true")
        }
    }

    @Test
    fun anEmptyFirstRunOffersItsOwnStartASessionAction() {
        signedIn(EMPTY_SCENARIO, "sidebar-empty") { _, _, page ->
            // The empty panel renders only once the first snapshot has landed — an empty list and an
            // unanswered daemon are different things, and the loading note is what says so. Asserting the
            // panel first is therefore also the wait that makes the count below mean anything.
            assertThat(page.locator("#empty-sessions")).isVisible()
            assertThat(page.locator("#sessions-loading")).hasCount(0)
            assertThat(page.locator("#session-list .session-row")).hasCount(0)
            assertThat(page.locator("#attention-section")).hasCount(0)

            // The command palette is where rare actions live, but a first run has nothing to select and
            // no reason to know the palette exists yet, so this one action stays direct on the screen.
            assertThat(page.locator("#empty-new-session-button")).hasText("Start a session")
            page.locator("#empty-new-session-button").click()
            assertThat(page.locator("#new-session-dialog")).isVisible()
        }
    }
}

/** The scenario with no sessions at all — a daemon nobody has started anything on yet. */
private const val EMPTY_SCENARIO: String = "empty"

/**
 * Sign a fresh context in, open the app, and hand the test its harness, context and page.
 *
 * A fresh [BrowserContext] per test is mandatory (a kotgent cookie is not scoped by port, so a reused one
 * is sent to the next harness and fails its HMAC), and [BrowserContext.traced] keeps a trace and a
 * screenshot only when the body fails.
 */
private fun signedIn(
    scenario: String,
    trace: String,
    block: (Harness, BrowserContext, Page) -> Unit,
) {
    Harness(scenario).use { harness ->
        Playwright.create().use { pw ->
            touchChromium(pw).use { browser ->
                browser.newContext().use { context ->
                    context.traced(trace) {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)
                        val page = context.newPage()
                        page.navigate("${harness.baseUrl}/")
                        assertThat(page.locator("#sidebar")).isVisible()
                        block(harness, context, page)
                    }
                }
            }
        }
    }
}

/**
 * Configure grouping the way an operator with no base path has to: through the command palette.
 *
 * With `basePath` empty the sidebar draws no base-path note, so the palette really is the only route to
 * Preferences at that moment — which is why this helper opens it from there and the depth test opens it
 * from the note instead.
 */
private fun configureGrouping(page: Page, basePath: String, level: Int) {
    // The header's ⋯ rather than ⌘K: the button is the palette's guaranteed path on every surface (the
    // chord and its mnemonics are the command-palette tests' own subject), and it opens the same leader
    // grid the chord does.
    page.locator("#palette-button").click()
    assertThat(page.locator("#command-palette")).isVisible()
    page.locator(".command-palette-leader-command")
        .filter(Locator.FilterOptions().setHasText("Preferences"))
        .click()
    assertThat(page.locator("#prefs-dialog")).isVisible()
    savePreferences(page, basePath, level)
}

/**
 * Fill and submit the open Preferences dialog, then wait for it to be gone.
 *
 * The dialog UNMOUNTS on a successful save, so its absence is the honest signal that the write landed and
 * the daemon's answer has been applied — a `PUT /preferences` that failed leaves the dialog up with its
 * error line, and every assertion after this one would then be measuring the old preference.
 */
private fun savePreferences(page: Page, basePath: String?, level: Int) {
    if (basePath != null) page.locator("#prefs-base-path").fill(basePath)
    page.locator("#prefs-grouping-level").selectOption(level.toString())
    page.locator("#prefs-submit").click()
    assertThat(page.locator("#prefs-dialog")).hasCount(0)
}

/**
 * Wait until the folded sidebar has settled, so the one-shot DOM read below cannot race the render.
 *
 * [Page.evaluate] does not retry, unlike a Playwright assertion, so every reconstruction of the tree is
 * preceded by auto-waiting assertions that pin the shape it is about to read. The row count is the
 * `sessions` scenario's four, which is what every caller here folds.
 */
private fun awaitFoldedTree(page: Page, deepestFolder: String) {
    assertThat(page.locator("#session-list.grouped")).hasCount(1)
    assertThat(page.locator("#session-list .group-title[title='$deepestFolder']")).hasCount(1)
    assertThat(page.locator("#session-list .session-row")).hasCount(4)
}

/** One folder's head row, addressed by the full path its title carries. */
private fun folderHead(page: Page, path: String): Locator =
    page.locator("#session-list .group-head:has(.group-title[title='$path'])")

/**
 * The sidebar's session list, rebuilt from the live DOM as indented text.
 *
 * A folder is rendered `label [path] (count)` and a session as its `data-id`; nesting is two spaces per
 * level. Reconstructing the whole shape in one value — rather than asserting a handful of independent
 * counts — is what makes the expectation readable AS the tree and makes a wrong parent, a missing level
 * or a session filed under the wrong folder fail with a diff of the actual hierarchy.
 */
private fun sidebarTree(page: Page): String {
    val tree = page.evaluate(SIDEBAR_TREE_SCRIPT)
    return tree as? String ?: fail("the sidebar tree script answered ${tree ?: "null"}")
}

private fun assertSidebarTree(page: Page, expected: String, message: String) {
    assertEquals(expected, sidebarTree(page), message)
}

private val SIDEBAR_TREE_SCRIPT: String = """
    () => {
      const list = document.querySelector("#session-list");
      if (!list) return "(no #session-list)";
      const walk = (ul, depth) => {
        const out = [];
        const pad = "  ".repeat(depth);
        for (const li of Array.from(ul.children)) {
          if (li.classList.contains("session-group")) {
            const head = li.querySelector(":scope > .group-head");
            const title = head ? head.querySelector(".group-title") : null;
            const count = head ? head.querySelector(".group-count") : null;
            const label = title ? title.textContent.trim() : "(no group title)";
            const path = title ? (title.getAttribute("title") || "") : "";
            const total = count ? count.textContent.trim() : "(no group count)";
            out.push(pad + label + " [" + path + "] (" + total + ")");
            const contents = li.querySelector(":scope > ul.group-contents");
            if (contents) out.push(...walk(contents, depth + 1));
          } else if (li.classList.contains("session-row")) {
            out.push(pad + (li.getAttribute("data-id") || "(no data-id)"));
          }
        }
        return out;
      };
      return walk(list, 0).join("\n");
    }
""".trimIndent()

/** `{"version":"…"}` — matched rather than parsed, so this module needs no JSON library of its own. */
private val VERSION_FIELD = Regex("\"version\"\\s*:\\s*\"([^\"]*)\"")

/** What `GET /api/v1/version` answers this signed-in context — the value the footer must agree with. */
private fun versionReportedByTheDaemon(context: BrowserContext, baseUrl: String): String {
    // The context's own request client, so the session cookie rides along: /version is authenticated.
    val response = context.request().get("$baseUrl/api/v1/version")
    val body = response.text()
    assertEquals(200, response.status(), "GET /api/v1/version answered ${response.status()}: $body")
    val version = VERSION_FIELD.find(body)?.groupValues?.get(1)
        ?: fail("no \"version\" field in the daemon's answer: $body")
    assertTrue(version.isNotBlank(), "the daemon reported a blank version: $body")
    return version
}
