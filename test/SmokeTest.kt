package io.kotgent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeTest {
    @Test
    fun reportsVersion() {
        assertEquals("kotgent $VERSION", versionLine())
        assertTrue(VERSION.isNotBlank(), "VERSION must be set")
    }
}
