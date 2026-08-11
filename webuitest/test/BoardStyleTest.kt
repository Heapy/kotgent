package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BoardStyleTest {


    @Test
    fun theBoardAndTheDetailWearTheTerminalPanesCardGeometry() =
        onScreen(DEEP_LINK_SCENARIO, "board-card-geometry") { harness, page ->
            page.navigate(harness.baseUrl + "/s/deep-session")
            val pane = page.locator("#terminal-pane")
            assertThat(pane).isVisible()
            val paneBox = pane.rect()
            val paneRadius = pane.style("border-top-left-radius")
            val paneShadow = pane.style("box-shadow")
            val paneFill = pane.style("background-color")
            assertNotEquals(
                TRANSPARENT,
                paneFill,
                "the pane the board borrows its geometry from is itself a filled card",
            )

            page.navigate(harness.baseUrl + "/tasks")
            val board = page.locator(".board")
            assertThat(board).isVisible()

            assertSameBox(paneBox, board.rect(), "the board takes exactly the terminal pane's slot")
            assertEquals(CARD_RADIUS, paneRadius, "the shell's pane radius is the one both are drawn to")
            assertEquals(paneRadius, board.style("border-top-left-radius"), "…and the board keeps it")
            assertNotEquals("none", paneShadow, "the pane really does cast a shadow, so the next line has teeth")
            assertEquals(paneShadow, board.style("box-shadow"), "…and the board casts the same one")
            assertNotEquals(
                TRANSPARENT,
                board.style("background-color"),
                "the board is a filled panel, not a hole in the shell",
            )

            page.navigate(taskUrl(harness.baseUrl, "local:7"))
            val detail = page.locator(".task-detail")
            assertThat(detail).isVisible()
            assertEquals(
                board.style("border-top-left-radius"),
                detail.style("border-top-left-radius"),
                "the detail panel is the same card as the board behind it",
            )
            assertEquals(
                board.style("background-color"),
                detail.style("background-color"),
                "…painted in the same panel fill",
            )
            assertNotEquals("none", detail.style("box-shadow"), "…and it casts a shadow of its own")
        }

    @Test
    fun theBoardNeverScrollsWhileEachColumnScrollsItsOwnListUnderAStickyHead() =
        onScreen(BOARD_SCENARIO, "board-column-scroll") { harness, page ->
            page.navigate(harness.baseUrl + "/tasks")
            assertThat(page.locator(".task-card")).hasCount(BOARD_CARDS)

            val todo = column(page, "todo")
            val done = column(page, "done")
            page.setViewportSize(DESKTOP_WIDTH, shortViewportHeight(page, todo, done))

            val board = page.locator(".board")
            assertClose(
                board.number("el => el.clientHeight"),
                board.number("el => el.scrollHeight"),
                "the board's own content fits it — the columns clip inside `.board-columns`",
            )

            assertTrue(
                todo.number("el => el.scrollHeight") > todo.number("el => el.clientHeight") + 1,
                "the short viewport really did overflow `todo`, or nothing below is being tested",
            )
            assertClose(
                done.number("el => el.clientHeight"),
                done.number("el => el.scrollHeight"),
                "`done` holds two cards and does not overflow — the control for the measurement above",
            )

            val before = BOARD_STATES.map { column(page, it).rect() }
            assertTrue(
                todo.number("el => { el.scrollTop = 9999; return el.scrollTop; }") > 0,
                "a long column scrolls inside its own track",
            )
            val after = BOARD_STATES.map { column(page, it).rect() }
            val boardBox = board.rect()
            for (index in BOARD_STATES.indices) {
                assertSameBox(
                    before[index],
                    after[index],
                    "scrolling `todo` moved the `${BOARD_STATES[index]}` column",
                )
                assertTrue(
                    after[index].bottom <= boardBox.bottom + 1 && after[index].top >= boardBox.top - 1,
                    "`${BOARD_STATES[index]}` stays inside the board — a long todo never pushes it off",
                )
            }

            val head = todo.locator(".board-column-head")
            val headBox = head.rect()
            assertClose(
                todo.rect().top + 1,
                headBox.top,
                "the head is pinned to the top of its own scrolled column (1px is the column's border)",
            )
            assertEquals(
                todo.style("background-color"),
                head.style("background-color"),
                "the head paints the column's own fill, inherited through `--column-fill`",
            )
            assertNotEquals(TRANSPARENT, head.style("background-color"), "…and that fill is opaque")
            val stack = page.stackAt(headBox.centerX, headBox.centerY)
            assertEquals(
                ".board-column-head",
                stack.firstOrNull(),
                "something is painted over the sticky head at its own centre; the stack was $stack",
            )
            assertTrue(
                stack.contains(".task-card"),
                "no card is behind the head, so this point proves nothing about cards showing through " +
                    "it — the column is not scrolled far enough; the stack was $stack",
            )
        }


    @Test
    fun eachColumnStateOwnsOneAccentThatItsHeadAndItsDropTintShare() =
        onScreen(BOARD_SCENARIO, "board-column-accents") { harness, page ->
            page.navigate(harness.baseUrl + "/tasks")
            assertThat(page.locator(".task-card")).hasCount(BOARD_CARDS)

            val headColours = linkedMapOf<String, String>()
            for (state in BOARD_STATES) {
                val section = column(page, state)
                val colour = section.locator(".board-column-head").style("color")
                assertEquals(
                    section.resolvedInside("var(--column-accent)"),
                    colour,
                    "the `$state` head is painted from its column's own accent property",
                )
                for ((other, seen) in headColours) {
                    assertNotEquals(seen, colour, "`$state` and `$other` share a head colour")
                }
                headColours[state] = colour
            }

            val idleFill = BOARD_STATES.associateWith {
                page.paintSettled(columnSelector(it))
                column(page, it).style("background-color")
            }
            val idleBorder = column(page, "in_progress").style("border-top-color")

            val handle = page.locator(".task-card[data-ref='local:1'] .task-card-handle")
            val grip = handle.rect()
            page.mouse().move(grip.centerX, grip.centerY)
            page.mouse().down()
            page.mouse().move(grip.centerX + 20, grip.centerY + 2)

            val card = page.locator(".task-card[data-ref='local:1']")
            assertThat(card).hasClass(Regex(".*\\bis-dragging\\b.*").toPattern())
            assertTrue(
                card.number("el => parseFloat(getComputedStyle(el).opacity)") < 1.0,
                "the dragged card lifts off the column",
            )
            assertThat(card).hasCSS("border-top-color", page.resolved("var(--accent)"))

            val tint = linkedMapOf<String, String>()
            val tintBorder = linkedMapOf<String, String>()
            for (state in listOf("in_progress", "review")) {
                val section = column(page, state)
                val box = section.rect()
                page.mouse().move(box.centerX, box.top + 12)
                assertThat(page.locator(".board-column[data-state='$state'].board-drop-target")).hasCount(1)
                page.paintSettled(columnSelector(state))

                val fill = section.style("background-color")
                assertNotEquals(idleFill[state], fill, "the `$state` column tints while the pointer is over it")
                assertEquals(
                    fill,
                    section.locator(".board-column-head").style("background-color"),
                    "…and the tint reaches the sticky head, which is what `--column-fill` buys",
                )
                tint[state] = fill
                tintBorder[state] = section.style("border-top-color")
            }
            assertNotEquals(
                tint["in_progress"],
                tint["review"],
                "the drop tint derives from each column's own accent, not from one drag colour",
            )
            assertNotEquals(
                idleBorder,
                tintBorder["in_progress"],
                "the drop target's border moves with its fill",
            )

            page.mouse().move(4.0, 4.0)
            page.mouse().up()
            assertThat(page.locator(".board-drop-target")).hasCount(0)
            for (state in BOARD_STATES) {
                page.paintSettled(columnSelector(state))
                assertEquals(
                    idleFill[state],
                    column(page, state).style("background-color"),
                    "the `$state` column returns to its idle fill when the drag ends",
                )
            }
        }

    @Test
    fun aLinkedSessionDotIsColouredForEveryStateInBothSpellings() =
        onScreen(TASK_LINKED_SESSION_SCENARIO, "board-session-dots") { harness, page ->
            page.navigate(taskUrl(harness.baseUrl, "local:1"))
            assertThat(page.locator(".task-detail")).isVisible()
            assertThat(page.locator(".task-card[data-ref='local:1']")).hasCount(1)

            val muted = page.resolved("var(--muted)")
            val measured = linkedMapOf<String, String>()
            for ((state, badge) in DOT_STATES) {
                harness.send("emit s-linked-1 $state")
                val onCard = page.locator(".task-card[data-ref='local:1'] .task-session-dot[data-state='$state']")
                val inPanel = page.locator(".task-detail .task-sessions .task-session-dot[data-state='$badge']")
                assertThat(onCard).hasCount(1)
                assertThat(inPanel).hasCount(1)

                val colour = onCard.style("background-color")
                assertEquals(
                    colour,
                    inPanel.style("background-color"),
                    "the card's `$state` dot and the panel's `$badge` dot are one state in two spellings",
                )
                assertNotEquals(muted, colour, "a `$state` dot fell through to the muted fallback")
                measured[state] = colour
            }

            assertEquals(
                measured["needs_approval"],
                measured["needs_answer"],
                "both attention states are one colour — `stateBadge` folds them onto one class",
            )
            val buckets = measured.filterKeys { it != "needs_answer" }
            for ((state, colour) in buckets) {
                for ((other, seen) in buckets) {
                    if (other == state) continue
                    assertNotEquals(seen, colour, "`$state` and `$other` are drawn in the same colour")
                }
            }
        }

    @Test
    fun everyActivityKindGetsItsOwnLeftRuleThatTheBorderShorthandCannotErase() =
        onScreen(TASK_DETAIL_SCENARIO, "board-activity-kinds") { harness, page ->
            page.navigate(taskUrl(harness.baseUrl, "local:3"))
            assertThat(page.locator("#task-detail-form")).isVisible()
            val rows = page.locator(".task-activity-row")
            val rendered = rows.count()
            assertTrue(rendered > 0, "the seeded task has an activity feed to measure")

            for (index in 0 until rendered) {
                val row = rows.nth(index)
                val kind = row.getAttribute("data-kind")
                assertEquals("3px", row.style("border-left-width"), "the `$kind` row keeps its 3px rule")
                assertEquals("1px", row.style("border-top-width"), "…on top of the ordinary 1px box")
                assertNotEquals(
                    row.style("border-top-color"),
                    row.style("border-left-color"),
                    "the `$kind` row's left rule is its own colour, not the shorthand's",
                )
            }

            val probe = rows.first()
            val original = probe.getAttribute("data-kind")
            try {
                val stripes = linkedMapOf<String, String>()
                for (kind in ACTIVITY_KINDS) {
                    probe.evaluate("(el, k) => { el.setAttribute('data-kind', k); }", kind)
                    val stripe = probe.style("border-left-color")
                    for ((other, seen) in stripes) {
                        assertNotEquals(seen, stripe, "`$kind` and `$other` feed rows are indistinguishable")
                    }
                    stripes[kind] = stripe
                }
                probe.evaluate("el => { el.setAttribute('data-kind', 'no-such-kind'); }")
                val fallback = probe.style("border-left-color")
                assertEquals(
                    probe.style("border-top-color"),
                    fallback,
                    "an unmatched kind falls back to the plain border — which is how the sweep above can fail",
                )
                for ((kind, stripe) in stripes) {
                    assertNotEquals(fallback, stripe, "the `$kind` row is not distinguishable from an unknown one")
                }
            } finally {
                probe.evaluate("(el, k) => { el.setAttribute('data-kind', k); }", original)
            }
        }


    @Test
    fun theTaskDetailFloatsOverTheBoardWithoutTakingAPixelFromItsColumns() =
        onScreen(BOARD_SCENARIO, "board-detail-float") { harness, page ->
            page.navigate(harness.baseUrl + "/tasks")
            assertThat(page.locator(".task-card")).hasCount(BOARD_CARDS)
            val closed = BOARD_STATES.map { column(page, it).rect() }
            val boardBox = page.locator(".board").rect()

            page.locator(".task-card[data-ref='local:3'] .task-card-title").click()
            val detail = page.locator(".task-detail")
            assertThat(detail).isVisible()
            assertThat(page.locator(".board")).isVisible()

            val opened = BOARD_STATES.map { column(page, it).rect() }
            for (index in BOARD_STATES.indices) {
                assertSameBox(
                    closed[index],
                    opened[index],
                    "opening the detail squeezed the `${BOARD_STATES[index]}` column",
                )
            }
            assertSameBox(boardBox, page.locator(".board").rect(), "…and the board itself did not move either")

            val app = page.locator("#app")
            val appBox = app.rect()
            val panel = detail.rect()
            assertClose(appBox.top + CARD_INSET, panel.top, "the panel is inset from the top of the shell")
            assertClose(appBox.right - CARD_INSET, panel.right, "…and from its right")
            assertClose(appBox.bottom - CARD_INSET, panel.bottom, "…and from its bottom")
            assertClose(
                (0.34 * appBox.width).coerceIn(320.0, 520.0),
                panel.width,
                "the panel keeps the bounded width the flex basis used to give it",
            )
            assertTrue(panel.left < boardBox.right, "the panel overlaps the board rather than sitting beside it")
            assertTrue(
                panel.left < opened.last().right,
                "…and `done` is what it covers, which is the accepted half of the trade",
            )
            assertTrue(
                page.hitTest(panel.centerX, panel.centerY, ".task-detail"),
                "the panel wins the hit test over the columns it covers",
            )

            app.evaluate("(el, w) => { el.style.borderRight = w + 'px solid transparent'; }", PROBE_BORDER_PX)
            try {
                assertClose(
                    appBox.right - PROBE_BORDER_PX - CARD_INSET,
                    detail.rect().right,
                    "the panel is positioned against `#app`, not against the viewport",
                )
            } finally {
                app.evaluate("el => { el.style.borderRight = ''; }")
            }
        }

    @Test
    fun thePhoneGivesTheDetailTheWholeScreenAndKeepsItsCloseButtonInTheCorner() =
        onScreen(BOARD_SCENARIO, "board-detail-phone", PHONE_WIDTH, PHONE_HEIGHT, mobile = true) { harness, page ->
            page.navigate(taskUrl(harness.baseUrl, "local:3"))
            assertThat(page.locator("#task-detail-form")).isVisible()
            assertThat(page.locator(".board")).isHidden()

            val panel = page.locator(".task-detail")
            assertSameBox(page.locator("#app").rect(), panel.rect(), "the detail is the whole phone screen")
            assertEquals("0px", panel.style("border-top-left-radius"), "…full-bleed, giving up the card inset")
            assertEquals("none", panel.style("box-shadow"), "…and the card shadow with it")

            val head = page.locator(".task-detail-head")
            val close = page.locator("#task-detail-close")
            val ident = page.locator("#task-detail-ident")
            val project = page.locator("#task-detail-project")
            val headBox = head.rect()
            val corner = close.rect()
            assertClose(headBox.right - PHONE_DETAIL_GUTTER, corner.right, "the × sits at the head's right edge")
            assertClose(headBox.top + PHONE_DETAIL_GUTTER, corner.top, "…at the top of it")
            assertTrue(
                page.locator("#task-detail-tools").rect().top >= ident.rect().bottom - 1,
                "the toolbar spans the row BELOW the identity, so it never pushes the × anywhere",
            )

            project.setText("/Users/operator/" + "checkout-".repeat(12) + "repo")
            assertSameBox(corner, close.rect(), "a long project path pushed the × out of the corner")
            assertTrue(
                project.number("el => el.scrollWidth") > project.number("el => el.clientWidth"),
                "the path ellipses instead of widening the elastic track",
            )
            assertTrue(ident.rect().right <= corner.left + 1, "…and the identity never grows into the ×")
            assertTrue(ident.rect().right <= panel.rect().right, "…nor past the panel it lives in")
        }


    @Test
    fun theBoardCollapsesAtTheBreakpointWhileTheDragReservationDoesNot() =
        onScreen(BOARD_SCENARIO, "board-breakpoint", BREAKPOINT + 1, DESKTOP_HEIGHT) { harness, page ->
            page.navigate(harness.baseUrl + "/tasks")
            assertThat(page.locator(".task-card")).hasCount(BOARD_CARDS)

            assertEquals(
                true,
                page.evaluate("() => matchMedia('(any-pointer: coarse)').matches"),
                "this arm is meant to be a wide TOUCH device; on a fine pointer it says nothing about " +
                    "the drag a finger makes",
            )

            val app = page.locator("#app")
            val board = page.locator(".board")
            assertThat(page.locator(".board-column")).hasCount(BOARD_STATES.size)
            assertThat(page.locator(".board-column-switch")).hasCount(0)
            var appBox = app.rect()
            var boardBox = board.rect()
            assertClose(appBox.top + CARD_INSET, boardBox.top, "one pixel above the breakpoint the board is an inset card")
            assertClose(appBox.right - CARD_INSET, boardBox.right, "…inset on the other side too")
            assertEquals(CARD_RADIUS, board.style("border-top-left-radius"), "…with the shell's corner radius")
            assertNotEquals("none", board.style("box-shadow"), "…and its shadow")
            assertDragReservation(page, "at ${BREAKPOINT + 1}px, one pixel above the phone breakpoint")

            page.setViewportSize(BREAKPOINT, DESKTOP_HEIGHT)
            assertThat(page.locator(".board-column")).hasCount(1)
            val switcher = page.locator(".board-column-switch")
            assertThat(switcher).isVisible()
            assertThat(switcher.locator("button")).hasCount(BOARD_STATES.size)
            appBox = app.rect()
            boardBox = board.rect()
            assertSameBox(appBox, boardBox, "one pixel below it the board is the whole screen")
            assertEquals("0px", board.style("border-top-left-radius"), "…with no radius")
            assertEquals("none", board.style("box-shadow"), "…and no shadow")
            assertDragReservation(page, "at ${BREAKPOINT}px, one pixel below it")
        }

    @Test
    fun theStackedPhoneColumnHoldsEveryCardWhileTheTrackScrollsUnderAPinnedHead() =
        onScreen(BOARD_SCENARIO, "board-phone-track", PHONE_WIDTH, PHONE_HEIGHT, mobile = true) { harness, page ->
            page.navigate(harness.baseUrl + "/tasks")
            val cards = page.locator(".task-card")
            assertThat(cards).hasCount(BOARD_CARDS - 5)

            val track = page.locator(".board-columns")
            val todo = column(page, "todo")
            page.setViewportSize(PHONE_WIDTH, overflowingPhoneHeight(page, track))
            assertTrue(
                track.number("el => el.scrollHeight") > track.number("el => el.clientHeight") + 1,
                "the derived viewport still fits the whole column — nothing below is being tested",
            )

            val port = track.rect()
            val idle = todo.rect()
            assertTrue(
                idle.height > port.height + 1,
                "the column $idle is no taller than the port $port it scrolls in — it was sized by the " +
                    "viewport rather than by its own cards",
            )
            assertCardsInside(cards, idle, "before the track is scrolled")

            val scrolled = track.number("el => { el.scrollTop = 9999; return el.scrollTop; }")
            assertTrue(scrolled > 0, "the stacked track is what scrolls on a phone")
            val moved = todo.rect()
            assertClose(idle.top - scrolled, moved.top, "the column travels with the track it is stacked in")
            assertCardsInside(cards, moved, "after the track is scrolled to the bottom")

            val head = todo.locator(".board-column-head")
            val headBox = head.rect()
            assertClose(port.top, headBox.top, "the head pins to the very top of the track")
            val stack = page.stackAt(headBox.centerX, headBox.centerY)
            assertEquals(
                ".board-column-head",
                stack.firstOrNull(),
                "something is painted over the pinned head at its own centre; the stack was $stack",
            )
            assertTrue(
                stack.contains(".task-card"),
                "no card is behind the head, so this point proves nothing about the ones it hides — the " +
                    "track is not scrolled far enough; the stack was $stack",
            )

            // The other half of the row's sizing: a column SHORTER than the port still fills it, which a
            // `max-content` row would not (it never reaches grid's stretch step).
            page.locator(".board-column-switch button[data-state='review']").click()
            val short = column(page, "review")
            assertThat(short).isVisible()
            assertThat(short.locator(".task-card")).hasCount(1)
            val contentBottom = track.number(
                "el => el.getBoundingClientRect().bottom - parseFloat(getComputedStyle(el).paddingBottom)",
            )
            val shortBox = short.rect()
            assertClose(contentBottom, shortBox.bottom, "a short column stops above the track's content box")
            assertTrue(
                shortBox.bottom > short.locator(".task-card").rect().bottom + 1,
                "…while its one card ends well above that, or this measures nothing",
            )
        }

    @Test
    fun thePhoneSwitcherIsFourEqualSegmentsThatSayWhichColumnIsOnScreen() =
        onScreen(BOARD_SCENARIO, "board-switcher", PHONE_WIDTH, PHONE_HEIGHT, mobile = true) { harness, page ->
            page.navigate(harness.baseUrl + "/tasks")
            assertThat(page.locator(".task-card")).hasCount(BOARD_CARDS - 5)

            val segments = page.locator(".board-column-switch button")
            assertThat(segments).hasCount(BOARD_STATES.size)

            val visible = page.locator(".board-column").rect()
            assertClose(visible.left, segments.first().rect().left, "the first segment starts where the column does")
            assertClose(visible.right, segments.last().rect().right, "…and the last one ends where it ends")

            val widths = BOARD_STATES.indices.map { segments.nth(it).rect().width }
            for (index in 1 until widths.size) {
                assertClose(
                    widths[0],
                    widths[index],
                    "the `${BOARD_STATES[index]}` segment is a different size from the `todo` one $widths",
                )
            }
            val label = segments.first().locator("span").first()
            val count = segments.first().locator("span").last()
            assertTrue(
                count.number("el => parseFloat(getComputedStyle(el).opacity)") <
                    label.number("el => parseFloat(getComputedStyle(el).opacity)"),
                "the count should step back from the label it annotates",
            )
            assertEquals("To do 5", segments.first().evaluate("el => el.textContent.trim()"))

            for (state in BOARD_STATES) {
                page.locator(".board-column-switch button[data-state='$state']").click()
                assertThat(page.locator(columnSelector(state))).hasCount(1)
                val pressed = page.locator(".board-column-switch button[aria-pressed='true']")
                assertThat(pressed).hasCount(1)
                assertEquals(state, pressed.getAttribute("data-state"), "the pressed segment is the shown column")
                page.paintSettled(".board-column-switch button[aria-pressed='true']")
                val accent = pressed.resolvedInside("var(--column-accent)")
                val idle = page.locator(".board-column-switch button[aria-pressed='false']").first()
                assertEquals(
                    pressed.resolvedInside("color-mix(in srgb, var(--column-accent) 20%, var(--bg))"),
                    pressed.style("background-color"),
                    "the `$state` segment should be filled from its own column's accent",
                )
                assertNotEquals(
                    idle.style("background-color"),
                    pressed.style("background-color"),
                    "the `$state` segment reads exactly like the three it is not",
                )
                assertEquals(
                    accent,
                    column(page, state).locator(".board-column-head").style("color"),
                    "the segment and the column head below it should say one colour",
                )
            }

            page.setViewportSize(NARROW_PHONE_WIDTH, PHONE_HEIGHT)
            page.paintSettled(".board-column-switch")
            for (index in BOARD_STATES.indices) {
                val segment = segments.nth(index)
                val box = segment.rect()
                val text = segment.locator("span")
                for (part in 0 until text.count()) {
                    val span = text.nth(part).textRect()
                    assertTrue(
                        span.left >= box.left - 0.5 && span.right <= box.right + 0.5,
                        "at ${NARROW_PHONE_WIDTH}px the `${BOARD_STATES[index]}` label $span leaves its " +
                            "own button $box",
                    )
                }
            }
        }


    @Test
    fun theNewTaskDialogInsetsItsPanelAndDressesItsTextareaAsAField() =
        onScreen(BOARD_SCENARIO, "board-new-task-dialog", coarse = false) { harness, page ->
            page.navigate(harness.baseUrl + "/tasks")
            assertThat(page.locator(".task-card")).hasCount(BOARD_CARDS)
            page.locator(".board-new-task").click()

            val dialog = page.locator("#new-task-dialog")
            assertThat(dialog).isVisible()
            assertThat(dialog.locator(".dialog-grabber")).isHidden()
            val panel = dialog.rect()
            val form = page.locator("#new-task-form").rect()
            assertClose(1.0, form.left - panel.left, "the panel's own padding is still zero on the left")
            assertClose(1.0, panel.right - form.right, "…and on the right")
            val blocks = linkedMapOf(
                "the head" to page.locator("#new-task-form .dialog-head"),
                "the title field" to page.locator("#new-task-title-input").locator("xpath=.."),
                "the actions row" to page.locator("#new-task-form .dialog-actions"),
            )
            for ((name, block) in blocks) {
                val box = block.rect()
                assertClose(DIALOG_INSET, box.left - form.left, "$name takes the form's left inset")
                assertClose(DIALOG_INSET, form.right - box.right, "$name takes the same inset on the right")
            }

            val input = page.locator("#new-task-title-input")
            val textarea = page.locator("#new-task-body")
            assertClose(
                input.rect().width,
                textarea.rect().width,
                "the textarea fills the form like every other field instead of being 20 `cols` wide",
            )
            assertEquals(
                input.style("background-color"),
                textarea.style("background-color"),
                "…dressed in the same field fill",
            )
            assertEquals(
                input.style("border-top-left-radius"),
                textarea.style("border-top-left-radius"),
                "…with the same corners",
            )
            assertEquals(
                input.style("font-family"),
                textarea.style("font-family"),
                "…in the same face, or the global `font: inherit` reset missed it",
            )
            assertEquals(
                "vertical",
                textarea.style("resize"),
                "…and resizable only along the axis that cannot break the panel's width",
            )

            assertEquals(
                "none",
                textarea.style("outline-style"),
                "an untouched field draws no ring, so the ring below is the focus and not the border",
            )
            page.keyboard().press("Tab")
            assertEquals(
                page.resolved("var(--accent)"),
                textarea.style("outline-color"),
                "a focused textarea takes the app's accent ring like every other field",
            )
            assertEquals("2px", textarea.style("outline-width"), "…at the app's ring width")

            val copy = page.locator("#new-task-form .dialog-head > div")
            val closeButton = page.locator("#new-task-form .dialog-head .icon-button")
            val copyBefore = copy.rect()
            val closeBefore = closeButton.rect()
            page.locator("#new-task-form .dialog-head p").setText("W".repeat(PROJECT_NAME_MAX_LENGTH))
            val closeAfter = closeButton.rect()
            assertClose(panel.width, dialog.rect().width, "a 100-character project name did not widen the panel")
            assertClose(closeBefore.right, closeAfter.right, "…and the × stayed in its corner")
            assertTrue(
                copy.rect().right <= closeAfter.left + 1,
                "an unbroken 100-character name never grows into the close button",
            )
            assertTrue(
                copy.rect().height > copyBefore.height,
                "…because it wrapped, which is the other half of what makes that possible",
            )
        }

    @Test
    fun theDetailsDescriptionOutranksTheSharedFieldRuleWithoutLosingItsWidth() =
        onScreen(TASK_DETAIL_SCENARIO, "board-detail-textarea") { harness, page ->
            page.navigate(taskUrl(harness.baseUrl, "local:3"))
            assertThat(page.locator("#task-detail-form")).isVisible()

            val input = page.locator("#task-detail-title-input")
            val body = page.locator("#task-detail-body")
            assertClose(
                input.rect().width,
                body.rect().width,
                "both fill the form — that width is what `:where(textarea)` is in the shared rule FOR",
            )
            assertNotEquals(
                input.style("border-top-left-radius"),
                body.style("border-top-left-radius"),
                "the description keeps its own corners, so the shared field rule did not outrank it",
            )
            assertNotEquals(input.style("padding-left"), body.style("padding-left"), "…and its own inset")
            assertTrue(
                body.rect().height >= DETAIL_BODY_MIN_HEIGHT,
                "…and its own height, which the shared rule's 38px minimum would have taken away",
            )

            val form = page.locator("#task-detail-form").rect()
            val box = body.rect()
            assertTrue(
                box.left > form.left && box.right < form.right,
                "the description sits inside the panel's gutter rather than reaching past it",
            )
        }


    @Test
    fun theUnknownTaskBadgeIsAPillOfItsOwnInTheMonospaceFace() =
        onScreen(TASK_LINKED_SESSION_SCENARIO, "board-task-badges") { harness, page ->
            page.navigate(harness.baseUrl + "/")
            val known = page.locator("a.task-badge:not(.task-badge-unknown)").first()
            val unknown = page.locator("a.task-badge-unknown").first()
            assertThat(known).isVisible()
            assertThat(unknown).isVisible()

            for (shape in listOf("border-top-left-radius", "padding-left", "font-size")) {
                assertEquals(
                    known.style(shape),
                    unknown.style(shape),
                    "the two badges are the same pill: `$shape` must not be one of the overrides",
                )
            }
            assertTrue(
                unknown.style("font-family").startsWith("Menlo"),
                "the unknown badge shows the bare ref in the monospace face",
            )
            assertNotEquals(
                unknown.style("font-family"),
                known.style("font-family"),
                "…which the resolved badge, showing a title, is not",
            )
            assertEquals("dashed", unknown.style("border-top-style"), "…outlined rather than filled")
            assertEquals("solid", known.style("border-top-style"), "…unlike the resolved one")
            assertEquals(
                TRANSPARENT,
                unknown.style("background-color"),
                "…and it drops the accent fill: it is no longer claiming to name anything",
            )
            assertNotEquals(
                TRANSPARENT,
                known.style("background-color"),
                "…while a resolved ref is an accent pill",
            )

            page.navigate(harness.baseUrl + "/s/s-linked-1")
            val header = page.locator("#terminal-task")
            assertThat(header).isVisible()
            val title = page.locator("#terminal-title")
            assertTrue(title.rect().width > 0, "the session's own name is on the header row beside it")
            header.setLabel("W".repeat(120))
            assertTrue(
                header.rect().width <= BADGE_MAX_WIDTH + 1,
                "a long task title is clamped rather than allowed to grow the badge",
            )
            assertTrue(
                header.number("el => el.scrollWidth") > header.number("el => el.clientWidth"),
                "…which is a truncation, so the ellipsis has something to do",
            )
            val identity = page.locator(".terminal-identity")
            assertClose(
                identity.number("el => el.clientWidth"),
                identity.number("el => el.scrollWidth"),
                "…and the header row it shares never overflows, so the badge truncated instead of pushing",
            )
            assertTrue(title.rect().width > 0, "…leaving the session name a box of its own")
        }


    private fun onScreen(
        scenario: String,
        trace: String,
        width: Int = DESKTOP_WIDTH,
        height: Int = DESKTOP_HEIGHT,
        mobile: Boolean = false,
        coarse: Boolean = true,
        block: (Harness, Page) -> Unit,
    ) {
        Harness(scenario).use { harness ->
            onChromium { browser ->
                val context = if (coarse) {
                    browser.touchContext(width, height, if (mobile) 3.0 else 1.0, mobile)
                } else {
                    browser.fineContext(width, height)
                }
                context.use {
                    context.loginWithTicket(harness.ticket, harness.baseUrl)
                    context.traced(trace) { block(harness, context.newPage()) }
                }
            }
        }
    }

    private fun taskUrl(baseUrl: String, ref: String): String = baseUrl + "/tasks/" + ref.replace(":", "%3A")

    private fun shortViewportHeight(page: Page, longer: Locator, shorter: Locator): Int {
        // The squeeze makes scrollHeight expose content instead of the column's stretched box.
        page.setViewportSize(DESKTOP_WIDTH, PROBE_HEIGHT)
        val track = longer.number("el => el.clientHeight")
        assertTrue(track > 0, "the probe viewport left no column track at all to measure the chrome from")
        val chrome = PROBE_HEIGHT - track
        val shorterContent = shorter.number("el => el.scrollHeight")
        val longerContent = longer.number("el => el.scrollHeight")
        assertTrue(
            longerContent > shorterContent + 2 * COLUMN_SLACK,
            "the fixture's two columns have to differ by more than the slack for one height to separate " +
                "them — measured ${longerContent}px against ${shorterContent}px",
        )
        return (chrome + shorterContent + COLUMN_SLACK).toInt()
    }

    private fun overflowingPhoneHeight(page: Page, track: Locator): Int {
        // On a tall viewport scrollHeight clamps to clientHeight, so measure content while squeezed.
        page.setViewportSize(PHONE_WIDTH, PROBE_HEIGHT)
        val port = track.number("el => el.clientHeight")
        assertTrue(port > 0, "the probe viewport left no track at all to measure the chrome from")
        val content = track.number("el => el.scrollHeight")
        assertTrue(
            content > port + PHONE_TRACK_OVERFLOW,
            "the probe viewport did not overflow the track by enough to measure the cards at all — " +
                "${content}px of content in a ${port}px port",
        )
        return (PROBE_HEIGHT - port + content - PHONE_TRACK_OVERFLOW).toInt()
    }

    private fun assertCardsInside(cards: Locator, box: Box, where: String) {
        val count = cards.count()
        assertTrue(count > 0, "$where there were no cards to measure at all")
        for (index in 0 until count) {
            val card = cards.nth(index).rect()
            assertTrue(
                card.top >= box.top - 1 && card.bottom <= box.bottom + 1,
                "$where card ${index + 1} of $count $card is painted outside its column $box",
            )
        }
    }

    private fun columnSelector(state: String): String = ".board-column[data-state='$state']"

    private fun column(page: Page, state: String): Locator = page.locator(columnSelector(state))

    private fun Page.paintSettled(selector: String) {
        // Computed colors are stable only after the subtree's CSS transitions finish.
        waitForFunction(
            "s => { const el = document.querySelector(s); return !!el && " +
                "el.getAnimations({ subtree: true }).every((a) => a.playState === 'finished'); }",
            selector,
        )
    }

    private fun assertDragReservation(page: Page, where: String) {
        assertEquals(
            "none",
            page.locator(".task-card-handle").first().style("touch-action"),
            "$where the card handle stops reserving the vertical gesture",
        )
        for (selector in listOf(".task-card", ".board-column", ".board-columns", ".board")) {
            assertNotEquals(
                "none",
                page.locator(selector).first().style("touch-action"),
                "$where `$selector` took the finger's scroll away — the reservation is the handle's alone",
            )
        }
    }

    private class Box(val left: Double, val top: Double, val width: Double, val height: Double) {
        val right get() = left + width
        val bottom get() = top + height
        val centerX get() = left + width / 2
        val centerY get() = top + height / 2
        override fun toString(): String = "[${round(left)}, ${round(top)} ${round(width)}×${round(height)}]"
        private fun round(value: Double): Double = (value * 10).toInt() / 10.0
    }

    private fun Locator.rect(): Box {
        val raw = evaluate(
            "el => { const r = el.getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; }",
        ) as List<*>
        return Box(raw.at(0), raw.at(1), raw.at(2), raw.at(3))
    }

    private fun List<*>.at(index: Int): Double = (this[index] as Number).toDouble()

    private fun Locator.textRect(): Box {
        val raw = evaluate(
            "el => { const range = document.createRange(); range.selectNodeContents(el);" +
                " const r = range.getBoundingClientRect(); range.detach();" +
                " return [r.left, r.top, r.width, r.height]; }",
        ) as List<*>
        return Box(raw.at(0), raw.at(1), raw.at(2), raw.at(3))
    }

    private fun Locator.style(property: String): String =
        evaluate("(el, p) => getComputedStyle(el).getPropertyValue(p)", property) as String

    private fun Locator.number(expression: String): Double = (evaluate(expression) as Number).toDouble()

    private fun Locator.setText(text: String) {
        evaluate("(el, t) => { el.textContent = t; }", text)
    }

    private fun Locator.setLabel(text: String) {
        evaluate("(el, t) => { el.childNodes[el.childNodes.length - 1].nodeValue = t; }", text)
    }

    private fun Page.resolved(value: String): String = evaluate(
        "v => { const probe = document.createElement('span'); probe.style.color = v;" +
            " document.body.appendChild(probe); const c = getComputedStyle(probe).color;" +
            " probe.remove(); return c; }",
        value,
    ) as String

    private fun Locator.resolvedInside(value: String): String = evaluate(
        "(el, v) => { const probe = document.createElement('span'); probe.style.color = v;" +
            " el.appendChild(probe); const c = getComputedStyle(probe).color; probe.remove(); return c; }",
        value,
    ) as String

    private fun Page.hitTest(x: Double, y: Double, selector: String): Boolean = evaluate(
        "a => { const el = document.elementFromPoint(a[0], a[1]); return !!(el && el.closest(a[2])); }",
        listOf(x, y, selector),
    ) as Boolean

    private fun Page.stackAt(x: Double, y: Double): List<String> {
        val raw = evaluate(
            """
            (a) => {
              const [x, y, selectors] = a;
              const out = [];
              for (const el of document.elementsFromPoint(x, y)) {
                for (const selector of selectors) {
                  if (el.closest(selector) && !out.includes(selector)) out.push(selector);
                }
              }
              return out;
            }
            """.trimIndent(),
            listOf(x, y, STACK_SELECTORS),
        ) as List<*>
        return raw.map { it.toString() }
    }

    private fun assertClose(expected: Double, actual: Double, message: String, tolerance: Double = 1.0) {
        assertTrue(abs(expected - actual) <= tolerance, "$message — expected ≈$expected, measured $actual")
    }

    private fun assertSameBox(expected: Box, actual: Box, message: String) {
        assertTrue(
            abs(expected.left - actual.left) <= 0.5 && abs(expected.top - actual.top) <= 0.5 &&
                abs(expected.width - actual.width) <= 0.5 && abs(expected.height - actual.height) <= 0.5,
            "$message — expected $expected, measured $actual",
        )
    }

    private companion object {
        const val DESKTOP_WIDTH = 1280
        const val DESKTOP_HEIGHT = 800

        const val PROBE_HEIGHT = 240

        const val COLUMN_SLACK = 12.0
        const val PHONE_WIDTH = 390
        const val PHONE_HEIGHT = 844

        const val PHONE_TRACK_OVERFLOW = 160.0

        const val NARROW_PHONE_WIDTH = 320

        const val BREAKPOINT = 720

        const val BOARD_CARDS = 10

        const val DIALOG_INSET = 20.0

        const val CARD_INSET = 12.0
        const val CARD_RADIUS = "14px"

        const val PROBE_BORDER_PX = 40.0

        const val PHONE_DETAIL_GUTTER = 10.0

        const val DETAIL_BODY_MIN_HEIGHT = 132.0

        const val BADGE_MAX_WIDTH = 120.0

        // Duplicated across the native/JVM boundary; an import-backed native test pins the value.
        const val PROJECT_NAME_MAX_LENGTH = 100

        const val TRANSPARENT = "rgba(0, 0, 0, 0)"

        val STACK_SELECTORS = listOf(".board-column-head", ".task-card", ".board-column", ".board")

        val BOARD_STATES = listOf("todo", "in_progress", "review", "done")

        val DOT_STATES = listOf(
            "running" to "badge-running",
            "ready" to "badge-ready",
            "needs_approval" to "badge-attention",
            "needs_answer" to "badge-attention",
            "stopped" to "badge-dead",
            "crashed" to "badge-crashed",
            "resumable" to "badge-resumable",
        )

        val ACTIVITY_KINDS = listOf("created", "comment", "transition", "linked", "unlinked")
    }
}
