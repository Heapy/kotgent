package io.kotgent.webuitest

import com.microsoft.playwright.CDPSession
import com.microsoft.playwright.Page
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.BoundingBox
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class TerminalSwipeTest {

    @Test
    fun aVerticalSwipeReportsOneLinePerRowInTheDirectionOfTheFinger() =
        onTerminal("terminal-swipe-vertical") { f ->
            val box = screenBox(f.page)
            val rowHeight = box.height / visibleRows(f.page)
            val travel = box.height * 0.5
            val expected = travel / rowHeight
            val x = box.x + box.width / 2
            val low = box.y + box.height * 0.9
            val high = low - travel

            f.swipe(x, low, x, high)
            f.waitForEcho("[<65;")
            f.settle()

            val up = f.wheels()
            assertTrue(up.total > 0, "a vertical swipe must reach xterm as wheel events")
            assertEquals(
                "none",
                f.touchActionOfTheTerminal(),
                "the terminal must reserve the vertical gesture unconditionally, or the browser claims " +
                    "the swipe before a single `pointermove` reaches the bridge",
            )
            assertEquals(
                0,
                up.trusted,
                "every report in the log is the bridge's own dispatch, so the counts below are about the " +
                    "bridge's rate and not about anything the browser contributed",
            )
            assertEquals(
                0,
                up.nonLine,
                "every report is line-based (DOM_DELTA_LINE), so xterm converts it with its own cell " +
                    "metrics instead of a pixel delta this side would have to guess",
            )
            assertEquals(0, up.nonUnit, "one line per report — the row-for-row rate")
            assertEquals(
                up.total,
                up.positive,
                "a finger moving up scrolls the content down, so every delta is positive",
            )
            assertTrue(
                expected >= SWIPE_STEPS * 2.5,
                "this grid gives a ${travel.toInt()}px swipe only ${expected.toInt()} rows of travel " +
                    "against $SWIPE_STEPS touch moves, so the band below no longer separates the row-for-" +
                    "row rate from one report per move",
            )
            assertTrue(
                up.total >= expected * 0.6,
                "a ${travel.toInt()}px swipe over ${rowHeight.toInt()}px rows is about " +
                    "${expected.toInt()} rows of travel, got ${up.total} reports — converting at tmux's " +
                    "copy-mode rate of five lines per report would land here",
            )
            assertTrue(
                up.total <= expected * 1.4 + 2,
                "the bank is spent row for row, not multiplied: expected about ${expected.toInt()} " +
                    "reports, got ${up.total}",
            )
            assertTrue(
                !f.wire().contains("[<64;"),
                "the upward swipe sent an opposite-direction report as well; the wire was: ${f.wire()}",
            )

            f.resetWheels()
            val alreadySent = f.wire().length
            f.swipe(x, high, x, low)
            f.waitForEcho("[<64;")
            f.settle()

            val down = f.wheels()
            assertTrue(down.total > 0, "the reverse swipe reports too")
            assertEquals(
                down.total,
                down.negative,
                "a finger moving down scrolls the content up, so every delta is negative",
            )
            val reverse = f.wire().substring(alreadySent)
            assertTrue(
                reverse.contains("[<64;") && !reverse.contains("[<65;"),
                "the reverse gesture must send the opposite SGR button and only that one, sent: $reverse",
            )
        }

    @Test
    fun aHorizontalSwipeIsNotClaimedWhileTheSameStartPointStillScrollsVertically() =
        onTerminal("terminal-swipe-horizontal") { f ->
            val box = screenBox(f.page)
            val startX = box.x + box.width * 0.08
            val startY = box.y + box.height * 0.45

            f.swipe(startX, startY, startX, startY - box.height * 0.35)
            f.waitForEcho("[<65;")
            f.settle()
            assertTrue(
                f.wheels().total > 0,
                "the fixture must be able to report at all before an absence below can mean anything",
            )

            f.resetWheels()
            val alreadySent = f.wire().length
            f.swipe(startX, startY, box.x + box.width * 0.92, startY + 40.0)
            f.settle()

            val across = f.wheels()
            assertEquals(
                0,
                across.total,
                "a predominantly horizontal sweep is not a scroll, even with vertical drift past the slop",
            )
            val sweep = f.wire().substring(alreadySent)
            assertTrue(
                !sweep.contains("[<64;") && !sweep.contains("[<65;"),
                "and no wheel report reached the daemon either, sent: $sweep",
            )
        }

    @Test
    fun aClaimedSwipeLeavesTheKeyboardShutThatATapOpens() =
        onTerminal("terminal-swipe-keyboard") { f ->
            val box = screenBox(f.page)
            val x = box.x + box.width / 2

            f.page.touchscreen().tap(x, box.y + box.height / 2)
            f.page.waitForFunction(
                "() => !!document.activeElement && " +
                    "document.activeElement.classList.contains('xterm-helper-textarea')",
            )
            assertEquals(
                0,
                f.wheels().total,
                "a tap is not a swipe: it is never claimed and reports nothing",
            )

            f.page.locator("#palette-button").focus()
            assertEquals("palette-button", f.activeElement(), "focus starts outside the terminal")

            f.resetWheels()
            f.swipe(x, box.y + box.height * 0.9, x, box.y + box.height * 0.4)
            f.waitForEcho("[<65;")
            f.settle()

            assertTrue(f.wheels().total > 0, "the swipe was claimed, so the bridge did see this gesture")
            val focused = f.activeElement()
            assertTrue(
                focused != "xterm-helper-textarea",
                "a claimed swipe must not move focus into xterm's helper textarea — that, and only that, " +
                    "is what opens the software keyboard on iOS; focus ended on <$focused>",
            )

            f.resetWheels()
            f.swipe(x, box.y + box.height * 0.9, x, box.y + box.height * 0.4, restMillis = 0.0)
            f.page.locator(TERMINAL_HOST).dispatchEvent("click")
            assertTrue(
                f.activeElement() != "xterm-helper-textarea",
                "a click delivered within 350ms of a claimed swipe was answered with focus, so a browser " +
                    "that does complete the tap after a drag would open the keyboard the swipe suppressed",
            )
            f.settle()
            assertTrue(
                f.wheels().total > 0,
                "that swipe has to have been CLAIMED for the refusal above to be the suppression window " +
                    "— an unclaimed gesture arms nothing and would have focused",
            )

            f.page.waitForTimeout(FOCUS_SUPPRESSION_MS)
            f.page.locator(TERMINAL_HOST).dispatchEvent("click")
            f.page.waitForFunction(
                "() => !!document.activeElement && " +
                    "document.activeElement.classList.contains('xterm-helper-textarea')",
            )
        }

    @Test
    fun aLegacyEncodedMouseReportLeavesOnTermOnBinaryWithItsHighBytesIntact() {
        Harness(TERMINAL_X10_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext(LEGACY_MOUSE_WIDTH, LEGACY_MOUSE_HEIGHT).use { context ->
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    context.traced("terminal-legacy-mouse") {
                        val page = context.newPage()
                        val sent = CopyOnWriteArrayList<ByteArray>()
                        page.onWebSocket { socket ->
                            if (socket.url().contains("/terminal")) {
                                socket.onFrameSent { frame -> frame.binary()?.let { sent.add(it) } }
                            }
                        }
                        page.navigate(harness.baseUrl + "/s/" + LEGACY_MOUSE_SESSION)
                        page.waitForFunction(LEGACY_MOUSE_TERMINAL_READY)

                        val box = screenBox(page)
                        page.mouse().click(box.x + box.width - LEGACY_MOUSE_EDGE_INSET, box.y + box.height / 2)
                        page.waitForCondition { sent.isNotEmpty() }

                        val report = sent.first()
                        assertEquals(
                            listOf(0x1b, '['.code, 'M'.code),
                            report.take(3).map { it.toInt() and 0xff },
                            "a DEFAULT-encoded report is `ESC [ M` and then three coordinate bytes; got " +
                                report.joinToString(" ") { (it.toInt() and 0xff).toString(16) },
                        )
                        assertEquals(
                            6,
                            report.size,
                            "six bytes, or the payload went through a TextEncoder and the high coordinate " +
                                "became two: " + report.joinToString(" ") { (it.toInt() and 0xff).toString(16) },
                        )
                        val x = report[4].toInt() and 0xff
                        assertTrue(
                            x > 0x7f,
                            "the click has to land past column $LEGACY_MOUSE_MIN_COLUMNS for the narrowing " +
                                "to be under test at all: the x byte was $x, i.e. column ${x - 32} of a " +
                                "grid ${LEGACY_MOUSE_WIDTH}px wide",
                        )
                    }
                }
            }
        }
    }
}

