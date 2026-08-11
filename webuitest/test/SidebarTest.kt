package io.kotgent.webuitest

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
 * - "Done" (`archived`) is a THIRD list, not a deletion: the row leaves the main list, a `#done-section`
 *   appears with its own count, and only a row inside it is given Restore — which takes the state badge's
 *   place. No scenario can seed an archived session, so the browser makes one the way an operator does.
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
 * ## The three list-protocol states that are also sidebar chrome
 * A row's unread pill, the body's "Loading sessions…" note and the footer's `#status-line` are drawn by
 * this component, so the three behaviours behind them are exercised here — the last of the claims that
 * `WebUiServingTest.daemonServesTheAppEntryModule` used to make by grepping `app.js` for identifiers
 * (`markReadIfViewing`, `READ_RETRY_DELAY_MS`, `sessionsReady`, `disconnectAnnouncedRef.current`), with a
 * comment explaining that "there is no JS harness, so these greps are what stops the whole feature from
 * being deleted". There is a harness now:
 * - the unread badge is the DAEMON's number and the mark-read POST is what moves it, with a retry loop
 *   that heals a lost POST and gives up on an answer that can never change;
 * - "Loading sessions…" is what an unanswered daemon looks like, and it is deliberately not the same
 *   thing as "No sessions yet" — the list has exactly one source, and it is the socket's snapshot;
 * - both status-line announcements are latched: the routine "N session(s)." fires only on the FIRST
 *   snapshot, and the outage line once per outage rather than once per 2 s reconnect attempt.
 *
 * Scenario data is the frozen wave-1/2 contract: `s-alpha` claude `/a/b` running, `s-beta` codex `/a/b`
 * ready, `s-gamma` junie `/a/c` needs_approval, `s-delta` shell `/d` resumable, in that seed order (the
 * daemon lists sessions `ORDER BY created_at, id` and the browser never re-sorts). The `attention`
 * scenario's `s-unread` is `running` with `lastSeq` 5 and a read cursor of 2, i.e. an unread count of 3.
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

    /**
     * "Done" hides a row without losing it, and Restore brings it back — the sidebar's third list.
     *
     * `archived` is one of the three fields written OUTSIDE the reducer, and it is the only one with a
     * body of sidebar chrome of its own: a `#done-section` that exists only while something is archived, a
     * disclosure that keeps its own count, a `#done-list` the rows move into, and a Restore control that
     * only a row in that list is given. None of that was reachable from any fixture — no scenario seeds an
     * archived session — so the whole branch shipped unexercised once the grep tier that pinned
     * `general.show-done` went away.
     *
     * The browser can produce the state itself, which is the point: `⌘K d` runs the same
     * `POST /sessions/{id}/done` an operator runs, so what is set up here is exactly what is asserted
     * about. `s-delta` is the `resumable` shell — selecting it attaches no terminal, and "Done" on it is a
     * pure archive with no pane to kill.
     *
     * The disclosure is asserted CLOSED first. That is not ceremony: the row is out of `#session-list` the
     * moment it is archived, so without the closed state being observed the test could not tell "hidden
     * behind a collapsed section" from "gone".
     */
    @Test
    fun aDoneSessionMovesIntoItsOwnCollapsedListAndRestoreBringsItBack() {
        signedIn(SESSIONS_SCENARIO, "sidebar-done") { _, _, page ->
            assertThat(page.locator("#session-list .session-row")).hasCount(4)
            // Nothing is archived yet, so the section does not exist at all — an empty disclosure would be
            // furniture on every first run.
            assertThat(page.locator("#done-section")).hasCount(0)

            // "Done" is one of the two destructive verbs that ask first, and Playwright DISMISSES a native
            // dialog unless something answers it — which would silently make this test about a cancelled
            // confirm. Accepting is what an operator does; the prompt itself is the palette's subject.
            page.onDialog { dialog -> dialog.accept() }

            page.locator("#session-list .session-row[data-id='$DONE_SESSION']").click()
            runLeaderCommand(page, "Done current session")

            assertThat(page.locator("#session-list .session-row")).hasCount(3)
            assertThat(page.locator("#session-list .session-row[data-id='$DONE_SESSION']")).hasCount(0)
            val toggle = page.locator("#show-done-toggle")
            assertThat(toggle).hasText("▸ Show done (1)")
            assertThat(toggle).hasAttribute("aria-expanded", "false")
            assertThat(page.locator("#done-list")).hasCount(0)

            toggle.click()
            assertThat(toggle).hasAttribute("aria-expanded", "true")
            val doneRow = page.locator("#done-list .session-row[data-id='$DONE_SESSION']")
            assertThat(doneRow).hasCount(1)
            // The control only this list gives a row — `onRestore` is passed here and nowhere else — and
            // it takes the state badge's PLACE, which is the visible difference between an archived row
            // and a live one.
            val restore = doneRow.locator(".session-restore")
            assertThat(restore).hasCount(1)
            assertThat(doneRow.locator(".badge")).hasCount(0)

            restore.click()

            // Un-archived: back in the main list, and the section is gone again because it is empty.
            assertThat(page.locator("#session-list .session-row[data-id='$DONE_SESSION']")).hasCount(1)
            assertThat(page.locator("#session-list .session-row")).hasCount(4)
            assertThat(page.locator("#done-section")).hasCount(0)
        }
    }

    /**
     * The unread pill is the DAEMON's number, and `POST /sessions/{id}/read` is the only thing that moves
     * it: nothing is zeroed locally, because the cursor is server state that every other client (a phone,
     * a second tab) reads the same badge out of.
     *
     * The POST is driven from imperative triggers rather than from a `useEffect` on `[id, lastSeq,
     * unread]`, and this test exercises two of them — a selection and a `session_update` frame for the
     * session on screen. That shape is exactly why the retry loop below has to exist: when a POST fails,
     * none of those three primitives changes, so an effect's `Object.is` dep check would skip the retry
     * precisely when it matters most (a `needs_approval` session may emit no further frame at all).
     *
     * The three answers the daemon can give are all produced here, because the rule that separates them is
     * the whole design. A `4xx` is the daemon's own answer about this session (a 401 after a rotation, a
     * 404 for a session that is gone) and can never succeed, so the loop stops — a page that stays open
     * for days must not hammer an unwinnable route. A network failure carries no status at all and is
     * therefore transient, so the loop keeps going. And a 200 is what actually clears the badge, over the
     * ordinary `session_update` the daemon broadcasts after writing the cursor.
     *
     * The two phases are measured over the SAME six seconds — three of the loop's own 2 s delays — and the
     * transient one is what makes the definitive one falsifiable: the window that produced no second
     * attempt after the 404 fills with retries once the failure carries no status, so the quiet window was
     * a decision and not merely a short wait.
     */
    @Test
    fun theUnreadBadgeIsTheDaemonsNumberAndALostMarkReadHealsItself() {
        signedIn(ATTENTION_SCENARIO, "sidebar-unread") { harness, _, page ->
            val attempts = AtomicInteger()
            val bodies = CopyOnWriteArrayList<String>()
            val answer = AtomicReference(READ_DEFINITE)
            interceptMarkRead(page, UNREAD_SESSION, answer, attempts, bodies)

            val row = page.locator("#session-list .session-row[data-id='$UNREAD_SESSION']")
            val pill = row.locator(".unread-pill")
            assertThat(pill).hasText("3")

            // TRIGGER ONE: the selection. `showSession` marks the row it was handed, because
            // `sessionsRef` need not list it yet (a session the operator just started is selected before
            // any frame carries it).
            row.click()
            page.waitForCondition { attempts.get() >= 1 }
            assertEquals(
                listOf("""{"seq":5}"""),
                bodies.distinct(),
                "the POST carries the seq the row DISPLAYS (lastSeq 5) — not its read cursor, and not a count",
            )

            // The daemon refused, so the badge stands. A client that zeroed its own pill would show a
            // cleared badge on this tab and the old count on the phone beside it.
            assertThat(pill).hasText("3")
            page.waitForTimeout(RETRY_WINDOW_MILLIS)
            assertEquals(
                1,
                attempts.get(),
                "a 404 is the daemon's own answer about this session and cannot change — the loop stopped",
            )

            // TRIGGER TWO: a `session_update` for the session on screen. `emit` moves only the state, so
            // the row still carries lastSeq 5 against a cursor of 2 and the frame's own numbers warrant
            // another mark — which is what re-arms a poster the definitive answer had put down.
            answer.set(READ_UNREACHABLE)
            harness.send("emit $UNREAD_SESSION ready")
            page.waitForCondition { attempts.get() >= 2 }
            val beforeWindow = attempts.get()
            page.waitForTimeout(RETRY_WINDOW_MILLIS)
            val retries = attempts.get() - beforeWindow
            assertTrue(
                retries >= 2,
                "an unreachable daemon is transient, so the loop keeps trying: expected at least 2 retries " +
                    "in ${RETRY_WINDOW_MILLIS.toLong()}ms, saw $retries",
            )
            assertThat(pill).hasText("3")
            assertEquals(
                listOf("""{"seq":5}"""),
                bodies.distinct(),
                "every retry re-sends the newest seq",
            )

            // COALESCED, and this is the only assertion that can say so. Every trigger here carries the
            // same seq, so a `distinct()` over the bodies answers one element whether the poster keeps one
            // request per session or fires one per trigger — what separates the two is the COUNT. A burst
            // of frames lands while a retry timer is already pending; `deliverRead`'s guard must turn each
            // of them into nothing at all, so the only request that may appear inside a window shorter
            // than one retry delay is that pending retry itself.
            val beforeBurst = attempts.get()
            repeat(TRIGGER_BURST) { i ->
                harness.send("emit $UNREAD_SESSION ${if (i % 2 == 0) "running" else "ready"}")
            }
            page.waitForTimeout(BURST_WINDOW_MILLIS)
            val burst = attempts.get() - beforeBurst
            assertTrue(
                burst <= 1,
                "$TRIGGER_BURST session_update frames inside ${BURST_WINDOW_MILLIS.toLong()}ms produced " +
                    "$burst requests — one per trigger rather than one in flight per session",
            )

            // And the badge clears only now, on the daemon's own broadcast: the retry that finally lands
            // writes the cursor, and the recomputed `unread` comes back as an ordinary session_update.
            answer.set(READ_ACCEPTED)
            assertThat(pill).hasCount(0)
            assertThat(row).hasCount(1)
        }
    }

    /**
     * Before the first snapshot the sidebar says it is LOADING, because an empty list and an unanswered
     * daemon are different facts and only one of them is knowable yet.
     *
     * The `empty` scenario is the sharp form of that claim: the list is genuinely empty in both states, so
     * the only difference between "Loading sessions…" and "No sessions yet. Start one to attach it here."
     * is whether the snapshot has arrived. Answering the second one early is not a cosmetic slip — it is
     * the daemon-is-down screen telling an operator that the work they left running does not exist.
     *
     * The fault is injected at the WebSocket CONSTRUCTOR, which is also the failure path with the longest
     * history: it used to give up for the life of the page, leaving a permanently empty UI, and it is now
     * one of the two places that reschedule on the same 2 s cadence (the other is `onclose`, which
     * [theOutageIsAnnouncedOncePerOutageAndTheRoutineLineOnlyOnce] drives). Healing the fault and watching
     * the app come back on its own is what proves the retry, where the deleted grep counted the two
     * `setTimeout(connect, 2000)` occurrences in the source.
     *
     * The last assertion is the negative pin this whole protocol was written for: the list has exactly ONE
     * source. There is no `GET /sessions` on load — a reload used to issue 206 of them — and the loading
     * state above is the same statement made visible, since a daemon answering HTTP perfectly well (the
     * footer's version came over it) still left the list unknown.
     */
    @Test
    fun theSidebarSaysItIsLoadingUntilTheFirstSnapshotDecidesTheListIsEmpty() {
        signedIn(
            EMPTY_SCENARIO,
            "sidebar-loading",
            initScripts = listOf(SOCKET_FAULT_SCRIPT, eventsFaultScript(EVENTS_THROWN), FETCH_RECORDER_SCRIPT),
        ) { _, _, page ->
            // The daemon is UP and this page is talking to it — the footer's version arrived over plain
            // HTTP — so what is missing is the list alone, which is the state under test.
            assertThat(page.locator("#current-version")).isVisible()
            assertThat(page.locator("#status-line")).containsText("events WS error")

            assertThat(page.locator("#sessions-loading")).hasText("Loading sessions…")
            assertThat(page.locator("#empty-sessions")).hasCount(0)
            assertThat(page.locator("#session-list .session-row")).hasCount(0)

            setEventsFault(page, EVENTS_HEALTHY)

            // The reschedule that used to not exist: nothing in this test reloads or reconnects by hand.
            assertThat(page.locator("#empty-sessions")).isVisible()
            assertThat(page.locator("#sessions-loading")).hasCount(0)
            assertThat(page.locator("#status-line")).hasText("0 session(s).")

            // The recorder is proven live against a request this page certainly makes before the absence
            // below is read: `__kotgentFetches` is installed by an init script, and if that ever stopped
            // wrapping `window.fetch` the count would be zero for the wrong reason.
            assertTrue(
                recordedFetches(page).any { it.contains("/api/v1/preferences") },
                "the fetch recorder saw nothing at all, so the zero below would be its own silence: " +
                    "${recordedFetches(page)}",
            )
            assertEquals(
                0,
                wholesaleSessionFetches(page),
                "the session list is never fetched wholesale over HTTP — the snapshot frame IS the list",
            )
        }
    }

    /**
     * Both status-line announcements are latched, and the latches are about a screen reader: the footer's
     * `#status-line` is an `aria-live` region, so every text it takes is read out loud to somebody.
     *
     * The routine "N session(s)." belongs to the first snapshot only. A reconnect delivers the same
     * snapshot, and repeating the line there would announce a number nobody asked for on top of whatever
     * the operator was doing.
     *
     * "Daemon connection lost — reconnecting…" belongs to the outage, not to the retry: `onclose` fires
     * every 2 s while the daemon is down. **The cost of an unlatched version is exactly the assertion this
     * test makes**, and it is worth spelling out why the obvious form would prove nothing: two identical
     * `say` calls produce no DOM mutation at all (preact writes a text node only when the text differs),
     * so a second announcement of the same string is not observable — what IS observable is that it
     * clobbers a DIFFERENT message. So the test interposes one: Copy tmux command is the operator action
     * that needs no daemon at all, its answer lands in this very region, and it must survive every later
     * reconnect attempt. The attempts themselves are the barrier — each one really opens a socket — so the
     * waiting window cannot be accidentally too short.
     *
     * The second outage is the other half: the announcement must be re-ARMED by a snapshot rather than
     * spent for the life of the page.
     */
    @Test
    fun theOutageIsAnnouncedOncePerOutageAndTheRoutineLineOnlyOnce() {
        signedIn(
            SESSIONS_SCENARIO,
            "sidebar-outage",
            initScripts = listOf(SOCKET_FAULT_SCRIPT),
        ) { harness, _, page ->
            val status = page.locator("#status-line")
            assertThat(status).hasText("4 session(s).")

            // A live session is selected purely so the interposed message below is available: without one
            // the palette draws Copy tmux command disabled ("no session is selected") and it cannot run.
            page.locator("#session-list .session-row[data-id='s-alpha']").click()

            // FIRST OUTAGE. The fault is set before the close, so the reconnect finds the broken address.
            setEventsFault(page, EVENTS_REJECTED)
            val socketsBefore = eventsSocketCount(page)
            closeNewestEventsSocket(page)
            assertThat(status).hasText(DISCONNECT_LINE)

            runLeaderCommand(page, "Copy tmux command")
            val operatorLine = awaitStatusOtherThan(page, DISCONNECT_LINE)

            page.waitForCondition { eventsSocketCount(page) >= socketsBefore + 3 }
            assertEquals(
                operatorLine,
                statusText(page),
                "three more failed reconnects announced nothing: the outage was announced once, not per retry",
            )

            // HEALED. The emit is the barrier that frames are really flowing again — it rides the same
            // socket AFTER the snapshot, so seeing it means the snapshot was applied, which is the moment
            // the routine line would have been repeated.
            setEventsFault(page, EVENTS_HEALTHY)
            harness.send("emit s-beta needs_approval")
            assertThat(page.locator("#attention-list .session-row[data-id='s-beta']")).hasCount(1)
            assertEquals(
                operatorLine,
                statusText(page),
                "the reconnect snapshot re-announced nothing: 'N session(s).' belongs to the first one only",
            )

            // SECOND OUTAGE: the snapshot re-armed the announcement, so this one is heard.
            setEventsFault(page, EVENTS_REJECTED)
            closeNewestEventsSocket(page)
            assertThat(status).hasText(DISCONNECT_LINE)
        }
    }
}

