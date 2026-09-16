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
    fun detailsOpenOnHoverAndKeyboardFocusAndEscapeKeepsFocusWithoutReflow() {
        usagePage(USAGE_SCENARIO, "usage-tooltip-keyboard") { _, page ->
            val meter = window(page, "claude", "five_hour")
            val button = meter.getByRole(AriaRole.BUTTON)
            val tooltip = meter.getByRole(AriaRole.TOOLTIP)
            val strip = page.locator("#usage-strip")
            val height = strip.boundingBox()!!.height
            assertThat(tooltip).isHidden()
            button.hover()
            assertThat(tooltip).isVisible()
            assertThat(detail(meter, "Time left")).not().hasText("unknown")
            assertEquals(height, strip.boundingBox()!!.height)
            val _ = page.screenshot(Page.ScreenshotOptions().setPath(testResultsDir().resolve("usage-compact-desktop.png")))
            page.mouse().move(800.0, 200.0)
            assertThat(tooltip).isHidden()
            button.focus()
            assertThat(tooltip).isVisible()
            button.press("Escape")
            assertThat(tooltip).isHidden()
            assertThat(button).isFocused()
            assertThat(page.locator("#sidebar")).isVisible()
            button.press("Enter")
            assertThat(tooltip).isVisible()
            button.press("Tab")
            assertThat(tooltip).isHidden()
            page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Sessions").setExact(true)).focus()
            button.hover()
            assertThat(tooltip).isVisible()
            page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Tasks").setExact(true)).focus()
            assertThat(tooltip).isHidden()
        }
    }

    @Test
    fun keyboardFocusedDetailsSurviveMouseLeavingUntilDismissedOrFocusMoves() {
        usagePage(USAGE_SCENARIO, "usage-tooltip-mixed-input") { _, page ->
            val meter = window(page, "claude", "seven_day")
            val button = meter.getByRole(AriaRole.BUTTON)
            val tooltip = meter.getByRole(AriaRole.TOOLTIP)
            window(page, "claude", "five_hour").getByRole(AriaRole.BUTTON).focus()
            page.keyboard().press("Tab")
            assertThat(button).isFocused()
            assertThat(tooltip).isVisible()

            button.hover()
            page.mouse().move(800.0, 200.0)
            assertThat(button).isFocused()
            assertThat(tooltip).isVisible()

            button.press("Escape")
            assertThat(tooltip).isHidden()
            assertThat(button).isFocused()
            button.press("Enter")
            assertThat(tooltip).isVisible()
            button.press("Tab")
            assertThat(tooltip).isHidden()
        }
    }

    @Test
    fun tappingAMeterShowsItsDetailsWithinThePhoneSidebarAndTappingOutsideClosesThem() {
        usagePage(USAGE_SCENARIO, "usage-tooltip-touch", touch = true) { _, page ->
            val meter = window(page, "claude", "seven_day")
            val button = meter.getByRole(AriaRole.BUTTON)
            val tooltip = meter.getByRole(AriaRole.TOOLTIP)
            button.tap()
            assertThat(tooltip).isVisible()
            val bounds = tooltip.boundingBox()!!
            val sidebar = page.locator("#sidebar").boundingBox()!!
            assertTrue(bounds.x >= sidebar.x && bounds.x + bounds.width <= sidebar.x + sidebar.width)
            assertTrue(bounds.y >= sidebar.y && bounds.y + bounds.height <= sidebar.y + sidebar.height)
            val _ = page.screenshot(Page.ScreenshotOptions().setPath(testResultsDir().resolve("usage-compact-phone.png")))
            page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Kotgent").setExact(true)).tap()
            assertThat(tooltip).isHidden()
        }
    }

    @Test
    fun unknownTimesHaveNoMarkerAndReachingAKnownResetPreservesTheLastUsage() {
        usagePage(EMPTY_SCENARIO, "usage-time-boundaries") { harness, page ->
            val serverNow = snapshotServerNow(page)
            page.clock().pauseAt(EPOCH + 60_000)
            harness.send("usage claude five_hour 82 - $serverNow")
            harness.send("usage codex primary 14 ${serverNow + 90_000} $serverNow -")
            assertUsage(page, "claude", "five_hour" to "82")
            assertUsage(page, "codex", "primary" to "14")
            assertThat(page.locator(".usage-now-marker")).hasCount(0)
            assertThat(detail(window(page, "claude", "five_hour"), "Time left")).hasText("unknown")

            harness.send("usage claude five_hour 82 ${serverNow + 90_000} ${serverNow + 1}")
            val meter = window(page, "claude", "five_hour")
            assertThat(meter.locator(".usage-now-marker")).isVisible()
            page.clock().runFor(90_001)
            assertThat(detail(meter, "Time left")).hasText("Waiting for update")
            assertTrue(markerPosition(meter) > 0.99)
            assertUsage(page, "claude", "five_hour" to "82")
        }
    }

    @Test
    fun theNowMarkerAdvancesWithoutProviderPollingAndIgnoresPhoneClockSkew() {
        usagePage(EMPTY_SCENARIO, "usage-now-marker") { harness, page ->
            val serverNow = snapshotServerNow(page)
            page.clock().pauseAt(EPOCH + 60_000)
            harness.send("usage claude five_hour 36 ${serverNow + 7_200_000} $serverNow")
            assertUsage(page, "claude", "five_hour" to "36")
            val meter = window(page, "claude", "five_hour")
            assertThat(meter.locator(".usage-now-marker")).isVisible()
            val frames = frameCount(page, "usage_update")
            page.clock().setSystemTime(EPOCH - 12 * 60 * 60 * 1_000)
            val start = markerPosition(meter)
            assertEquals(0.6, start, 0.005)
            page.clock().runFor(30 * 60 * 1_000)
            assertEquals(start + 0.1, markerPosition(meter), 0.005)
            assertUsage(page, "claude", "five_hour" to "36")
            assertEquals(frames, frameCount(page, "usage_update"), "time advances without provider polling")
        }
    }

    @Test
    fun aClockCorrectionFromOneProviderMovesBothMarkersWithoutRevivingTheSilentProvider() {
        usagePage(EMPTY_SCENARIO, "usage-shared-clock") { harness, page ->
            val serverNow = snapshotServerNow(page)
            val resetsAt = serverNow + 7_200_000
            page.clock().pauseAt(EPOCH + 60_000)
            harness.send("usage claude five_hour 36 $resetsAt $serverNow")
            harness.send("usage codex primary 58 $resetsAt $serverNow 18000")
            assertUsage(page, "claude", "five_hour" to "36")
            assertUsage(page, "codex", "primary" to "58")
            val claude = window(page, "claude", "five_hour")
            val codex = window(page, "codex", "primary")
            codex.getByRole(AriaRole.BUTTON).click()
            val codexReceipt = detail(codex, "Observed").textContent()
            page.clock().runFor(STALE_MILLIS + 1)
            assertThat(provider(page, "claude")).hasClass(STALE)
            assertThat(provider(page, "codex")).hasClass(STALE)
            val frames = frameCount(page, "usage_update")

            val correctedNow = serverNow - 3_600_000
            harness.send("usage claude five_hour 36 $resetsAt $correctedNow")
            val correctedDate = page.evaluate("stamp => new Date(stamp).toLocaleString()", correctedNow.toDouble()) as String
            assertThat(detail(claude, "Now")).hasText(correctedDate)
            assertThat(detail(codex, "Now")).hasText(correctedDate)
            assertThat(detail(codex, "Time left")).hasText("3h 0m")
            assertEquals(0.4, markerPosition(claude), 0.005)
            assertEquals(0.4, markerPosition(codex), 0.005)
            assertThat(codex.getByRole(AriaRole.TOOLTIP)).isVisible()
            assertThat(detail(codex, "Observed")).hasText(codexReceipt)
            assertUsage(page, "codex", "primary" to "58")
            assertThat(provider(page, "claude")).not().hasClass(STALE)
            assertThat(provider(page, "codex")).hasClass(STALE)
            assertEquals(frames + 1, frameCount(page, "usage_update"), "only Claude published a new reading")
        }
    }

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
            assertTrue("%" !in page.locator("#usage-strip").innerText(), "the compact strip has no permanent numeric labels")
            assertThat(provider(page, "junie")).hasCount(0)
            assertThat(provider(page, "shell")).hasCount(0)
            assertDetails(page, "claude", "five_hour", "23", FIVE_HOUR_RESET, EPOCH)
            assertDetails(page, "claude", "seven_day", "41", WEEKLY_RESET, EPOCH)
            assertDetails(page, "codex", "primary", "58", WEEKLY_RESET, EPOCH)
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
            assertDetails(page, "claude", "five_hour", "23", null, EPOCH)
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
            page.clock().setSystemTime(serverNow + 12 * 60 * 60 * 1_000)
            harness.send("usage claude seven_day 41 - ${serverNow - 700_000}")
            harness.send("usage codex primary 58 - $serverNow 604800")
            assertThat(provider(page, "codex")).not().hasClass(STALE)

            page.reload()
            awaitSnapshot(page)
            assertThat(provider(page, "claude")).hasClass(STALE)
            assertThat(provider(page, "codex")).not().hasClass(STALE)

            page.clock().setSystemTime(serverNow - 12 * 60 * 60 * 1_000)
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
            page.clock().pauseAt(anchor)
            harness.send("usage claude five_hour 23 $FIVE_HOUR_RESET $anchor")
            harness.send("usage claude seven_day 41 $WEEKLY_RESET $anchor")
            harness.send("usage codex primary 58 $WEEKLY_RESET $anchor 604800")
            assertDetails(page, "claude", "five_hour", "23", FIVE_HOUR_RESET, anchor)
            assertDetails(page, "claude", "seven_day", "41", WEEKLY_RESET, anchor)
            assertDetails(page, "codex", "primary", "58", WEEKLY_RESET, anchor)
            val claudeBars = claude.locator(".usage-window-button").first()
            val freshOpacity = opacity(claudeBars)
            assertThat(claude).not().hasClass(STALE)
            val initialFrames = frameCount(page, "usage_update")

            page.clock().runFor(STALE_MILLIS)
            assertThat(claude).not().hasClass(STALE)
            assertThat(codex).not().hasClass(STALE)
            page.clock().runFor(1)
            assertThat(claude).hasClass(STALE)
            assertThat(codex).hasClass(STALE)
            assertTrue(opacity(claudeBars) < freshOpacity, "stale data is visibly dimmed, not only marked with a class")
            assertEquals(initialFrames, frameCount(page, "usage_update"), "the timer needs no backend update")

            val heartbeatAt = anchor + STALE_MILLIS + 1
            harness.send("usage claude seven_day 41 $WEEKLY_RESET $heartbeatAt")
            assertDetails(page, "claude", "seven_day", "41", WEEKLY_RESET, heartbeatAt)
            assertUsage(page, "claude", "five_hour" to "23", "seven_day" to "41")
            assertThat(claude).not().hasClass(STALE)
            assertEquals(freshOpacity, opacity(claudeBars))
            assertThat(codex).hasClass(STALE)
            assertDetails(page, "claude", "five_hour", "23", FIVE_HOUR_RESET, anchor)

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
            assertDetails(page, "claude", "seven_day", "45", WEEKLY_RESET, EPOCH + 1)
            assertEquals(0, frameCount(page, "usage_update"), "the fresh page received the persisted value in its snapshot")
        }
    }
}

