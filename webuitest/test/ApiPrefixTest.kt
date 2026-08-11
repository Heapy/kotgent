package io.kotgent.webuitest

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.net.URI
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every call the page makes to the daemon goes under `/api/v1`, and the `/auth` bootstrap is the one
 * exemption.
 *
 * ## Why this cannot be a source check
 * `lib/api.js` puts the prefix on in exactly one place (`apiPath`, reached from `apiRequest` and
 * `wsUrl`), and the Kotlin tier used to assert that by matching the literal expression inside it. That
 * grep failed on the wrong input in both directions: renaming a local inside `apiPath` broke it, while
 * the failure it was written for — a call site that bypasses `apiRequest` and hand-builds
 * `fetch("/sessions")` — kept it green, because the expression it pinned was still there. Only a
 * running page can answer "did EVERY call carry the prefix", and it answers it about the calls the app
 * really makes rather than the ones a reader of the source expects.
 *
 * The Kotlin tier keeps the half a browser genuinely cannot see: `sw.js` hand-writes the same prefix
 * because a classic worker cannot import the module, and its fetches only ever run inside a push
 * handler no headless browser can trigger
 * (`WebUiServingTest.theServiceWorkerHandWritesTheSameApiPrefixTheModuleDeclares`).
 *
 * ## What counts as a daemon call
 * The `fetch` / `xhr` resource types and every WebSocket — never a document, script, stylesheet or
 * image, which are the static shell and are deliberately served OUTSIDE the prefix. WebSockets do not
 * arrive as requests in Playwright at all, so they are collected through the page's own `webSocket`
 * event; that half is load-bearing, because `wsUrl` builds its URL by a different route than
 * `apiRequest` and the two sockets (`/events`, a session's `/terminal`) are the app's most important
 * calls.
 */
class ApiPrefixTest {

    /**
     * One page, driven through every surface that talks to the daemon, with a collector on the whole
     * context.
     *
     * The drive is not decoration. A collector over an idle page proves nothing, so the test asserts
     * FIRST that it saw the specific calls each door owes — the events socket and a terminal socket
     * (through `wsUrl`), the preferences, project and version reads (through `apiRequest`), and both
     * exempted `/auth` calls — and only then that the whole collected set obeys the rule. Without those
     * the "every URL carried the prefix" assertion would be vacuously true of a page that made no calls.
     */
    @Test
    fun everyDaemonCallThePageMakesCarriesTheApiPrefixExceptTheAuthBootstrap() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    // Appended to from Playwright's event threads, read from the test thread.
                    val urls = Collections.synchronizedList(mutableListOf<String>())
                    context.onRequest { request ->
                        if (request.resourceType() in DAEMON_RESOURCE_TYPES) urls.add(request.url())
                    }

                    context.traced("api-prefix-every-call") {
                        // The sign-in form runs before the SPA exists and is part of the exempted
                        // surface; collecting it too is deliberate — it is the strongest live proof that
                        // the exemption is real rather than merely spelled in the source.
                        context.loginWithTicket(harness.ticket, harness.baseUrl)

                        val page = context.newPage()
                        // `webSocket` is a PAGE event — a BrowserContext has no such signal — so it is
                        // subscribed here, before the first navigation opens `/events`.
                        page.onWebSocket { socket -> urls.add(socket.url()) }

                        page.navigate("${harness.baseUrl}/")
                        assertThat(page.locator("#sidebar")).isVisible()
                        assertThat(page.locator("#session-list .session-row")).hasCount(SESSION_ROWS)

                        // A live session: selecting it opens the terminal socket and posts a read cursor.
                        page.locator("#session-list .session-row[data-id='$LIVE_SESSION']").click()
                        assertThat(page.locator("#terminal-host")).isVisible()

                        // The exemption's live caller: the phone dialog mints a ticket through
                        // `apiRequest("/auth/ticket")` — i.e. through the very function that adds the
                        // prefix — and the URL must come back WITHOUT one.
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

    /**
     * Open the phone dialog through the palette's leader grid (`⌘K m`).
     *
     * The dialog issues its ticket on mount, and the request has to be OBSERVED, not merely started —
     * so this waits for the "minting" line to be replaced. The harness serves no `publicUrl`, so what
     * replaces it is the setup note rather than a QR; the response body is not this test's subject, the
     * request URL is.
     */
    private fun openPhoneDialog(page: Page) {
        page.keyboard().press("Meta+KeyK")
        assertThat(page.locator("#command-palette")).isVisible()
        // Leader mode takes the keyboard in a post-paint effect; a mnemonic pressed before that lands on
        // the <dialog> and is dropped in silence (`CommandPalette.js`).
        assertThat(page.locator(".command-palette-shell.leader")).isFocused()
        page.keyboard().press("KeyM")
        assertThat(page.locator("#phone-dialog")).isVisible()
        assertThat(page.locator("#phone-status")).hasCount(0)
    }

    /** The path of an absolute `http`/`ws` URL — what the prefix rule is actually about. */
    private fun pathOf(url: String): String = URI(url).path ?: url

    private fun onChromium(block: (Browser) -> Unit) {
        Playwright.create().use { pw ->
            touchChromium(pw).use { browser -> block(browser) }
        }
    }

    private companion object {
        const val API_PREFIX = "/api/v1"

        /** The `sessions` scenario's four rows, and its one `running` claude session. */
        const val SESSION_ROWS = 4
        const val LIVE_SESSION = "s-alpha"

        /**
         * The resource types a daemon call arrives as. Everything else — `document`, `script`,
         * `stylesheet`, `image`, `font` — is the static shell, which is served off the bare paths on
         * purpose and would make this rule false for the wrong reason.
         */
        val DAEMON_RESOURCE_TYPES = setOf("fetch", "xhr")

        /**
         * What the drive above owes the collector, as `(description, substring)`.
         *
         * One per DOOR rather than one per endpoint: the two sockets go out through `wsUrl`, the three
         * reads through `apiRequest`, and the ticket through the exemption inside `apiPath` — plus the
         * sign-in exchange, which the `/auth` page's own script issues before any module is loaded.
         *
         * Two absences are deliberate. The board's task list: the `sessions` scenario registers no
         * project, so there is nothing to fetch, and it would be the `apiRequest` door again. And the
         * read cursor: every row in this scenario is seeded `lastSeq = readCursor = 0`, so there is
         * nothing unread and `postRead` correctly sends nothing — measured, not assumed.
         */
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