/** The `attention` row that carries an unread count. Its sibling `s-quiet` has none, and is untouched. */
private const val UNREAD_SESSION: String = "s-unread"

/** The `sessions` scenario's `resumable` shell: selecting it attaches no pty, so Done has none to kill. */
private const val DONE_SESSION: String = "s-delta"

/** What `onclose` says, once per outage. Spelled exactly as `app.js` says it, ellipsis included. */
private const val DISCONNECT_LINE: String = "Daemon connection lost — reconnecting…"

/**
 * How the intercepted mark-read POST is answered, and each name is a rule rather than a status code:
 * a `4xx` is the daemon's own answer ABOUT this session and stops the loop, a network failure carries no
 * status and keeps it, and a real answer is what clears the badge.
 */
private const val READ_DEFINITE: String = "definite"
private const val READ_UNREACHABLE: String = "unreachable"
private const val READ_ACCEPTED: String = "accepted"

/**
 * Three of the retry loop's own 2 s delays. Mirrored from `READ_RETRY_DELAY_MS` in `app.js` — a timing
 * dependency, not a grep: what the tests need from that constant is a window long enough for retries to
 * be due, and the transient phase proves the window really is long enough by filling it with them.
 */
private const val RETRY_WINDOW_MILLIS: Double = 6_000.0

