package io.kotgent.cli

import io.kotgent.transport.defaultTokenPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class ConfigTest {

    private val path: String = run {
        val dir = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        "$dir/kotgent-configtest-${getpid()}.json"
    }

    @AfterTest
    fun cleanup() {
        unlink(path)
    }

    @Test
    fun writeConfigThenReadConfigRoundTripsThePublicUrlAs0600() {
        unlink(path)
        writeConfig(path, KotgentConfig(publicUrl = "https://kotgent.heapyhop.com"))

        assertEquals(
            KotgentConfig(publicUrl = "https://kotgent.heapyhop.com"),
            readConfig(path),
            "the written config reads back verbatim",
        )
        assertEquals(0b110_000_000, fileMode(path) and 0b111_111_111, "the config file is 0600")
    }

    @Test
    fun writeConfigReplacesAnExistingConfig() {
        unlink(path)
        writeConfig(path, KotgentConfig(publicUrl = "https://old.example.com"))
        writeConfig(path, KotgentConfig(publicUrl = "https://new.example.com"))
        assertEquals("https://new.example.com", readConfig(path).publicUrl, "the second write wins")
    }

    @Test
    fun anEmptyConfigRoundTripsAsNoPublicUrl() {
        unlink(path)
        writeConfig(path, KotgentConfig())
        assertNull(readConfig(path).publicUrl, "an unset publicUrl stays unset")
    }

    @Test
    fun readConfigOnAMissingFileIsTheEmptyConfig() {
        unlink(path)
        assertEquals(KotgentConfig(), readConfig(path), "a missing config file reads as the empty config")
    }

    @Test
    fun readConfigOnAnEmptyFileIsTheEmptyConfig() {
        writeText(path, "")
        assertEquals(KotgentConfig(), readConfig(path), "an empty config file reads as the empty config")
        writeText(path, "  \n ")
        assertEquals(KotgentConfig(), readConfig(path), "a whitespace-only config file reads as the empty config")
    }

    @Test
    fun readConfigOnBrokenJsonFailsWithThePathInTheMessage() {
        writeText(path, "{ this is not json")
        val e = assertFailsWith<ConfigException>("broken JSON must fail loudly, not silently default") {
            readConfig(path)
        }
        assertTrue(e.message!!.contains(path), "the error names the file to fix: ${e.message}")
    }

    @Test
    fun readConfigIgnoresUnknownKeys() {
        writeText(path, """{"publicUrl":"https://kotgent.heapyhop.com","somethingNewer":42}""")
        assertEquals("https://kotgent.heapyhop.com", readConfig(path).publicUrl, "unknown keys are ignored")
    }

    @Test
    fun readConfigRejectsAnInvalidPublicUrlOnDisk() {
        writeText(path, """{"publicUrl":"https://kotgent.heapyhop.com/some/path"}""")
        val e = assertFailsWith<ConfigException> { readConfig(path) }
        assertTrue(e.message!!.contains(path), "the error names the file: ${e.message}")
    }

    @Test
    fun readConfigCanonicalisesThePublicUrl() {
        writeText(path, """{"publicUrl":"HTTPS://Kotgent.Heapyhop.COM/"}""")
        assertEquals(
            "https://kotgent.heapyhop.com",
            readConfig(path).publicUrl,
            "the stored URL is lower-cased and loses its trailing slash",
        )
    }

    @Test
    fun writeConfigRefusesAnInvalidPublicUrlWithoutTouchingTheFile() {
        unlink(path)
        writeConfig(path, KotgentConfig(publicUrl = "https://good.example.com"))
        assertFailsWith<ConfigException> { writeConfig(path, KotgentConfig(publicUrl = "https://bad.example.com/x")) }
        assertEquals(
            "https://good.example.com",
            readConfig(path).publicUrl,
            "a rejected write leaves the previous config intact",
        )
    }

    @Test
    fun publicUrlValidationAcceptsOnlyABareHttpsOrigin() {
        for (good in listOf(
            "https://kotgent.heapyhop.com",
            "https://kotgent.heapyhop.com/",
            "https://kotgent.heapyhop.com:8443",
            "  https://kotgent.heapyhop.com  ",
            "http://127.0.0.1:27508",
            "http://localhost",
        )) {
            assertNull(publicUrlProblem(good), "'$good' should be accepted")
        }

        for (bad in listOf(
            "",
            "   ",
            "kotgent.heapyhop.com",
            "://kotgent.heapyhop.com",
            "ftp://kotgent.heapyhop.com",
            "wss://kotgent.heapyhop.com",
            "https://",
            "https://kotgent.heapyhop.com/path",
            "https://kotgent.heapyhop.com/?q=1",
            "https://kotgent.heapyhop.com/#frag",
            "https://user@kotgent.heapyhop.com",
            "https://kotgent.heapyhop.com com",
            "http://kotgent.heapyhop.com",
        )) {
            assertTrue(publicUrlProblem(bad) != null, "'$bad' should be rejected")
        }
    }

    @Test
    fun defaultConfigPathSitsNextToTheToken() {
        assertEquals("${kotgentHome()}/$CONFIG_FILE_NAME", defaultConfigPath())
        assertEquals(
            defaultTokenPath().substringBeforeLast('/'),
            defaultConfigPath().substringBeforeLast('/'),
            "the config lives in the same directory as the token",
        )
    }

    private fun fileMode(p: String): Int = memScoped {
        val st = alloc<platform.posix.stat>()
        platform.posix.stat(p, st.ptr)
        st.st_mode.toInt()
    }

    private fun writeText(p: String, text: String) {
        val fp = fopen(p, "wb") ?: error("cannot open $p for write")
        fputs(text, fp)
        fclose(fp)
    }
}
