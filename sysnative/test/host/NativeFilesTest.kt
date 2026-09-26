package io.kotgent.host

import io.kotgent.exe.NativeExe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.F_OK
import platform.posix.access
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class NativeFilesTest {
    @Test
    fun executablePathResolvesTheRunningBinaryAndItsTimestamps() {
        val executable = assertNotNull(NativeExe.path())
        assertTrue(executable.startsWith('/'))
        assertEquals(0, access(executable, F_OK))
        val times = assertNotNull(fileTimes(executable))
        assertTrue(times.modifiedMillis > 0)
        assertTrue(times.birthOrModifiedMillis > 0)
        if (hostOs == HostOs.LINUX) assertNull(times.birthMillis)
    }

    @Test
    fun missingFilesHaveNoTimestamp() {
        assertNull(fileTimes("/dev/null/not-a-directory"))
    }
}
