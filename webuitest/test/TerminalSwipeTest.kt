package io.kotgent.webuitest

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.microsoft.playwright.CDPSession
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.BoundingBox
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The phone swipe → xterm wheel bridge (`installSwipeScroll` in `components/TerminalPane.js`), executed
 * instead of grepped.
 *
 * A finger reaches xterm through nothing of its own. xterm 5.5 ran its native touch scrolling ONLY while
 * mouse tracking was off, and 6.0 removed the terminal element's `touchstart`/`touchmove` handlers
 * altogether when the viewport moved onto VS Code's scrollable element. Kotgent keeps tracking on so a
 * desktop wheel reaches tmux's pane history, so on a phone this bridge is the only path a swipe has —
 * which is why its gesture-ownership rules are worth a test at all.
 *
 * WHAT THE GESTURE IS HERE. Playwright's `touchscreen()` offers taps, not drags, so a swipe is driven as a
 * CDP `Input.dispatchTouchEvent` sequence: one `touchStart`, several `touchMove`s along real coordinates
 * inside `.xterm-screen`, one `touchEnd`. That is deliberately NOT `element.dispatchEvent(new
 * PointerEvent(...))`, which the plan's spike already recorded as proving only "our listeners answer
 * invented events". Two things make the difference load-bearing rather than stylistic: Chromium turns CDP
 * touch into genuine `pointerType: "touch"` pointer events, so the bridge's `setPointerCapture` finds an
 * ACTIVE pointer (a made-up `pointerId` throws `NotFoundError` out of the pointerdown handler and leaves
 * the bridge inert before it ever records a gesture), and the `touch-action: none` reservation, the
 * capture retargeting and the compatibility mouse burst are then the browser's own, not ours.
 *
 * What it still is not is a finger on glass: no contact geometry, no jitter, no platform gesture
 * recognizer competing for the stream, and Chromium is not WebKit. The measurements CLAUDE.md records from
 * a real iPhone — that an uncaptured pointer delivered 1-2 reports per gesture over repainting rows, that
 * `pinch-zoom`/`auto` let the browser eat the gesture entirely, that after a swipe neither xterm's
 * `mousedown` focus nor the pane's click handler runs — remain real-device facts. This file cannot
 * re-measure them; it pins the behaviour that survives translation to a desktop engine.
 *
 * WHAT IS OBSERVABLE IN THIS HARNESS. The pane behind this pty is `cat`, not tmux, and the browser
 * terminal runs with `scrollback: 0` — so there is no local history to move, and "the viewport scrolled"
 * is not an available observable here (nor in production: the history lives in the tmux pane, which is
 * exactly why the wheel has to leave the browser at all). What a claimed swipe produces is mouse REPORTS,
 * and they are watched at three independent points:
 *
 *  1. the `wheel` events the bridge dispatches, caught by a capture-phase probe installed on `window`
 *     before the page loads — this is where the rate (one line per report), the `DOM_DELTA_LINE` unit and
 *     the direction are readable exactly;
 *  2. the bytes the browser then sends on the terminal WebSocket — proof the reports became real SGR mouse
 *     input for the daemon under the pane's active protocol, rather than events xterm dropped;
 *  3. the characters that come back. The scenario's pty is a plain `openpty` with default termios, i.e.
 *     cooked mode with ECHO and ECHOCTL, so the line discipline echoes the report and its `ESC` arrives
 *     as a printable `^[`. That is what turns an invisible control sequence into on-screen text an
 *     assertion can read, and it is the closest thing to "visible scroll" this fixture can honestly offer.
 *
 * Delete `installSwipeScroll` and all three go silent, so none of these assertions is satisfiable without
 * the code under test. The scenario's payload enables SGR (`?1006h`) BEFORE the tracker (`?1000h`) so the
 * reports ride `term.onData` as ASCII rather than `term.onBinary` as raw bytes above 127.
 *
 * Replaces `WebUiServingTest.theWebUiBridgesPhoneSwipesIntoXtermWheelEvents`.
 */