private fun usagePage(scenario: String, trace: String, touch: Boolean = false, block: (Harness, Page) -> Unit) {
    Harness(scenario).use { harness ->
        onChromium { browser ->
            (if (touch) browser.touchContext() else browser.fineContext()).use { context ->
                context.traced(trace) {
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    val page = context.newPage()
                    page.addInitScript(FRAME_RECORDER)
                    page.clock().install(Clock.InstallOptions().setTime(EPOCH))
                    page.navigate("${harness.baseUrl}/")
                    awaitSnapshot(page)
                    if (touch) page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Show the session list")).tap()
                    assertThat(page.locator("#sidebar")).isVisible()
                    block(harness, page)
                }
            }
        }
    }
}

private fun awaitSnapshot(page: Page) {
    page.waitForCondition {
        listOf("usage_snapshot", "sessions_snapshot", "tasks_snapshot").all { frameCount(page, it) > 0 }
    }
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

private fun assertDetails(page: Page, provider: String, key: String, percent: String, resetsAt: Long?, observedAt: Long) {
    fun localDate(stamp: Long): String = page.evaluate("stamp => new Date(stamp).toLocaleString()", stamp.toDouble()) as String
    val meter = window(page, provider, key)
    assertThat(meter.locator(".usage-tooltip strong")).containsText("$percent% used")
    assertThat(detail(meter, "Resets")).hasText(resetsAt?.let(::localDate) ?: "unknown")
    assertThat(detail(meter, "Observed")).hasText(localDate(observedAt))
}

private fun detail(meter: Locator, label: String): Locator = meter.locator(".usage-tooltip dl > div")
    .filter(Locator.FilterOptions().setHasText(Pattern.compile("^$label"))).locator("dd")

private fun opacity(locator: Locator): Double =
    (locator.evaluate("el => Number(getComputedStyle(el).opacity)") as Number).toDouble()

private fun markerPosition(meter: Locator): Double {
    val track = meter.getByRole(AriaRole.PROGRESSBAR).boundingBox()!!
    val marker = meter.locator(".usage-now-marker").boundingBox()!!
    return (marker.x + marker.width / 2 - track.x) / track.width
}

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
