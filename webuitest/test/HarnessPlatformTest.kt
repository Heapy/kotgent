package io.kotgent.webuitest

import kotlin.test.Test
import kotlin.test.assertTrue

class HarnessPlatformTest {
    @Test
    fun eachHostSelectsOnlyExecutablesForItsOwnArchitecture() {
        for ([os, arch, target] in listOf(
            Triple("Mac OS X", "aarch64", "MacosArm64"),
            Triple("Linux", "amd64", "LinuxX64"),
            Triple("Linux", "aarch64", "LinuxArm64"),
        )) {
            val binaries = harnessBinaries(os, arch)
            assertTrue(binaries.all { "link$target" in it })
            assertTrue(binaries.first().contains("Debug"))
        }
    }
}
