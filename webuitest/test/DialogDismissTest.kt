package io.kotgent.webuitest

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.CDPSession
import com.microsoft.playwright.Mouse
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Light dismiss: closing a modal with a pointer, in a real browser.
 *
 * ## What this protects, and why it reads the way it does
 *
 * A native `<dialog>` opened with `showModal()` gives Esc, a focus trap and a painted backdrop — and a
 * backdrop that dismisses NOTHING. Before the wrapper in `components/dialogs.js`, every modal was closable
 * only from a keyboard or its own ×, and the command palette, the one dialog that draws no `.dialog-head`,
 * had no × at all: on a phone it could not be dismissed. The wrapper adds the two gestures a pointer-only
 * device has — a press outside the panel, and a downward swipe of a TOUCH pointer off the panel's grabber —
 * and every screen inherits them, so neither may be re-implemented per dialog.
 *
 * The rules are not obvious and each was paid for:
 *
 * - **Outside-ness is the panel's GEOMETRY, not the event's target.** A press on the backdrop reports its
 *   target as the `<dialog>` itself — but so does a drag that started on the panel (selecting a path,
 *   releasing a slider) and so does a click a native `<select>` popup lets through. So `pointerdown` and
 *   `click` are paired and each is tested against `getBoundingClientRect()`; both halves must land outside.
 *   That geometry branch has a test OF ITS OWN
 *   ([aPressWhoseTargetIsTheDialogButWhoseCoordinatesAreInsideItIsRefused]) and it needs one: every other
 *   "inside" press in this file lands on a CHILD (`dialog` is `padding: 0`, so a form or the grabber
 *   covers the content box), and a child target is refused by `event.target !== el` before a rectangle is
 *   ever read. Deleting the whole branch left this file green until that test was written.
 * - **A dismiss is a positively completed down → up → click transaction by ONE pointer.** The record is one
 *   slot, `{ pointerId, released }`, armed only by `event.isPrimary && event.button === 0`. Both halves are
 *   load-bearing. The BUTTON, because a press that answers with no `click` at all (a secondary button
 *   reports `contextmenu`/`auxclick`) would otherwise stay armed until a later drag OUT of the panel spent
 *   it — closing a dialog from a gesture that began inside it. `isPrimary`, because on a touchscreen a
 *   second finger also reports button 0 while only the primary pointer produces a `click` at all, so it
 *   could arm a press the FIRST finger's click then spends. A set of votes was tried and is worse than a
 *   flag for exactly that reason. `released` is the other half: without it a finger merely HOLDING the
 *   backdrop authorised a mouse's click, since only the arming pointer's own release may complete (outside)
 *   or withdraw (inside) the press.
 * - **`click` additionally matches `event.pointerId` where the platform names one** — Pointer Events makes
 *   `click` a `PointerEvent` — but that is belt to `released`'s braces, not the guarantee.
 * - **Every rule fails toward KEEPING the dialog**, because what a dialog holds is unsaved and local. The
 *   accepted cost is the opposite error, worth one tap: a second contact landing inside disarms a pending
 *   press.
 * - **A screen with work in flight opts out entirely** (`lightDismiss={!busy}` on New session, Upload files
 *   and Preferences, plus the board's two forms). Each already disables its own buttons while working for
 *   the same reason; the gesture is the hole those `disabled` attributes leave. The flag is read at three
 *   points — the press, the move, and the release/click that actually dismiss — and Esc, the × and Cancel
 *   are never gated, because those are the operator saying it on purpose.
 *
 * ## Genuine gestures versus synthesised events — read this before adding a test
 *
 * This file supersedes `WebUiServingTest.everyDialogIsDismissableWithoutAKeyboard`, 230 lines of grep over
 * the SOURCE TEXT of the state machine above. Grep could prove a line was present; it could not prove a
 * press closes anything. What replaces it must not repeat the trick in a browser, so each assertion here
 * declares which pipeline it rides:
 *
 * - **GENUINE** — `touchscreen().tap()` and `mouse` drive Chromium's real input pipeline through CDP. A tap
 *   produces the measured chain `pointerdown:touch#2 | touchstart | pointerup:touch#2 | touchend |
 *   click:touch#2`: **one and the same `pointerId` across down, up and click**, which is exactly the
 *   invariant the dismiss transaction rests on. Hit-testing, the compatibility mouse burst, `touch-action`
 *   and pointer capture are all the platform's own here. WebKit delivered the element nothing at all for
 *   the same call, which is why this tier is Chromium-only and deliberately so.
 * - **SYNTHESISED** — `page.evaluate` dispatching constructed `PointerEvent`s. This proves our LISTENERS
 *   behave against invented events and nothing more: an untrusted event creates no active pointer, drives
 *   no hit-test and triggers no compatibility burst. It is used for exactly one thing no single-tap API can
 *   express — a two-pointer sequence — and every such assertion says so at its call site.
 *
 * - **CDP TOUCH DRAG** — `Input.dispatchTouchEvent` through `context.newCDPSession`, which is how the SWIPE
 *   half is driven. Playwright's `Touchscreen` really does offer `tap` alone, but Chromium turns a CDP
 *   touch sequence into genuine `pointerType: "touch"` events with an ACTIVE pointer, which is exactly what
 *   `el.setPointerCapture(event.pointerId)` needs (a made-up id throws `NotFoundError` and leaves the
 *   gesture inert before it can translate anything). So the claim, the capture, the transform and the
 *   release are all the platform's own here; only the contact geometry is synthetic.
 *
 * One thing stays out of reach and is recorded rather than faked. The note in CLAUDE.md stands unchanged:
 * **on a current iPhone and iPad, confirm a backdrop `pointerdown`/`pointerup`/`click` carry the same
 * `pointerId`, including in a two-finger sequence.** WebKit has regressed click metadata before; `released`
 * is what carries the guarantee if it has again.
 *
 * ## Vacuity
 *
 * Every negative here ends with a positive control on the same dialog, in the same test: an assertion that
 * a gesture did NOT close a dialog is worthless beside a dialog that could not be closed at all.
 */
class DialogDismissTest {

    /**
     * GENUINE touch. The whole point of the wrapper, in one test: the backdrop dismisses, the panel does
     * not. Both taps are the same finger at two positions, so the only difference under test is geometry.
     */
    @Test
    fun aBackdropTapClosesADialogAndATapOnThePanelDoesNot() {
        withApp("dialog-backdrop-tap", touch = true) { _, _, page ->
            openHelp(page)

            // Inside the panel: the click reports a target that is not the <dialog>, and its coordinates
            // are inside the box either way. Nothing arms, nothing closes.
            val title = centerOf(page, "#help-title")
            page.touchscreen().tap(title.x, title.y)
            assertStaysOpen(page, HELP_DIALOG, "a tap on the panel's own title closed the dialog")

            // Outside it: target IS the <dialog> (the backdrop reports as its host) and the coordinates
            // fall outside the box. Down, up and click all land there, all under one pointer id.
            val backdrop = backdropPoint(page, HELP_DIALOG)
            page.touchscreen().tap(backdrop.x, backdrop.y)
            assertThat(page.locator(HELP_DIALOG)).hasCount(0)
        }
    }

    /**
     * GENUINE touch, on the dialog the gesture was written for.
     *
     * The palette draws no `.dialog-head`, so before the wrapper it had no × and no Esc a phone can type:
     * it was the one screen with no way out at all. A tap on its panel keeps it and a tap on the backdrop
     * closes it — the whole way out, delivered by one thumb.
     */
    @Test
    fun thePaletteTheOneDialogWithNoHeadIsDismissableByThumbAlone() {
        withApp("dialog-palette-thumb", touch = true) { _, _, page ->
            openPalette(page)

            // The panel's own inner corner, measured rather than named: the leader grid scrolls inside the
            // shell, so which ROW is on screen is the layout's business. Under a coarse pointer this strip
            // is the `.dialog-grabber` — the handle a swipe starts from — so the tap is also the statement
            // that a press with no travel is not a swipe: it opens a drag record and closes nothing.
            val panel = panelPoint(page, PALETTE)
            page.touchscreen().tap(panel.x, panel.y)
            assertStaysOpen(page, PALETTE, "a tap on the palette's own panel closed it")

            val backdrop = backdropPoint(page, PALETTE)
            page.touchscreen().tap(backdrop.x, backdrop.y)
            assertThat(page.locator(PALETTE)).hasCount(0)
        }
    }

    /**
     * GENUINE mouse, desktop context. Outside-ness is decided by the panel's box and BOTH halves of the
     * press have to land outside it.
     *
     * A press that starts on the panel and ends on the backdrop is a real gesture — dragging a selection
     * out of a form, releasing a slider past the edge — and the browser reports its `click` on the nearest
     * common ancestor of the two, which for a press inside a dialog and a release on its backdrop is the
     * `<dialog>` element itself, at coordinates outside the box. Target alone would therefore read it as a
     * backdrop click and throw the draft away. The converse gesture is here too — a press that starts on the
     * backdrop and is dragged back onto the panel is the operator changing their mind, and `pointerUp`
     * withdraws the arm rather than completing it.
     */
    @Test
    fun aPressThatBeginsOnThePanelAndEndsOnTheBackdropKeepsTheDialog() {
        withApp("dialog-drag-out", touch = false) { _, _, page ->
            openHelp(page)

            // A point inside the panel that is padding rather than text: pressing on a paragraph starts a
            // selection drag, and this test is about the press, not about what it drags.
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

            // The control. Without it the two negatives above would pass just as happily against a dialog
            // that no pointer could ever close.
            page.mouse().click(backdrop.x, backdrop.y)
            assertThat(page.locator(HELP_DIALOG)).hasCount(0)
        }
    }

    /**
     * GENUINE mouse. The geometry branch of `outside()`, and the only test in this file that reaches it.
     *
     * Every other "inside" press here lands on a CHILD of the `<dialog>` — `dialog { padding: 0 }` means a
     * form (or, under a coarse pointer, the grabber) covers the whole content box — so `event.target !== el`
     * refuses them and `getBoundingClientRect()` is never consulted. The branch therefore existed with no
     * test at all: deleting it left all six other tests green.
     *
     * What produces the missing case is the panel's own EDGE. A `<dialog>` with no padding still has a 1px
     * border and a 12px radius, so there are real points that belong to the dialog element itself and lie
     * INSIDE its bounding rectangle — the border strip, and the notch a rounded corner leaves. Those are
     * the same shape as the two cases the branch is written for and cannot be staged: a click a native
     * `<select>` popup lets through, and a drag that began on the panel whose `click` the browser reports
     * on the nearest common ancestor at a coordinate the operator never meant as "outside".
     *
     * The point is SEARCHED for and its target is recorded from the real events rather than assumed, so a
     * layout in which no such point exists fails loudly instead of quietly re-testing the target check.
     */
    @Test
    fun aPressWhoseTargetIsTheDialogButWhoseCoordinatesAreInsideItIsRefused() {
        withApp("dialog-inside-geometry", touch = false) { _, _, page ->
            openHelp(page)
            val owned = dialogOwnedPointInsideTheBox(page, HELP_DIALOG)
            recordDialogTargets(page, HELP_DIALOG)

            page.mouse().move(owned.x, owned.y)
            page.mouse().down()
            page.mouse().up()

            // The precondition, read off the events that were actually delivered: both halves of the press
            // reported the <dialog> itself, and both landed inside its box. That is precisely the input the
            // geometry branch exists to refuse — and the only input for which `event.target !== el` cannot.
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

            // The control. Without it the refusal above would pass just as happily against a dialog no
            // pointer could ever close.
            val backdrop = backdropPoint(page, HELP_DIALOG)
            page.mouse().click(backdrop.x, backdrop.y)
            assertThat(page.locator(HELP_DIALOG)).hasCount(0)
        }
    }

    /**
     * CDP TOUCH DRAG. The other gesture the wrapper adds, on the dialog it was written for.
     *
     * A downward pull that begins on `.dialog-grabber` translates the panel with the finger and dismisses
     * on release once it has travelled far enough; a shorter pull springs the panel back and keeps the
     * screen. Both halves are asserted through the inline `transform`, which is what `springBack` clears
     * synchronously — so "the panel came back" is read as the state the code writes, not as a picture.
     *
     * The distances bracket `SWIPE_DISMISS_PX` (96) from both sides and the drag rests before its lift, so
     * neither arm can be decided by the flick rule instead: a stationary contact ages its last speed sample
     * past `SWIPE_FLICK_HANDOFF_MS`, which the wrapper counts as zero velocity.
     */
    @Test
    fun aDownwardSwipeOffTheGrabberDismissesWhileAShorterPullSpringsBack() {
        Harness(SESSIONS_SCENARIO).use { harness ->
            Playwright.create().use { pw ->
                touchChromium(pw).use { browser ->
                    browser.touchContext().use { context ->
                        context.traced("dialog-swipe") {
                            context.loginWithTicket(harness.ticket, harness.baseUrl)
                            val page = context.newPage()
                            page.navigate("${harness.baseUrl}/")
                            assertThat(page.locator("#sidebar")).hasCount(1)
                            openPalette(page)
                            val cdp = context.newCDPSession(page)
                            try {
                                swipeCases(page, cdp)
                            } finally {
                                cdp.detach()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun swipeCases(page: Page, cdp: CDPSession) {
        val grabber = page.locator("$PALETTE .dialog-grabber").boundingBox()
            ?: fail("the palette drew no swipe handle in a coarse-pointer context")
        val x = grabber.x + grabber.width / 2
        val y = grabber.y + grabber.height / 2

        // A pull the wrapper claims (past the 8px slop, dominantly downward) but does not act on.
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

        // The same gesture, past the dismissal distance.
        touchDown(cdp, page, x, y)
        touchDragTo(cdp, page, x, y, x, y + LONG_PULL_PX)
        touchUp(cdp, page)
        assertThat(page.locator(PALETTE)).hasCount(0)
    }

    /**
     * SYNTHESISED `PointerEvent`s — the one thing a single-tap API cannot express, and labelled as such.
     *
     * Read this test as "our listeners, against invented events". It dispatches constructed pointer events
     * on the `<dialog>` element, so the platform contributes no hit-test, no compatibility mouse burst and
     * no pointer capture; what it does contribute is a second pointer id, which is the whole subject. Three
     * presses that must arm nothing or complete nothing, then one that must close — the control, which also
     * proves the synthesised chain is capable of closing this dialog at all.
     *
     * Every sub-case fires its `click` at the SAME backdrop point and, where it can, under the SAME pointer
     * id as the press, so the only variable is the rule under test.
     */
    @Test
    fun onlyTheArmingPointersOwnReleaseCanCompleteADismissal() {
        withApp("dialog-two-pointers", touch = true) { _, _, page ->
            openHelp(page)
            val at = backdropPoint(page, HELP_DIALOG)

            // 1. `released`. The primary pointer presses the backdrop and is still DOWN; a second contact
            // releases over the same spot. That release belongs to nobody's press, so the arm stays
            // unreleased — and the click is then delivered under the ARMING pointer's own id, which means
            // the pointer-identity check in `click` cannot be what saves the dialog here. Only `released`
            // can. Without it, a finger merely holding the backdrop authorises another pointer's click.
            pointer(page, "pointerdown", PRIMARY_ID, isPrimary = true, button = 0, at = at)
            pointer(page, "pointerup", SECOND_ID, isPrimary = false, button = 0, at = at)
            pointer(page, "click", PRIMARY_ID, isPrimary = true, button = 0, at = at)
            assertStaysOpen(page, HELP_DIALOG, "an unreleased press authorised another pointer's release")

            // 2. `isPrimary`. On a touchscreen every contact reports button 0, but only the primary pointer
            // produces a `click` at all — so a second finger that armed a press would have it spent by the
            // FIRST finger's click, closing a dialog from a gesture that began on the panel. A complete,
            // self-consistent transaction by a non-primary pointer must therefore still arm nothing.
            pointer(page, "pointerdown", SECOND_ID, isPrimary = false, button = 0, at = at)
            pointer(page, "pointerup", SECOND_ID, isPrimary = false, button = 0, at = at)
            pointer(page, "click", SECOND_ID, isPrimary = false, button = 0, at = at)
            assertStaysOpen(page, HELP_DIALOG, "a non-primary contact armed a dismissal of its own")

            // 3. `button === 0`. A secondary press answers with `contextmenu`/`auxclick` and never with a
            // `click`, so an arm it left behind would sit there until an unrelated click — a drag OUT of
            // the panel, say — spent it. It must never be armed in the first place.
            pointer(page, "pointerdown", PRIMARY_ID, isPrimary = true, button = 2, at = at)
            pointer(page, "pointerup", PRIMARY_ID, isPrimary = true, button = 2, at = at)
            pointer(page, "click", PRIMARY_ID, isPrimary = true, button = 0, at = at)
            assertStaysOpen(page, HELP_DIALOG, "a secondary-button press armed a dismissal")

            // 4. The control: one pointer, down → up → click, all outside. This is the only shape that may
            // close, and it must.
            pointer(page, "pointerdown", PRIMARY_ID, isPrimary = true, button = 0, at = at)
            pointer(page, "pointerup", PRIMARY_ID, isPrimary = true, button = 0, at = at)
            pointer(page, "click", PRIMARY_ID, isPrimary = true, button = 0, at = at)
            assertThat(page.locator(HELP_DIALOG)).hasCount(0)
        }
    }

    /**
     * GENUINE touch, GENUINE mouse, GENUINE keyboard: a screen with work in flight refuses the backdrop,
     * and Esc and its × still answer.
     *
     * Preferences is made genuinely busy by holding its `PUT /api/v1/preferences` in the browser — the
     * request is intercepted and never answered, so the form stays exactly where an operator on a slow
     * daemon finds it. Unmounting it there would discard a typed draft while the write completes invisibly,
     * which is why `lightDismiss` is `!busy` and why the flag is re-read where each dismissal is DECIDED
     * rather than only where a gesture starts. Esc, the × and Cancel are deliberately never gated.
     *
     * The × runs on a SECOND page rather than after reopening on the first: `savePreferences` refuses an
     * overlapping PUT while one is in flight, and the held request never completes, so the first page can
     * only ever be made busy once.
     */
    @Test
    fun aBusyScreenIgnoresTheBackdropWhileEscAndItsCloseButtonStillWork() {
        val heldWrites = AtomicInteger(0)
        withApp(
            "dialog-busy",
            touch = true,
            beforeLoad = { context ->
                context.route("**$PREFERENCES_PATH") { route: Route ->
                    if (route.request().method().equals("PUT", ignoreCase = true)) {
                        // Held, not fulfilled and not aborted: the dialog stays busy for the whole test.
                        heldWrites.incrementAndGet()
                    } else {
                        // The app reads its preferences on load through this same path.
                        route.resume()
                    }
                }
            },
        ) { harness, context, page ->
            openBusyPreferences(page)
            // The interception is what makes "busy" last; the button's own state can be read a beat before
            // the handler runs, so give it that beat rather than racing it.
            var waited = 0
            while (heldWrites.get() < 1 && waited < HOLD_WAIT_STEPS) {
                page.waitForTimeout(HOLD_WAIT_STEP_MILLIS)
                waited++
            }
            assertTrue(
                heldWrites.get() >= 1,
                "the preferences write was never intercepted, so the dialog is not busy for the reason " +
                    "this test believes it is",
            )

            val backdrop = backdropPoint(page, PREFS_DIALOG)
            page.touchscreen().tap(backdrop.x, backdrop.y)
            assertStaysOpen(page, PREFS_DIALOG, "a backdrop tap closed a screen with a write in flight")
            page.mouse().click(backdrop.x, backdrop.y)
            assertStaysOpen(page, PREFS_DIALOG, "a backdrop click closed a screen with a write in flight")
            // Still busy, i.e. the two refusals above were the opt-out and not a form that had finished.
            assertThat(page.locator(PREFS_SUBMIT)).isDisabled()

            page.keyboard().press("Escape")
            assertThat(page.locator(PREFS_DIALOG)).hasCount(0)

            val second = context.newPage()
            second.navigate("${harness.baseUrl}/")
            assertThat(second.locator("#sidebar")).hasCount(1)
            openBusyPreferences(second)
            second.locator(PREFS_CLOSE).click()
            assertThat(second.locator(PREFS_DIALOG)).hasCount(0)
        }
    }

    /**
     * The swipe affordance is scoped by POINTER ACCURACY, not by viewport width — asserted from geometry in
     * three real contexts rather than from the text of a media query.
     *
     * The bug this pins is recorded: a `max-width: 720px` query left the palette — the one dialog with no
     * head, and therefore the one with no other handle — unswipeable on every tablet, the exact device the
     * reservation was written for. So the handle must be drawn on a phone AND on a viewport far wider than
     * the phone breakpoint that still has a coarse pointer, and must not be drawn where a mouse is. The
     * palette's × is measured in the same pass: it sits about 16px above an option row whose tap RUNS a
     * command, so on a coarse pointer it gets a thumb-sized box instead of the desktop icon size.
     */
    @Test
    fun theSwipeHandleIsDrawnWhereverACoarsePointerIsAndNowhereElse() {
        Playwright.create().use { pw ->
            touchChromium(pw).use { browser ->
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
    }

    // --- fixtures ------------------------------------------------------------------------------------

    /**
     * One harness, one browser, one signed-in context, one page on the SPA.
     *
     * A fresh context per test is mandatory (the session cookie is not scoped by port), and the browser is
     * the touch-capable Chromium either way: [touch] chooses whether its CONTEXT gets `hasTouch`, which is
     * what decides both `touchscreen().tap()` and the coarse-pointer ink. [beforeLoad] runs after sign-in
     * and before the app is ever loaded, which is where a request interception has to be installed.
     */
    private fun withApp(
        trace: String,
        touch: Boolean,
        beforeLoad: (BrowserContext) -> Unit = {},
        block: (Harness, BrowserContext, Page) -> Unit,
    ) {
        Harness(SESSIONS_SCENARIO).use { harness ->
            Playwright.create().use { pw ->
                touchChromium(pw).use { browser ->
                    val context = if (touch) browser.touchContext() else browser.newContext()
                    context.use {
                        context.traced(trace) {
                            context.loginWithTicket(harness.ticket, harness.baseUrl)
                            beforeLoad(context)
                            val page = context.newPage()
                            page.navigate("${harness.baseUrl}/")
                            // The app's own first render, not index.html's static mount point: the global
                            // keydown listener that opens the palette is installed with it.
                            assertThat(page.locator("#sidebar")).hasCount(1)
                            block(harness, context, page)
                        }
                    }
                }
            }
        }
    }

    /** Open the command palette on its leader grid, the state a mnemonic row can be tapped from. */
    private fun openPalette(page: Page) {
        page.keyboard().press("Control+Shift+K")
        assertThat(page.locator(PALETTE)).isVisible()
        // The opener TOGGLES between the two views, so a second press would land on search and the row
        // this helper's callers click would not be there. Assert the view rather than discover it later.
        assertThat(page.locator("#command-palette-search-mode")).isVisible()
    }

    /**
     * Open a screen by tapping its row in the leader grid rather than by typing its mnemonic.
     *
     * A click needs no focus: leader mode's key handler sits on the palette's shell and only answers a
     * keystroke that bubbles out of it, so a mnemonic typed before that shell's focus effect has run is
     * silently dropped. The row is also the gesture a phone actually uses.
     */
    private fun openFromPalette(page: Page, title: String, dialog: String) {
        openPalette(page)
        page.locator(LEADER_COMMAND, Page.LocatorOptions().setHasText(title)).click()
        assertThat(page.locator(dialog)).isVisible()
        assertThat(page.locator(PALETTE)).hasCount(0)
    }

    private fun openHelp(page: Page) = openFromPalette(page, "Help", HELP_DIALOG)

    /** Open Preferences and submit it, leaving the form waiting on the write this test is holding. */
    private fun openBusyPreferences(page: Page) {
        openFromPalette(page, "Preferences", PREFS_DIALOG)
        page.locator(PREFS_SUBMIT).click()
        assertThat(page.locator(PREFS_SUBMIT)).isDisabled()
        assertThat(page.locator(PREFS_SUBMIT)).containsText("Saving")
    }

    /**
     * Sign a fresh context in against a HARNESS OF ITS OWN, open the palette, and measure the ink a
     * pointer of that accuracy gets.
     *
     * A daemon per context, and that is not extravagance: `TicketStore` mints ONE sign-in code per process
     * and burns it on redemption, so a second context typing the same code is refused and never leaves
     * `/auth`. That is exactly how this test used to fail — a 30s `waitForURL` inside `loginWithTicket`,
     * which reads as a hung browser rather than as a spent credential. The harness has no "issue another
     * ticket" command and should not grow one; one code per process is the daemon's own rule, and the
     * fixture's contract is a fresh context per sign-in anyway. The context is BUILT and closed inside the
     * harness's `use` — built there so a harness that throws on construction cannot leak one, closed there
     * so no page is still fetching when `Harness.close()` asserts a clean exit.
     */
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

    /** What one context's pointer accuracy earned: the swipe handle, and the palette's way out. */
    private data class Ink(
        val grabberVisible: Boolean,
        val grabberHeight: Double,
        val closeWidth: Double,
        val closeHeight: Double,
    )

    // --- gestures and geometry -----------------------------------------------------------------------

    private data class ViewportPoint(val x: Double, val y: Double)

    /**
     * A point that hit-tests to the `<dialog>` ELEMENT and lies inside its bounding rectangle — the one
     * input shape the geometry branch of `outside()` exists to refuse, and the one no other test here
     * produces.
     *
     * `dialog { padding: 0 }` means the content box belongs to a child, so the candidates are the parts of
     * the box that are still the dialog's own paint: its 1px border, and the notch its 12px radius leaves
     * at each corner (Chromium hit-tests a rounded border box, so a press in the notch reaches whatever is
     * behind — the backdrop, which reports the dialog). Searched rather than named, because which of the
     * two answers depends on the engine's rounding; a layout that offers neither fails here with the box it
     * measured rather than silently degrading into a second target-check test.
     */
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

    /**
     * Record, for every pointer event the dialog element receives, whether its target WAS that element and
     * whether its coordinates fell inside the element's box. That pair is the whole input to `outside()`.
     */
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

    /** The panel's inline transform — what a claimed swipe writes and `springBack` clears. */
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
        // Rest before the lift, so the release cannot be read as a FLICK: a stationary contact emits no
        // further move, and the wrapper ages a sample older than `SWIPE_FLICK_HANDOFF_MS` to zero velocity.
        page.waitForTimeout(TOUCH_REST_MILLIS)
    }

    private fun touchUp(cdp: CDPSession, page: Page) {
        // Chromium refuses a `touchEnd` that still names points: the release IS their absence.
        dispatchTouch(cdp, "touchEnd", null, null)
        page.waitForTimeout(TOUCH_FRAME_MILLIS)
    }

    private fun dispatchTouch(cdp: CDPSession, type: String, x: Double?, y: Double?) {
        val points = JsonArray()
        if (x != null && y != null) {
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

    /**
     * Dispatch ONE constructed `PointerEvent` on the dialog element itself — SYNTHESISED, see the class
     * comment. Every field the wrapper reads is supplied explicitly, because the defaults of the
     * constructor are exactly the values this test must be able to vary.
     */
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

    /**
     * The `buttons` bitmask a real press of [button] would carry. Nothing in the wrapper reads it — the
     * arming rule is about `button`, the one that is DOWN — but an event that contradicts itself is a trap
     * for whoever reads this test next.
     */
    private fun buttonsMask(button: Int): Int = when (button) {
        0 -> 1
        1 -> 4
        2 -> 2
        else -> 0
    }

    /**
     * A point on the backdrop: inside the viewport, and as far from the panel's box as the layout allows.
     *
     * Computed rather than hardcoded, because that is the thing under test — a dialog is centred and
     * `max-width: calc(100vw - 32px)` leaves it a margin on every device, but which side has room is the
     * layout's business, not this test's. A panel that leaves no room at all fails loudly instead of
     * silently tapping itself.
     *
     * **The ROOMIEST side, not the first side that fits, and that is measured.** Chromium performs TOUCH
     * ADJUSTMENT: a tap near a touch target is snapped onto it. Taking the first side left the palette's
     * tap in the 16px gutter its `width: min(680px, calc(100vw - 32px))` leaves on a 390px phone, and the
     * events that arrived carried `target = .command-palette-shell` with the click retargeted from x=8 to
     * x=17 — the panel's own edge. The dismissal was refused correctly, by a press that was never on the
     * backdrop at all. The same phone leaves ~82px above and below the palette, so choosing the largest
     * margin puts the press ~41px clear and out of the adjustment's reach. This is only about where the
     * test aims; the wrapper's geometry rule is untouched.
     */
    private fun backdropPoint(page: Page, dialog: String): ViewportPoint {
        val box = boxOf(page, dialog)
        val viewport = page.viewportSize() ?: fail("the page has no viewport size")
        // Clamped into the viewport: a panel taller than the screen (`dialog:modal` makes it its own
        // scroller) still leaves a full-height margin on each side, but its own midpoint is off-screen and
        // a press dispatched there reaches nothing at all.
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

    /**
     * A point inside the panel and clear of its content: the inner top-left corner.
     *
     * Taken from the panel's own box rather than from a named child, because a dialog that overflows is its
     * own scroller and which child is on screen is then the layout's business — while the corner is the one
     * part of an overflowing sheet that is always visible. It is padding on every screen in this file (and
     * the handle strip where a coarse pointer draws one), so a press there is a press on the panel and
     * nothing else.
     */
    private fun panelPoint(page: Page, dialog: String): ViewportPoint {
        val box = boxOf(page, dialog)
        return ViewportPoint(box.x + PANEL_INSET_PX, box.y + PANEL_INSET_PX)
    }

    private fun boxOf(page: Page, selector: String) =
        page.locator(selector).boundingBox() ?: fail("$selector has no box on screen")

    /**
     * The negative assertion of this file, and the reason it is written by hand.
     *
     * A closing dialog unmounts within a microtask of the click that closed it, so an auto-waiting
     * "is visible" would pass against a dialog that is about to disappear. Let the frame land first, then
     * read the DOM once, and say which rule was broken when it is gone.
     */
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

        /** The daemon-wide preferences resource, under the API prefix every browser call carries. */
        const val PREFERENCES_PATH = "/api/v1/preferences"

        /** Distinct pointer ids so a mixed-up one is visible in a failure rather than silently equal. */
        const val PRIMARY_ID = 21
        const val SECOND_ID = 22

        /** The floor: below this much clear backdrop on its roomiest side, a dialog has none to press. */
        const val MIN_BACKDROP_PX = 8.0

        /** Inside the panel, clear of its text: enough to be padding on every dialog in this file. */
        const val PANEL_INSET_PX = 6.0

        /** Intermediate mouse moves, so a drag is a drag and not a teleport. */
        const val DRAG_STEPS = 8

        /**
         * The two swipe distances, bracketing `SWIPE_DISMISS_PX` (96) from both sides: one past the 8px
         * slop and well under the threshold, one comfortably over it.
         */
        const val SHORT_PULL_PX = 40.0
        const val LONG_PULL_PX = 150.0

        /** Intermediate touch points, and a frame between them, so the wrapper sees a stream of moves. */
        const val TOUCH_STEPS = 8
        const val TOUCH_FRAME_MILLIS = 16.0

        /** Longer than `SWIPE_FLICK_HANDOFF_MS` (90), so neither arm can be decided by the flick rule. */
        const val TOUCH_REST_MILLIS = 150.0

        /** The handle is 20px on a coarse pointer; anything at or above this is drawn rather than absent. */
        const val MIN_GRABBER_PX = 12.0

        /** The thumb-sized box the palette's × is given where a thumb aims at it. */
        const val THUMB_PX = 44.0

        /** Wider than the phone breakpoint by a long way, and still a touch device. */
        const val TABLET_WIDTH = 1024
        const val TABLET_HEIGHT = 1366
        const val TABLET_SCALE = 2.0

        /** One frame is not enough to be sure a close did not happen; a quarter second is. */
        const val SETTLE_MILLIS = 250.0

        /** Up to a second, in twentieths, for the held write to reach its interceptor. */
        const val HOLD_WAIT_STEPS = 20
        const val HOLD_WAIT_STEP_MILLIS = 50.0
    }
}
