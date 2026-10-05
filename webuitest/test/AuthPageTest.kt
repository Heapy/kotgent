package io.kotgent.webuitest

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthPageTest {

    @Test
    fun thePublicPageRendersTheCodeFormWithoutStartingTheApp() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext(Browser.NewContextOptions().setViewportSize(360, 800)).use { context ->
                    context.traced("auth-public-form") {
                        val page = context.newPage()
                        val requests = mutableListOf<String>()
                        val sockets = mutableListOf<String>()
                        page.onRequest { requests.add(URI(it.url()).path) }
                        page.onWebSocket { sockets.add(it.url()) }
                        page.navigate(harness.baseUrl + AUTH_PAGE_PATH)
                        assertThat(page.locator("#code-form")).isVisible()
                        assertThat(page.locator("#code")).isFocused()
                        assertThat(page.locator("#status")).hasText("Enter your sign-in code.")
                        assertThat(page.locator("#code-help")).hasText("8 characters, one-time, good for 5 minutes.")
                        assertThat(page.locator("#code")).hasAttribute("autocomplete", "one-time-code")
                        assertThat(page.locator("#code")).hasAttribute("autocapitalize", "characters")
                        assertThat(page.locator("#code")).hasAttribute("autocorrect", "off")
                        assertThat(page.locator("#code")).hasAttribute("spellcheck", "false")
                        assertThat(page.locator("#code")).hasAttribute("aria-describedby", "code-help")
                        assertThat(page.locator("link[rel=manifest]")).hasAttribute("href", "/manifest.webmanifest")
                        assertThat(page.locator("link[rel=apple-touch-icon]")).hasCount(1)
                        assertThat(page.locator("meta[name=apple-mobile-web-app-capable]")).hasAttribute("content", "yes")
                        assertThat(page.locator("#app")).hasCount(0)
                        val bounds = page.locator(".auth-page").boundingBox()
                        assertTrue(bounds.x >= 0 && bounds.x + bounds.width <= 360, "the form fits a phone viewport")
                        assertEquals("rgb(20, 23, 28)", page.evaluate("getComputedStyle(document.body).backgroundColor"))
                        assertTrue(requests.any { it.startsWith("/assets/auth-") && it.endsWith(".js") })
                        assertFalse(requests.any { it.startsWith("/assets/index-") })
                        assertFalse(requests.any { it.startsWith("/api/") })
                        assertTrue(sockets.isEmpty(), "sign-in does not open application sockets")
                    }
                }
            }
        }
    }

    @Test
    fun aTicketFragmentSignsInAutomaticallyAndIsAbsentFromTheLandingUrl() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("auth-ticket-fragment") {
                        val page = context.newPage()
                        val urls = mutableListOf<String>()
                        page.onRequest { urls.add(it.url()) }
                        page.navigate("${harness.baseUrl}$AUTH_PAGE_PATH#ticket=${harness.ticket}")
                        assertThat(page).hasURL(harness.baseUrl + "/")
                        assertThat(page.locator("#sidebar")).isVisible()
                        assertFalse(urls.any { harness.ticket in it }, "the ticket never appears in an HTTP URL")
                    }
                }
            }
        }
    }

    @Test
    fun aRefusedFragmentOffersManualEntryAndAValidCodeCanStillSignIn() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("auth-refused-fragment") {
                        val page = context.newPage()
                        page.navigate("${harness.baseUrl}$AUTH_PAGE_PATH#ticket=invalid")
                        assertThat(page.locator("#status")).containsText("not valid")
                        assertThat(page.locator("#code-form")).isVisible()
                        assertThat(page.locator("#code")).isFocused()
                        assertThat(page.locator("#code-submit")).isEnabled()
                        page.locator("#code").fill("  ${harness.ticket}  ")
                        page.locator("#code").press("Enter")
                        assertThat(page).hasURL(harness.baseUrl + "/")
                        assertThat(page.locator("#sidebar")).isVisible()
                    }
                }
            }
        }
    }

    @Test
    fun submissionIsSingleFlightAndThrottleAndNetworkFailuresPermitRetry() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("auth-exchange-retry") {
                        val page = context.newPage()
                        var pending: Route? = null
                        var attempts = 0
                        page.route("**/api/v1/auth/exchange") { route ->
                            attempts += 1
                            when (attempts) {
                                1 -> pending = route
                                2 -> route.abort()
                                else -> route.resume()
                            }
                        }
                        page.navigate(harness.baseUrl + AUTH_PAGE_PATH)
                        page.locator("#code").fill(harness.ticket)
                        page.locator("#code-submit").click()
                        page.waitForCondition { pending != null }
                        assertThat(page.locator("#code-submit")).isDisabled()
                        assertThat(page.locator("#status")).hasText("Signing in…")
                        page.evaluate(
                            """
                            async () => {
                              const form = document.getElementById("code-form");
                              form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
                              form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
                              await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                            }
                            """.trimIndent(),
                        )
                        pending!!.fulfill(Route.FulfillOptions().setStatus(429).setBody("rate limited"))
                        assertThat(page.locator("#status")).containsText("Too many attempts")
                        assertThat(page.locator("#code-submit")).isEnabled()
                        assertEquals(1, attempts, "repeated submit events do not spend another exchange attempt")
                        page.waitForFunction(
                            """() => {
                              const input = document.getElementById("code");
                              return input.selectionStart === 0 && input.selectionEnd === input.value.length;
                            }""".trimIndent(),
                        )
                        assertEquals(
                            harness.ticket,
                            page.locator("#code").evaluate("input => input.value.slice(input.selectionStart, input.selectionEnd)"),
                        )
                        page.locator("#code-submit").click()
                        assertThat(page.locator("#status")).containsText("Could not reach kotgent")
                        assertThat(page.locator("#code-submit")).isEnabled()
                        page.locator("#code-submit").click()
                        assertThat(page).hasURL(harness.baseUrl + "/")
                        assertThat(page.locator("#sidebar")).isVisible()
                        assertEquals(3, attempts)
                    }
                }
            }
        }
    }
}
