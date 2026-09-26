package io.kotgent.host

import platform.posix.PATH_MAX
import kotlin.test.Test
import kotlin.test.assertEquals

class HostOsLinuxTest {
    @Test
    fun theHostAndPathLimitMatchLinux() {
        assertEquals(HostOs.LINUX, hostOs)
        assertEquals(PATH_MAX - 1, pathLengthLimit().max)
    }
}
