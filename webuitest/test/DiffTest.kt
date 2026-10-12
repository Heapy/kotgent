package io.kotgent.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import kotlin.test.Test
import kotlin.test.assertEquals
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

@org.junit.jupiter.api.Tag("diff-viewer")
class DiffTest {
    @Test fun treeFoldersKeyboardFilteringAndRemountKeepTheSelectedPatch() = diffBrowser("diff-tree") { harness, page ->
        val inventory = page.context().request().get(harness.baseUrl + "/api/v1/sessions/s-diff/diff/worktrees")
        val root = java.nio.file.Path.of(com.google.gson.JsonParser.parseString(inventory.text()).asJsonObject
            .getAsJsonObject("data").get("currentWorktreeId").asString)
        java.nio.file.Files.createDirectories(root.resolve("src/nested"))
        java.nio.file.Files.writeString(root.resolve("src/nested/One.kt"), "class One\n")
        java.nio.file.Files.writeString(root.resolve("src/nested/Two.kt"), "class Two\n")
        page.openDiff()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Tree").setExact(true)).click()
        val src = page.locator(".diff-folder-row[title='src']")
        val nested = page.locator(".diff-folder-row[title='src/nested']")
        assertThat(src).hasAttribute("aria-expanded", "true")
        page.file("src/nested/One.kt").click()
        assertThat(page.patch()).containsText("class One")
        src.click()
        assertThat(src).hasAttribute("aria-expanded", "false")
        assertThat(page.file("src/nested/One.kt")).hasCount(0)
        assertThat(page.patch()).containsText("class One")
        page.locator("#workspace-tab-t1").click()
        page.openDiff()
        assertThat(src).hasAttribute("aria-expanded", "false")
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Tree").setExact(true))).hasAttribute("aria-pressed", "true")
        src.press("ArrowRight")
        assertThat(src).hasAttribute("aria-expanded", "true")
        src.press("ArrowRight")
        assertThat(nested).isFocused()
        nested.press("ArrowRight")
        assertThat(page.file("src/nested/One.kt")).isFocused()
        page.file("src/nested/One.kt").press("ArrowDown")
        page.file("src/nested/Two.kt").press("Enter")
        assertThat(page.patch()).containsText("class Two")
        assertThat(page.file("src/nested/Two.kt")).isFocused()
        nested.click()
        page.getByLabel("Filter changed files", exact()).fill("One.kt")
        assertThat(nested).hasAttribute("aria-expanded", "true")
        assertThat(page.file("src/nested/One.kt")).isVisible()
        assertThat(page.file("src/nested/Two.kt")).hasCount(0)
        page.getByLabel("Filter changed files", exact()).fill("")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("List").setExact(true)).click()
        assertThat(page.locator(".diff-folder-row")).hasCount(0)
        assertThat(page.file("src/nested/Two.kt")).hasAttribute("aria-current", "true")
        page.assertOneTerminal()
    }

    @Test fun refreshAndPatchLoadingDoNotMoveTheViewer() = diffBrowser("diff-loading") { _, page ->
        page.openDiff()
        page.file("tracked.txt").click()
        assertThat(page.patch()).containsText("working line 0")
        page.assertStableDiffLoading()
        page.installResponseGate("/diff/patch", "switch")
        val before = page.patch().boundingBox()
        page.file("untracked.txt").click()
        page.waitForFunction("() => window.__diffHeld_switch === true")
        assertEquals(before.y, page.patch().boundingBox().y)
        assertEquals(before.height, page.patch().boundingBox().height)
        page.evaluate("() => window.__diffRelease_switch()")
        assertThat(page.patch()).containsText("untracked")
        assertEquals(before.y, page.patch().boundingBox().y)
        assertEquals(before.height, page.patch().boundingBox().height)
    }

    @Test fun localScopesReadingStateRefreshAndWorktreesKeepTheTerminal() = diffBrowser("diff-local") { harness, page ->
        page.openDiff()
        assertThat(page.locator(".workspace-tab")).hasCount(2)
        page.openDiff()
        assertThat(page.locator(".workspace-tab")).hasCount(2)
        assertThat(page.files()).hasCount(4) // HEAD deletion and untracked inventory both retain removed-index.txt.
        assertThat(page.files().filter(com.microsoft.playwright.Locator.FilterOptions().setHasText("removed-index.txt"))).hasCount(2)
        assertThat(page.file("reversed.txt")).hasCount(0)
        page.getByLabel(java.util.regex.Pattern.compile("^Scope")).selectOption("staged")
        assertThat(page.file("reversed.txt")).isVisible()
        assertThat(page.file("untracked.txt")).hasCount(0)
        page.file("tracked.txt").click()
        assertThat(page.patch()).containsText("staged line 0")
        assertThat(page.patch()).containsText("original line 0")
        assertThat(page.patch()).not().containsText("working line 0")
        page.getByLabel(java.util.regex.Pattern.compile("^Scope")).selectOption("unstaged")
        page.file("tracked.txt").click()
        assertThat(page.patch()).containsText("working line 0")
        assertThat(page.patch()).containsText("staged line 0")
        assertThat(page.patch()).not().containsText("original line 0")
        assertThat(page.file("untracked.txt")).isVisible()
        page.getByLabel(java.util.regex.Pattern.compile("^Scope")).selectOption("all")
        page.file("tracked.txt").click()
        assertThat(page.patch()).containsText("working line 0")
        page.getByLabel(java.util.regex.Pattern.compile("^Layout")).selectOption("split")
        assertThat(page.locator(".diff-split")).isVisible()
        page.getByLabel("Wrap", exact()).check()
        assertThat(page.locator(".diff-wrap")).isVisible()
        page.getByLabel("Viewed", exact()).check()
        page.getByLabel("Hide viewed", exact()).check()
        assertThat(page.file("tracked.txt")).hasCount(0)
        page.getByLabel("Hide viewed", exact()).uncheck()
        page.getByLabel("Filter changed files", exact()).fill("tracked.txt")
        assertThat(page.files()).hasCount(2)
        assertThat(page.file("removed-index.txt")).hasCount(0)
        page.patch().evaluate("el => el.scrollTop = 400")
        page.waitForFunction("() => document.querySelector('.diff-patch-scroll').scrollTop >= 390")
        page.locator("#workspace-tab-t1").click()
        assertThat(page.locator("#terminal-host .xterm-rows")).containsText("KOTGENT-DIFF-READY")
        page.openDiff()
        assertThat(page.getByLabel("Viewed", exact())).isChecked()
        assertThat(page.getByLabel("Filter changed files", exact())).hasValue("tracked.txt")
        assertThat(page.getByLabel("Wrap", exact())).isChecked()
        assertThat(page.locator(".diff-patch-pane h3")).hasText("tracked.txt")
        assertThat(page.getByLabel(java.util.regex.Pattern.compile("^Layout"))).hasValue("split")
        page.waitForFunction("() => document.querySelector('.diff-patch-scroll').scrollTop >= 390")
        harness.send("diff-edit")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Refresh").setExact(true)).click()
        assertThat(page.patch()).containsText("refreshed content")
        assertThat(page.getByLabel("Viewed", exact())).not().isChecked()
        page.getByLabel("Filter changed files", exact()).fill("")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Next unviewed")).click()
        assertThat(page.locator(".diff-patch-pane h3")).not().hasText("tracked.txt")
        page.chooseWorktree("no-session")
        assertThat(page.file("unowned.txt")).isVisible()
        page.file("unowned.txt").click()
        assertThat(page.patch()).containsText("worktree without a session")
        assertThat(page).hasURL(harness.baseUrl + "/s/s-diff")
        page.chooseWorktree("clean")
        assertThat(page.locator(".diff-source")).containsText("Locked (readable)")
        assertThat(page.locator(".diff-files")).containsText("No changes in this comparison.")
        page.chooseWorktree("missing")
        assertThat(page.locator(".diff-panel")).containsText("Worktree unavailable")
        assertThat(page.locator(".diff-files")).not().containsText("No changes in this comparison.")
        page.assertOneTerminal()
    }

    @Test fun historyPagesRemainPinnedAndCommitParentsAndBranchBasesAreSelectable() = diffBrowser("diff-history") { harness, page ->
        page.openDiff()
        page.getByLabel(java.util.regex.Pattern.compile("^Compare")).selectOption("commit")
        assertThat(page.locator(".diff-history li")).hasCount(100)
        val first = page.locator(".diff-history li button").first().textContent()
        harness.send("diff-head")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Load more commits")).click()
        assertThat(page.locator(".diff-history li")).hasCount(105)
        assertEquals(first, page.locator(".diff-history li button").first().textContent())
        assertThat(page.locator(".diff-history")).not().containsText("Advanced fixture HEAD")
        page.locator(".diff-history button").filter(com.microsoft.playwright.Locator.FilterOptions().setHasText("Merge side fixture")).click()
        assertThat(page.getByLabel(java.util.regex.Pattern.compile("^Parent"))).hasValue("1")
        assertThat(page.file("side.txt")).isVisible()
        page.getByLabel(java.util.regex.Pattern.compile("^Parent")).selectOption("2")
        assertThat(page.file("topic.txt")).isVisible()
        assertThat(page.file("side.txt")).hasCount(0)
        page.showHistory()
        page.locator(".diff-history button").filter(com.microsoft.playwright.Locator.FilterOptions().setHasText("Root fixture")).click()
        assertThat(page.locator(".diff-commit")).containsText("Root commit")
        assertThat(page.getByLabel(java.util.regex.Pattern.compile("^Parent"))).hasCount(0)
        page.getByLabel(java.util.regex.Pattern.compile("^Compare")).selectOption("branch")
        assertThat(page.getByLabel(java.util.regex.Pattern.compile("^Base branch"))).hasValue("refs/heads/main")
        assertThat(page.file("topic.txt")).isVisible()
        page.getByLabel(java.util.regex.Pattern.compile("^Base branch")).selectOption("refs/heads/side")
        assertThat(page.file("side.txt")).hasCount(0)
        assertThat(page.locator(".diff-comparison").first()).containsText("merge-base")
        page.showHistory()
        page.locator(".diff-history li button").first().click()
        assertThat(page.getByLabel(java.util.regex.Pattern.compile("^Compare"))).hasValue("commit")
        assertThat(page.locator(".diff-commit")).containsText("Advanced fixture HEAD")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Refresh").setExact(true)).click()
        assertThat(page.locator(".diff-source")).containsText("Checkout HEAD")
        page.assertOneTerminal()
    }

    @Test fun phoneFilesPatchBackAndTaskEntryReuseDiff() = diffBrowser("diff-phone", phone = true) { harness, page ->
        page.locator("#session-details-toggle").click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open task").setExact(true)).click()
        assertThat(page).hasURL(harness.baseUrl + "/tasks/local%3A1")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open changes").setExact(true)).click()
        assertThat(page).hasURL(harness.baseUrl + "/s/s-diff")
        assertThat(page.locator("#terminal-host .xterm")).hasCount(1)
        page.evaluate("() => document.querySelector('#terminal-host .xterm').__diffKept = true")
        assertThat(page.locator(".diff-files")).isVisible()
        page.file("tracked.txt").click()
        assertThat(page.patch()).containsText("working line 0")
        assertThat(page.locator(".diff-files")).isHidden()
        assertThat(page.locator(".diff-patch-pane h3")).isFocused()
        page.assertStableDiffLoading()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Back to files")).click()
        assertThat(page.locator(".diff-files")).isVisible()
        assertThat(page.getByLabel("Filter changed files", exact())).isFocused()
        assertEquals(true, page.evaluate("() => document.documentElement.scrollWidth <= innerWidth"))
        page.assertOneTerminal(2)
        page.locator("#session-details-toggle").click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open task").setExact(true)).click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open changes").setExact(true)).click()
        assertThat(page.locator(".diff-panel")).isVisible()
        assertThat(page.locator(".workspace-tab")).hasCount(2)
        assertThat(page).hasURL(harness.baseUrl + "/s/s-diff")
    }

    @Test fun changedLocalBytesRejectTheOldSnapshotUntilRefresh() = diffBrowser("diff-stale-local") { harness, page ->
        page.openDiff()
        page.file("tracked.txt").click()
        assertThat(page.patch()).containsText("working line 0")
        page.getByLabel("Viewed", exact()).check()
        harness.send("diff-edit")
        page.file("untracked.txt").click()
        assertThat(page.locator(".diff-status")).containsText("Showing stale content")
        assertThat(page.getByLabel("Viewed", exact())).not().isChecked()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Refresh").setExact(true)).click()
        assertThat(page.locator(".diff-status")).hasCount(0)
        page.file("tracked.txt").click()
        assertThat(page.patch()).containsText("refreshed content")
        page.assertOneTerminal()
    }

    @Test fun latePatchReplyCannotReplaceTheNewWorktree() = diffBrowser("diff-late-patch") { _, page ->
        page.openDiff()
        // Delay publication after the real response, ignoring AbortSignal deliberately.
        page.installResponseGate("/diff/patch", "patch")
        page.file("tracked.txt").click()
        page.waitForFunction("() => window.__diffHeld_patch === true")
        page.chooseWorktree("no-session")
        page.file("unowned.txt").click()
        assertThat(page.patch()).containsText("worktree without a session")
        page.evaluate("() => window.__diffRelease_patch()")
        page.waitForFunction("() => window.__diffReleased_patch === true")
        assertThat(page.locator(".diff-patch-pane h3")).hasText("unowned.txt")
        assertThat(page.patch()).not().containsText("working line")
        page.assertOneTerminal()
    }

    @org.junit.jupiter.api.Tag("diff-task-race")
    @Test fun taskDiscoveryCannotPublishAfterDependencyNavigationOrUnlink() = diffBrowser("diff-task-race") { harness, page ->
        page.locator("#session-details-toggle").click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open task").setExact(true)).click()
        page.installResponseGate("/diff/worktrees", "navigate")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open changes").setExact(true)).click()
        page.waitForFunction("() => window.__diffHeld_navigate === true")
        page.releaseOnTaskRender("navigate", "local:2")
        page.locator(".task-deps a").click()
        assertThat(page.locator("#task-detail-title-input")).hasValue("Dependency without a session")
        page.waitForFunction("() => window.__diffReleased_navigate === true")
        assertThat(page).hasURL(harness.baseUrl + "/tasks/local%3A2")
        assertThat(page.locator(".task-changes")).hasCount(0)
        page.goBack()
        assertThat(page.locator("#task-detail-title-input")).hasValue("Review Git changes")
        page.installResponseGate("/diff/worktrees", "unlink", fail = true)
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open changes").setExact(true)).click()
        page.waitForFunction("() => window.__diffHeld_unlink === true")
        page.releaseOnTaskRender("unlink", "local:1", unlinked = true)
        harness.send("diff-unlink")
        assertThat(page.locator(".task-sessions")).not().containsText("diff-review")
        page.waitForFunction("() => window.__diffReleased_unlink === true")
        assertThat(page).hasURL(harness.baseUrl + "/tasks/local%3A1")
        assertThat(page.locator(".task-changes")).hasCount(0)
        assertThat(page.locator("body")).not().containsText("controlled old discovery failure")
    }

    @Test fun desktopWidthHidingCanRevealDiffWithoutChangingSavedFractions() = diffBrowser("diff-width") { _, page ->
        page.evaluate("""() => {
          localStorage.setItem('kotgent.workspaces.v1', JSON.stringify({'s-diff': {
            tabs: [{id:'t1',columns:[{type:'terminal'},{type:'diff'}],fractions:[0.7,0.3],focus:0}],
            activeTab:'t1',touchedAt:Date.now()
          }}));
        }""")
        page.reload()
        // Set the actual workspace width independently of sidebar width.
        page.addStyleTag(Page.AddStyleTagOptions().setContent(".workspace { width: 1200px !important; flex: none !important; }"))
        assertThat(page.locator(".workspace-column[data-type='diff']")).isVisible()
        assertEquals(1200.0, page.locator(".workspace").boundingBox().width)
        page.addStyleTag(Page.AddStyleTagOptions().setContent(".workspace { width: 900px !important; }"))
        assertThat(page.locator(".workspace-column[data-type='diff']")).hasCount(0)
        assertEquals(900.0, page.locator(".workspace").boundingBox().width)
        page.openDiff()
        assertThat(page.locator(".diff-panel")).isVisible()
        assertThat(page.locator(".workspace-tab")).hasCount(1)
        page.locator(".workspace-switch[data-type=terminal]").click()
        page.locator(".workspace-switch[data-type=diff]").click()
        assertThat(page.locator(".diff-panel")).isVisible()
        assertEquals(listOf(0.7, 0.3), page.evaluate("() => JSON.parse(localStorage.getItem('kotgent.workspaces.v1'))['s-diff'].tabs[0].fractions"))
    }
}

