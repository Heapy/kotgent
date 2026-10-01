package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals

class FindingDecisionTest {
    @Test fun decisionsPersistThroughReconnectAndOnlyACompleteBatchCanBeSent() = onFindings("finding-batch") { harness, page ->
        val cards = page.locator(".finding-card")
        assertThat(cards).hasCount(2)
        assertThat(cards.first()).containsText("A stale reply replaces newer data.")
        val high = page.locator("[data-finding='f_6']")
        assertThat(high.locator(".finding-verifier")).containsText("confirmed")
        assertThat(high.locator(".recommended")).containsText("Check the revision.")
        assertThat(page.locator(".finding-send")).isDisabled()
        high.getByLabel("Fix option").selectOption("1")
        high.getByLabel("Decision note").fill("Reload is acceptable for this case.")
        high.getByText("Save decision", exact()).click()
        assertThat(high.locator(".finding-decision-summary")).containsText("Fix now · Option 2")
        assertThat(page.locator(".finding-send")).isDisabled()
        val low = page.locator("[data-finding='f_5']")
        low.getByLabel("Fix later", Locator.GetByLabelOptions().setExact(true)).check()
        low.getByText("Save decision", exact()).click()
        assertThat(page.locator(".finding-send")).isEnabled()
        harness.send("restart")
        assertThat(high.locator(".finding-decision-summary")).containsText("Reload is acceptable")
        page.reload()
        assertThat(page.locator(".finding-send")).isEnabled()
        page.locator(".finding-send").click()
        assertThat(page.locator(".plan-task-status")).containsText("running")
        assertThat(page.locator(".finding-decision")).hasCount(0)
        assertThat(page.locator(".finding-review")).containsText("Decisions have been sent")
    }

    @Test fun staleDecisionsKeepTheirNoteAndRequireTheNewRevision() = onFindings("finding-conflict", phone = true) { harness, page ->
        val card = page.locator("[data-finding='f_6']")
        card.getByLabel("Decision note").fill("Keep this reasoning")
        page.route("**/plan/findings/f_6/decide") { route ->
            harness.send("plan-finding-amend local:1 f_6 A different triggering condition.")
            route.resume()
        }
        card.getByText("Save decision", exact()).click()
        assertThat(card.locator(".finding-conflict")).isVisible()
        assertThat(card.getByLabel("Decision note")).hasValue("Keep this reasoning")
        assertThat(card.getByText("Save decision", exact())).isDisabled()
        assertThat(card.locator(".finding-assessment")).containsText("A different triggering condition.")
        page.unroute("**/plan/findings/f_6/decide")
        card.getByText("Use finding revision", Locator.GetByTextOptions().setExact(false)).click()
        card.getByText("Save decision", exact()).click()
        assertThat(card.locator(".finding-decision-summary")).containsText("Keep this reasoning")
        assertEquals(true, page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth"))
    }


    @Test fun investigatorOpensTheFindingReusesItsSessionAndDecisionArchivesOnlyTheChild() = onFindings("finding-investigator") { harness, page ->
        page.locator("[data-finding='f_6'] .finding-investigate").click()
        assertThat(page.locator(".workspace-tab.active")).containsText("Terminal · Plan")
        assertThat(page.locator("[data-finding='f_6']")).isFocused()
        val investigatorUrl = page.url()
        val id = investigatorUrl.substringAfterLast('/')
        assertEquals(true, page.evaluate("""async id => {
            const rows = await (await fetch('/api/v1/sessions')).json();
            const row = rows.find(row => row.id === id);
            return row.readOnly && row.parentSessionId === 's-work' && row.cwd === '/repo/worker' && row.taskRef === 'local:1';
        }""", id))
        harness.send("restart")
        page.navigate(harness.baseUrl + "/tasks/local%3A1/plan")
        page.locator("[data-finding='f_6'] .finding-investigate").click()
        assertThat(page).hasURL(investigatorUrl)
        assertThat(page.locator("[data-finding='f_6']")).isFocused()
        val card = page.locator("[data-finding='f_6']")
        card.getByLabel("Fix later", Locator.GetByLabelOptions().setExact(true)).click()
        assertThat(card.getByLabel("Fix later", Locator.GetByLabelOptions().setExact(true))).isChecked()
        card.getByText("Save decision", exact()).click()
        page.waitForFunction("""async id => {
            const rows = await (await fetch('/api/v1/sessions')).json();
            return rows.find(row => row.id === id)?.archived === true;
        }""", id).dispose()
        assertEquals(true, page.evaluate("""async () => {
            const rows = await (await fetch('/api/v1/sessions')).json();
            const root = rows.find(row => row.id === 's-work');
            return root.taskRef === 'local:1' && !root.archived;
        }"""))
        page.navigate(harness.baseUrl + "/tasks/local%3A1")
        assertThat(page.locator("#task-detail-state")).hasValue("in_progress")
    }

    @Test fun changingModePreservesThisReviewAndAutonomousReviewsHideOperatorControls() {
        onFindings("finding-next-mode") { _, page ->
            page.getByLabel("Execution mode", Page.GetByLabelOptions().setExact(true)).selectOption("autonomous")
            assertThat(page.locator(".finding-review")).containsText("This review stays supervised")
            assertThat(page.locator(".finding-decision")).hasCount(2)
            page.locator(".plan-task-meta a").click()
            assertThat(page).hasURL(Regex(".*/s/s-worker").toPattern())
        }
        onFindings("finding-autonomous", scenario = "plan-findings-auto") { _, page ->
            assertThat(page.locator(".finding-mode")).hasText("autonomous")
            assertThat(page.locator(".finding-decision,.finding-send,.finding-investigate")).hasCount(0)
            page.getByLabel("Execution mode", Page.GetByLabelOptions().setExact(true)).selectOption("supervised")
            assertThat(page.locator(".finding-review")).containsText("This review stays autonomous")
            assertThat(page.locator(".finding-decision,.finding-send,.finding-investigate")).hasCount(0)
        }
    }
}
private fun exact() = Locator.GetByTextOptions().setExact(true)
private fun onFindings(trace: String, phone: Boolean = false, scenario: String = "plan-findings", body: (Harness, Page) -> Unit) {
    Harness(scenario).use { harness -> onChromium { browser ->
        val context = if (phone) browser.touchContext() else browser.fineContext(1600, 900)
        context.use {
            context.loginWithTicket(harness.ticket, harness.baseUrl)
            context.traced(trace) {
                val page = context.newPage()
                page.navigate(harness.baseUrl + "/tasks/local%3A1/plan")
                body(harness, page)
            }
        }
    } }
}