/**
 * A burst of triggers, and a window shorter than ONE `READ_RETRY_DELAY_MS` to watch it in. Both halves
 * are load-bearing: the burst has to be big enough that "one request per trigger" is unmistakable, and the
 * window short enough that at most one scheduled retry can fall inside it.
 */
private const val TRIGGER_BURST: Int = 6
private const val BURST_WINDOW_MILLIS: Double = 900.0

/** Fault modes for [SOCKET_FAULT_SCRIPT]; the empty one is a healthy socket. */
private const val EVENTS_HEALTHY: String = ""
private const val EVENTS_THROWN: String = "throw"
private const val EVENTS_REJECTED: String = "reject"

/**
 * Record every WebSocket the page opens, and let a test break the EVENTS socket in either of the two ways
 * the app has to survive.
 *
 * Nothing in the app exposes its sockets, and the harness deliberately has no "drop the sockets but keep
 * the port" command, so the page is the only place an outage can be produced without taking the daemon
 * down — which is exactly what these tests need, since the daemon must stay reachable over HTTP while the
 * list is unknown.
 *
 * `throw` fails the CONSTRUCTOR, which is the path that used to give up for the life of the page.
 * `reject` sends the handshake to an address the daemon does not serve, so the socket really is opened
 * and really fails: the browser's own failure, delivered through `onclose`, on the app's own cadence. The
 * rewritten URL still contains `/events`, so it is still counted as an events socket by [eventsSocketCount].
 */
