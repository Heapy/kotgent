package io.kotgent.sys

import io.kotgent.host.HostOs
import io.kotgent.host.hostOs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostEnvironmentTest {
    @Test
    fun aDaemonKeepsAbsoluteUserPathsAheadOfThePlatformFloor() {
        val path = mergedExecutablePath(":.:/custom/bin:relative:/custom/bin:/usr/bin")
        assertEquals(listOf("/custom/bin", "/usr/bin"), path.split(':').take(2))
        assertEquals(path.split(':').distinct(), path.split(':'))
        assertTrue(path.split(':').all { it.startsWith('/') })
        assertEquals(DEFAULT_EXECUTABLE_PATH, mergedExecutablePath(null))
    }

    @Test
    fun theBrowserOpenerReceivesOneLiteralUrlArgument() {
        val url = "http://localhost/auth?one=two&three=four"
        assertEquals(listOf(if (hostOs == HostOs.LINUX) "xdg-open" else "open", url), browserOpenCommand(url))
    }
}
