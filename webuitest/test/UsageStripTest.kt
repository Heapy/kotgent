package io.kotgent.webuitest

import com.microsoft.playwright.Clock
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsageStripTest {
    @Test
    fun theSnapshotShowsUsageBarsAndTheActualCodexWindowDuration() {
        usagePage(USAGE_SCENARIO, "usage-snapshot") { _, page ->
            assertThat(page.getByRole(AriaRole.GROUP, Page.GetByRoleOptions().setName("Usage limits").setExact(true)))
                .isVisible()
            assertThat(page.locator(".usage-provider")).hasCount(2)
            assertUsage(page, "claude", "five_hour" to "23", "seven_day" to "41")
            assertUsage(page, "codex", "primary" to "58")
            assertThat(window(page, "claude", "five_hour").getByRole(AriaRole.PROGRESSBAR))
                .hasAccessibleName("claude 5h usage")
            assertThat(window(page, "claude", "seven_day").getByRole(AriaRole.PROGRESSBAR))
                .hasAccessibleName("claude 7d usage")
            assertThat(window(page, "codex", "primary").getByRole(AriaRole.PROGRESSBAR))
                .hasAccessibleName("codex 7d usage")
            assertThat(page.locator("#usage-strip")).not().containsText("%")
            assertThat(provider(page, "junie")).hasCount(0)
            assertThat(provider(page, "shell")).hasCount(0)
            assertThat(window(page, "claude", "five_hour")).hasAttribute(
                "title", title(page, "23", FIVE_HOUR_RESET, EPOCH),
            )
            assertThat(window(page, "claude", "seven_day")).hasAttribute(
                "title", title(page, "41", WEEKLY_RESET, EPOCH),
            )
            assertThat(window(page, "codex", "primary")).hasAttribute(
                "title", title(page, "58", WEEKLY_RESET, EPOCH),
            )
            assertThat(page.locator(".usage-provider.stale")).hasCount(0)
        }
    }

    @Test
    fun anEmptyStripAppearsOnlyWhenAProviderActuallyPublishesAndUpdatesWithoutReloading() {
        usagePage(EMPTY_SCENARIO, "usage-live-empty") { harness, page ->
            assertThat(page.locator("#usage-strip")).hasCount(0)
            assertThat(page.locator(".usage-provider")).hasCount(0)

            harness.send("usage claude five_hour 23 - $EPOCH")
            assertUsage(page, "claude", "five_hour" to "23")
            assertThat(window(page, "claude", "five_hour")).hasAttribute("title", title(page, "23", null, EPOCH))
            assertThat(provider(page, "codex")).hasCount(0)
            assertThat(provider(page, "junie")).hasCount(0)

            harness.send("usage claude seven_day 41 $WEEKLY_RESET $EPOCH")
            assertUsage(page, "claude", "five_hour" to "23", "seven_day" to "41")
            harness.send("usage claude five_hour 24.5 $FIVE_HOUR_RESET ${EPOCH + 1}")
            assertUsage(page, "claude", "five_hour" to "24.5", "seven_day" to "41")
            assertThat(page.locator(".usage-provider")).hasCount(1)

            harness.send("usage codex primary 58 $WEEKLY_RESET $EPOCH 604800")
            assertUsage(page, "codex", "primary" to "58")
            assertThat(page.locator(".usage-provider")).hasCount(2)
            assertEquals(4, frameCount(page, "usage_update"), "all four mutations arrived over the events socket")
            assertEquals(1, frameCount(page, "usage_snapshot"), "the updates did not require reconnecting")
        }
    }

    @Test
    fun snapshotFreshnessUsesDaemonAgeWhenThePhoneClockIsHoursAheadOrBehind() {
        usagePage(EMPTY_SCENARIO, "usage-clock-skew") { harness, page ->
            val serverNow = snapshotServerNow(page)
            page.clock().setFixedTime(serverNow + 12 * 60 * 60 * 1_000)
            harness.send("usage claude seven_day 41 - ${serverNow - 700_000}")
            harness.send("usage codex primary 58 - $serverNow 604800")
            assertThat(provider(page, "codex")).not().hasClass(STALE)

            page.reload()
            awaitSnapshot(page)
            assertThat(provider(page, "claude")).hasClass(STALE)
            assertThat(provider(page, "codex")).not().hasClass(STALE)

            page.clock().setFixedTime(serverNow - 12 * 60 * 60 * 1_000)
            page.reload()
            awaitSnapshot(page)
            assertThat(provider(page, "claude")).hasClass(STALE)
            assertThat(provider(page, "codex")).not().hasClass(STALE)
            assertUsage(page, "claude", "seven_day" to "41")
        }
    }

    @Test
    fun timeAloneDimsTheStripAndAnUnchangedHeartbeatRevivesItsProviderAndRearmsTheTimer() {
        usagePage(USAGE_SCENARIO, "usage-stale-heartbeat") { harness, page ->
            val claude = provider(page, "claude")
            val codex = provider(page, "codex")
            assertUsage(page, "claude", "five_hour" to "23", "seven_day" to "41")
            assertUsage(page, "codex", "primary" to "58")
            // Boot with running timers, then publish heartbeats after pausing so every deadline is
            // scheduled against this controlled clock rather than the bootstrap timer's real ticks.
            val anchor = EPOCH + 60_000
            page.clock().install(Clock.InstallOptions().setTime(EPOCH))
            page.clock().pauseAt(anchor)
            harness.send("usage claude five_hour 23 $FIVE_HOUR_RESET $anchor")
            harness.send("usage claude seven_day 41 $WEEKLY_RESET $anchor")
            harness.send("usage codex primary 58 $WEEKLY_RESET $anchor 604800")
            assertThat(window(page, "claude", "five_hour")).hasAttribute("title", title(page, "23", FIVE_HOUR_RESET, anchor))
            assertThat(window(page, "claude", "seven_day")).hasAttribute("title", title(page, "41", WEEKLY_RESET, anchor))
            assertThat(window(page, "codex", "primary")).hasAttribute("title", title(page, "58", WEEKLY_RESET, anchor))
            val freshOpacity = opacity(claude)
            assertThat(claude).not().hasClass(STALE)
            val initialFrames = frameCount(page, "usage_update")

            page.clock().setSystemTime(anchor + 12 * 60 * 60 * 1_000)
            page.clock().runFor(STALE_MILLIS)
            assertThat(claude).not().hasClass(STALE)
            assertThat(codex).not().hasClass(STALE)
            page.clock().runFor(1)
            assertThat(claude).hasClass(STALE)
            assertThat(codex).hasClass(STALE)
            assertTrue(opacity(claude) < freshOpacity, "stale data is visibly dimmed, not only marked with a class")
            assertEquals(initialFrames, frameCount(page, "usage_update"), "the timer needs no backend update")

            val heartbeatAt = anchor + STALE_MILLIS + 1
            harness.send("usage claude seven_day 41 $WEEKLY_RESET $heartbeatAt")
            assertThat(window(page, "claude", "seven_day")).hasAttribute(
                "title", title(page, "41", WEEKLY_RESET, heartbeatAt),
            )
            assertUsage(page, "claude", "five_hour" to "23", "seven_day" to "41")
            assertThat(claude).not().hasClass(STALE)
            assertEquals(freshOpacity, opacity(claude))
            assertThat(codex).hasClass(STALE)
            assertThat(window(page, "claude", "five_hour")).hasAttribute(
                "title", title(page, "23", FIVE_HOUR_RESET, anchor),
            )

            page.clock().setSystemTime(anchor - 12 * 60 * 60 * 1_000)
            page.clock().runFor(STALE_MILLIS)
            assertThat(claude).not().hasClass(STALE)
            page.clock().runFor(1)
            assertThat(claude).hasClass(STALE)
            assertEquals(initialFrames + 1, frameCount(page, "usage_update"), "only the heartbeat published new data")
        }
    }

    @Test
    fun aListenerRestartKeepsTheRealUsageProjectionForTheNextSnapshot() {
        usagePage(USAGE_SCENARIO, "usage-restart") { harness, page ->
            harness.send("usage claude seven_day 45 $WEEKLY_RESET ${EPOCH + 1}")
            assertUsage(page, "claude", "five_hour" to "23", "seven_day" to "45")

            harness.send("restart")
            page.reload()
            awaitSnapshot(page)

            assertUsage(page, "claude", "five_hour" to "23", "seven_day" to "45")
            assertUsage(page, "codex", "primary" to "58")
            assertThat(window(page, "claude", "seven_day")).hasAttribute(
                "title", title(page, "45", WEEKLY_RESET, EPOCH + 1),
            )
            assertEquals(0, frameCount(page, "usage_update"), "the fresh page received the persisted value in its snapshot")
        }
    }
}

