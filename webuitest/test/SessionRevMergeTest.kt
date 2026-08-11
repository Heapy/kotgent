package io.kotgent.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Predicate
import kotlin.test.Test

class SessionRevMergeTest {

    @Test
    fun aStaleActionResponseCannotRollBackARowTheFrameAlreadyMoved() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    context.traced("session-rev-merge") {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)
                        val page = context.newPage()

                        val held = AtomicReference<Route?>(null)
                        page.route(
                            Predicate<String> { url -> url.endsWith(INTERRUPT_PATH) },
                            Consumer<Route> { route ->
                                if (!held.compareAndSet(null, route)) route.resume()
                            },
                        )

                        page.navigate("${harness.baseUrl}/")
                        assertThat(page.locator("#sidebar")).isVisible()
                        assertThat(badge(page)).hasText(RUNNING)

                        page.locator("#session-list .session-row[data-id='$SESSION']").click()
                        assertThat(page.locator("#terminal-host")).isVisible()
                        interruptFromPalette(page)

                        page.waitForCondition { held.get() != null }
                        val stale = held.get()!!.fetch()
                        assertThat(badge(page)).hasText(READY)

                        harness.send("emit $SESSION $NEWER_STATE")
                        assertThat(badge(page)).hasText(NEWER_LABEL)

                        held.get()!!.fulfill(Route.FulfillOptions().setResponse(stale))
                        assertThat(page.locator("#status-line")).containsText(INTERRUPT_DONE)

                        assertThat(badge(page)).hasText(NEWER_LABEL)
                    }
                }
            }
        }
    }

    private fun interruptFromPalette(page: Page) {
        page.keyboard().press(PALETTE_OPENER)
        assertThat(page.locator("#command-palette")).isVisible()
        assertThat(page.locator(".command-palette-shell.leader")).isFocused()
        page.keyboard().press("KeyI")
        assertThat(page.locator("#command-palette")).hasCount(0)
    }

    private fun badge(page: Page) =
        page.locator("#session-list .session-row[data-id='$SESSION'] .badge")

    private companion object {
        const val SESSION = "s-alpha"
        const val INTERRUPT_PATH = "/api/v1/sessions/$SESSION/interrupt"

        const val RUNNING = "running"
        const val READY = "ready"

        const val NEWER_STATE = "needs_approval"
        const val NEWER_LABEL = "needs approval"

        const val INTERRUPT_DONE = "Interrupt completed"
    }
}