private const val LEGACY_MOUSE_SESSION = "s-x10"
private const val LEGACY_MOUSE_WIDTH = 1400
private const val LEGACY_MOUSE_HEIGHT = 900

private const val LEGACY_MOUSE_MIN_COLUMNS = 96

private const val LEGACY_MOUSE_EDGE_INSET = 4.0

private val LEGACY_MOUSE_TERMINAL_READY = """
    () => {
      const rows = document.querySelector("#terminal-host .xterm-rows");
      const screen = document.querySelector("#terminal-host .xterm");
      return !!rows && !!screen &&
        rows.textContent.includes("KOTGENT-X10-READY") &&
        screen.classList.contains("enable-mouse-events");
    }
""".trimIndent()

private const val TERMINAL_SESSION = "s-term"

private const val SETTLE_MS = 250.0

private const val TERMINAL_HOST = "#terminal-host"

private const val FOCUS_SUPPRESSION_MS = 500.0

private const val SWIPE_STEPS = 8

// Resting removes inertia, making report count a function of travel rather than CDP timing.
private const val REST_BEFORE_LIFT_MS = 150.0

// Capture before xterm's listener consumes the bridge's synthetic wheel event.
private val WHEEL_PROBE = """
    window.__kotgentWheels = [];
    window.addEventListener("wheel", (event) => {
      window.__kotgentWheels.push({
        deltaY: event.deltaY,
        deltaMode: event.deltaMode,
        trusted: event.isTrusted,
      });
    }, { capture: true, passive: true });
""".trimIndent()