private fun usagePage(scenario: String, trace: String, block: (Harness, Page) -> Unit) {
    Harness(scenario).use { harness ->
        onChromium { browser ->
            browser.fineContext().use { context ->
                context.traced(trace) {
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    val page = context.newPage()
                    page.addInitScript(FRAME_RECORDER)
                    page.clock().setFixedTime(EPOCH)
                    page.navigate("${harness.baseUrl}/")
                    awaitSnapshot(page)
                    assertThat(page.locator("#sidebar")).isVisible()
                    block(harness, page)
                }
            }
        }
    }
}

private fun awaitSnapshot(page: Page) {
    page.waitForCondition { frameCount(page, "usage_snapshot") > 0 }
}

private fun provider(page: Page, providerName: String): Locator =
    page.locator(".usage-provider[data-provider='$providerName']")

private fun window(page: Page, providerName: String, key: String): Locator =
    provider(page, providerName).locator(".usage-window[data-window='$key']")

private fun assertUsage(page: Page, providerName: String, vararg values: Pair<String, String>) {
    assertThat(provider(page, providerName).getByRole(AriaRole.PROGRESSBAR)).hasCount(values.size)
    for (entry in values) {
        val [key, percent] = entry
        val bar = window(page, providerName, key).getByRole(AriaRole.PROGRESSBAR)
        assertThat(bar).hasAttribute("value", percent)
        assertThat(bar).hasAttribute("max", "100")
        assertThat(bar).hasAttribute("aria-valuetext", "$percent% used")
        assertThat(bar).isVisible()
    }
}

