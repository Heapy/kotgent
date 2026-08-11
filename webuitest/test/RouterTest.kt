package io.kotgent.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.WaitUntilState
import java.util.regex.Pattern
import kotlin.test.Test

class RouterTest {

    @Test
    fun aSessionPathOpensThatSessionOnFirstLoad() = routerTest("session-path") { base, page ->
        page.navigate("$base/s/$DEEP_SESSION")
        assertSessionView(page)
        assertThat(page.locator(TERMINAL_TASK)).hasAttribute("href", TASK_HREF)
        assertThat(page).hasURL("$base/s/$DEEP_SESSION")

        page.navigate("$base/s/no-such-session")
        assertSessionView(page)
        assertThat(page.locator("#status-line")).hasText(SNAPSHOT_ANNOUNCEMENT)
        assertThat(page.locator(TERMINAL_TASK)).hasCount(0)
        assertThat(page.locator("li.session-row[aria-current='true']")).hasCount(0)
        assertThat(page).hasURL("$base/s/no-such-session")
    }

    @Test
    fun aTaskPathOpensThatTasksDetailOnFirstLoad() = routerTest("task-path") { base, page ->
        page.navigate("$base/tasks/$ENCODED_TASK")
        assertThat(page).hasURL(taskUrl(base))
        assertTaskDetail(page)

        page.navigate("$base/tasks/$DEEP_TASK")
        assertThat(page).hasURL(taskUrl(base))
        assertTaskDetail(page)
    }

    @Test
    fun aNotificationDeepLinkSelectsTheSessionAndThePathOutranksIt() = routerTest("deep-link") { base, page ->
        page.navigate("$base/?session=$DEEP_SESSION")
        assertSessionView(page)
        assertThat(page.locator(TERMINAL_TASK)).hasAttribute("href", TASK_HREF)
        assertThat(page).hasURL("$base/s/$DEEP_SESSION")

        page.navigate("$base/s/$DEEP_SESSION?session=no-such-session")
        assertSessionView(page)
        assertThat(page.locator(TERMINAL_TASK)).hasAttribute("href", TASK_HREF)
        assertThat(page).hasURL("$base/s/$DEEP_SESSION")
    }

    @Test
    fun theSidebarsTwoLinksMoveBetweenTheScreensFromEitherOne() = routerTest("nav-switch") { base, page ->
        page.navigate("$base/s/$DEEP_SESSION")
        assertSessionView(page)
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

    @Test
    fun linkNavigationCrossLinksTheScreensAndBackForwardRetracesIt() = routerTest("cross-link") { base, page ->
        page.navigate("$base/s/$DEEP_SESSION")
        assertSessionView(page)

        page.locator(TERMINAL_TASK).click()
        assertThat(page).hasURL(taskUrl(base))
        assertTaskDetail(page)

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

    @Test
    fun everyScreenKeepsItsOwnAddressAcrossAReload() = routerTest("reload") { base, page ->
        roundTrip(page, "$base/") {
            assertSessionView(page)
            assertThat(page.locator("#status-line")).hasText(SNAPSHOT_ANNOUNCEMENT)
            assertThat(page.locator(TERMINAL_TASK)).hasCount(0)
            assertThat(page).hasURL("$base/")
        }
        roundTrip(page, "$base/s/$DEEP_SESSION") {
            assertSessionView(page)
            assertThat(page.locator(TERMINAL_TASK)).hasAttribute("href", TASK_HREF)
            assertThat(page).hasURL("$base/s/$DEEP_SESSION")
        }
        roundTrip(page, "$base/tasks") {
            assertBoard(page)
            assertThat(page.locator("section.task-detail")).hasCount(0)
            assertThat(page).hasURL("$base/tasks")
        }
        roundTrip(page, "$base/tasks/$ENCODED_TASK") {
            assertTaskDetail(page)
            assertThat(page).hasURL(taskUrl(base))
        }
    }


    private fun routerTest(name: String, block: (String, Page) -> Unit) {
        Harness(DEEP_LINK_SCENARIO).use { harness ->
            Playwright.create().use { playwright ->
                touchChromium(playwright).use { browser ->
                    browser.fineContext(width = 1280, height = 900).use { context ->
                        context.traced(name) {
                            context.loginWithTicket(harness.ticket, harness.baseUrl)
                            block(harness.baseUrl, context.newPage())
                        }
                    }
                }
            }
        }
    }

    private fun roundTrip(page: Page, url: String, expect: () -> Unit) {
        page.navigate(url)
        expect()
        page.reload()
        expect()
    }

    private fun assertSessionView(page: Page) {
        assertThat(page.locator("#terminal-pane")).isVisible()
        assertThat(page.locator("main.board")).hasCount(0)
    }

    private fun assertBoard(page: Page) {
        assertThat(page.locator("main.board")).isVisible()
        assertThat(page.locator("#terminal-pane")).hasCount(0)
    }

    private fun assertTaskDetail(page: Page) {
        assertBoard(page)
        assertThat(page.locator("section.task-detail")).isVisible()
        assertThat(page.locator("#task-detail-title")).hasText(DEEP_TASK)
        assertThat(page.locator("#task-detail-form")).isVisible()
    }
}

private const val DEEP_SESSION = "deep-session"
private const val DEEP_TASK = "local:7"

private const val ENCODED_TASK = "local%3A7"

private const val TERMINAL_TASK = "#terminal-task"

private const val TASK_HREF = "/tasks/$ENCODED_TASK"

private val SNAPSHOT_ANNOUNCEMENT: Pattern = Pattern.compile("""\d+ session\(s\)\.""")

private fun taskUrl(base: String): Pattern =
    Pattern.compile("^" + regexLiteral("$base/tasks/local") + "(%3A|:)7$")
