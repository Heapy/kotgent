package io.kotgent.webuitest

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves two rendered typeahead hooks keep independent choices under real keyboard input. */
class TypeaheadInstanceTest {

    @Test
    fun twoMountedPickersDoNotShareOneActiveRow() {
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    context.traced("typeahead-two-instances") {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)
                        val page = context.newPage()
                        page.navigate("${harness.baseUrl}/")
                        assertThat(page.locator("#empty-sessions")).isVisible(visibleWithin(BOOT_TIMEOUT_MS))

                        val probeUrl = page.routeWebUiProbe("typeahead.js")
                        val moduleUrl = page.evaluate("async url => (await import(url)).mount()", probeUrl)
                        assertEquals(
                            harness.baseUrl + probeUrl,
                            moduleUrl,
                            "the probe mounted the served Vite bundle built from the real typeahead hook",
                        )

                        assertThat(active("one", page)).hasText(FIRST)
                        assertThat(active("two", page)).hasText(FIRST)

                        page.locator("#typeahead-probe-input-one").press("ArrowDown")
                        assertThat(active("one", page)).hasText(SECOND)
                        assertThat(active("two", page)).hasText(
                            FIRST,
                        )

                        page.locator("#typeahead-probe-input-two").press("ArrowDown")
                        page.locator("#typeahead-probe-input-two").press("ArrowDown")
                        assertThat(active("two", page)).hasText(THIRD)
                        assertThat(active("one", page)).hasText(SECOND)

                        page.locator("#typeahead-probe-input-one").press("Enter")
                        page.locator("#typeahead-probe-input-two").press("Enter")
                        assertEquals(
                            "one:$SECOND two:$THIRD",
                            page.evaluate("async url => (await import(url)).commits.join(' ')", probeUrl),
                            "each Enter committed the row its own picker had active",
                        )

                        page.evaluate("async url => (await import(url)).unmount()", probeUrl)
                        assertThat(page.locator("#typeahead-probe")).hasCount(0)
                    }
                }
            }
        }
    }

    private fun active(id: String, page: com.microsoft.playwright.Page) =
        page.locator("#typeahead-probe-active-$id")

    private companion object {
        const val FIRST = "local:1"
        const val SECOND = "local:2"
        const val THIRD = "local:3"
    }
}
