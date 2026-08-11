package io.kotgent.webuitest

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.Test

class SmokeTest {

    @Test
    fun aTicketLoginLandsOnTheAppAndTheSidebarCarriesTheScenariosSessions() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("smoke-sidebar") {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)

                        val page = context.newPage()
                        page.navigate("${harness.baseUrl}/")
                        assertThat(page.locator("#sidebar")).isVisible()

                        for (cwd in SESSION_CWDS) {
                            assertThat(page.locator("#session-list .session-row[title='$cwd']").first())
                                .isVisible()
                        }
                        assertThat(page.locator("#empty-sessions")).hasCount(0)

                        assertThat(page.locator("#session-list .session-row")).hasCount(SESSION_ROWS)
                    }
                }
            }
        }
    }

    @Test
    fun aWrongCodeIsRefusedAndLeavesTheSignInForm() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("smoke-wrong-code") {
                        val page = context.newPage()
                        page.navigate(harness.baseUrl + AUTH_PAGE_PATH)
                        assertThat(page.locator("#code-form")).isVisible()

                        page.locator("#code").fill(wrongCode(harness.ticket))
                        page.locator("#code-submit").click()

                        assertThat(page.locator("#status")).containsText("not valid")
                        assertThat(page.locator("#code-form")).isVisible()
                        assertThat(page).hasURL(harness.baseUrl + AUTH_PAGE_PATH)
                        assertThat(page.locator("#app")).hasCount(0)
                    }
                }
            }
        }
    }

    private fun wrongCode(real: String): String =
        listOf("23456789", "9876543Z").first { !it.equals(real, ignoreCase = true) }
}

private val SESSION_CWDS = listOf("/a/b", "/a/c", "/d")

private const val SESSION_ROWS = 4
