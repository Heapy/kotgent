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

class SidebarTest {

    @Test
    fun theSidebarIsOneFlatListOfEverySessionUntilABasePathIsConfigured() {
        signedIn(SESSIONS_SCENARIO, "sidebar-flat") { _, _, page ->
            assertThat(page.locator("#session-list .session-row")).hasCount(4)

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

            assertThat(folderHead(page, "/a").locator(".group-count")).hasText("3")

            assertThat(folderHead(page, "/a/b").locator(".group-new"))
                .hasAttribute("aria-label", "New session in /a/b")
            assertThat(folderHead(page, "/d").locator(".group-new"))
                .hasAttribute("aria-label", "New session in /d")

            assertThat(page.locator("#base-path-note")).hasText("/")

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

            assertThat(folderHead(page, "/a").locator(".attn-dot")).hasCount(0)

            folderHead(page, "/a").locator(".group-toggle").click()

            assertThat(folderHead(page, "/a").locator(".group-toggle"))
                .hasAttribute("aria-expanded", "false")
            assertSidebarTree(
                page,
                """
                a [/a] (3)
                d [/d] (1)
                  s-delta
                """.trimIndent(),
                "a collapsed folder keeps its aggregate count and drops only its own contents",
            )
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

            page.locator("#base-path-note").click()
            assertThat(page.locator("#prefs-dialog")).isVisible()
            assertThat(page.locator("#prefs-base-path")).hasValue("/")
            savePreferences(page, basePath = null, level = 1)

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

            page.locator("#session-list .session-row[data-id='s-delta']").click()
            assertThat(page).hasURL("${harness.baseUrl}/s/s-delta")
            assertThat(page.locator(".session-row[data-id='s-delta']").first())
                .hasAttribute("aria-current", "true")
            assertThat(page.locator("#session-list .session-row[data-id='s-alpha']"))
                .not().hasAttribute("aria-current", "true")

            page.goBack()
            assertThat(page).hasURL("${harness.baseUrl}/s/s-alpha")
            assertThat(page.locator(".session-row[data-id='s-alpha']").first())
                .hasAttribute("aria-current", "true")

            page.goBack()
            assertThat(page).hasURL("${harness.baseUrl}/")
            assertThat(page.locator(".session-row[data-id='s-alpha']").first())
                .hasAttribute("aria-current", "true")
        }
    }

    @Test
    fun anEmptyFirstRunOffersItsOwnStartASessionAction() {
        signedIn(EMPTY_SCENARIO, "sidebar-empty") { _, _, page ->
            assertThat(page.locator("#empty-sessions")).isVisible()
            assertThat(page.locator("#sessions-loading")).hasCount(0)
            assertThat(page.locator("#session-list .session-row")).hasCount(0)
            assertThat(page.locator("#attention-section")).hasCount(0)

            assertThat(page.locator("#empty-new-session-button")).hasText("Start a session")
            page.locator("#empty-new-session-button").click()
            assertThat(page.locator("#new-session-dialog")).isVisible()
        }
    }

    @Test
    fun aDoneSessionMovesIntoItsOwnHiddenListAndRestoreBringsItBack() {
        signedIn(SESSIONS_SCENARIO, "sidebar-done") { _, _, page ->
            assertThat(page.locator("#session-list .session-row")).hasCount(4)
            assertThat(page.locator("#done-section")).hasCount(0)
            assertThat(page.locator("#show-done-toggle")).hasCount(0)

            page.onDialog { dialog -> dialog.accept() }
            markDone(page, DONE_SESSION)

            assertThat(page.locator("#session-list .session-row")).hasCount(3)
            assertThat(page.locator("#session-list .session-row[data-id='$DONE_SESSION']")).hasCount(0)
            val toggle = page.locator("#show-done-toggle")
            assertThat(toggle).hasAttribute("aria-pressed", "false")
            // The label names what the button is about; aria-pressed alone carries the state.
            assertThat(toggle).hasAttribute("title", "Done sessions (1)")
            assertThat(page.locator("#done-list")).hasCount(0)

            toggle.click()
            assertThat(toggle).hasAttribute("aria-pressed", "true")
            assertThat(page.locator("#done-count")).hasText("1")
            val doneRow = page.locator("#done-list .session-row[data-id='$DONE_SESSION']")
            assertThat(doneRow).hasCount(1)
            val restore = doneRow.locator(".session-restore")
            assertThat(restore).hasCount(1)
            assertThat(doneRow.locator(".badge")).hasCount(0)

            restore.click()

            assertThat(page.locator("#session-list .session-row[data-id='$DONE_SESSION']")).hasCount(1)
            assertThat(page.locator("#session-list .session-row")).hasCount(4)
            assertThat(page.locator("#done-section")).hasCount(0)
            assertThat(page.locator("#show-done-toggle")).hasCount(0)
        }
    }

