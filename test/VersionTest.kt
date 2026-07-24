package io.kotgent

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {
    @Test
    fun localBuildShowsVersionAndEmbeddedHash() {
        assertEquals("9.8.7+deadbee", formatUiVersion("9.8.7", "deadbee", releaseBuild = false))
        assertEquals(
            "9.8.7+deadbeef",
            formatUiVersion("9.8.7", "  deadbeef  ", releaseBuild = false),
            "harmless surrounding process-output whitespace is ignored",
        )
    }

    @Test
    fun releaseBuildShowsOnlyTheVersionHomebrewPublishes() {
        assertEquals("9.8.7", formatUiVersion("9.8.7", "deadbee", releaseBuild = true))
    }

    @Test
    fun missingBlankOrMalformedHashesFallBackToTheVersion() {
        for (hash in listOf<String?>(
            null,
            "",
            "   ",
            "abcdef",
            "DEADBEE",
            "not-a-hash",
            "deadbee\$suffix",
            "deadbee\nmore",
        )) {
            assertEquals(
                "9.8.7",
                formatUiVersion("9.8.7", hash, releaseBuild = false),
                "hash ${hash?.let { "\"$it\"" }} must not enter the UI version",
            )
        }
    }
}
