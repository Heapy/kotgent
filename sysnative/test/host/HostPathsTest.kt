package io.kotgent.host

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostPathsTest {
    @Test
    fun macosAdmitsPathsUpTo1023Bytes() {
        val limit = pathLengthLimit(HostOs.MACOS)
        assertTrue(limit.admits("/" + "a".repeat(1022)))
        assertFalse(limit.admits("/" + "a".repeat(1023)))
    }

    @Test
    fun linuxAdmitsPathsUpTo4095Bytes() {
        val limit = pathLengthLimit(HostOs.LINUX)
        assertTrue(limit.admits("/" + "a".repeat(4094)))
        assertFalse(limit.admits("/" + "a".repeat(4095)))
    }

    @Test
    fun windowsAdmitsPathsUpTo32767CodeUnits() {
        val limit = pathLengthLimit(HostOs.WINDOWS)
        assertTrue(limit.admits("C:\\" + "a".repeat(32_764)))
        assertFalse(limit.admits("C:\\" + "a".repeat(32_765)))
    }

    @Test
    fun posixCountsEncodedBytesNotCharacters() {
        val twoBytesEach = "/" + "ж".repeat(512)
        assertTrue(twoBytesEach.length < 1023, "the character count alone would pass")
        assertFalse(pathLengthLimit(HostOs.MACOS).admits(twoBytesEach))
        assertTrue(pathLengthLimit(HostOs.MACOS).admits("/" + "ж".repeat(511)))
    }

    @Test
    fun windowsCountsUtf16CodeUnitsNotBytes() {
        val limit = pathLengthLimit(HostOs.WINDOWS)
        assertTrue(limit.admits("C:\\" + "ж".repeat(32_764)), "each character is one code unit though two bytes")
        assertFalse(limit.admits("C:\\" + "😀".repeat(16_383)), "a character outside the BMP is two code units")
    }
}
