package io.kotgent.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.net.URI
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

class ApiPrefixTest {

    @Test
    fun everyDaemonCallThePageMakesCarriesTheApiPrefixExceptTheAuthBootstrap() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    // Playwright callbacks run off the test thread that later snapshots this collector.
                    val urls = Collections.synchronizedList(mutableListOf<String>())
                    context.onRequest { request ->
                        if (request.resourceType() in DAEMON_RESOURCE_TYPES) urls.add(request.url())
                    }

                    context.traced("api-prefix-every-call") {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)

                        val page = context.newPage()
                        page.onWebSocket { socket -> urls.add(socket.url()) }

                        page.navigate("${harness.baseUrl}/")
                        assertThat(page.locator("#sidebar")).isVisible()
                        assertThat(page.locator("#session-list .session-row")).hasCount(SESSION_ROWS)

                        page.locator("#session-list .session-row[data-id='$LIVE_SESSION']").click()
                        assertThat(page.locator("#terminal-host")).isVisible()

                        openPhoneDialog(page)

                        val seen = urls.toList()
                        for ((what, needle) in EXPECTED_CALLS) {
                            assertTrue(
                                seen.any { needle in it },
                                "the collector never saw $what, so the rule below would be vacuous.\n" +
                                    "collected:\n${seen.joinToString("\n")}",
                            )
                        }

                        val offenders = seen.map { pathOf(it) }
                            .filter { !it.startsWith(API_PREFIX) && !it.startsWith(AUTH_PAGE_PATH) }
                            .distinct()
                        assertTrue(
                            offenders.isEmpty(),
                            "every daemon call must live under $API_PREFIX, or be part of the " +
                                "$AUTH_PAGE_PATH bootstrap. These did neither: $offenders",
                        )
                    }
                }
            }
        }
    }

    private fun openPhoneDialog(page: Page) {
        page.keyboard().press(PALETTE_OPENER)
        assertThat(page.locator("#command-palette")).isVisible()
        assertThat(page.locator(".command-palette-shell.leader")).isFocused()
        page.keyboard().press("KeyM")
        assertThat(page.locator("#phone-dialog")).isVisible()
        assertThat(page.locator("#phone-status")).hasCount(0)
    }

    private fun pathOf(url: String): String = URI(url).path ?: url

    private companion object {
        const val API_PREFIX = "/api/v1"

        const val SESSION_ROWS = 4
        const val LIVE_SESSION = "s-alpha"

        val DAEMON_RESOURCE_TYPES = setOf("fetch", "xhr")

        val EXPECTED_CALLS = listOf(
            "the global events socket" to "$API_PREFIX/events",
            "the preferences read" to "$API_PREFIX/preferences",
            "the project list" to "$API_PREFIX/projects",
            "the version read" to "$API_PREFIX/version",
            "a terminal socket" to "/terminal",
            "the sign-in exchange" to "$AUTH_PAGE_PATH/exchange",
            "the exempted ticket mint" to "$AUTH_PAGE_PATH/ticket",
        )
    }
}
