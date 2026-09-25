package io.kotgent.host

import platform.posix.PATH_MAX
import kotlin.test.Test
import kotlin.test.assertEquals

class HostOsMacosTest {
    @Test
    fun theHostIsMacos() {
        assertEquals(HostOs.MACOS, hostOs)
    }

    @Test
    fun theMacosLimitIsTheSdkPathMaxWithoutItsNul() {
        assertEquals(PATH_MAX - 1, pathLengthLimit(HostOs.MACOS).max)
        assertEquals(pathLengthLimit(HostOs.MACOS), pathLengthLimit())
    }
}
