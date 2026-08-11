package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * How the task board and the task detail are actually LAID OUT and PAINTED, measured in a browser.
 *
 * ## What this replaces, and why the replacement is a different kind of test
 * The file this supersedes (`test/transport/WebUiBoardStyleTest.kt`) fetched `/style.css` over HTTP and
 * asserted that particular declarations appeared, as text, inside particular rules. That could prove a
 * string was written down; it could not prove a single pixel, and every one of its assertions would have
 * gone on passing against a rule that matched nothing, lost the cascade, or was overridden two hundred
 * lines further down. Every check below reads the browser's own answer instead: a
 * `getBoundingClientRect`, a scroll metric, a hit test, or a COMPUTED value on a real element — the
 * resolved end of the cascade rather than its source.
 *
 * Three habits follow from that, and they are what make these assertions falsifiable where a text search
 * was not:
 *
 * - **Relationships, not literals.** No colour constant is spelled here. A column head's tint is asserted
 *   to BE its own column's `--column-accent` (measured through a probe element) and to differ from the
 *   other three; a state dot's colour is asserted to equal the colour the OTHER spelling of the same
 *   state produces. Re-theming the app changes nothing below; breaking a derivation fails it.
 * - **Both sides of the breakpoint, in one page.** Where the old file located a rule by its character
 *   offset relative to `@media (max-width: 720px)`, this crosses 721 → 720 px and measures what moved.
 *   That is the stronger statement (the two shapes really do flip at one width) and it costs one call.
 * - **Long content is supplied where the fixture's is short.** Two rules exist only to survive a value
 *   the daemon can hand the page — a 100-character project name, an arbitrarily long project path. The
 *   fixture's own values are short, so the test writes a long one into the rendered node and measures the
 *   consequence. The element and the rule are real; only the length is the test's.
 *
 * ## What was dropped, deliberately
 * The old file's first test walked the plan's frozen "Board CSS vocabulary" and asserted every name was a
 * selector somewhere in the sheet. There is no browser counterpart worth writing — a name that matches
 * nothing shows up here as an element with no ink, which is what the measurements below are for — and its
 * companion half (that the markup EMITS those names) survives in `test/transport/WebUiServingTest.kt`.
 * Two smaller
 * assertions went the same way rather than being faked into a pass; each is named at the point where it
 * would have gone.
 */
class BoardStyleTest {

    // --- the board's own frame -----------------------------------------------------------------