class TerminalSwipeTest {

    /**
     * A vertical swipe is claimed, and every report it makes is one LINE in the direction of the finger.
     *
     * The rate is the interesting half. One report per ROW is deliberate even though tmux's own copy-mode
     * binding is `send-keys -X -N 5 scroll-up` and therefore moves five lines per report: what a report is
     * worth depends on who consumes it, and the browser cannot tell them apart, because tmux keeps mouse
     * reporting enabled on the client either way while the PANE decides. A quiet pane enters copy-mode
     * (five lines); an agent pane forwards the wheel to its full-screen TUI (typically one). Converting at
     * copy-mode's rate made the agent pane — the common case — scroll five times too slowly. The count
     * band below is what makes that measurable from outside: five-lines-per-report would emit a fifth of
     * the reports and land far under the lower bound.
     *
     * Both directions run, because the sign is a real contract: the bank is `lastY - clientY`, so a finger
     * moving UP scrolls the content DOWN (positive `deltaY`, SGR button 65) and vice versa (64). An
     * inverted bridge would still produce a plausible-looking burst of reports.
     */
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
            // The pty's line discipline echoes what the browser sent, ESC included as a printable `^[`,
            // so this waits on the whole round trip rather than sleeping for it.
            f.waitForEcho("[<65;")
            f.settle()

            val up = f.wheels()
            assertTrue(up.total > 0, "a vertical swipe must reach xterm as wheel events")
            assertEquals(
                0,
                up.trusted,
                "the reports are the bridge's own dispatches; a trusted wheel would mean the browser " +
                    "scrolled something itself, which is what `touch-action: none` exists to prevent",
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
                f.wire().contains("[<65;"),
                "an SGR wheel-down report reached the daemon on the terminal socket",
            )

            f.resetWheels()
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
            assertTrue(
                f.wire().contains("[<64;"),
                "the opposite SGR button reached the daemon, so the sign survives the whole path",
            )
        }

    /**
     * The direction lock, from both sides in one test.
     *
     * A gesture is claimed only when its vertical travel beats both the 6px slop AND its own horizontal
     * travel, and that test is a LOCK made once, at claim time, the way a native sheet behaves — a sweep
     * across the terminal cannot claim the gesture by drifting past the slop. The horizontal swipe here
     * therefore carries a real 40px of vertical drift: "no vertical movement at all" would pass against a
     * bridge that only checked the slop.
     *
     * The second half is the control that keeps the first from being vacuous. "No reports" is exactly what
     * a broken terminal, a dead socket or a CDP sequence the browser never delivered would also produce,
     * so the same start point is then swiped vertically and must report — proving the gesture stream was
     * live all along and the silence was the lock, not the fixture.
     */
    @Test
    fun aHorizontalSwipeIsNotClaimedWhileTheSameStartPointStillScrollsVertically() =
        onTerminal("terminal-swipe-horizontal") { f ->
            val box = screenBox(f.page)
            val startX = box.x + box.width * 0.08
            val startY = box.y + box.height * 0.45

            f.swipe(startX, startY, box.x + box.width * 0.92, startY + 40.0)
            f.settle()

            val across = f.wheels()
            assertEquals(
                0,
                across.total,
                "a predominantly horizontal sweep is not a scroll, even with vertical drift past the slop",
            )
            assertTrue(
                !f.wire().contains("[<64;") && !f.wire().contains("[<65;"),
                "and no wheel report reached the daemon either",
            )

            f.resetWheels()
            f.swipe(startX, startY, startX, startY - box.height * 0.35)
            f.waitForEcho("[<65;")
            f.settle()

            assertTrue(
                f.wheels().total > 0,
                "the same start point still scrolls when the gesture is vertical, so the silence above " +
                    "was the direction lock rather than a terminal that never saw the touch",
            )
        }

    /**
     * A claimed swipe must not summon the software keyboard, which on iOS opens only when xterm's helper
     * textarea takes focus.
     *
     * What actually keeps it shut on a real iPhone is the bridge's `preventDefault()` on a claimed move,
     * which suppresses the whole compatibility mouse burst — measured there, after a swipe neither xterm's
     * own `mousedown` focus nor the pane's click handler runs at all. `shouldFocus()`'s 350ms suppression
     * is a second line for a browser that still delivers a click, not the mechanism.
     *
     * This test cannot attribute the result to either half, and says so: Chromium's own touch slop already
     * withholds the tap gesture (and therefore the click) from a drag this long, so "focus did not move"
     * has more than one possible author here. What keeps it from being vacuous is the pairing. The tap
     * first proves this fixture CAN observe focus arriving in the helper textarea — the unclaimed gesture
     * that is supposed to focus, which is also why `preventDefault()` may only run after the claim gate.
     * Then focus is parked on a header button, and the swipe must both report (so it was genuinely claimed
     * and reached the bridge) and leave that focus alone.
     */
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

            // Park focus outside the terminal on a control the phone header always renders, so "focus did
            // not move into the textarea" is a statement about this gesture and not about the page never
            // having had focus anywhere.
            f.page.locator("#palette-button").focus()
            assertEquals("palette-button", f.activeElement(), "focus starts outside the terminal")

            f.resetWheels()
            f.swipe(x, box.y + box.height * 0.9, x, box.y + box.height * 0.4)
            f.waitForEcho("[<65;")
            f.settle()

            assertTrue(f.wheels().total > 0, "the swipe was claimed, so the bridge did see this gesture")
            // The invariant is about the textarea specifically, not about focus never moving: the keyboard
            // opens on iOS when xterm's helper textarea takes focus and at no other moment.
            val focused = f.activeElement()
            assertTrue(
                focused != "xterm-helper-textarea",
                "a claimed swipe must not move focus into xterm's helper textarea — that, and only that, " +
                    "is what opens the software keyboard on iOS; focus ended on <$focused>",
            )
        }
}

