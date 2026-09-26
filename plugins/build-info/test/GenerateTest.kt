package io.kotgent.buildinfo

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GenerateTest {
    @Test
    fun kotlinStringLiteralEscapesEverySourceMetacharacter() {
        val escaped = kotlinStringLiteral("\"\\${'$'}\n\r\t\b\u000c\u0001\u2028")
        val expected = buildString {
            append('"')
            append("\\\"")
            append("\\\\")
            append('\\')
            append('$')
            append("\\n\\r\\t\\b\\u000c\\u0001\\u2028")
            append('"')
        }
        assertEquals(expected, escaped)
    }

    @Test
    fun generatedSourceUsesQuotedValuesAndLiteralBoolean() {
        val source = generatedBuildInfoSource("1.2.3", "deadbee", releaseBuild = false)
        assertTrue(source.contains("""VERSION: String = "1.2.3""""))
        assertTrue(source.contains("""BUILD_GIT_HASH: String = "deadbee""""))
        assertTrue(source.contains("BUILD_IS_RELEASE: Boolean = false"))
    }

    @Test
    fun unchangedSourceLeavesTheFileUntouched() {
        val dir = Files.createTempDirectory("build-info-generate")
        try {
            val file = dir.resolve("io/kotgent/GeneratedBuildInfo.kt")
            writeBuildInfo(dir, "first")
            val stamp = FileTime.fromMillis(0)
            file.setLastModifiedTime(stamp)

            writeBuildInfo(dir, "first")
            assertEquals(stamp, file.getLastModifiedTime())

            writeBuildInfo(dir, "second")
            assertEquals("second", file.readText())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun releaseFlagMustBeExplicit() {
        assertEquals(false, releaseBuildFrom(null))
        assertEquals(true, releaseBuildFrom("true"))
        assertFailsWith<IllegalStateException> { releaseBuildFrom("") }
        assertFailsWith<IllegalStateException> { releaseBuildFrom("TRUE") }
        assertFailsWith<IllegalStateException> { releaseBuildFrom("1") }
    }
}