    /**
     * The two screens the router can put where the terminal pane goes have to behave like the pane they
     * replace, or switching to the board rearranges the whole shell around it.
     *
     * The old assertion compared declaration text — `flex: 1 1 auto`, `min-width: 0`, `margin: 12px`, and
     * the mere PRESENCE of a radius, a shadow and a background — against the pane's own rule. What all of
     * that is FOR is one thing: the board must occupy the same rectangle, with the same card ink. So this
     * measures the pane on `/s/{id}`, then the board on `/tasks`, in one page at one size, and demands the
     * same box back. `deep-link` is the one scenario that owns both a session and a task.
     *
     * The backgrounds are deliberately NOT compared with each other: the pane is `--term-bg` and the board
     * is `--panel`, which is right — a terminal is black. What both must be is opaque, i.e. a card rather
     * than a hole in the shell.
     */
    @Test
    fun theBoardAndTheDetailWearTheTerminalPanesCardGeometry() =
        onScreen(DEEP_LINK_SCENARIO, "board-card-geometry") { harness, page ->
            page.navigate(harness.baseUrl + "/s/deep-session")
            val pane = page.locator("#terminal-pane")
            assertThat(pane).isVisible()
            // Everything the pane is asked for is read HERE, while the pane is on screen. `/tasks` is the
            // other arm of the same branch in `app.js` — it unmounts the pane outright — so a `pane.…`
            // read deferred until after that navigation resolves against nothing and waits out its whole
            // timeout on an element the router has deliberately destroyed.
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
            // …and so is the pane it borrows the geometry from, asserted above where `paneFill` is read.
            // The two values are deliberately never compared with each other: the pane is `--term-bg` and
            // the board is `--panel`, which is right, because a terminal is black.

            // The detail is the third occupant of that slot. On a desktop it leaves the flow (see
            // `theTaskDetailFloatsOverTheBoard…`), so what has to match there is the INK, not the box.
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

    /**
     * The board clips and each column scrolls; that is what stops a long `todo` from pushing `done` off
     * the bottom of the screen.
     *
     * Measured rather than read: the viewport is made short enough that `todo` (five cards, one carrying a
     * blocked marker) genuinely overflows, and the three claims are then checked as facts — the board's
     * own content fits the board, the overflowing column really scrolls, and the four column boxes are
     * unchanged by that scroll and still inside the board. `done`, which holds two cards, does not
     * overflow: it is the control that proves the first measurement is not simply reporting "no column
     * ever overflows".
     *
     * That height is DERIVED from the rendered board rather than picked (see [shortViewportHeight]) — the
     * arithmetic behind a guessed one is invisible in the number, and the first guess written here was
     * short enough to overflow the control column too, which quietly removes the contrast the test is
     * built on.
     *
     * The sticky head rides along here because it means nothing until something scrolls under it. Both of
     * its halves are measured: it stays pinned to the top of its column, and a hit test at its centre
     * lands on the head rather than on the card behind it — which is the "cards never scroll through it"
     * that inheriting the opaque `--column-fill` was written for.
     */
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
            // A hit test at the head's centre is nearly free of content on its own — `elementFromPoint`
            // does not care about transparency, and a sticky head is above the in-flow cards by
            // definition. What makes it a statement is the STACK: the head has to be on top AND a card
            // has to be underneath it at that very point, which is only true because the column really is
            // scrolled far enough for one to have travelled behind the head.
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

    // --- colour that is derived, not repeated ---------------------------------------------------

    /**
     * A column's identity is ONE custom property, and both the head's label and the drag's drop highlight
     * derive from it. Written as two rules that must agree: if the accent were spelled per-rule, a fifth
     * state (or a recoloured one) would light the head and leave the drag feedback on the old colour.
     *
     * The old test read that agreement out of the stylesheet's text. Here the drag is REAL — a pointer
     * press on a card's handle, past the 8px slop, over one column and then another — and the agreement is
     * measured three ways: the tint reaches the sticky head (the head's background moves with the
     * column's, which is the whole reason the rule sets `--column-fill` rather than `background`), the
     * tint is DIFFERENT over a different column (so it derives from that column's accent instead of being
     * one hard-coded drag colour), and the border moves with it.
     *
     * The gesture is released over the sidebar, where `dropTargetAt` finds no column: no drop, no request.
     * This test measures paint and mutates no backlog. The dragged card's own lifted state is checked on
     * the way past — it is the third thing a drag paints.
     *
     * Every colour here is read only once its 120ms transition is over ([paintSettled], and Playwright's
     * own retrying `hasCSS` for the one value known in advance). Both `.board-column` and `.task-card`
     * transition `background` and `border-color`, so the value in the instant after a pointer move is the
     * one the transition started FROM — the accent read would report the idle border, and the fill and its
     * head, being two round trips, need not even have reached the same point of the same curve. The
     * drop-target readings are also taken while that column IS the target: the pointer moves on to the
     * next column, and a border sampled afterwards is one on its way back to idle, which is a value that
     * proves nothing and would flip to a failure the moment it settled.
     */
    @Test
    fun eachColumnStateOwnsOneAccentThatItsHeadAndItsDropTintShare() =
        onScreen(BOARD_SCENARIO, "board-column-accents") { harness, page ->
            page.navigate(harness.baseUrl + "/tasks")
            assertThat(page.locator(".task-card")).hasCount(BOARD_CARDS)

            // Every column head is tinted from its OWN column's `--column-accent`, and the four differ.
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
            // Past the slop, which is what turns the press into a drag rather than a click.
            page.mouse().move(grip.centerX + 20, grip.centerY + 2)

            val card = page.locator(".task-card[data-ref='local:1']")
            assertThat(card).hasClass(Regex(".*\\bis-dragging\\b.*").toPattern())
            assertTrue(
                card.number("el => parseFloat(getComputedStyle(el).opacity)") < 1.0,
                "the dragged card lifts off the column",
            )
            // …and outlines itself in the app's accent while it is in play. Retried rather than read, for
            // the 120ms the border spends travelling there.
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

            // Released over the sidebar: no column under the pointer, so no drop and no request.
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

    /**
     * A linked session's dot is coloured for every state, in BOTH spellings the components emit — the raw
     * `SessionState` on a card (`TaskCard.js`) and the `cls` half of `stateBadge`'s return in the detail
     * panel (`TaskDetail.js`). A dot whose value matched no rule would render in the muted fallback, which
     * reads as a real state rather than as a miss.
     *
     * The old test asserted thirteen selectors existed. This walks one session through all seven states
     * with the harness's `emit` and, for each, measures the two dots that are on screen at the same time —
     * the card behind the panel and the row inside it — and demands they agree. That is the actual
     * contract ("the two spellings collapse to the same six colours"), and it also proves the fallback is
     * never reached, since `--muted` is resolved here and compared against.
     *
     * `needs_approval` and `needs_answer` are expected to MATCH on purpose: `stateBadge` folds both onto
     * `badge-attention`, so a card that distinguished them would be the drift this looks for.
     */
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
            // The six remaining buckets are pairwise distinct, or two states are indistinguishable.
            val buckets = measured.filterKeys { it != "needs_answer" }
            for ((state, colour) in buckets) {
                for ((other, seen) in buckets) {
                    if (other == state) continue
                    assertNotEquals(seen, colour, "`$state` and `$other` are drawn in the same colour")
                }
            }
        }

    /**
     * One rule per `ActivityKind`; a feed row whose kind matched nothing would lose its left rule and read
     * as an ordinary box.
     *
     * Two halves, and the second is the one a text search could only approximate. First, every row the
     * fixture actually renders is checked as drawn: a 3px left border in a colour that is NOT the colour of
     * its other three. That one comparison is the whole "the left rule is written after the border
     * shorthand that would otherwise erase it" assertion, and it fails exactly the way a reordered
     * stylesheet would.
     *
     * Second, the enum is swept on a REAL row by driving its `data-kind` through the five values the
     * domain can produce. Coverage of an enum is a statement about values the fixture need not contain, so
     * this is the honest way to ask it in a browser — and the sweep is made falsifiable by ending on a
     * value no rule matches, which must fall back to the plain border colour. Without that last step, a
     * sweep in which every per-kind rule had been deleted would look identical to a passing one.
     */
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

            // The enum sweep, on the first rendered row. Restored from a `finally`: a failure part-way
            // through would otherwise leave the row wearing a kind the fixture never produced, and that
            // patched page is what `traced` photographs for whoever reads the failure.
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

    // --- the detail floats, and does not squeeze --------------------------------------------------

    /**
     * At `/tasks/{ref}` the board and the detail are SIBLINGS in `#app`'s flex row, so any detail that
     * stays IN that row is paid for by the board: two `flex: 1 1 auto` claimants split the viewport, and
     * even bounding the panel left the four tracks dividing what remained — about 150px each on a 1440px
     * window, narrower than the title of the card inside them. So the panel leaves the flow and floats
     * over the board, and the board keeps every pixel it has when no task is open.
     *
     * This is the measurement that claim was always asking for: the four column boxes are recorded with no
     * task open, a card is opened, and the same four boxes are demanded back unchanged. Then the float
     * itself — bounded width, pinned to three edges of `#app`, overlapping the board's right, and winning
     * the hit test inside that overlap, which is the `z-index` over the columns' sticky heads.
     *
     * The last step pins the cross-rule dependency the panel's own rule cannot state: `#app` carries
     * `position: relative`, so the panel's containing block is the SHELL rather than the initial one. In
     * this layout the two rectangles coincide, so the difference is made observable by giving `#app` a
     * temporary right border — which shrinks its padding box, and therefore moves an absolutely positioned
     * child anchored to it, while a child anchored to the viewport would not move at all.
     */
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
            // `clamp(320px, 34%, 520px)` against `#app`'s content box — the old flex basis, literally.
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

            // Restored from a `finally`, because a failure BETWEEN the two would otherwise leave the shell
            // 40px narrower — and that patched page is exactly what `traced` screenshots on the way out.
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

    /**
     * The phone answer is the opposite: there is no room to overlay either, so the detail REPLACES the
     * board — which is also what the head's × already promises, since it goes back to `/tasks`, i.e. back
     * to a board this hid.
     *
     * The × is the second subject here, and it is why the head is a grid rather than a wrapping flex row.
     * Flowed, it was the last item of a row that wraps as soon as the identity beside it is wide — which,
     * with a project path in it on a 320px panel, is always — so the one control all four head shapes
     * promise ended up under the launch button. Placed, it is in the top-right corner whatever else the
     * head contains. The fixture's path is short, so a long one is written into the rendered node: the
     * corner must not move, the path must ellipse instead of widening its track, and the toolbar must
     * still be on the row below.
     */
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

    // --- one breakpoint, and one gesture that is not scoped to it ---------------------------------

    /**
     * The phone branch: one column, the switcher that picks it, and both screens giving up the card inset
     * — all inside the ONE width breakpoint the rest of the sheet uses, because a second breakpoint is how
     * two screens start disagreeing about what "mobile" means.
     *
     * The old file asserted that by character offset: a rule was "inside" the breakpoint if its index fell
     * between two `@media` strings. Here the viewport crosses 721 → 720 px in one page and both shapes are
     * measured. That says the same thing about a single boundary while also proving each shape renders —
     * and `Board.js` watches the same query through `matchMedia`, so the component's idea of "phone" is
     * measured against the stylesheet's in the same step.
     *
     * The drag reservation rides along as the counter-example, which is the whole point of pairing them:
     * `touch-action: none` on the handle must hold on BOTH sides, because a touch tablet is wider than the
     * phone breakpoint and drags with a finger all the same — while its four ancestors must never take the
     * reservation, or the finger loses the scroll that reaches the rest of the backlog.
     */
    @Test
    fun theBoardCollapsesAtTheBreakpointWhileTheDragReservationDoesNot() =
        onScreen(BOARD_SCENARIO, "board-breakpoint", BREAKPOINT + 1, DESKTOP_HEIGHT) { harness, page ->
            page.navigate(harness.baseUrl + "/tasks")
            assertThat(page.locator(".task-card")).hasCount(BOARD_CARDS)

            // The device this runs on, stated rather than implied: a TOUCH tablet one pixel above the
            // phone breakpoint, which is exactly the machine the drag-reservation half is about. The
            // board's own collapse is a width query and answers the same on either kind of pointer.
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

    /**
     * The phone counterpart of [theBoardNeverScrollsWhileEachColumnScrollsItsOwnListUnderAStickyHead], and
     * its inversion: there the board clips and every column scrolls its own list, here the TRACK scrolls
     * and the one column on screen is simply as tall as the cards it holds.
     *
     * It shipped broken, and the mechanism is worth writing down because the repair is one line of track
     * sizing. `.board-columns` is a grid whose rows are all implicit, so the stacked column sat in an
     * `auto` row; such a row takes its base size from the item's MINIMUM contribution, which
     * `.board-column`'s `min-height: 0` pins at zero, and the algorithm then maximizes that base into the
     * free space of a container whose own height is definite. The row therefore came out exactly one
     * scrollport tall whatever the column held — and since a phone column's overflow is `visible` (the
     * track is what scrolls), every card past that point was painted OUTSIDE it: on a long `todo` the
     * fill, the border and the rounded corner ended part-way down the list and the rest of the backlog
     * hung in the board's own background, under a head pinned over a box its content had left.
     *
     * So the observable is containment, measured on both sides of a scroll, plus the statement that makes
     * it falsifiable: the column is TALLER than the port it scrolls in. A column clipped to the scrollport
     * is exactly as tall as it, so that line is what fails the moment a row is sized by the viewport again
     * — while containment alone would pass on the broken shape at any height short enough to hold the
     * whole fixture.
     *
     * The head rides along as it does on the desktop, but its claim here is the phone's own: it pins to
     * the very top of the track, with no band above it for a card to travel through in full view. That gap
     * belongs to the switcher, which is outside the scrolling box entirely.
     */
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
        }

    /**
     * The phone's switcher, which is the only way three of the four columns are reachable there — and
     * which shipped with three defects at once, each measurable and none visible to a stylesheet search.
     *
     * It had no ink for `aria-pressed`, so the four segments were identical and which column was on
     * screen could be learned only by reading the head below them. It had no inset, so the first segment
     * started hard against the edge of the phone while the column it switches to started eight pixels
     * in. And its buttons were sized by their own words (measured: 79/113/87/77 across a 390px phone),
     * so the row read as four differently-sized answers to one question.
     *
     * All three are asserted as relationships rather than literals. The inset is compared against the
     * COLUMN's own left edge, so re-tuning the board's gutter moves both or fails here. The pressed ink
     * is compared against the pressed segment's own `--column-accent` — the same property the column head
     * paints from, resolved through a probe inside the element — so the segment and the column below it
     * are proven to be saying the same colour rather than two colours that happen to match today.
     * Equality is compared across the four widths.
     *
     * The narrow arm is the one that fixes the size in place. Four equal quarters of a 320px phone cannot
     * hold "In progress 2", and what keeps the label inside its border there is the flex automatic
     * minimum — `min-width: auto` on an item resolves to its min-content size, so the long segment simply
     * takes what it needs. That is free until somebody writes the `min-width: 0` this shape usually
     * invites, which switches it off and puts the label back over the corner. So the observable here is
     * containment rather than a width, and it is measured on the GLYPHS: a span is itself a flex item and
     * shrinks below its own text without complaint, so its box stays put while the text spills. Asserting
     * equality alone would pass on exactly the broken row.
     */
    @Test
    fun thePhoneSwitcherIsFourEqualSegmentsThatSayWhichColumnIsOnScreen() =
        onScreen(BOARD_SCENARIO, "board-switcher", PHONE_WIDTH, PHONE_HEIGHT, mobile = true) { harness, page ->
            page.navigate(harness.baseUrl + "/tasks")
            assertThat(page.locator(".task-card")).hasCount(BOARD_CARDS - 5)

            val segments = page.locator(".board-column-switch button")
            assertThat(segments).hasCount(BOARD_STATES.size)

            // The row lines up with the board it switches, on both edges.
            val visible = page.locator(".board-column").rect()
            assertClose(visible.left, segments.first().rect().left, "the first segment starts where the column does")
            assertClose(visible.right, segments.last().rect().right, "…and the last one ends where it ends")

            // Four equal boxes, and the count is a quieter span than the label rather than one text node.
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
            // The space between the two spans is what the accessible name is computed from.
            assertEquals("To do 5", segments.first().evaluate("el => el.textContent.trim()"))

            // The pressed segment wears its own column's accent; the other three do not.
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

            // The narrowest phone still in use: the quarters no longer fit the longest label, so the rule
            // that has to hold is containment, not equality.
            page.setViewportSize(NARROW_PHONE_WIDTH, PHONE_HEIGHT)
            page.paintSettled(".board-column-switch")
            for (index in BOARD_STATES.indices) {
                val segment = segments.nth(index)
                val box = segment.rect()
                val text = segment.locator("span")
                for (part in 0 until text.count()) {
                    // The GLYPHS, not the span. A span is itself a flex item and shrinks below its own
                    // text without complaint, so its box stays inside the button while the label prints
                    // over the border — the exact failure this arm exists for, and one that a
                    // `getBoundingClientRect` on the element cannot see.
                    val span = text.nth(part).textRect()
                    assertTrue(
                        span.left >= box.left - 0.5 && span.right <= box.right + 0.5,
                        "at ${NARROW_PHONE_WIDTH}px the `${BOARD_STATES[index]}` label $span leaves its " +
                            "own button $box",
                    )
                }
            }
        }

    // --- the dialog and the two textareas ---------------------------------------------------------

    /**
     * The board's dialogs wear the shell's dialog chrome, and both halves of that shipped missing.
     *
     * `dialog` itself is `padding: 0` — the inset belongs to each FORM, so that the grabber above it can
     * span the full width — and `#new-task-form` was simply absent from the group that declares it. That
     * is not a slightly tighter dialog: the head, every field and the actions row all sat flush against
     * the border. Measured here as the geometry it is — every block in the form shares one inset from the
     * panel's edge, the same on both sides.
     *
     * The textarea is the second half, and it is specific to this screen: New task is the only DIALOG with
     * one. `.field input, .field select` never mentioned it, so it fell through to the UA — a replaced
     * element whose `auto` width is its `cols` attribute rather than its container, in a monospace face of
     * its own. Both consequences are measurable: its box must be exactly as wide as the input above it,
     * and its face must be that input's rather than the UA's.
     *
     * The focus ring is the third: a field-dressed textarea takes the app's accent ring like every other
     * field, which is a computed colour in two states — exactly what a ring is for.
     *
     * The head's long copy is the fourth. A dialog head is a flex row (copy left, × right) and a flex item
     * will not shrink below its content unless it is told to, while this head interpolates the PROJECT
     * NAME — up to `PROJECT_NAME_MAX_LENGTH` characters with no guaranteed break opportunity, since
     * `validProjectName` bars control characters and nothing else. The fixture's project name is short, so
     * the longest legal one is written in and the head is measured under it.
     */
    @Test
    fun theNewTaskDialogInsetsItsPanelAndDressesItsTextareaAsAField() =
        // A FINE pointer, and that is load-bearing rather than tidy. Every other context in this file has
        // `hasTouch`, which resolves `@media (any-pointer: coarse)` at EVERY width — so a "desktop" dialog
        // measured in one is really the phone's sheet, complete with the 20px grabber and the padding-top
        // that compensates for it. This test reasons about the mouse-only shape, so it runs on one.
        onScreen(BOARD_SCENARIO, "board-new-task-dialog", coarse = false) { harness, page ->
            page.navigate(harness.baseUrl + "/tasks")
            assertThat(page.locator(".task-card")).hasCount(BOARD_CARDS)
            page.locator(".board-new-task").click()

            val dialog = page.locator("#new-task-dialog")
            assertThat(dialog).isVisible()
            // The statement that this really is the desktop shape: the swipe handle exists only where a
            // coarse pointer does, and the gesture it affords is `DialogDismissTest`'s subject.
            assertThat(dialog.locator(".dialog-grabber")).isHidden()
            val panel = dialog.rect()
            val form = page.locator("#new-task-form").rect()
            // `dialog` is `padding: 0`, so its 1px border is all that stands between panel and form.
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
            // The title input is autofocused, and one Tab reaches the textarea from the keyboard — which
            // is what `:focus-visible` answers to.
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

    /**
     * The task panel's description is the ONE textarea in the app that dresses itself, and keeping it that
     * way is a specificity fix rather than a tidy-up.
     *
     * Written plainly, `.field textarea` is (0,1,1) — and `.task-detail-body` is (0,1,0) inside a `.field`
     * label of its own, so a bare selector silently takes this element's width, padding, radius and height
     * away, and being declared later saves a class rule from nothing. `:where()` contributes zero, which
     * puts the two at the same specificity and hands the tie back to source order, where the specialised
     * rule really does come last.
     *
     * The old test read that out of the sheet, including a guard forbidding the bare spelling. In a
     * browser the same question has a direct answer, and this page has both litigants side by side: the
     * title INPUT is dressed by the shared rule and the description by its own. Had the shared rule won
     * the cascade, they would agree on radius, inset and height. They must not — except on WIDTH, which is
     * the one declaration the textarea genuinely needs from the shared rule, because a textarea's `auto`
     * width is its `cols` attribute rather than its container.
     */
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

    // --- the session → task badge ------------------------------------------------------------------

    /**
     * The unknown-ref badge is the post-delete fallback, so it must read as a bare ref rather than as a
     * title. `task-linked-session` seeds both arms on purpose: `s-linked-1` points at a task that resolves
     * and `s-linked-3` at a deliberately dangling `local:404`.
     *
     * The ink is measured in the sidebar, where both arms are on screen together: the same pill shape, but
     * the monospace face, a dashed outline and no accent fill. The old file also asserted that the unknown
     * rule REPEATS the pill geometry rather than merely overriding it, so that it survives arriving alone —
     * that half is dropped, because both components compose the two classes and no code path emits the
     * second one on its own, leaving nothing a browser could tell apart.
     *
     * The truncation is measured in the TERMINAL HEADER instead, and the split is a measured fact rather
     * than a convenience. `.task-badge` declares `flex: 0 0 auto`, `max-width: 120px` and `overflow:
     * hidden`, and none of those apply to a non-replaced INLINE box — which is what the badge is inside
     * `.session-main`, a plain block. In `.terminal-identity`, a flex container, the badge is a flex item
     * and therefore blockified, so the cap and the ellipsis are live there. So the header is where a long
     * title can be shown to clamp rather than to push the row apart.
     */
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

    // --- fixtures and measurement -------------------------------------------------------------------

    /**
     * One harness, one browser, one fresh context (the cookie is not bound to a port, so a context is
     * never reused) and one page. The trace and its screenshot are kept only if [block] throws.
     */
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

    /** The URL the app's own `taskPath` produces for [ref]. */
    private fun taskUrl(baseUrl: String, ref: String): String = baseUrl + "/tasks/" + ref.replace(":", "%3A")

    /**
     * The viewport height at which [longer] overflows its column and [shorter] still fits.
     *
     * Both halves of that sentence have to be true at once, and the number that makes them true depends on
     * how tall a card renders — a guess survives until the day a card grows a line. So it is measured, in
     * two steps and one resize.
     *
     * Squeezed to [PROBE_HEIGHT], where every column overflows, a column's `scrollHeight` IS its content
     * height (unsqueezed it merely reports the stretched box back). The difference between the viewport and
     * a column's `clientHeight` is the shell's fixed chrome — the app's padding, the board's margin, its
     * header, the track's padding — and none of that moves with the viewport, so handing the columns
     * exactly [shorter]'s content plus [COLUMN_SLACK] leaves the control fitting with room to spare while
     * [longer], three cards taller, cannot. Measuring the content under a squeeze can only OVER-state it
     * (a scrolling column loses width to its bar, and a narrower card is never shorter), which errs toward
     * the control fitting — the safe direction, since the alternative is the failure this replaced.
     */
    private fun shortViewportHeight(page: Page, longer: Locator, shorter: Locator): Int {
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

    /**
     * The phone viewport height that leaves the stacked track [PHONE_TRACK_OVERFLOW] short of its content.
     *
     * Derived rather than picked, for [shortViewportHeight]'s reason, and the squeeze to [PROBE_HEIGHT] is
     * load-bearing rather than a shortcut: `scrollHeight` never answers below `clientHeight`, so on a tall
     * phone it reports the PORT and the arithmetic returns a height that still fits everything — which is
     * exactly what the first draft of this did. Under the squeeze it reports the cards, and the chrome
     * above the track does not move with the viewport. Both readings hold whether the column is sized by
     * its cards or clipped to the port, which is what lets the same number come out on both sides of the
     * defect this measures.
     */
    private fun overflowingPhoneHeight(page: Page, track: Locator): Int {
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

    /** Every card the page currently draws, inside [box] — the column's own border box. */
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

    /**
     * Wait until nothing under [selector] is still travelling.
     *
     * A computed colour is only the colour an operator sees once the transition that carries it is over,
     * and the board transitions `background` and `border-color` for 120ms on both `.board-column` and
     * `.task-card`. Chromium keeps a running CSS transition in `Element.getAnimations()` and drops it as
     * soon as it finishes, so an empty (or wholly finished) list is the arrival signal; `subtree` is what
     * reaches the sticky head, which tints with its column and is read in the same breath as it. The
     * stylesheet declares no `@keyframes` at all, so there is no looping animation here to wait on
     * forever.
     */
    private fun Page.paintSettled(selector: String) {
        waitForFunction(
            "s => { const el = document.querySelector(s); return !!el && " +
                "el.getAnimations({ subtree: true }).every((a) => a.playState === 'finished'); }",
            selector,
        )
    }

    /**
     * The drag reservation, wherever the viewport currently is. The handle must reserve the vertical
     * gesture — without it a pointer drag never starts, because the browser claims the gesture for
     * scrolling first — and none of its ancestors may, or the finger loses the column scroll that is the
     * only way to reach the rest of the backlog on a touch device.
     *
     * The handle carries the reservation twice, in the stylesheet and inline in `TaskCard.js`. Which of
     * the two supplies it is not observable from here and does not matter: what the gesture needs is that
     * the browser resolves it, which is what this reads.
     */
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

    /**
     * The box the element's TEXT actually occupies, measured with a `Range` over its contents.
     *
     * [rect] answers for the element, which is a different question wherever the element can be smaller
     * than what it draws — a flex item shrunk below its own content, or anything with `overflow:
     * visible`. There the element's box stays put while the glyphs spill out of it.
     */
    private fun Locator.textRect(): Box {
        val raw = evaluate(
            "el => { const range = document.createRange(); range.selectNodeContents(el);" +
                " const r = range.getBoundingClientRect(); range.detach();" +
                " return [r.left, r.top, r.width, r.height]; }",
        ) as List<*>
        return Box(raw.at(0), raw.at(1), raw.at(2), raw.at(3))
    }

    /** A resolved value off the element itself — the END of the cascade, not a line of the stylesheet. */
    private fun Locator.style(property: String): String =
        evaluate("(el, p) => getComputedStyle(el).getPropertyValue(p)", property) as String

    private fun Locator.number(expression: String): Double = (evaluate(expression) as Number).toDouble()

    private fun Locator.setText(text: String) {
        evaluate("(el, t) => { el.textContent = t; }", text)
    }

    /** A badge's label is its last child; the state dot before it has to survive the replacement. */
    private fun Locator.setLabel(text: String) {
        evaluate("(el, t) => { el.childNodes[el.childNodes.length - 1].nodeValue = t; }", text)
    }

    /** What [value] resolves to, painted by a throwaway probe rather than parsed out of a token. */
    private fun Page.resolved(value: String): String = evaluate(
        "v => { const probe = document.createElement('span'); probe.style.color = v;" +
            " document.body.appendChild(probe); const c = getComputedStyle(probe).color;" +
            " probe.remove(); return c; }",
        value,
    ) as String

    /** The same, resolved INSIDE this element, so an inherited custom property is the one measured. */
    private fun Locator.resolvedInside(value: String): String = evaluate(
        "(el, v) => { const probe = document.createElement('span'); probe.style.color = v;" +
            " el.appendChild(probe); const c = getComputedStyle(probe).color; probe.remove(); return c; }",
        value,
    ) as String

    /** Whether the topmost element at this point is [selector] or something inside it. */
    private fun Page.hitTest(x: Double, y: Double, selector: String): Boolean = evaluate(
        "a => { const el = document.elementFromPoint(a[0], a[1]); return !!(el && el.closest(a[2])); }",
        listOf(x, y, selector),
    ) as Boolean

    /**
     * Which of [STACK_SELECTORS] the browser finds at this point, front to back and deduplicated.
     *
     * `elementsFromPoint` rather than `elementFromPoint`: what is on TOP is only half of "the head hides
     * what is behind it" — the other half is that something is behind it at all.
     */
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

        /**
         * Short enough that EVERY column overflows, which is what makes a column's `scrollHeight` report
         * its content rather than its stretched box. Nothing is asserted at this height; it is the ruler
         * `shortViewportHeight` reads the real one off.
         */
        const val PROBE_HEIGHT = 240

        /** The room a column is given beyond the content it must fit — and must not be given twice over. */
        const val COLUMN_SLACK = 12.0
        const val PHONE_WIDTH = 390
        const val PHONE_HEIGHT = 844

        /**
         * How far the stacked phone track is made to overflow its port. Deep enough that several cards
         * really travel behind the pinned head — the hit test at its centre is only a statement while one
         * of them is under it — and shallow enough to leave a short viewport that still draws the chrome.
         */
        const val PHONE_TRACK_OVERFLOW = 160.0

        /**
         * The narrowest phone still in use (an iPhone SE-class screen). A quarter of it is 72px, which
         * "In progress 2" does not fit into at any weight the row is legible at — so this is the width at
         * which the switcher's equal tracks have to give way to their `min-content` floor.
         */
        const val NARROW_PHONE_WIDTH = 320

        /** The one width query the whole sheet uses: `max-width: 720px`. */
        const val BREAKPOINT = 720

        /** The `board` scenario seeds `local:1..10`, and `done` holds two, well under its display cap. */
        const val BOARD_CARDS = 10

        /** `dialog` is `padding: 0`; every dialog FORM declares this inset instead. */
        const val DIALOG_INSET = 20.0

        /** The shell's own card margin and corner radius — what every occupant of the pane's slot wears. */
        const val CARD_INSET = 12.0
        const val CARD_RADIUS = "14px"

        /**
         * The temporary right border the float test gives `#app`, wide enough that a panel anchored to the
         * VIEWPORT instead of to the shell could not land within [assertClose]'s tolerance of the answer.
         */
        const val PROBE_BORDER_PX = 40.0

        /** `--detail-gutter` inside the width breakpoint, which is also the head's padding there. */
        const val PHONE_DETAIL_GUTTER = 10.0

        /** `.task-detail-body`'s own minimum, which the shared `.field` rule's 38px would have replaced. */
        const val DETAIL_BODY_MIN_HEIGHT = 132.0

        /** `.task-badge`'s cap — the width a long title is clamped to where the badge is a flex item. */
        const val BADGE_MAX_WIDTH = 120.0

        /**
         * `PROJECT_NAME_MAX_LENGTH` — what `POST /projects` really accepts, and therefore what the New task
         * head must be able to hold. Restated rather than imported: it is a constant of the native root
         * module, which this JVM module cannot see, and `test/transport/WebUiServingTest.kt` keeps the
         * import-backed check.
         */
        const val PROJECT_NAME_MAX_LENGTH = 100

        /** What Chromium computes for a fully transparent background. */
        const val TRANSPARENT = "rgba(0, 0, 0, 0)"

        /** What [stackAt] reports on, innermost first, so the answer reads as a paint order. */
        val STACK_SELECTORS = listOf(".board-column-head", ".task-card", ".board-column", ".board")

        /** The four workflow states of `TaskState`, in board order — what `data-state` carries. */
        val BOARD_STATES = listOf("todo", "in_progress", "review", "done")

        /**
         * Every `SessionState` a dot can carry, paired with the `cls` half of `stateBadge`'s return for the
         * same state. The two attention states share one class on purpose.
         */
        val DOT_STATES = listOf(
            "running" to "badge-running",
            "ready" to "badge-ready",
            "needs_approval" to "badge-attention",
            "needs_answer" to "badge-attention",
            "stopped" to "badge-dead",
            "crashed" to "badge-crashed",
            "resumable" to "badge-resumable",
        )

        /** Every `ActivityKind` a feed row can carry. */
        val ACTIVITY_KINDS = listOf("created", "comment", "transition", "linked", "unlinked")
    }
}