// Mouse tracking must be active; otherwise the bridge intentionally yields the gesture.
private val TERMINAL_READY = """
    () => {
      const rows = document.querySelector("#terminal-host .xterm-rows");
      const screen = document.querySelector("#terminal-host .xterm");
      return !!rows && !!screen &&
        rows.textContent.includes("KOTGENT-TERMINAL-READY") &&
        screen.classList.contains("enable-mouse-events");
    }
""".trimIndent()

private class SwipeWheelLog(
    val total: Int,
    val positive: Int,
    val negative: Int,
    val nonLine: Int,
    val trusted: Int,
    val nonUnit: Int,
)

private class TerminalSwipeFixture(
    val page: Page,
    private val cdp: CDPSession,
    private val sent: List<String>,
) {

    fun wire(): String = sent.joinToString("").replace("\u001b", "^[")

    fun activeElement(): String = page.evaluate(
        "() => { const a = document.activeElement; return a ? (a.id || a.className || a.tagName) : ''; }",
    ) as String

    fun touchActionOfTheTerminal(): String = page.evaluate(
        "() => getComputedStyle(document.querySelector('$TERMINAL_HOST .xterm')).touchAction",
    ) as String

    fun waitForEcho(needle: String) {
        // Darwin's default ECHOCTL makes the PTY echo mouse-report ESC bytes as printable `^[`.
        try {
            page.waitForFunction(
                "(needle) => document.querySelector('#terminal-host .xterm-rows').textContent.includes(needle)",
                needle,
                Page.WaitForFunctionOptions().setTimeout(10_000.0),
            )
        } catch (timedOut: TimeoutError) {
            fail(
                "no <$needle> echo reached the rendered terminal within 10s. What the browser sent on the " +
                    "terminal socket meanwhile: <${wire()}> (${timedOut.message})",
            )
        }
    }

    fun settle() = page.waitForTimeout(SETTLE_MS)

    fun resetWheels() {
        page.evaluate("() => { window.__kotgentWheels.length = 0; }")
    }

    fun wheels(): SwipeWheelLog {
        val raw = page.evaluate(
            """
            () => {
              const w = window.__kotgentWheels;
              return [
                w.length,
                w.filter((e) => e.deltaY > 0).length,
                w.filter((e) => e.deltaY < 0).length,
                w.filter((e) => e.deltaMode !== 1).length,
                w.filter((e) => e.trusted).length,
                w.filter((e) => Math.abs(e.deltaY) !== 1).length,
              ].join(",");
            }
            """.trimIndent(),
        ) as String
        val counts = raw.split(",").map { it.trim().toInt() }
        return SwipeWheelLog(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5])
    }

    fun swipe(
        fromX: Double,
        fromY: Double,
        toX: Double,
        toY: Double,
        restMillis: Double = REST_BEFORE_LIFT_MS,
    ) {
        val steps = SWIPE_STEPS
        touch("touchStart", fromX, fromY)
        page.waitForTimeout(16.0)
        for (step in 1..steps) {
            val fraction = step.toDouble() / steps
            touch("touchMove", fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction)
            page.waitForTimeout(16.0)
        }
        // Zero is reserved for the focus-suppression timing case; normal swipes rest to shed inertia.
        page.waitForTimeout(restMillis)
        touch("touchEnd", toX, toY)
    }

    private fun touch(type: String, x: Double, y: Double) {
        if (type == "touchEnd") dispatchTouch(cdp, type, null, null) else dispatchTouch(cdp, type, x, y)
    }
}

