package io.kotgent.webuitest

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanReviewTest {
    @Test
    fun aFullRoundPreservesEditsThroughConcurrentChangesAndRestart() = onPlan("plan-round") { harness, page ->
        page.navigate(harness.baseUrl + "/tasks/local%3A1/plan")
        val overview = page.locator("[data-block='s_1']")
        assertThat(overview.locator("strong")).hasText("safe")
        assertThat(overview.locator("script,img,a[href^='javascript:']")).hasCount(0)
        assertThat(overview.locator("table")).hasCount(1)
        assertEquals(null, page.evaluate("() => window.planXss"))
        overview.locator("input[type=checkbox]").click()
        assertThat(overview.locator("input[type=checkbox]")).isChecked()
        assertThat(page.locator(".plan-progress")).hasText("1/4 viewed")
        overview.getByText("Edit", exactText()).click()
        overview.locator("textarea").fill("My review edit")
        harness.send("plan-edit local:1 s_1 New agent version")
        assertThat(overview.locator(".plan-conflict")).containsText("New agent version")
        assertThat(overview.locator("textarea")).hasValue("My review edit")
        assertThat(page.locator(".plan-progress")).hasText("0/4 viewed")
        overview.getByText("Keep my edit against revision", Locator.GetByTextOptions().setExact(false)).click()
        overview.getByText("Save edit", exactText()).click()
        assertThat(overview.locator(".markdown")).hasText("My review edit")
        overview.getByText("Ask", exactText()).click()
        overview.getByLabel("Question", Locator.GetByLabelOptions().setExact(true)).fill("Why keep one connection?")
        overview.getByText("Ask question", exactText()).click()
        assertThat(overview.locator(".plan-thread")).containsText("Why keep one connection?")
        harness.send("plan-reply local:1 To preserve the terminal buffer.")
        assertThat(overview.locator(".plan-thread")).containsText("To preserve the terminal buffer.")
        page.locator(".plan-submit").click()
        assertThat(page.locator(".plan-round")).containsText("Round 1 · changes")
        harness.send("plan-review local:1")
        assertThat(page.locator(".plan-round")).containsText("Round 2 · open")
        harness.send("restart")
        assertThat(page.locator(".plan-round")).containsText("Round 2 · open")
        page.locator(".plan-approve").click()
        assertThat(page.locator("dialog.plan-confirm")).isVisible()
        page.getByText("Approve anyway", Page.GetByTextOptions().setExact(true)).click()
        assertThat(page.locator(".plan-round")).containsText("Round 2 · approved")
        assertThat(page.locator(".plan-approve")).isDisabled()
    }

    @Test
    fun taskEntryOpensPlanBesideTheTerminalAndPhoneUsesContentsDropdown() = onPlan("plan-phone", phone = true) { harness, page ->
        page.navigate(harness.baseUrl + "/s/s-work")
        page.awaitSessionView()
        page.addWorkspaceColumn()
        page.locator(".task-open-plan").click()
        assertThat(page.locator(".workspace-tab.active")).containsText("Terminal · Plan")
        assertThat(page.locator(".workspace-column[data-type='plan']")).isVisible()
        assertThat(page.locator(".plan-toc-select")).isVisible()
        page.locator(".plan-toc-select select").selectOption("s_2")
        assertThat(page.locator("[data-block='s_2']")).isFocused()
        page.locator("[data-block='s_2']").press("v")
        assertThat(page.locator(".plan-progress")).hasText("1/4 viewed")
        assertEquals(true, page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth"))
    }

    @Test
    fun shortcutsLeaveNativeAndPlaintextEditingAloneAndJumpUsesTheMountedBlock() = onPlan("plan-editing-shortcuts", phone = true) { harness, page ->
        page.navigate(harness.baseUrl + "/tasks/local%3A1/plan")
        val block = page.locator("[data-block='s_2']")
        assertThat(block).isVisible()
        val _ = block.evaluate("""el => {
            const editor = document.createElement('div');
            editor.id = 'plaintext-editor';
            editor.contentEditable = 'plaintext-only';
            editor.textContent = 'Draft';
            el.appendChild(editor);
        }""")
        val editor = page.locator("#plaintext-editor")
        editor.press("v")
        assertThat(page.locator(".plan-progress")).hasText("0/4 viewed")
        assertThat(editor).isFocused()
        assertThat(editor).containsText("v")
        page.locator(".plan-toc-select select").selectOption("s_2")
        assertThat(block).isFocused()
        harness.send("plan-edit local:1 s_2 A newer block body")
        assertThat(block.locator(".markdown")).containsText("A newer block body")
        page.locator(".plan-toc-select select").selectOption("s_1")
        assertThat(page.locator("[data-block='s_1']")).isFocused()
        page.locator(".plan-toc-select select").selectOption("s_2")
        assertThat(block).isFocused()
    }

    @Test
    fun rejectedWriteKeepsDraftAndLoadsTheNewVersion() = onPlan("plan-http-conflict") { harness, page ->
        page.navigate(harness.baseUrl + "/tasks/local%3A1/plan")
        val block = page.locator("[data-block='s_2']")
        block.getByText("Edit", exactText()).click()
        block.locator("textarea").fill("Retained text")
        page.route("**/plan/blocks/s_2") { route ->
            if (route.request().method() == "PATCH") {
                harness.send("plan-edit local:1 s_2 A concurrent write")
                route.resume()
            } else route.resume()
        }
        block.getByText("Save edit", exactText()).click()
        assertThat(block.locator("textarea")).hasValue("Retained text")
        assertThat(block.locator(".plan-conflict")).containsText("A concurrent write")
        assertThat(page.locator(".plan-panel [role=alert]")).containsText("unsaved text is kept")
    }
}

private fun exactText() = Locator.GetByTextOptions().setExact(true)

private fun onPlan(trace: String, phone: Boolean = false, body: (Harness, Page) -> Unit) {
    Harness("plan-review").use { harness ->
        onChromium { browser ->
            val context = if (phone) browser.touchContext() else browser.fineContext(1600, 900)
            context.use {
                context.loginWithTicket(harness.ticket, harness.baseUrl)
                context.traced(trace) { body(harness, context.newPage()) }
            }
        }
    }
}
