package io.kotgent.webuitest

import com.microsoft.playwright.Clock
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.WaitUntilState
import java.util.Collections
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals

class MutexesTest {

    @Test
    fun forceReleaseConfirmsInTheAppAndHandsTheMutexToTheNextWaiter() =
        mutexPage("mutexes-force-release") { harness, page ->
            val nativeDialogs = Collections.synchronizedList(mutableListOf<String>())
            page.onDialog { dialog ->
                nativeDialogs.add(dialog.type())
                dialog.dismiss()
            }
            harness.send("mutex-acquire s-builder kotlin-build")
            harness.send("mutex-wait s-tester kotlin-build")
            page.openMutexes(harness)

            val row = page.mutexRow("kotlin-build")
            assertThat(row.locator(".mutex-holder")).hasText("held by builder")
            assertThat(row.locator(".mutex-holder a")).hasAttribute("href", "/s/s-builder")
            assertThat(row.locator(".mutex-waiter")).hasCount(1)
            assertThat(row.locator(".mutex-waiter .mutex-position")).hasText("#1")
            assertThat(row.locator(".mutex-waiter .mutex-session")).hasText("tester")
            assertThat(page.locator("#mutexes-summary")).hasText("1 held · 1 waiting")

            row.locator(".mutex-force-release").click()
            val dialog = page.locator("#force-release-dialog")
            assertThat(dialog).isVisible()
            assertThat(page.locator("#force-release-key")).hasText("kotlin-build")
            assertThat(page.locator("#force-release-holder")).hasText("held by builder")
            assertThat(page.locator("#force-release-cancel")).isFocused()
            page.locator("#force-release-cancel").click()
            assertThat(dialog).hasCount(0)
            assertThat(row.locator(".mutex-holder")).hasText("held by builder")

            row.locator(".mutex-force-release").click()
            page.locator("#force-release-submit").click()
            assertThat(dialog).hasCount(0)
            assertThat(page.locator("#mutexes-status")).hasText("Released kotlin-build.")
            assertThat(row.locator(".mutex-holder")).hasText("held by tester")
            assertThat(row.locator(".mutex-waiter")).hasCount(0)
            assertEquals(emptyList(), nativeDialogs.toList(), "force release never opens a browser dialog")
        }

    @Test
    fun aHolderThatChangedWhileTheDialogWasOpenIsNotReleased() =
        mutexPage("mutexes-force-release-race") { harness, page ->
            harness.send("mutex-acquire s-builder kotlin-build")
            harness.send("mutex-wait s-tester kotlin-build")
            page.openMutexes(harness)
            val row = page.mutexRow("kotlin-build")
            assertThat(row.locator(".mutex-waiter")).hasCount(1)

            row.locator(".mutex-force-release").click()
            harness.send("mutex-release kotlin-build")
            assertThat(row.locator(".mutex-holder")).hasText("held by tester")
            page.locator("#force-release-submit").click()
            assertThat(page.locator("#force-release-error")).containsText("is now held by tester")
            assertThat(row.locator(".mutex-holder")).hasText("held by tester")

            page.locator("#force-release-cancel").click()
            row.locator(".mutex-force-release").click()
            harness.send("mutex-release kotlin-build")
            assertThat(row).hasCount(0)
            page.locator("#force-release-submit").click()
            assertThat(page.locator("#force-release-dialog")).hasCount(0)
            assertThat(page.locator("#mutexes-status")).hasText("kotlin-build was no longer held.")
            assertThat(page.locator("#mutexes-empty")).isVisible()
        }

    @Test
    fun theTerminalHeadShowsTheOpenSessionsHoldingsAndPlaceInLine() =
        mutexPage("mutexes-pills") { harness, page ->
            page.navigate(harness.baseUrl + "/s/s-builder")
            page.awaitSessionView()
            val pills = page.locator("#terminal-head .mutex-pill")
            assertThat(pills).hasCount(0)

            harness.send("mutex-acquire s-builder kotlin-build")
            assertThat(pills).hasCount(1)
            assertThat(pills.first()).hasText("🔒 kotlin-build")
            assertThat(pills.first()).hasAttribute("data-kind", "held")

            harness.send("mutex-acquire s-deployer deploy")
            harness.send("mutex-wait s-tester deploy")
            harness.send("mutex-wait s-builder deploy")
            assertThat(pills).hasCount(2)
            assertThat(page.locator("#terminal-head .mutex-pill[data-kind='waiting']")).hasText("⏳ deploy · #2")

            page.navigate(harness.baseUrl + "/s/s-tester")
            page.awaitSessionView()
            assertThat(pills).hasCount(1)
            assertThat(pills.first()).hasText("⏳ deploy · #1")

            page.locator("#session-details-toggle").click()
            pills.first().click()
            assertThat(page).hasURL(harness.baseUrl + "/mutexes")
            assertThat(page.locator("main.mutexes-screen")).isVisible()
            assertThat(page.mutexRow("deploy").locator(".mutex-waiter")).hasCount(2)

            page.goBack(Page.GoBackOptions().setWaitUntil(WaitUntilState.COMMIT))
            page.awaitSessionView()
            harness.send("mutex-end s-tester")
            assertThat(pills).hasCount(0)
        }