    @Test
    fun theDoneListIsItsOwnFoldedTreeOrderedByWhatWasFinishedLast() {
        signedIn(SESSIONS_SCENARIO, "sidebar-done-tree") { _, _, page ->
            configureGrouping(page, basePath = "/", level = 2)
            awaitFoldedTree(page, deepestFolder = "/a/b")

            page.onDialog { dialog -> dialog.accept() }
            markDone(page, "s-beta")
            markDone(page, "s-alpha")
            markDone(page, "s-delta")

            page.locator("#show-done-toggle").click()
            assertThat(page.locator("#done-list.grouped")).hasCount(1)
            assertSidebarTree(
                page,
                """
                d [/d] (1)
                  s-delta
                a [/a] (2)
                  b [/a/b] (2)
                    s-alpha
                    s-beta
                """.trimIndent(),
                "the archive keeps the directory tree but reads newest-first, folder and row alike",
                listSelector = "#done-list",
            )
            assertThat(page.locator("#done-list .group-new")).hasCount(0)

            doneFolderHead(page, "/a").locator(".group-toggle").click()

            assertThat(doneFolderHead(page, "/a").locator(".group-toggle"))
                .hasAttribute("aria-expanded", "false")
            assertSidebarTree(
                page,
                """
                d [/d] (1)
                  s-delta
                a [/a] (2)
                """.trimIndent(),
                "collapsing an archived folder hides its own contents",
                listSelector = "#done-list",
            )
            assertSidebarTree(
                page,
                """
                a [/a] (1)
                  c [/a/c] (1)
                    s-gamma
                """.trimIndent(),
                "the live tree has its own collapse state: the same path stays open there",
            )

            page.locator("#done-list .session-row[data-id='s-delta'] .session-restore").click()

            assertThat(page.locator("#session-list .session-row[data-id='s-delta']")).hasCount(1)
            assertThat(page.locator("#done-count")).hasText("2")
            assertThat(page.locator("#done-list .group-title[title='/d']")).hasCount(0)
        }
    }

    @Test
    fun anArchiveSeenOnlyAsAPatchStillOrdersByWhenItWasFinished() {
        signedIn(SESSIONS_SCENARIO, "sidebar-done-patch") { harness, _, page ->
            // No action of this page's own: the archive arrives the way a second tab would learn it.
            harness.send("done s-delta ${SEED_EPOCH_MILLIS + 5_000}")
            harness.send("done s-alpha ${SEED_EPOCH_MILLIS + 9_000}")

            page.locator("#show-done-toggle").click()
            assertThat(page.locator("#done-count")).hasText("2")
            assertSidebarTree(
                page,
                """
                s-alpha
                s-delta
                """.trimIndent(),
                "the stamp rode the patch: by the snapshot's own stamps delta would still be on top",
                listSelector = "#done-list",
            )
        }
    }

    @Test
    fun aFinishedFolderAndAFinishedSessionBesideItCompeteOnRecencyAlone() {
        signedIn(SESSIONS_MIXED_SCENARIO, "sidebar-done-mixed") { harness, _, page ->
            configureGrouping(page, basePath = "/", level = 2)
            assertThat(page.locator("#session-list.grouped")).hasCount(1)

            harness.send("done m-side ${SEED_EPOCH_MILLIS + 1_000}")
            harness.send("done m-root ${SEED_EPOCH_MILLIS + 2_000}")
            harness.send("done m-deep ${SEED_EPOCH_MILLIS + 3_000}")

            page.locator("#show-done-toggle").click()
            assertThat(page.locator("#done-count")).hasText("3")
            assertSidebarTree(
                page,
                """
                a [/a] (3)
                  b [/a/b] (1)
                    m-deep
                  m-root
                  c [/a/c] (1)
                    m-side
                """.trimIndent(),
                "a session held directly by a folder sorts among that folder's subfolders, not above them",
                listSelector = "#done-list",
            )
        }
    }

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

