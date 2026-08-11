package io.kotgent.adapter.junie

import io.kotgent.tmux.ProcessResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JunieCliTest {

    @Test
    fun parsesTheJunieVersionBanner() {
        assertEquals(JunieVersion(26, 8, 3), JunieCli.parseVersion("Junie version: 26.8.3 (2548.3) eap\n"))
        assertEquals(JunieVersion(1, 2, 3), JunieCli.parseVersion("1.2.3"))
        assertNull(JunieCli.parseVersion("Junie version: unknown"), "no triple -> null, never a crash")
        assertNull(JunieCli.parseVersion(""), "empty output -> null")
    }

    @Test
    fun theBuildNumberDoesNotOutrankTheVersion() {
        assertEquals(JunieVersion(26, 8, 3), JunieCli.parseVersion("Junie version: 26.8.3 (2548.3.1) eap"))
    }

    @Test
    fun versionsCompareByComponent() {
        assertTrue(JunieVersion(26, 8, 3) > JunieVersion(26, 7, 9))
        assertTrue(JunieVersion(27, 0, 0) > JunieVersion(26, 8, 3))
        assertEquals("26.8.3", JunieVersion(26, 8, 3).toString())
    }

    @Test
    fun cliDegradesWhenTheBinaryIsMissing() {
        val cli = JunieCli(runner = { ProcessResult(127, ByteArray(0), "command not found".encodeToByteArray()) })
        assertNull(cli.locate(), "an absent binary locates to null")
        assertNull(cli.detectVersion(), "…and has no version")
        assertFalse(cli.isInstalled())
    }

    @Test
    fun cliReadsLocationAndVersionFromTheRunner() {
        val cli = JunieCli(
            runner = { argv ->
                when {
                    argv.contains("--version") ->
                        ProcessResult(0, "Junie version: 26.8.3 (2548.3) eap\n".encodeToByteArray(), ByteArray(0))
                    else -> ProcessResult(0, "/Users/u/.local/bin/junie\n".encodeToByteArray(), ByteArray(0))
                }
            },
        )
        assertEquals("/Users/u/.local/bin/junie", cli.locate())
        assertEquals(JunieVersion(26, 8, 3), cli.detectVersion())
        assertTrue(cli.isInstalled())
    }

    @Test
    fun realJunieIfInstalledReportsAParsableVersion() {
        val cli = JunieCli()
        val version = cli.detectVersion() ?: return
        assertTrue(version.major > 0, "a real junie reports a non-zero major version: $version")
    }
}