/** The scenario whose pty prints the mouse-mode payload and then `exec cat`s. */
private const val TERMINAL_SCENARIO = "terminal"

/** Its single session (see the plan's scenario table): `s-term`, claude, `/w/terminal`, running. */
private const val TERMINAL_SESSION = "s-term"

/** Time for the bridge's frame loop to spend the bank and for the echo tail to land. */
private const val SETTLE_MS = 250.0

/**
 * Every `wheel` event the page sees, recorded before any application script runs.
 *
 * Capture phase on `window`, so it sees the bridge's `dispatchEvent` on the `.xterm` element regardless of
 * bubbling, and it sees it BEFORE xterm's own `{ passive: false }` listener consumes it. `isTrusted` is
 * recorded because it discriminates the bridge's synthetic report from a wheel the browser itself
 * generated — the second would mean the terminal's `touch-action` reservation had leaked the gesture.
 */
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

/**
 * The terminal is ready when the banner — the LAST thing the payload prints — has been rendered and xterm
 * has entered a mouse protocol. The second half is not decoration: with `mouseTrackingMode === "none"` the
 * bridge yields the gesture on purpose, so a test that swiped before the tracker arrived would observe the
 * documented no-op and blame the bridge.
 */
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

    /**
     * Every input byte the browser has sent on the terminal socket, one byte per char, with `ESC` spelled
     * `^[` — the same rendering the pty's own echo produces, which keeps a failure message printable
     * without hiding what went out.
     */
    fun wire(): String = sent.joinToString("").replace("\u001b", "^[")

    fun activeElement(): String = page.evaluate(
        "() => { const a = document.activeElement; return a ? (a.id || a.className || a.tagName) : ''; }",
    ) as String

    /**
     * Wait until [needle] has been echoed back into the rendered grid.
     *
     * The bound is short and the failure names the assumption, because this is the one observation that
     * rests on the pty's termios rather than on the browser: `openpty` hands out the kernel default, i.e.
     * cooked mode with ECHO and ECHOCTL, so a mouse report typed at the master is echoed by the line
     * discipline with its ESC rendered as a printable `^[`. Without ECHOCTL the same report would be
     * echoed as a real control sequence, which xterm parses as an unhandled CSI and draws as nothing at
     * all — a silence the diagnostics below have to distinguish from "no report was ever sent".
     */
    fun waitForEcho(needle: String) {
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

    /**
     * One finger, from (fromX, fromY) to (toX, toY), as real browser touch input.
     *
     * The pause before the lift is not padding. A still-moving finger is a THROW: the bridge keeps the
     * frame loop running under a decaying velocity for up to 1200ms, which would make the report count a
     * function of CDP round-trip timing rather than of the travel. A finger that rested first means "stop
     * here" (`inertiaHandoffMs`), so the count becomes travel/rowHeight and can be asserted.
     */
    fun swipe(fromX: Double, fromY: Double, toX: Double, toY: Double) {
        val steps = 8
        touch("touchStart", fromX, fromY)
        page.waitForTimeout(16.0)
        for (step in 1..steps) {
            val fraction = step.toDouble() / steps
            touch("touchMove", fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction)
            page.waitForTimeout(16.0)
        }
        page.waitForTimeout(150.0)
        touch("touchEnd", toX, toY)
    }

    private fun touch(type: String, x: Double, y: Double) {
        val points = JsonArray()
        // Chromium rejects a `touchEnd` that still names points: the release is expressed by their
        // absence, which is also how Playwright's own `touchscreen.tap()` ends a tap.
        if (type != "touchEnd") {
            val point = JsonObject()
            point.addProperty("x", x)
            point.addProperty("y", y)
            point.addProperty("id", 0)
            points.add(point)
        }
        val params = JsonObject()
        params.addProperty("type", type)
        params.add("touchPoints", points)
        cdp.send("Input.dispatchTouchEvent", params)
    }
}

