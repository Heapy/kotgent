package io.kotgent

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun reportsVersion() {
        assertEquals("kotgent 0.1.0-SNAPSHOT", versionLine())
    }
}
