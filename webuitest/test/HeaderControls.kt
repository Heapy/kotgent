package io.kotgent.webuitest

import com.microsoft.playwright.Page

/** The session menu owns Commands; screens without a session expose it directly. */
fun Page.openHeaderCommands() {
    val actions = locator("#session-actions-toggle")
    if (actions.isVisible && actions.getAttribute("aria-expanded") != "true") actions.click()
    locator("#palette-button").click()
}
