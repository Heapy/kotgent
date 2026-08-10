package io.kotgent.webuitest

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The browser tier's own smoke test: it proves the three things every other test in this module rests on,
 * and nothing more.
 *
 * 1. The `webuicheck` binary is where the fixture looks for it, speaks its handshake, and exits 0.
 * 2. A ticket typed into the REAL `/auth` form yields a session cookie that reaches the SPA.
 * 3. The scenario's sessions arrive in the sidebar through the live `/api/v1/events` socket — i.e. the
 *    fake edges behind the harness are wired to a server the browser actually accepts.
 *
 * The negative half matters just as much. A login helper that "worked" by silently landing anywhere would
 * make every later test's first assertion a lie, so a wrong code is checked to leave the operator exactly
 * where they were, on the form, with the SPA nowhere in sight.
 *
 * Depth of assertion is deliberately shallow here: the sidebar's real hierarchy, grouping and routing are
 * `SidebarTest`'s subject. This file only has to fail first, and legibly, when the plumbing is broken.
 */
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

                        // The `sessions` scenario's working directories are part of its frozen contract, so
                        // each one must produce a row. `title` is the row's own cwd (Sidebar.js), which
                        // makes this independent of grouping: a grouped row is nested inside
                        // `ul.group-contents`, still under `#session-list`.
                        for (cwd in SESSION_CWDS) {
                            assertThat(page.locator("#session-list .session-row[title='$cwd']").first())
                                .isVisible()
                        }
                        // The empty-state panel and a populated list are mutually exclusive; asserting its
                        // absence is what catches a list that rendered from a snapshot of nothing.
                        assertThat(page.locator("#empty-sessions")).hasCount(0)

                        // Read the count only after the per-directory assertions have auto-waited the list
                        // into place — `count()` itself does not retry.
                        val rows = page.locator("#session-list .session-row").count()
                        assertTrue(
                            rows >= SESSION_CWDS.size,
                            "expected at least ${SESSION_CWDS.size} session rows (one per scenario " +
                                "directory ${SESSION_CWDS.joinToString()}), found $rows",
                        )
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

                        // One message for every way a code can be wrong — expired, spent, never minted.
                        assertThat(page.locator("#status")).containsText("not valid")
                        // Still on the form, still on /auth, and the SPA shell was never served. The last
                        // of the three is the one that would catch a redirect that "succeeded" wrongly.
                        assertThat(page.locator("#code-form")).isVisible()
                        assertThat(page).hasURL(harness.baseUrl + AUTH_PAGE_PATH)
                        assertThat(page.locator("#app")).hasCount(0)
                    }
                }
            }
        }
    }

    private fun onChromium(block: (Browser) -> Unit) {
        Playwright.create().use { pw ->
            touchChromium(pw).use { browser -> block(browser) }
        }
    }

    /**
     * A well-formed sign-in code that is not the live one.
     *
     * Well-formed matters: the point is to prove a WRONG code is refused, not that garbage is rejected by
     * the input's own validation. Both candidates avoid `I`, `L`, `O` and `U`, which the ticket alphabet
     * excludes and the redeemer folds onto `1` and `0` — so neither can normalize into the real code, and
     * the pair only exists in case the harness ever mints the first one for real.
     */
    private fun wrongCode(real: String): String =
        listOf("23456789", "9876543Z").first { !it.equals(real, ignoreCase = true) }
}

/** The working directories the `sessions` scenario is contracted to produce. */
private val SESSION_CWDS = listOf("/a/b", "/a/c", "/d")