private val SOCKET_FAULT_SCRIPT: String = """
    (() => {
      const Native = window.WebSocket;
      window.__kotgentSockets = [];
      window.__kotgentEventsFault = "";
      class HarnessWebSocket extends Native {
        constructor(url, ...rest) {
          let target = String(url);
          if (target.indexOf("/events") >= 0) {
            const fault = window.__kotgentEventsFault;
            if (fault === "throw") throw new Error("the test refused to construct the events socket");
            if (fault === "reject") target = target.replace("/events", "/events-refused-by-the-test");
          }
          super(target, ...rest);
          window.__kotgentSockets.push(this);
        }
      }
      window.WebSocket = HarnessWebSocket;
    })()
""".trimIndent()

/** An init script that arms a fault before the app's very first connect attempt. */
private fun eventsFaultScript(fault: String): String = """window.__kotgentEventsFault = "$fault";"""

/**
 * Record every URL the page fetches, so a request made during the first paint can still be counted.
 *
 * A `page.onRequest` listener cannot answer this: the block runs after the SPA has loaded, and the request
 * this is looking for would be the very first one it made.
 */
private val FETCH_RECORDER_SCRIPT: String = """
    (() => {
      window.__kotgentFetches = [];
      const native = window.fetch;
      window.fetch = function (input) {
        try {
          window.__kotgentFetches.push(typeof input === "string" ? input : input.url);
        } catch (_) { /* an exotic input must not break the page under test */ }
        return native.apply(this, arguments);
      };
    })()
""".trimIndent()

