package io.kotgent.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskBadgeTest {


    @Test
    fun aLinkedSessionRendersItsTaskTitleAndAnUnlinkedOneRendersNothing() =
        browse(TASK_LINKED_SESSION_SCENARIO, "badge-linked-title") { _, page ->
            val badge = page.locator("$SESSION_LIST li[data-id='s-linked-1'] a.task-badge")
            assertThat(badge).hasCount(1)
            assertThat(badge).hasAttribute("href", "/tasks/local%3A1")
            assertThat(badge).hasClass("task-badge")
            val title = page.taskTitle("local:1")
            assertTrue(
                title.isNotBlank() && title != "local:1",
                "the fixture's local:1 must carry a real title for this assertion to mean anything, got '$title'",
            )
            assertThat(badge).hasText(title)
            assertThat(badge.locator(".task-session-dot")).hasCount(1)

            assertThat(page.locator("$SESSION_LIST li[data-id='s-linked-2']")).hasCount(1)
            assertThat(page.locator("$SESSION_LIST li[data-id='s-linked-2'] a.task-badge")).hasCount(0)
        }

    @Test
    fun aDanglingRefRendersTheUnknownBadgeRatherThanBreakingTheRow() =
        browse(TASK_LINKED_SESSION_SCENARIO, "badge-dangling-ref") { _, page ->
            val row = page.locator("$SESSION_LIST li[data-id='s-linked-3']")
            val badge = row.locator("a.task-badge")
            assertThat(badge).hasCount(1)
            assertThat(badge).hasClass("task-badge task-badge-unknown")
            assertThat(badge).hasText("local:404")
            assertThat(badge).hasAttribute("href", "/tasks/local%3A404")
            assertThat(badge).hasAttribute(
                "title",
                "local:404 — no such task (it may have just been deleted)",
            )
            assertThat(row.locator(".session-name")).hasCount(1)
            assertThat(row.locator(".badge")).hasCount(1)
        }

    @Test
    fun aPlainClickOnTheBadgeOpensTheTaskRouteInsteadOfSelectingTheRow() =
        browse(TASK_LINKED_SESSION_SCENARIO, "badge-plain-click") { _, page ->
            val badge = page.locator("$SESSION_LIST li[data-id='s-linked-1'] a.task-badge")
            assertThat(badge).hasCount(1)
            badge.click()

            assertThat(page.locator("#projects-section")).isVisible()
            assertThat(page.locator(SESSION_LIST)).hasCount(0)
            val url = page.url()
            assertTrue(url.contains("/tasks/"), "the badge routed to the task screen; the address bar says $url")
            assertTrue(
                !url.contains("/s/"),
                "the click stopped at the badge: the row underneath must not also select ($url)",
            )
            assertTrue(page.sameDocument(), "a plain click goes through the router, never a document load")
        }


    @Test
    fun linkingASessionThroughTheApiBringsItsBadgeInWithoutAReload() =
        browse(TASK_LINKED_SESSION_SCENARIO, "badge-link-arrives") { _, page ->
            val badge = page.locator("$SESSION_LIST li[data-id='s-linked-2'] a.task-badge")
            assertThat(page.locator("$SESSION_LIST li[data-id='s-linked-2']")).hasCount(1)
            assertThat(badge).hasCount(0)

            val status = page.postStatus("/api/v1/tasks/local%3A2/link", "{\"sessionId\":\"s-linked-2\"}")
            assertEquals(200, status, "POST /api/v1/tasks/local:2/link should link s-linked-2 to local:2")

            awaitFrame(page, SESSION_UPDATE, "\"sessionId\":\"s-linked-2\"", "\"taskRef\":\"local:2\"")
            assertThat(badge).hasCount(1)
            assertThat(badge).hasAttribute("href", "/tasks/local%3A2")
            assertThat(badge).hasClass("task-badge")
            assertTrue(page.sameDocument(), "the badge arrived on the patch, not on a reload")
        }

    @Test
    fun aTaskClosedToDoneUnlinksItsHolderAndTheBadgeLeavesLive() =
        browse(TASK_LINKED_SESSION_SCENARIO, "badge-unlink-on-done") { harness, page ->
            val badge = page.locator("$SESSION_LIST li[data-id='s-linked-1'] a.task-badge")
            assertThat(badge).hasCount(1)

            harness.send("task local:1 done")
            awaitFrame(page, TASK_UPDATE, "\"ref\":\"local:1\"", "\"state\":\"done\"")
            awaitFrame(page, SESSION_UPDATE, "\"sessionId\":\"s-linked-1\"", "\"taskRef\":null")

            assertThat(badge).hasCount(0)
            assertThat(page.locator("$SESSION_LIST li[data-id='s-linked-1']")).hasCount(1)
            assertTrue(page.sameDocument(), "the badge left on the patch, not on a reload")
        }

    @Test
    fun anEmitMovesTheSessionStateOnTheWireAndTheSidebarFollows() =
        browse(ATTENTION_SCENARIO, "badge-state-emit") { harness, page ->
            val row = page.locator("$SESSION_LIST li[data-id='s-quiet']")
            assertThat(row).hasCount(1)
            assertThat(page.locator("#attention-num")).hasText("0")
            assertThat(row.locator(".badge")).hasText("ready")

            harness.send("emit s-quiet needs_approval")
            awaitFrame(page, SESSION_UPDATE, "\"sessionId\":\"s-quiet\"", "\"state\":\"needs_approval\"")

            assertThat(page.locator("#attention-num")).hasText("1")
            assertThat(page.locator("#attention-list li[data-id='s-quiet']")).hasCount(1)
            assertThat(row.locator(".badge")).hasText("needs approval")
            assertThat(row.locator(".attn-dot")).hasCount(1)
            assertTrue(page.sameDocument(), "a state change is a patch; the shell is never reloaded for one")
        }

    @Test
    fun theNotifyEdgeRingsOnceForOneFalseToTrueTransition() =
        browse(ATTENTION_SCENARIO, "badge-notify-edge", extraInit = NOTIFICATION_RECORDER) { harness, page ->
            val row = page.locator("$SESSION_LIST li[data-id='s-quiet']")
            assertThat(row).hasCount(1)
            assertThat(page.locator("#attention-num")).hasText("0")
            assertEquals(
                emptyList<String>(),
                page.notificationTags(),
                "a session that was already in the list, quiet, rings for nothing",
            )

            harness.send("emit s-quiet needs_approval")
            awaitFrame(page, SESSION_UPDATE, "\"sessionId\":\"s-quiet\"", "\"state\":\"needs_approval\"")
            assertThat(page.locator("#attention-num")).hasText("1")
            assertEquals(
                listOf("kotgent-attn-s-quiet"),
                page.notificationTags(),
                "the false → true transition rang exactly once, keyed by session id",
            )

            harness.send("emit s-quiet needs_answer")
            awaitFrame(page, SESSION_UPDATE, "\"sessionId\":\"s-quiet\"", "\"state\":\"needs_answer\"")
            assertThat(row.locator(".badge")).hasText("needs answer")
            assertThat(page.locator("#attention-num")).hasText("1")
            assertEquals(
                listOf("kotgent-attn-s-quiet"),
                page.notificationTags(),
                "still one: the row never LEFT attention, so there was no new edge to ring for",
            )
        }


    private fun browse(
        scenario: String,
        trace: String,
        extraInit: String? = null,
        block: (Harness, Page) -> Unit,
    ) {
        onChromium { browser ->
            Harness(scenario).use { harness ->
                browser.touchContext(width = 1280, height = 900, deviceScaleFactor = 1.0, mobile = false)
                    .use { context ->
                        context.loginWithTicket(harness.ticket, harness.baseUrl)
                        context.addInitScript(FRAME_RECORDER)
                        if (extraInit != null) context.addInitScript(extraInit)
                        context.traced(trace) {
                            val page = context.newPage()
                            page.navigate(harness.baseUrl + "/")
                            page.evaluate("() => { window.__kotgentSameDocument = true; }")
                            block(harness, page)
                        }
                    }
            }
        }
    }

    private fun awaitFrame(page: Page, vararg needles: String) {
        try {
            page.waitForFunction(
                "needles => (window.__kotgentFrames || []).some((f) => needles.every((n) => f.includes(n)))",
                needles.toList(),
            )
        } catch (failure: TimeoutError) {
            val banked = runCatching { page.frameCount() }.getOrNull()
            throw AssertionError(
                "no /events frame carried all of ${needles.toList()}; " +
                    "the socket delivered ${banked ?: "an unreadable number of"} text frame(s)",
                failure,
            )
        }
    }

    private fun Page.frameCount(): Int =
        (evaluate("() => (window.__kotgentFrames || []).length") as Number).toInt()

    private fun Page.sameDocument(): Boolean =
        evaluate("() => window.__kotgentSameDocument === true") as Boolean

    private fun Page.notificationTags(): List<String> {
        val raw = evaluate("() => (window.__kotgentNotifications || []).map((n) => n.tag)") as List<*>
        return raw.map { it?.toString().orEmpty() }
    }

    private fun Page.postStatus(path: String, body: String): Int {
        val status = evaluate(
            """
            async ([p, b]) => {
              const res = await fetch(p, {
                method: "POST",
                credentials: "same-origin",
                headers: { "Content-Type": "application/json" },
                body: b,
              });
              return res.status;
            }
            """.trimIndent(),
            listOf(path, body),
        )
        return (status as Number).toInt()
    }

    private fun Page.taskTitle(ref: String): String = evaluate(
        """
        async (r) => {
          const res = await fetch("/api/v1/tasks/" + encodeURIComponent(r), { credentials: "same-origin" });
          if (!res.ok) throw new Error("GET /api/v1/tasks/" + r + " answered " + res.status);
          return (await res.json()).task.title;
        }
        """.trimIndent(),
        ref,
    ) as String

    private companion object {
        const val SESSION_LIST = "#session-list"

        const val SESSION_UPDATE = "\"type\":\"session_update\""
        const val TASK_UPDATE = "\"type\":\"task_update\""

        val NOTIFICATION_RECORDER = """
            (() => {
              const seen = [];
              window.__kotgentNotifications = seen;
              const Recording = function (title, options) {
                seen.push({
                  title: title,
                  tag: (options && options.tag) || "",
                  body: (options && options.body) || "",
                });
              };
              Recording.permission = "granted";
              Recording.requestPermission = () => Promise.resolve("granted");
              window.Notification = Recording;
              try { window.localStorage.setItem("kotgent.notifications.v1", "1"); } catch (e) { }
            })();
        """.trimIndent()
    }
}