    @Test
    fun aColdDeepLinkOpensTheListAndItsHolderLinkOpensTheSession() =
        mutexPage("mutexes-deep-link") { harness, page ->
            harness.send("mutex-acquire s-builder kotlin-build")
            page.openMutexes(harness)
            val holder = page.mutexRow("kotlin-build").locator(".mutex-holder a")
            assertThat(holder).hasText("builder")

            page.reload()
            assertThat(page.locator("main.mutexes-screen")).isVisible(visibleWithin(BOOT_TIMEOUT_MS))
            assertThat(holder).hasText("builder")
            assertThat(page).hasURL(harness.baseUrl + "/mutexes")

            holder.click()
            assertThat(page).hasURL(harness.baseUrl + "/s/s-builder")
            page.awaitSessionView()
            assertThat(page.locator("#terminal-title")).hasText("builder")
            assertThat(page.locator("#terminal-head .mutex-pill")).hasText("🔒 kotlin-build")
        }

    @Test
    fun aReconnectSnapshotCarriesWhatChangedWhileTheDaemonWasAway() =
        mutexPage("mutexes-reconnect", recordFrames = true) { harness, page ->
            harness.send("mutex-acquire s-builder kotlin-build")
            page.openMutexes(harness)
            assertThat(page.mutexRow("kotlin-build").locator(".mutex-holder")).hasText("held by builder")
            page.waitForCondition { page.frameCount("mutexes_snapshot") == 1 }
            val updates = page.frameCount("mutex_update")

            harness.send("restart")
            harness.send("mutex-release kotlin-build")
            harness.send("mutex-acquire s-tester deploy")

            page.waitForCondition { page.frameCount("mutexes_snapshot") == 2 }
            assertThat(page.mutexRow("kotlin-build")).hasCount(0)
            assertThat(page.mutexRow("deploy").locator(".mutex-holder")).hasText("held by tester")
            assertEquals(updates, page.frameCount("mutex_update"), "the recovery snapshot carried the changes")
        }

    @Test
    fun aHoldingOverFifteenMinutesIsHighlightedByDaemonAgeAndThenByLocalTime() =
        mutexPage("mutexes-held-long", clockAt = PHONE_CLOCK) { harness, page ->
            harness.send("usage-clock $DAEMON_EPOCH")
            harness.send("mutex-acquire s-builder old")
            harness.send("usage-clock ${DAEMON_EPOCH + 20 * MINUTE}")
            harness.send("mutex-acquire s-tester fresh")
            page.openMutexes(harness)

            val old = page.mutexRow("old")
            val fresh = page.mutexRow("fresh")
            assertThat(old).hasAttribute("data-long", "true")
            assertThat(old.locator(".mutex-long-flag")).hasText("held over 15 min")
            assertThat(old.locator(".mutex-duration")).hasText(Pattern.compile("^20m \\d\\ds$"))
            assertThat(fresh).isVisible()
            assertThat(fresh).not().hasAttribute("data-long", "true")
            assertThat(fresh.locator(".mutex-duration")).hasText(Pattern.compile("^\\ds$"))

            page.clock().pauseAt(PHONE_CLOCK + MINUTE)
            page.clock().runFor(16 * MINUTE)
            assertThat(fresh).hasAttribute("data-long", "true")
            assertThat(fresh.locator(".mutex-duration")).hasText(Pattern.compile("^16m \\d\\ds$"))
            assertThat(old.locator(".mutex-duration")).hasText(Pattern.compile("^36m \\d\\ds$"))
        }
}

private fun mutexPage(
    trace: String,
    recordFrames: Boolean = false,
    clockAt: Long? = null,
    block: (Harness, Page) -> Unit,
) {
    Harness(MUTEXES_SCENARIO).use { harness ->
        onChromium { browser ->
            browser.fineContext().use { context ->
                context.traced(trace) {
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    val page = context.newPage()
                    if (recordFrames) page.addInitScript(FRAME_RECORDER)
                    if (clockAt != null) page.clock().install(Clock.InstallOptions().setTime(clockAt))
                    block(harness, page)
                }
            }
        }
    }
}

private fun Page.openMutexes(harness: Harness) {
    navigate(harness.baseUrl + "/mutexes")
    assertThat(locator("main.mutexes-screen")).isVisible(visibleWithin(BOOT_TIMEOUT_MS))
    assertThat(locator("#mutexes-summary")).not().hasText("Loading…")
}

private fun Page.mutexRow(key: String): Locator = locator(".mutex-row[data-key='$key']")

private fun Page.frameCount(type: String): Int = (evaluate("""
    type => (window.__kotgentFrames || []).filter((raw) => JSON.parse(raw).type === type).length
""".trimIndent(), type) as Number).toInt()

private const val MINUTE: Long = 60_000
private const val DAEMON_EPOCH: Long = 1_800_000_000_000L

/** Hours away from the daemon clock: durations must not read the page's wall clock. */
private const val PHONE_CLOCK: Long = DAEMON_EPOCH - 12 * 60 * MINUTE