/**
 * Sign a fresh context in, open the app, and hand the test its harness, context and page.
 *
 * A fresh [BrowserContext] per test is mandatory (a kotgent cookie is not scoped by port, so a reused one
 * is sent to the next harness and fails its HMAC), and [BrowserContext.traced] keeps a trace and a
 * screenshot only when the body fails.
 *
 * [initScripts] are installed on the context BEFORE any page exists, which is the only way to reach the
 * app's first connect and its first fetch; they run in the order given, so a fault script may override a
 * default set by an earlier one.
 */
private fun signedIn(
    scenario: String,
    trace: String,
    initScripts: List<String> = emptyList(),
    block: (Harness, BrowserContext, Page) -> Unit,
) {
    Harness(scenario).use { harness ->
        onChromium { browser ->
            browser.newContext().use { context ->
                initScripts.forEach(context::addInitScript)
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

/**
 * Configure grouping the way an operator with no base path has to: through the command palette.
 *
 * With `basePath` empty the sidebar draws no base-path note, so the palette really is the only route to
 * Preferences at that moment — which is why this helper opens it from there and the depth test opens it
 * from the note instead.
 */
private fun configureGrouping(page: Page, basePath: String, level: Int) {
    runLeaderCommand(page, "Preferences")
    assertThat(page.locator("#prefs-dialog")).isVisible()
    savePreferences(page, basePath, level)
}

/**
 * Run one command from the palette's leader grid, by its title.
 *
 * The header's ⋯ rather than ⌘K: the button is the palette's guaranteed path on every surface (the chord
 * and its mnemonics are the command-palette tests' own subject), and it opens the same leader grid the
 * chord does.
 */
private fun runLeaderCommand(page: Page, title: String) {
    page.locator("#palette-button").click()
    assertThat(page.locator("#command-palette")).isVisible()
    page.locator(".command-palette-leader-command")
        .filter(Locator.FilterOptions().setHasText(title))
        .click()
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
 * Intercept the ONE route the unread badge depends on, counting every attempt and keeping its body.
 *
 * The suffix match is exact, so `GET /sessions/{id}` (the reattach liveness read) and every other route
 * pass through untouched; a non-POST on this very path is resumed rather than counted.
 */
private fun interceptMarkRead(
    page: Page,
    id: String,
    answer: AtomicReference<String>,
    attempts: AtomicInteger,
    bodies: MutableList<String>,
) {
    val suffix = "/sessions/$id/read"
    page.route({ url: String -> url.endsWith(suffix) }) { intercepted ->
        if (!intercepted.request().method().equals("POST", ignoreCase = true)) {
            intercepted.resume()
        } else {
            // The body is banked BEFORE the counter is bumped: a waiter on `attempts` would otherwise be
            // free to run between the two and read a list that is one entry short of the count it saw.
            bodies.add(intercepted.request().postData().orEmpty())
            attempts.incrementAndGet()
            when (answer.get()) {
                READ_DEFINITE -> intercepted.fulfill(
                    Route.FulfillOptions()
                        .setStatus(404)
                        .setContentType("text/plain")
                        .setBody("no such session $id"),
                )
                // No status at all — the daemon was not reached, which is the transient case.
                READ_UNREACHABLE -> intercepted.abort()
                else -> intercepted.resume()
            }
        }
    }
}

private fun setEventsFault(page: Page, fault: String) {
    page.evaluate("(fault) => { window.__kotgentEventsFault = fault; }", fault)
}

/** How many events sockets this page has ever built, failed ones included — the outage's own barrier. */
private fun eventsSocketCount(page: Page): Int {
    val count = page.evaluate(
        """() => window.__kotgentSockets.filter((s) => s.url.indexOf("/events") >= 0).length""",
    )
    return (count as Number).toInt()
}

private fun closeNewestEventsSocket(page: Page) {
    page.evaluate(
        """
        () => {
          const list = window.__kotgentSockets.filter((s) => s.url.indexOf("/events") >= 0);
          const socket = list[list.length - 1];
          if (!socket) throw new Error("the page has opened no events socket");
          socket.close();
        }
        """.trimIndent(),
    )
}

/** Every URL the page has fetched since the recorder was installed. */
private fun recordedFetches(page: Page): List<String> {
    val raw = page.evaluate("() => window.__kotgentFetches || []") as List<*>
    return raw.map { it.toString() }
}

/** How many times the page fetched the WHOLE session list. The answer must always be zero. */
private fun wholesaleSessionFetches(page: Page): Int {
    val count = page.evaluate(
        """
        () => window.__kotgentFetches
          .filter((u) => new URL(u, location.origin).pathname === "/api/v1/sessions").length
        """.trimIndent(),
    )
    return (count as Number).toInt()
}

/** The sidebar footer's aria-live region, as text. */
private fun statusText(page: Page): String = page.locator("#status-line").textContent().trim()

/** Wait until the status line says something of its own, and answer with whatever that turned out to be. */
private fun awaitStatusOtherThan(page: Page, previous: String): String {
    page.waitForCondition { statusText(page).let { it.isNotEmpty() && it != previous } }
    return statusText(page)
}

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