private fun title(page: Page, percent: String, resetsAt: Long?, observedAt: Long): String {
    fun localDate(stamp: Long): String = page.evaluate("stamp => new Date(stamp).toLocaleString()", stamp.toDouble()) as String
    return "$percent% used\nResets: ${resetsAt?.let(::localDate) ?: "unknown"}\nObserved: ${localDate(observedAt)}"
}

private fun opacity(locator: Locator): Double =
    (locator.evaluate("el => Number(getComputedStyle(el).opacity)") as Number).toDouble()

private fun frameCount(page: Page, type: String): Int = (page.evaluate("""
    type => (window.__kotgentFrames || []).filter((raw) => JSON.parse(raw).type === type).length
""".trimIndent(), type) as Number).toInt()

private fun snapshotServerNow(page: Page): Long = (page.evaluate("""
    () => (window.__kotgentFrames || []).map(JSON.parse).find(frame => frame.type === "usage_snapshot").serverNow
""".trimIndent()) as Number).toLong()

private val STALE: Pattern = Pattern.compile("\\bstale\\b")
private const val EPOCH: Long = 1_800_000_000_000L
private const val STALE_MILLIS: Long = 600_000
private const val FIVE_HOUR_RESET: Long = EPOCH + 18_000_000
private const val WEEKLY_RESET: Long = EPOCH + 604_800_000
