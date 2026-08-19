package io.kotgent.webuitest

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.CDPSession
import com.microsoft.playwright.Mouse
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class DialogDismissTest {

    @Test
    fun aBackdropTapClosesADialogAndATapOnThePanelDoesNot() {
        withApp("dialog-backdrop-tap", touch = true) { _, _, page ->
            openHelp(page)

            val title = centerOf(page, "#help-title")
            page.touchscreen().tap(title.x, title.y)
            assertStaysOpen(page, HELP_DIALOG, "a tap on the panel's own title closed the dialog")

            val backdrop = backdropPoint(page, HELP_DIALOG)
            page.touchscreen().tap(backdrop.x, backdrop.y)
            assertThat(page.locator(HELP_DIALOG)).hasCount(0)
        }
    }

    @Test
    fun thePaletteTheOneDialogWithNoHeadIsDismissableByThumbAlone() {
        withApp("dialog-palette-thumb", touch = true) { _, _, page ->
            openPalette(page)

            val panel = panelPoint(page, PALETTE)
            page.touchscreen().tap(panel.x, panel.y)
            assertStaysOpen(page, PALETTE, "a tap on the palette's own panel closed it")

            val backdrop = backdropPoint(page, PALETTE)
            page.touchscreen().tap(backdrop.x, backdrop.y)
            assertThat(page.locator(PALETTE)).hasCount(0)
        }
    }

    @Test
    fun aPressThatBeginsOnThePanelAndEndsOnTheBackdropKeepsTheDialog() {
        withApp("dialog-drag-out", touch = false) { _, _, page ->
            openHelp(page)

            val inside = panelPoint(page, HELP_DIALOG)
            val backdrop = backdropPoint(page, HELP_DIALOG)

            page.mouse().move(inside.x, inside.y)
            page.mouse().down()
            page.mouse().move(backdrop.x, backdrop.y, Mouse.MoveOptions().setSteps(DRAG_STEPS))
            page.mouse().up()
            assertStaysOpen(page, HELP_DIALOG, "a press that began on the panel closed it from outside")

            page.mouse().move(backdrop.x, backdrop.y)
            page.mouse().down()
            page.mouse().move(inside.x, inside.y, Mouse.MoveOptions().setSteps(DRAG_STEPS))
            page.mouse().up()
            assertStaysOpen(page, HELP_DIALOG, "a press dragged back onto the panel still dismissed it")

            page.mouse().click(backdrop.x, backdrop.y)
            assertThat(page.locator(HELP_DIALOG)).hasCount(0)
        }
    }

    @Test
    fun aPressWhoseTargetIsTheDialogButWhoseCoordinatesAreInsideItIsRefused() {
        withApp("dialog-inside-geometry", touch = false) { _, _, page ->
            openHelp(page)
            val owned = dialogOwnedPointInsideTheBox(page, HELP_DIALOG)
            recordDialogTargets(page, HELP_DIALOG)

            page.mouse().move(owned.x, owned.y)
            page.mouse().down()
            page.mouse().up()

            assertEquals(
                listOf("pointerdown:dialog:inside", "pointerup:dialog:inside", "click:dialog:inside"),
                dialogTargets(page),
                "the press did not produce the target-is-the-dialog-but-inside case this test is about",
            )
            assertStaysOpen(
                page,
                HELP_DIALOG,
                "a press whose target was the <dialog> but whose coordinates were INSIDE the panel closed " +
                    "it — outside-ness is being decided by the target alone",
            )

            val backdrop = backdropPoint(page, HELP_DIALOG)
            page.mouse().click(backdrop.x, backdrop.y)
            assertThat(page.locator(HELP_DIALOG)).hasCount(0)
        }
    }

    @Test
    fun aDownwardSwipeOffTheGrabberDismissesWhileAShorterPullSpringsBack() {
        withApp("dialog-swipe", touch = true) { _, context, page ->
            openPalette(page)
            val cdp = context.newCDPSession(page)
            try {
                swipeCases(page, cdp)
            } finally {
                cdp.detach()
            }
        }
    }

    private fun swipeCases(page: Page, cdp: CDPSession) {
        val grabber = page.locator("$PALETTE .dialog-grabber").boundingBox()
            ?: fail("the palette drew no swipe handle in a coarse-pointer context")
        val x = grabber.x + grabber.width / 2
        val y = grabber.y + grabber.height / 2

        touchDown(cdp, page, x, y)
        touchDragTo(cdp, page, x, y, x, y + SHORT_PULL_PX)
        assertTrue(
            panelTransform(page, PALETTE).contains("translateY"),
            "a claimed swipe must carry the panel with the finger; the panel never moved",
        )
        touchUp(cdp, page)
        assertStaysOpen(page, PALETTE, "a ${SHORT_PULL_PX.toInt()}px pull dismissed the palette")
        assertEquals(
            "",
            panelTransform(page, PALETTE),
            "the panel was left under its transform instead of being sprung back",
        )

        touchDown(cdp, page, x, y)
        touchDragTo(cdp, page, x, y, x, y + LONG_PULL_PX)
        touchUp(cdp, page)
        assertThat(page.locator(PALETTE)).hasCount(0)
    }

    @Test
    fun onlyTheArmingPointersOwnReleaseCanCompleteADismissal() {
        withApp("dialog-two-pointers", touch = true) { _, _, page ->
            openHelp(page)
            val at = backdropPoint(page, HELP_DIALOG)

            pointer(page, "pointerdown", PRIMARY_ID, isPrimary = true, button = 0, at = at)
            pointer(page, "pointerup", SECOND_ID, isPrimary = false, button = 0, at = at)
            pointer(page, "click", PRIMARY_ID, isPrimary = true, button = 0, at = at)
            assertStaysOpen(page, HELP_DIALOG, "an unreleased press authorised another pointer's release")

            pointer(page, "pointerdown", SECOND_ID, isPrimary = false, button = 0, at = at)
            pointer(page, "pointerup", SECOND_ID, isPrimary = false, button = 0, at = at)
            pointer(page, "click", SECOND_ID, isPrimary = false, button = 0, at = at)
            assertStaysOpen(page, HELP_DIALOG, "a non-primary contact armed a dismissal of its own")

            pointer(page, "pointerdown", PRIMARY_ID, isPrimary = true, button = 2, at = at)
            pointer(page, "pointerup", PRIMARY_ID, isPrimary = true, button = 2, at = at)
            pointer(page, "click", PRIMARY_ID, isPrimary = true, button = 0, at = at)
            assertStaysOpen(page, HELP_DIALOG, "a secondary-button press armed a dismissal")

            pointer(page, "pointerdown", PRIMARY_ID, isPrimary = true, button = 0, at = at)
            pointer(page, "pointerup", PRIMARY_ID, isPrimary = true, button = 0, at = at)
            pointer(page, "click", PRIMARY_ID, isPrimary = true, button = 0, at = at)
            assertThat(page.locator(HELP_DIALOG)).hasCount(0)
        }
    }

    @Test
    fun aBusyScreenIgnoresTheBackdropWhileEscAndItsCloseButtonStillWork() {
        val heldWrites = AtomicInteger(0)
        withApp(
            "dialog-busy",
            touch = true,
            beforeLoad = { context ->
                context.route("**$PREFERENCES_PATH") { route: Route ->
                    if (route.request().method().equals("PUT", ignoreCase = true)) {
                        heldWrites.incrementAndGet()
                    } else {
                        route.resume()
                    }
                }
            },
        ) { harness, context, page ->
            openBusyPreferences(page)
            page.waitForCondition { heldWrites.get() >= 1 }
            val busyDismissalLabel = page.locator(PREFS_CANCEL).textContent().trim()

            val backdrop = backdropPoint(page, PREFS_DIALOG)
            page.touchscreen().tap(backdrop.x, backdrop.y)
            assertStaysOpen(page, PREFS_DIALOG, "a backdrop tap closed a screen with a write in flight")
            page.mouse().click(backdrop.x, backdrop.y)
            assertStaysOpen(page, PREFS_DIALOG, "a backdrop click closed a screen with a write in flight")
            assertThat(page.locator(PREFS_SUBMIT)).isDisabled()

            page.keyboard().press("Escape")
            assertThat(page.locator(PREFS_DIALOG)).hasCount(0)

            val second = context.newPage()
            second.navigate("${harness.baseUrl}/")
            assertThat(second.locator("#sidebar")).hasCount(1)
            openBusyPreferences(second)
            second.locator(PREFS_CLOSE).click()
            assertThat(second.locator(PREFS_DIALOG)).hasCount(0)
            assertEquals(
                "Close",
                busyDismissalLabel,
                "closing a busy preferences form does not cancel its save",
            )
        }
    }

    @Test
    fun theSwipeHandleIsDrawnWhereverACoarsePointerIsAndNowhereElse() {
        onChromium { browser ->
            val phone = paletteInk("dialog-ink-phone") { browser.touchContext() }
            assertTrue(phone.grabberVisible, "a phone draws no swipe handle")
            assertTrue(
                phone.grabberHeight >= MIN_GRABBER_PX,
                "the phone's swipe handle is ${phone.grabberHeight}px tall, which is no handle",
            )
            assertTrue(
                phone.closeWidth >= THUMB_PX && phone.closeHeight >= THUMB_PX,
                "the palette's × is ${phone.closeWidth}x${phone.closeHeight} on a phone, smaller " +
                    "than the ${THUMB_PX}px box a thumb needs above a row that runs a command",
            )

            val tablet = paletteInk("dialog-ink-tablet") {
                browser.touchContext(
                    width = TABLET_WIDTH,
                    height = TABLET_HEIGHT,
                    deviceScaleFactor = TABLET_SCALE,
                )
            }
            assertTrue(
                tablet.grabberVisible && tablet.grabberHeight >= MIN_GRABBER_PX,
                "a ${TABLET_WIDTH}px-wide touch device drew no swipe handle — the affordance is " +
                    "scoped by viewport width again, which is the bug that left every tablet " +
                    "unable to dismiss the palette",
            )

            val desktop = paletteInk("dialog-ink-desktop") { browser.fineContext() }
            assertTrue(
                !desktop.grabberVisible,
                "a fine pointer was given a swipe handle it can never use",
            )
        }
    }


    private fun withApp(
        trace: String,
        touch: Boolean,
        beforeLoad: (BrowserContext) -> Unit = {},
        block: (Harness, BrowserContext, Page) -> Unit,
    ) {
        Harness(SESSIONS_SCENARIO).use { harness ->
            onChromium { browser ->
                val context = if (touch) browser.touchContext() else browser.newContext()
                context.use {
                    context.traced(trace) {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)
                        beforeLoad(context)
                        val page = context.newPage()
                        page.navigate("${harness.baseUrl}/")
                        assertThat(page.locator("#sidebar")).hasCount(1)
                        block(harness, context, page)
                    }
                }
            }
        }
    }

    private fun openPalette(page: Page) {
        page.keyboard().press("Control+Shift+K")
        assertThat(page.locator(PALETTE)).isVisible()
        assertThat(page.locator("#command-palette-search-mode")).isVisible()
    }

    private fun openFromPalette(page: Page, title: String, dialog: String) {
        openPalette(page)
        page.locator(LEADER_COMMAND, Page.LocatorOptions().setHasText(title)).click()
        assertThat(page.locator(dialog)).isVisible()
        assertThat(page.locator(PALETTE)).hasCount(0)
    }

    private fun openHelp(page: Page) = openFromPalette(page, "Help", HELP_DIALOG)

    private fun openBusyPreferences(page: Page) {
        openFromPalette(page, "Preferences", PREFS_DIALOG)
        page.locator(PREFS_SUBMIT).click()
        assertThat(page.locator(PREFS_SUBMIT)).isDisabled()
        assertThat(page.locator(PREFS_SUBMIT)).containsText("Saving")
    }

    // Each context needs its own harness because a sign-in ticket is single-use.
    private fun paletteInk(trace: String, newContext: () -> BrowserContext): Ink =
        Harness(SESSIONS_SCENARIO).use { harness ->
            newContext().use { context ->
                var measured: Ink? = null
                context.traced(trace) {
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    val page = context.newPage()
                    page.navigate("${harness.baseUrl}/")
                    assertThat(page.locator("#sidebar")).hasCount(1)
                    openPalette(page)
                    val grabber = page.locator("$PALETTE .dialog-grabber")
                    val visible = grabber.isVisible()
                    val grabberBox = if (visible) grabber.boundingBox() else null
                    val close = page.locator(PALETTE_CLOSE).boundingBox()
                        ?: fail("the palette's close button has no box on screen")
                    measured = Ink(
                        grabberVisible = visible,
                        grabberHeight = grabberBox?.height ?: 0.0,
                        closeWidth = close.width,
                        closeHeight = close.height,
                    )
                }
                measured ?: fail("the palette's ink was never measured in $trace")
            }
        }

    private data class Ink(
        val grabberVisible: Boolean,
        val grabberHeight: Double,
        val closeWidth: Double,
        val closeHeight: Double,
    )


    private data class ViewportPoint(val x: Double, val y: Double)

    // Search the rounded border because Chromium's exact hit-tested edge varies with pixel rounding.
    private fun dialogOwnedPointInsideTheBox(page: Page, dialog: String): ViewportPoint {
        val found = page.evaluate(
            """
            (sel) => {
              const el = document.querySelector(sel);
              if (!el) return null;
              const r = el.getBoundingClientRect();
              const midX = r.left + r.width / 2;
              const midY = r.top + r.height / 2;
              const candidates = [];
              for (const d of [0.5, 1.5, 2.5, 3.5]) {
                candidates.push([r.left + d, midY], [r.right - d, midY]);
                candidates.push([midX, r.top + d], [midX, r.bottom - d]);
                candidates.push([r.left + d, r.top + d], [r.right - d, r.top + d]);
                candidates.push([r.left + d, r.bottom - d], [r.right - d, r.bottom - d]);
              }
              for (const [x, y] of candidates) {
                if (x <= r.left || x >= r.right || y <= r.top || y >= r.bottom) continue;
                if (document.elementFromPoint(x, y) === el) return x + "," + y;
              }
              return "none:" + [r.left, r.top, r.width, r.height].join("/");
            }
            """.trimIndent(),
            dialog,
        ) as? String ?: fail("there is no $dialog on screen to measure")
        if (found.startsWith("none")) {
            fail(
                "no point of $dialog both hit-tests to the dialog element and lies inside its box " +
                    "(${found.removePrefix("none:")}), so the geometry branch of `outside()` cannot be " +
                    "reached from a real press any more — this test has stopped testing it",
            )
        }
        val (x, y) = found.split(",").map { it.toDouble() }
        return ViewportPoint(x, y)
    }

    private fun recordDialogTargets(page: Page, dialog: String) {
        page.evaluate(
            """
            (sel) => {
              const el = document.querySelector(sel);
              window.__kotgentDialogEvents = [];
              for (const type of ["pointerdown", "pointerup", "click"]) {
                el.addEventListener(type, (event) => {
                  const r = el.getBoundingClientRect();
                  const inside = event.clientX > r.left && event.clientX < r.right &&
                    event.clientY > r.top && event.clientY < r.bottom;
                  window.__kotgentDialogEvents.push(
                    type + ":" + (event.target === el ? "dialog" : "child") +
                    ":" + (inside ? "inside" : "outside"),
                  );
                }, true);
              }
            }
            """.trimIndent(),
            dialog,
        )
    }

    private fun dialogTargets(page: Page): List<String> {
        val raw = page.evaluate("() => window.__kotgentDialogEvents || []") as List<*>
        return raw.map { it.toString() }
    }

    private fun panelTransform(page: Page, dialog: String): String =
        page.evaluate("(sel) => document.querySelector(sel)?.style.transform ?? \"(gone)\"", dialog) as String

    private fun touchDown(cdp: CDPSession, page: Page, x: Double, y: Double) {
        dispatchTouch(cdp, "touchStart", x, y)
        page.waitForTimeout(TOUCH_FRAME_MILLIS)
    }

    private fun touchDragTo(cdp: CDPSession, page: Page, fromX: Double, fromY: Double, toX: Double, toY: Double) {
        for (step in 1..TOUCH_STEPS) {
            val fraction = step.toDouble() / TOUCH_STEPS
            dispatchTouch(cdp, "touchMove", fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction)
            page.waitForTimeout(TOUCH_FRAME_MILLIS)
        }
        // Rest before lift so the gesture cannot be classified as a velocity-driven flick.
        page.waitForTimeout(TOUCH_REST_MILLIS)
    }

    private fun touchUp(cdp: CDPSession, page: Page) {
        dispatchTouch(cdp, "touchEnd", null, null)
        page.waitForTimeout(TOUCH_FRAME_MILLIS)
    }

    private fun pointer(
        page: Page,
        type: String,
        pointerId: Int,
        isPrimary: Boolean,
        button: Int,
        at: ViewportPoint,
    ) {
        val delivered = page.evaluate(
            """
            () => {
              const el = document.querySelector("$HELP_DIALOG");
              if (!el) return false;
              el.dispatchEvent(new PointerEvent("$type", {
                bubbles: true,
                cancelable: true,
                composed: true,
                pointerType: "touch",
                pointerId: $pointerId,
                isPrimary: $isPrimary,
                button: $button,
                buttons: ${if (type == "pointerdown") buttonsMask(button) else 0},
                clientX: ${at.x},
                clientY: ${at.y}
              }));
              return true;
            }
            """.trimIndent(),
        )
        assertTrue(delivered == true, "there was no $HELP_DIALOG to dispatch a synthetic $type on")
    }

    private fun buttonsMask(button: Int): Int = when (button) {
        0 -> 1
        1 -> 4
        2 -> 2
        else -> 0
    }

    // The roomiest margin stays outside Chromium's touch-target adjustment radius.
    private fun backdropPoint(page: Page, dialog: String): ViewportPoint {
        val box = boxOf(page, dialog)
        val viewport = page.viewportSize() ?: fail("the page has no viewport size")
        val midX = (box.x + box.width / 2).coerceIn(1.0, viewport.width - 1.0)
        val midY = (box.y + box.height / 2).coerceIn(1.0, viewport.height - 1.0)
        val leftRoom = box.x
        val rightRoom = viewport.width - (box.x + box.width)
        val topRoom = box.y
        val bottomRoom = viewport.height - (box.y + box.height)
        val roomiest = maxOf(leftRoom, rightRoom, topRoom, bottomRoom)
        return when {
            roomiest < MIN_BACKDROP_PX -> fail(
                "$dialog fills its ${viewport.width}x${viewport.height} viewport (box ${box.width}x" +
                    "${box.height} at ${box.x},${box.y}), so it has no backdrop to press",
            )
            roomiest == topRoom -> ViewportPoint(midX, topRoom / 2)
            roomiest == bottomRoom -> ViewportPoint(midX, viewport.height - bottomRoom / 2)
            roomiest == leftRoom -> ViewportPoint(leftRoom / 2, midY)
            else -> ViewportPoint(viewport.width - rightRoom / 2, midY)
        }
    }

    private fun centerOf(page: Page, selector: String): ViewportPoint {
        val box = boxOf(page, selector)
        return ViewportPoint(box.x + box.width / 2, box.y + box.height / 2)
    }

    private fun panelPoint(page: Page, dialog: String): ViewportPoint {
        val box = boxOf(page, dialog)
        return ViewportPoint(box.x + PANEL_INSET_PX, box.y + PANEL_INSET_PX)
    }

    private fun boxOf(page: Page, selector: String) =
        page.locator(selector).boundingBox() ?: fail("$selector has no box on screen")

    private fun assertStaysOpen(page: Page, dialog: String, message: String) {
        page.waitForTimeout(SETTLE_MILLIS)
        assertTrue(page.locator(dialog).count() == 1, message)
    }

    private companion object {
        const val HELP_DIALOG = "#help-dialog"
        const val PALETTE = "#command-palette"
        const val PALETTE_CLOSE = "#command-palette-close"
        const val LEADER_COMMAND = ".command-palette-leader-command"
        const val PREFS_DIALOG = "#prefs-dialog"
        const val PREFS_SUBMIT = "#prefs-submit"
        const val PREFS_CLOSE = "#prefs-close"
        const val PREFS_CANCEL = "#prefs-cancel"

        const val PREFERENCES_PATH = "/api/v1/preferences"

        const val PRIMARY_ID = 21
        const val SECOND_ID = 22

        const val MIN_BACKDROP_PX = 8.0

        const val PANEL_INSET_PX = 6.0

        const val DRAG_STEPS = 8

        const val SHORT_PULL_PX = 40.0
        const val LONG_PULL_PX = 150.0

        const val TOUCH_STEPS = 8
        const val TOUCH_FRAME_MILLIS = 16.0

        const val TOUCH_REST_MILLIS = 150.0

        const val MIN_GRABBER_PX = 12.0

        const val THUMB_PX = 44.0

        const val TABLET_WIDTH = 1024
        const val TABLET_HEIGHT = 1366
        const val TABLET_SCALE = 2.0

        const val SETTLE_MILLIS = 250.0
    }
}