            row.click()
            page.waitForCondition { attempts.get() >= 1 }
            assertEquals(
                listOf("""{"seq":5}"""),
                bodies.distinct(),
                "the POST carries the seq the row DISPLAYS (lastSeq 5) — not its read cursor, and not a count",
            )

            assertThat(pill).hasText("3")
            page.waitForTimeout(RETRY_WINDOW_MILLIS)
            assertEquals(
                1,
                attempts.get(),
                "a 404 is the daemon's own answer about this session and cannot change — the loop stopped",
            )

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

            answer.set(READ_ACCEPTED)
            assertThat(pill).hasCount(0)
            assertThat(row).hasCount(1)
        }
    }

    @Test
    fun theBoardOnScreenStopsAFrameFromClearingTheHiddenRowsBadge() {
        signedIn(
            SESSIONS_SCENARIO,
            "sidebar-read-on-board",
            initScripts = listOf(FRAME_RECORDER),
        ) { harness, _, page ->
            val attempts = AtomicInteger()
            val bodies = CopyOnWriteArrayList<String>()
            val answer = AtomicReference(READ_ACCEPTED)
            interceptMarkRead(page, BOARD_GUARD_SESSION, answer, attempts, bodies)

            val row = page.locator("#session-list .session-row[data-id='$BOARD_GUARD_SESSION']")
            val pill = row.locator(".unread-pill")
            harness.send("append $BOARD_GUARD_SESSION")
            assertThat(pill).hasText("1")

            row.click()
            assertThat(pill).hasCount(0)
            assertEquals(1, attempts.get(), "the selection posted exactly one mark-read")

            page.locator(".nav-switch a[href='/tasks']").click()
            assertThat(page.locator(".board-columns")).isVisible()
            assertThat(page.locator("#session-list")).hasCount(0)

            harness.send("append $BOARD_GUARD_SESSION")
            harness.send("append $BOARD_GUARD_SESSION")
            page.waitForFunction(sawUnreadFrame(3))
            assertEquals(
                1,
                attempts.get(),
                "a frame for the selected session must NOT advance its cursor while the board is the " +
                    "screen this tab is showing — the badge belongs to output nobody has looked at",
            )

            page.locator(".nav-switch a[href='/s/$BOARD_GUARD_SESSION']").click()
            page.waitForCondition { attempts.get() >= 2 }
            assertThat(pill).hasCount(0)
            assertEquals(
                listOf("""{"seq":1}""", """{"seq":3}"""),
                bodies.toList(),
                "the second mark carries the seq the two appended events produced",
            )
        }
    }

    @Test
    fun theSidebarSaysItIsLoadingUntilTheFirstSnapshotDecidesTheListIsEmpty() {
        signedIn(
            EMPTY_SCENARIO,
            "sidebar-loading",
            initScripts = listOf(SOCKET_FAULT_SCRIPT, eventsFaultScript(EVENTS_THROWN), FETCH_RECORDER_SCRIPT),
        ) { _, _, page ->
            assertThat(page.locator("#current-version")).isVisible()
            assertThat(page.locator("#status-line")).containsText("events WS error")

            assertThat(page.locator("#sessions-loading")).hasText("Loading sessions…")
            assertThat(page.locator("#empty-sessions")).hasCount(0)
            assertThat(page.locator("#session-list .session-row")).hasCount(0)

            setEventsFault(page, EVENTS_HEALTHY)

            assertThat(page.locator("#empty-sessions")).isVisible()
            assertThat(page.locator("#sessions-loading")).hasCount(0)
            assertThat(page.locator("#status-line")).hasText("0 session(s).")

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

    @Test
    fun theOutageIsAnnouncedOncePerOutageAndTheRoutineLineOnlyOnce() {
        signedIn(
            SESSIONS_SCENARIO,
            "sidebar-outage",
            initScripts = listOf(SOCKET_FAULT_SCRIPT),
        ) { harness, _, page ->
            val status = page.locator("#status-line")
            assertThat(status).hasText("4 session(s).")

            page.locator("#session-list .session-row[data-id='s-alpha']").click()

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

            setEventsFault(page, EVENTS_HEALTHY)
            harness.send("emit s-beta needs_approval")
            assertThat(page.locator("#attention-list .session-row[data-id='s-beta']")).hasCount(1)
            assertEquals(
                operatorLine,
                statusText(page),
                "the reconnect snapshot re-announced nothing: 'N session(s).' belongs to the first one only",
            )

            setEventsFault(page, EVENTS_REJECTED)
            closeNewestEventsSocket(page)
            assertThat(status).hasText(DISCONNECT_LINE)
        }
    }

    @Test
    fun theCopiedTmuxCommandJoinsTheDaemonsOwnServerInUtf8() {
        signedIn(
            SESSIONS_SCENARIO,
            "sidebar-copy-tmux",
            initScripts = listOf(CLIPBOARD_RECORDER),
        ) { _, _, page ->
            page.locator("#session-list .session-row[data-id='s-alpha']").click()
            runLeaderCommand(page, "Copy tmux command")
            assertThat(page.locator("#status-line")).hasText("Tmux command copied to clipboard.")
            assertEquals(
                listOf("tmux -u -L kotgent attach -t kt-s-alpha"),
                page.evaluate("() => window.__kotgentClipboard") as List<*>,
                "the copied command joins kotgent's own tmux socket, in UTF-8, at this session's pane",
            )
        }
    }

    @Test
    fun aClearedModelDisappearsFromTheRowThePatchNames() {
        signedIn(SESSIONS_SCENARIO, "sidebar-model-clear") { harness, _, page ->
            val subline = page.locator("#session-list .session-row[data-id='s-alpha'] .session-sub")
            assertThat(subline).hasText("claude · claude-sonnet-4-5 · 2.1.218")

            harness.send("model s-alpha gpt-5-codex")
            assertThat(subline).hasText("claude · gpt-5-codex · 2.1.218")

            harness.send("model s-alpha -")
            assertThat(subline).hasText("claude · 2.1.218")
        }
    }
}

private val CLIPBOARD_RECORDER: String = """
    (() => {
      window.__kotgentClipboard = [];
      const clipboard = navigator.clipboard;
      if (clipboard) {
        clipboard.writeText = (text) => {
          window.__kotgentClipboard.push(String(text));
          return Promise.resolve();
        };
      }
    })();
""".trimIndent()

private const val UNREAD_SESSION: String = "s-unread"

private const val BOARD_GUARD_SESSION: String = "s-alpha"

private fun sawUnreadFrame(seq: Int): String = """
    () => (window.__kotgentFrames || []).some((f) =>
      f.indexOf("\"type\":\"session_update\"") >= 0 &&
      f.indexOf("\"sessionId\":\"$BOARD_GUARD_SESSION\"") >= 0 &&
      f.indexOf("\"lastSeq\":$seq") >= 0)
""".trimIndent()

private const val DONE_SESSION: String = "s-delta"

// Mirrors SEED_EPOCH_MS in the harness scenarios: archive stamps must outrank the seeded ones.
private const val SEED_EPOCH_MILLIS: Long = 1_700_000_000_000L

private const val DISCONNECT_LINE: String = "Daemon connection lost — reconnecting…"

private const val READ_DEFINITE: String = "definite"
private const val READ_UNREACHABLE: String = "unreachable"
private const val READ_ACCEPTED: String = "accepted"

private const val RETRY_WINDOW_MILLIS: Double = 6_000.0

private const val TRIGGER_BURST: Int = 6
private const val BURST_WINDOW_MILLIS: Double = 900.0

private const val EVENTS_HEALTHY: String = ""
private const val EVENTS_THROWN: String = "throw"
private const val EVENTS_REJECTED: String = "reject"

// A page-side socket subclass faults events while leaving daemon HTTP reachable.
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

private fun eventsFaultScript(fault: String): String = """window.__kotgentEventsFault = "$fault";"""

// Must wrap fetch before the app's first paint; a later request listener can miss that call.
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

private fun signedIn(
    scenario: String,
    trace: String,
    initScripts: List<String> = emptyList(),
    block: (Harness, BrowserContext, Page) -> Unit,
) {
    Harness(scenario).use { harness ->
        onChromium { browser ->
            browser.newContext().use { context ->
                // Install before any page exists so first-connect and first-fetch paths are observable.
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

private fun configureGrouping(page: Page, basePath: String, level: Int) {
    runLeaderCommand(page, "Preferences")
    assertThat(page.locator("#prefs-dialog")).isVisible()
    savePreferences(page, basePath, level)
}

private fun runLeaderCommand(page: Page, title: String) {
    page.locator("#palette-button").click()
    assertThat(page.locator("#command-palette")).isVisible()
    page.locator(".command-palette-leader-command")
        .filter(Locator.FilterOptions().setHasText(title))
        .click()
}

private fun savePreferences(page: Page, basePath: String?, level: Int) {
    if (basePath != null) page.locator("#prefs-base-path").fill(basePath)
    page.locator("#prefs-grouping-level").selectOption(level.toString())
    page.locator("#prefs-submit").click()
    assertThat(page.locator("#prefs-dialog")).hasCount(0)
}

private fun awaitFoldedTree(page: Page, deepestFolder: String) {
    assertThat(page.locator("#session-list.grouped")).hasCount(1)
    assertThat(page.locator("#session-list .group-title[title='$deepestFolder']")).hasCount(1)
    assertThat(page.locator("#session-list .session-row")).hasCount(4)
}

private fun folderHead(page: Page, path: String): Locator =
    page.locator("#session-list .group-head:has(.group-title[title='$path'])")

private fun doneFolderHead(page: Page, path: String): Locator =
    page.locator("#done-list .group-head:has(.group-title[title='$path'])")

/** Requires a dialog handler already armed on the page: the command confirms before it archives. */
private fun markDone(page: Page, id: String) {
    page.locator("#session-list .session-row[data-id='$id']").click()
    runLeaderCommand(page, "Done current session")
    assertThat(page.locator("#session-list .session-row[data-id='$id']")).hasCount(0)
}

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
            // Bank the body before waking a waiter that observes the incremented count.
            bodies.add(intercepted.request().postData().orEmpty())
            attempts.incrementAndGet()
            when (answer.get()) {
                READ_DEFINITE -> intercepted.fulfill(
                    Route.FulfillOptions()
                        .setStatus(404)
                        .setContentType("text/plain")
                        .setBody("no such session $id"),
                )
                READ_UNREACHABLE -> intercepted.abort()
                else -> intercepted.resume()
            }
        }
    }
}

private fun setEventsFault(page: Page, fault: String) {
    page.evaluate("(fault) => { window.__kotgentEventsFault = fault; }", fault)
}

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

private fun recordedFetches(page: Page): List<String> {
    val raw = page.evaluate("() => window.__kotgentFetches || []") as List<*>
    return raw.map { it.toString() }
}

private fun wholesaleSessionFetches(page: Page): Int {
    val count = page.evaluate(
        """
        () => window.__kotgentFetches
          .filter((u) => new URL(u, location.origin).pathname === "/api/v1/sessions").length
        """.trimIndent(),
    )
    return (count as Number).toInt()
}

private fun statusText(page: Page): String = page.locator("#status-line").textContent().trim()

private fun awaitStatusOtherThan(page: Page, previous: String): String {
    page.waitForCondition { statusText(page).let { it.isNotEmpty() && it != previous } }
    return statusText(page)
}

private fun sidebarTree(page: Page, listSelector: String = "#session-list"): String {
    val tree = page.evaluate(SIDEBAR_TREE_SCRIPT, listSelector)
    return tree as? String ?: fail("the sidebar tree script answered ${tree ?: "null"}")
}

private fun assertSidebarTree(
    page: Page,
    expected: String,
    message: String,
    listSelector: String = "#session-list",
) {
    assertEquals(expected, sidebarTree(page, listSelector), message)
}

private val SIDEBAR_TREE_SCRIPT: String = """
    (selector) => {
      const list = document.querySelector(selector);
      if (!list) return "(no " + selector + ")";
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

private val VERSION_FIELD = Regex("\"version\"\\s*:\\s*\"([^\"]*)\"")

private fun versionReportedByTheDaemon(context: BrowserContext, baseUrl: String): String {
    val response = context.request().get("$baseUrl/api/v1/version")
    val body = response.text()
    assertEquals(200, response.status(), "GET /api/v1/version answered ${response.status()}: $body")
    val version = VERSION_FIELD.find(body)?.groupValues?.get(1)
        ?: fail("no \"version\" field in the daemon's answer: $body")
    assertTrue(version.isNotBlank(), "the daemon reported a blank version: $body")
    return version
}
