package io.kotgent.webuitest

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeTest {

    @Test
    fun aTicketLoginLandsOnTheAppAndTheSidebarCarriesTheScenariosSessions() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.newContext().use { context ->
                    context.traced("smoke-sidebar") {
                        val assets = java.util.concurrent.CopyOnWriteArrayList<AssetResponse>()
                        context.onResponse { response ->
                            val url = response.url()
                            if (url.startsWith("${harness.baseUrl}/assets/") &&
                                (url.endsWith(".js") || url.endsWith(".css"))) {
                                assets.add(AssetResponse(url, response.status(), response.headers()))
                            }
                        }
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

                        assertTrue(assets.any { it.url.endsWith(".js") }, "Chromium loads built JavaScript")
                        assertTrue(assets.any { it.url.endsWith(".css") }, "Chromium loads built CSS")
                        for (asset in assets) {
                            assertEquals(200, asset.status, asset.url)
                            assertEquals("br", asset.headers["content-encoding"], asset.url)
                            assertEquals("Accept-Encoding", asset.headers["vary"], asset.url)
                            assertEquals(
                                "max-age=31536000, immutable", asset.headers["cache-control"], asset.url,
                            )
                        }
                        val resources = page.evaluate(
                            """
                            () => [
                              document.querySelector('script[type="module"][src]').src,
                              document.querySelector('link[rel="stylesheet"][href]').href,
                            ].map(name => {
                              const entry = performance.getEntriesByType("resource").find(item => item.name === name);
                              return {
                                name,
                                encodedBodySize: entry?.encodedBodySize ?? 0,
                                decodedBodySize: entry?.decodedBodySize ?? 0,
                              };
                            })
                            """.trimIndent(),
                        ) as List<*>
                        for (resource in resources) {
                            val timing = resource as Map<*, *>
                            val encoded = (timing["encodedBodySize"] as Number).toLong()
                            val decoded = (timing["decodedBodySize"] as Number).toLong()
                            val label = "${timing["name"]}: encoded=$encoded, decoded=$decoded"
                            assertTrue(encoded > 0, "Resource Timing recorded compressed bytes for $label")
                            assertTrue(encoded < decoded, "Chromium decoded the compressed resource: $label")
                        }
                    }
                }
            }
        }
    }

    private class AssetResponse(val url: String, val status: Int, val headers: Map<String, String>)

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