private fun screenBox(page: Page): BoundingBox = assertNotNull(
    page.locator("#terminal-host .xterm-screen").boundingBox(),
    "the terminal's character grid must be laid out before a gesture can name coordinates in it",
)

/**
 * The number of rows xterm is currently rendering. The DOM renderer emits one element per row inside
 * `.xterm-rows`, and `.xterm-screen`'s height divided by that is the same `rowHeight` the bridge itself
 * computes — so the expected report count is derived exactly the way the code under test derives it.
 */
private fun visibleRows(page: Page): Double {
    val counted = page.evaluate("() => document.querySelectorAll('#terminal-host .xterm-rows > div').length")
    val rows = (counted as Number).toInt()
    assertTrue(rows > 5, "the terminal must render a real grid, got $rows rows")
    return rows.toDouble()
}

/**
 * A logged-in phone-sized browser on the `terminal` scenario, attached to its one session and settled at
 * the payload banner, with the wheel probe installed and the terminal socket's outgoing frames recorded.
 *
 * The route does the attaching: `/s/{id}` selects the session, and a `running` one is alive, so the
 * terminal opens without a click into the mobile drawer.
 */
private fun onTerminal(name: String, body: (TerminalSwipeFixture) -> Unit) {
    Harness(TERMINAL_SCENARIO).use { harness ->
        Playwright.create().use { pw ->
            touchChromium(pw).use { browser ->
                browser.touchContext().use { context ->
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    context.traced(name) {
                        val page = context.newPage()
                        page.addInitScript(WHEEL_PROBE)
                        val sent = CopyOnWriteArrayList<String>()
                        page.onWebSocket { socket ->
                            if (socket.url().contains("/terminal")) {
                                socket.onFrameSent { frame ->
                                    // ISO-8859-1 keeps the mapping byte-for-char, so an ESC in the frame
                                    // stays one character and the SGR report is searchable as written.
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
}