private fun screenBox(page: Page): BoundingBox = assertNotNull(
    page.locator("#terminal-host .xterm-screen").boundingBox(),
    "the terminal's character grid must be laid out before a gesture can name coordinates in it",
)

private fun visibleRows(page: Page): Double {
    val counted = page.evaluate("() => document.querySelectorAll('#terminal-host .xterm-rows > div').length")
    val rows = (counted as Number).toInt()
    assertTrue(rows > 5, "the terminal must render a real grid, got $rows rows")
    return rows.toDouble()
}

private fun onTerminal(name: String, body: (TerminalSwipeFixture) -> Unit) {
    Harness(TERMINAL_SCENARIO).use { harness ->
        onChromium { browser ->
            browser.touchContext().use { context ->
                context.loginWithTicket(harness.ticket, harness.baseUrl)
                context.traced(name) {
                    val page = context.newPage()
                    page.addInitScript(WHEEL_PROBE)
                    val sent = CopyOnWriteArrayList<String>()
                    page.onWebSocket { socket ->
                        if (socket.url().contains("/terminal")) {
                            socket.onFrameSent { frame ->
                                // ISO-8859-1 preserves a one-character-per-byte diagnostic transcript.
                                frame.binary()?.let { sent.add(String(it, Charsets.ISO_8859_1)) }
                            }
                        }
                    }
                    page.navigate(harness.baseUrl + "/s/" + TERMINAL_SESSION)
                    page.waitForFunction(TERMINAL_READY)
                    val cdp = context.newCDPSession(page)
                    try {
                        body(TerminalSwipeFixture(page, cdp, sent))
                    } finally {
                        cdp.detach()
                    }
                }
            }
        }
    }
}