private fun exact() = Page.GetByLabelOptions().setExact(true)
private fun Page.files() = locator(".diff-files li button")
private fun Page.file(path: String) = files().filter(com.microsoft.playwright.Locator.FilterOptions().setHas(locator(".diff-file-path").filter(com.microsoft.playwright.Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^" + regexLiteral(path) + "$")))))
private fun Page.patch() = locator(".diff-patch-scroll")
private fun Page.showHistory() {
    if (locator(".diff-history").getAttribute("open") == null) locator(".diff-history summary").click()
}
private fun Page.openDiff() {
    openHeaderCommands()
    assertThat(locator(".command-palette-shell.leader")).isFocused()
    runFirstMatch("Open changes", "Open changes")
    assertThat(locator(".diff-panel")).isVisible()
    assertThat(locator(".diff-controls select").first()).not().hasValue("")
    assertThat(getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Refresh").setExact(true))).isEnabled()
    assertThat(locator(".diff-status")).hasCount(0)
}
private fun Page.assertStableDiffLoading() {
    val before = patch().boundingBox()
    installResponseGate("/diff/worktrees", "refresh")
    getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Refresh").setExact(true)).click()
    waitForFunction("() => window.__diffHeld_refresh === true")
    assertThat(locator(".diff-read-status")).containsText("Showing stale content")
    assertEquals(before.y, patch().boundingBox().y)
    assertEquals(before.height, patch().boundingBox().height)
    assertThat(locator(".diff-panel")).not().containsText("Loading changes")
    evaluate("() => window.__diffRelease_refresh()")
    assertThat(getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Refresh").setExact(true))).isEnabled()
    assertThat(locator(".diff-status")).hasCount(0)
    assertEquals(before.y, patch().boundingBox().y)
    assertEquals(before.height, patch().boundingBox().height)
}
private fun Page.chooseWorktree(name: String) {
    val select = getByLabel(java.util.regex.Pattern.compile("^Worktree"))
    val value = select.locator("option").all().single { it.getAttribute("value")!!.endsWith("/$name") }.getAttribute("value")!!
    select.selectOption(value)
    assertThat(locator(".diff-source")).containsText("/$name")
}
private fun Page.assertOneTerminal(expected: Int = 1) {
    assertEquals(List(expected) { "/api/v1/sessions/s-diff/terminal" }, evaluate("() => window.__diffTerminals"))
    assertEquals(true, evaluate("() => document.querySelector('#terminal-host .xterm').__diffKept === true"))
}
private fun Page.installResponseGate(path: String, key: String, fail: Boolean = false) {
    evaluate("""([path,key,fail]) => {
      const native = window.fetch; let hold = true;
      window.fetch = async (input, init) => {
        if (!hold || !String(input).includes(path)) return native(input,init);
        hold = false;
        const response = await native(input,{...init,signal:undefined});
        window['__diffHeld_'+key] = true;
        return new Promise((resolve,reject) => window['__diffRelease_'+key] = () => {
          if (fail) reject(new Error("controlled old discovery failure")); else resolve(response);
          // The assertion barrier follows the fetch consumer's promise and Preact render microtasks.
          setTimeout(() => window['__diffReleased_'+key] = true, 0);
        });
      };
    }""", listOf(path, key, fail))
}
private fun Page.releaseOnTaskRender(key: String, task: String, unlinked: Boolean = false) {
    evaluate("""([key,task,unlinked]) => {
      const observer = new MutationObserver(() => {
        if (document.querySelector('#task-detail-title')?.textContent !== task) return;
        if (unlinked && document.querySelector('.task-sessions')?.textContent.includes('diff-review')) return;
        observer.disconnect(); window['__diffRelease_'+key]();
      });
      observer.observe(document.querySelector('#app'), {subtree:true,childList:true,characterData:true});
    }""", listOf(key, task, unlinked))
}
private fun diffBrowser(trace: String, phone: Boolean = false, body: (Harness, Page) -> Unit) {
    var fixtureDirectory: java.nio.file.Path? = null
    Harness(DIFF_SCENARIO).use { harness ->
        onChromium { browser ->
            println("$trace: ${System.getProperty("os.name")}/${System.getProperty("os.arch")} Chromium ${browser.version()}")
            (if (phone) browser.touchContext() else browser.fineContext(1600, 900)).use { context ->
                context.loginWithTicket(harness.ticket, harness.baseUrl)
                val inventory = context.request().get(harness.baseUrl + "/api/v1/sessions/s-diff/diff/worktrees")
                assertEquals(200, inventory.status())
                val root = com.google.gson.JsonParser.parseString(inventory.text()).asJsonObject
                    .getAsJsonObject("data").get("currentWorktreeId").asString
                fixtureDirectory = java.nio.file.Path.of(root).parent
                context.addInitScript("""(() => {
                  window.__diffTerminals=[];
                  const Native=window.WebSocket;
                  window.WebSocket=class extends Native {
                    constructor(...args) { super(...args); const path=new URL(args[0]).pathname;
                      if(path.endsWith('/terminal')) window.__diffTerminals.push(path); }
                  };
                })();""")
                context.traced(trace) {
                    val page = context.newPage()
                    page.navigate(harness.baseUrl + "/s/s-diff")
                    assertThat(page.locator("#terminal-host .xterm-rows")).containsText("KOTGENT-DIFF-READY")
                    page.evaluate("() => document.querySelector('#terminal-host .xterm').__diffKept = true")
                    body(harness, page)
                }
            }
        }
    }
    assertEquals(false, java.nio.file.Files.exists(checkNotNull(fixtureDirectory)), "harness removes its owned fixture directory")
}
