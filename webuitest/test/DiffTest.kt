package io.kotgent.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import kotlin.test.Test
import kotlin.test.assertEquals
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

@org.junit.jupiter.api.Tag("diff-viewer")
class DiffTest {
    @Test fun imagesCompareBothVersionsAndOverlayWithoutOverflowingThePhone() = diffBrowser("diff-images") { harness, page ->
        val inventory = page.context().request().get(harness.baseUrl + "/api/v1/sessions/s-diff/diff/worktrees")
        val root = java.nio.file.Path.of(com.google.gson.JsonParser.parseString(inventory.text()).asJsonObject
            .getAsJsonObject("data").get("currentWorktreeId").asString)
        fun png(name: String, width: Int, height: Int, color: java.awt.Color) {
            val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                graphics.color = color; graphics.fillRoundRect(20, 20, width - 40, height - 40, 24, 24)
                graphics.color = java.awt.Color.WHITE; graphics.fillOval(40, 40, 64, 64)
            } finally { graphics.dispose() }
            assertEquals(true, javax.imageio.ImageIO.write(image, "png", root.resolve(name).toFile()))
        }
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("/usr/bin/git", "-C", root.toString(), "-c", "user.name=Fixture",
                "-c", "user.email=fixture@example.test") + args).apply {
                val _ = environment().keys.removeIf { it.startsWith("GIT_") }
                environment()["HOME"] = "/nonexistent"
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
        }
        png("image.png", 320, 200, java.awt.Color(166, 122, 224, 180))
        git("add", "--", "image.png"); git("commit", "--only", "-m", "Image before", "--", "image.png")
        png("image.png", 400, 240, java.awt.Color(100, 180, 150, 180))
        png("added.png", 320, 200, java.awt.Color.BLUE)
        val _ = java.nio.file.Files.writeString(root.resolve("invalid.png"), "<svg onload='alert(1)'/>")
        val corrupt = java.nio.file.Files.readAllBytes(root.resolve("added.png")).copyOf(33)
        val _ = java.nio.file.Files.write(root.resolve("corrupt.png"), corrupt)
        page.evaluate("""() => {
            window.imageUrls = { created: [], revoked: [] };
            const create = URL.createObjectURL.bind(URL), revoke = URL.revokeObjectURL.bind(URL);
            URL.createObjectURL = blob => { const url = create(blob); window.imageUrls.created.push(url); return url; };
            URL.revokeObjectURL = url => { window.imageUrls.revoked.push(url); revoke(url); };
        }""")
        page.openDiff()
        page.file("image.png").click()
        val before = page.getByRole(AriaRole.IMG, Page.GetByRoleOptions().setName("Before image").setExact(true))
        val after = page.getByRole(AriaRole.IMG, Page.GetByRoleOptions().setName("After image").setExact(true))
        page.waitForFunction("() => [...document.querySelectorAll('.diff-images img')].length === 2 && [...document.querySelectorAll('.diff-images img')].every(img => img.complete && img.naturalWidth > 0)")
        assertEquals(true, before.getAttribute("src")!!.startsWith("blob:"))
        assertEquals(true, after.getAttribute("src")!!.startsWith("blob:"))
        val firstUrls = listOf(before.getAttribute("src"), after.getAttribute("src"))
        assertEquals(320, before.evaluate("img => img.naturalWidth"))
        assertEquals(400, after.evaluate("img => img.naturalWidth"))
        assertEquals(true, kotlin.math.abs(before.boundingBox().width / 320 - after.boundingBox().width / 400) < 0.01,
            "side-by-side images use the same scale")
        assertThat(page.locator(".diff-images")).containsText("320 × 200 px")
        assertThat(page.locator(".diff-images")).containsText("400 × 240 px")
        val _ = page.screenshot(Page.ScreenshotOptions().setPath(testResultsDir().resolve("diff-images-side.png")))
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Overlay").setExact(true)).click()
        val opacity = page.getByRole(AriaRole.SLIDER, Page.GetByRoleOptions().setName("After opacity"))
        opacity.press("Home")
        assertThat(after).hasCSS("opacity", "0")
        opacity.press("End")
        assertThat(after).hasCSS("opacity", "1")
        assertEquals(true, after.boundingBox().width > before.boundingBox().width, "overlay keeps the images at the same scale")
        val _ = page.screenshot(Page.ScreenshotOptions().setPath(testResultsDir().resolve("diff-images-overlay.png")))
        page.setViewportSize(390, 844)
        assertEquals(true, page.evaluate("() => document.documentElement.scrollWidth <= innerWidth"))
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Side by side").setExact(true)).click()
        assertEquals(true, after.boundingBox().y > before.boundingBox().y, "the phone stacks the two versions")
        val _ = page.screenshot(Page.ScreenshotOptions().setPath(testResultsDir().resolve("diff-images-phone.png")))
        for (name in listOf("added.png", "invalid.png", "corrupt.png")) {
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Back to files")).click()
            page.file(name).click()
            assertThat(before).hasCount(0)
            assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Overlay").setExact(true))).isDisabled()
            when (name) {
                "added.png" -> { assertThat(after).isVisible(); assertThat(page.patch()).containsText("File did not exist") }
                "invalid.png" -> assertThat(page.patch()).containsText("Not a supported PNG, JPEG, GIF or WebP image")
                else -> assertThat(page.patch()).containsText("Cannot decode this image")
            }
        }
        assertEquals(true, page.evaluate("urls => urls.every(url => window.imageUrls.revoked.includes(url))", firstUrls),
            "switching files releases both prior Blob URLs")
    }

    @Test fun visibleBranchesOpenTheirOwnLogsWithoutChangingTheCheckout() = diffBrowser("diff-branches") { harness, page ->
        val inventory = page.context().request().get(harness.baseUrl + "/api/v1/sessions/s-diff/diff/worktrees")
        val root = com.google.gson.JsonParser.parseString(inventory.text()).asJsonObject
            .getAsJsonObject("data").get("currentWorktreeId").asString
        assertEquals(0, ProcessBuilder("/usr/bin/git", "-C", root, "update-ref", "refs/remotes/origin/side", "side").start().waitFor())
        page.openDiff()
        val branches = page.getByRole(AriaRole.COMPLEMENTARY, Page.GetByRoleOptions().setName("Branches"))
        assertThat(branches).isVisible()
        val checkout = page.locator(".diff-source").textContent()
        branches.getByRole(AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("main").setExact(true)).click()
        assertThat(page.locator(".diff-history li")).hasCount(1)
        assertThat(page.locator(".diff-history")).containsText("Root fixture")
        branches.getByRole(AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("side").setExact(true)).click()
        assertThat(page.locator(".diff-history li")).hasCount(2)
        assertThat(page.locator(".diff-history")).not().containsText("Topic change")
        page.locator(".diff-log-row").first().press("ArrowDown")
        assertThat(page.locator(".diff-log-row").nth(1)).isFocused()
        page.locator(".diff-log-row").nth(1).press("Enter")
        assertThat(page.locator(".diff-commit")).containsText("Root commit")
        assertThat(page.locator(".diff-history li")).hasCount(2)
        page.locator(".diff-log-row").first().click()
        page.file("side.txt").click()
        assertThat(page.patch()).containsText("side content")
        page.locator(".diff-log-row").nth(1).focus()
        page.locator(".diff-log-row").nth(1).press("Enter")
        assertThat(page.locator(".diff-commit")).containsText("Root commit")
        assertThat(page.locator(".diff-log-row").nth(1)).isFocused()
        branches.getByRole(AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("origin/side").setExact(true)).click()
        assertThat(page.locator(".diff-history li")).hasCount(2)
        assertThat(page.locator(".diff-history-toolbar")).containsText("origin/side")
        page.getByLabel("Filter loaded commits", exact()).fill("SIDE CHANGE")
        assertThat(page.locator(".diff-history li")).hasCount(1)
        page.locator(".diff-log-row").first().click()
        assertThat(page.patch()).containsText("side content")
        page.getByLabel("Filter loaded commits", exact()).fill("")
        page.getByLabel("Filter branches", exact()).fill("ORIGIN")
        assertThat(branches.locator("li")).hasCount(1)
        val toggle = page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(java.util.regex.Pattern.compile("^(Hide|Show) branches$")))
        val mainWidth = page.locator(".diff-main").boundingBox().width
        toggle.click()
        assertThat(branches).isHidden()
        assertThat(toggle).hasAttribute("aria-expanded", "false")
        assertThat(toggle).isFocused()
        assertEquals(true, page.locator(".diff-main").boundingBox().width > mainWidth + 100, "hiding branches gives the diff more room")
        assertThat(page.patch()).containsText("side content")
        toggle.press("Enter")
        assertThat(branches).isVisible()
        assertThat(page.getByLabel("Filter branches", exact())).hasValue("ORIGIN")
        toggle.click()
        page.locator("#workspace-tab-t1").click()
        page.openDiff()
        assertThat(branches).isHidden()
        assertThat(toggle).hasAttribute("aria-expanded", "false")
        assertThat(page.patch()).containsText("side content")
        val _ = page.screenshot(Page.ScreenshotOptions().setPath(testResultsDir().resolve("diff-branches-hidden.png")))
        toggle.click()
        assertThat(branches).isVisible()
        assertThat(toggle).hasAttribute("aria-expanded", "true")
        page.getByLabel("Filter branches", exact()).fill("")
        assertThat(page.locator(".diff-source")).hasText(checkout)
        page.assertOneTerminal()
        val _ = page.screenshot(Page.ScreenshotOptions().setPath(testResultsDir().resolve("diff-log-desktop.png")))
    }

    @Test fun phoneBranchLogKeepsHistoryAvailableWhileReadingACommit() = diffBrowser("diff-log-phone", phone = true) { _, page ->
        page.openDiff()
        assertThat(page.locator(".diff-branches-toggle")).isHidden()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Log").setExact(true)).click()
        page.getByLabel(java.util.regex.Pattern.compile("^History branch")).selectOption("refs/heads/side")
        assertThat(page.locator(".diff-history li")).hasCount(2)
        page.locator(".diff-log-row").first().click()
        page.file("side.txt").click()
        assertThat(page.patch()).containsText("side content")
        assertThat(page.locator(".diff-log-row").first()).isVisible()
        assertEquals(true, page.evaluate("() => document.documentElement.scrollWidth <= innerWidth"))
        val _ = page.screenshot(Page.ScreenshotOptions().setPath(testResultsDir().resolve("diff-log-phone.png")))
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Back to files")).click()
        assertThat(page.file("side.txt")).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Local changes").setExact(true)).click()
        assertThat(page.file("tracked.txt")).isVisible()
        page.assertOneTerminal()
    }

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

    @Test fun deepTreeRowsShareTheFullScrollableWidth() = diffBrowser("diff-tree-width") { harness, page ->
        val inventory = page.context().request().get(harness.baseUrl + "/api/v1/sessions/s-diff/diff/worktrees")
        val root = java.nio.file.Path.of(com.google.gson.JsonParser.parseString(inventory.text()).asJsonObject
            .getAsJsonObject("data").get("currentWorktreeId").asString)
        val directory = "core/src/main/kotlin/io/heapy/kotdoc/images"
        val _ = java.nio.file.Files.createDirectories(root.resolve(directory))
        val _ = java.nio.file.Files.writeString(root.resolve("$directory/ImageHdr.kt"), "class ImageHdr\n")
        val _ = java.nio.file.Files.writeString(root.resolve("$directory/SystemFloatMath.kt"), "class SystemFloatMath\n")
        page.openDiff()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Tree").setExact(true)).click()
        val tree = page.getByRole(AriaRole.TREE, Page.GetByRoleOptions().setName("Changed files"))
        assertThat(page.file("$directory/SystemFloatMath.kt")).isVisible()
        val parentName = page.locator(".diff-folder-row[title='$directory'] .diff-folder-name").boundingBox()
        val childName = page.file("$directory/ImageHdr.kt").locator(".diff-file-name").boundingBox()
        assertEquals(true, childName.x > parentName.x + 10, "a child file name is indented beyond its parent folder name")
        assertEquals(true, tree.evaluate("el => el.scrollWidth > el.clientWidth"), "deep paths need horizontal scrolling")
        assertEquals(true, tree.evaluate("""el => {
          const rows = [...el.querySelectorAll('[role=treeitem]')].map(row => row.getBoundingClientRect());
          return rows.every(row => Math.abs(row.width - rows[0].width) < 1);
        }"""), "all folder and file rows fill the same scrollable track")
        fun assertCountersAtTheVisibleRightEdge() {
            assertEquals(true, tree.evaluate("""el => {
              const right = el.getBoundingClientRect().left + el.clientLeft + el.clientWidth;
              return [...el.querySelectorAll('.diff-folder-count, .diff-stats')].every(counter => {
                const gap = right - counter.getBoundingClientRect().right;
                return gap >= 6 && gap <= 16;
              });
            }"""), "folder counts and file stats stay at the visible right edge")
        }
        assertCountersAtTheVisibleRightEdge()
        val _ = tree.evaluate("el => { el.scrollLeft = (el.scrollWidth - el.clientWidth) / 2; }")
        assertCountersAtTheVisibleRightEdge()
        val _ = tree.evaluate("el => { el.scrollLeft = el.scrollWidth; }")
        assertCountersAtTheVisibleRightEdge()
        assertEquals(true, tree.evaluate("""el => {
          const right = el.getBoundingClientRect().left + el.clientLeft + el.clientWidth;
          return [...el.querySelectorAll('[role=treeitem]')].every(row => row.getBoundingClientRect().right >= right - 8);
        }"""), "row backgrounds still reach the right edge after scrolling")
        val _ = tree.evaluate("el => { el.scrollLeft = 0; }")
        page.file("$directory/SystemFloatMath.kt").focus()
        assertEquals(0, tree.evaluate("el => el.scrollLeft"), "focusing a deep file keeps the left side of the tree visible")
        page.file("$directory/ImageHdr.kt").click()
        assertThat(page.patch()).containsText("class ImageHdr")
        val _ = tree.evaluate("el => { el.scrollLeft = el.scrollWidth; }")
        assertEquals(false, page.file("$directory/ImageHdr.kt").evaluate("""row => {
          const rect = row.getBoundingClientRect();
          return row.contains(document.elementFromPoint(rect.right + 2, rect.top + rect.height / 2));
        }"""), "the sticky counter background never protrudes beyond the selected row")
        val _ = page.screenshot(Page.ScreenshotOptions().setPath(testResultsDir().resolve("diff-tree-width.png")))
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("List").setExact(true)).click()
        assertEquals(true, page.locator(".diff-file-list").evaluate("el => el.scrollWidth <= el.clientWidth"), "List stays within the sidebar")
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

    @Test fun historyPagesRemainPinnedAndCommitParentsAndBranchBasesAreSelectable() = diffBrowser("diff-history", scenario = "diff-history") { harness, page ->
        page.openDiff()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Log").setExact(true)).click()
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
        page.locator(".diff-history button").filter(com.microsoft.playwright.Locator.FilterOptions().setHasText("Root fixture")).click()
        assertThat(page.locator(".diff-commit")).containsText("Root commit")
        assertThat(page.getByLabel(java.util.regex.Pattern.compile("^Parent"))).hasCount(0)
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Branch changes").setExact(true)).click()
        assertThat(page.getByLabel(java.util.regex.Pattern.compile("^Base branch"))).hasValue("refs/heads/main")
        assertThat(page.file("topic.txt")).isVisible()
        page.getByLabel(java.util.regex.Pattern.compile("^Base branch")).selectOption("refs/heads/side")
        assertThat(page.file("side.txt")).hasCount(0)
        assertThat(page.locator(".diff-comparison").first()).containsText("merge-base")
        page.locator(".diff-history li button").first().click()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Log").setExact(true))).hasAttribute("aria-pressed", "true")
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
        val linkedSessions = page.locator("section.task-detail section.task-sessions")
        assertThat(linkedSessions).containsText("diff-review")
        page.releaseOnTaskRender("unlink", "local:1", unlinked = true)
        harness.send("diff-unlink")
        assertThat(linkedSessions).not().containsText("diff-review")
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
        const linkedSessions = document.querySelector('section.task-detail section.task-sessions');
        if (unlinked && (!linkedSessions || linkedSessions.textContent.includes('diff-review'))) return;
        observer.disconnect(); window['__diffRelease_'+key]();
      });
      observer.observe(document.querySelector('#app'), {subtree:true,childList:true,characterData:true});
    }""", listOf(key, task, unlinked))
}
private fun diffBrowser(trace: String, phone: Boolean = false, scenario: String = DIFF_SCENARIO, body: (Harness, Page) -> Unit) {
    var fixtureDirectory: java.nio.file.Path? = null
    Harness(scenario).use { harness ->
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
