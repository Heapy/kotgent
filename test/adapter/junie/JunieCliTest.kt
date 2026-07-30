package io.kotgent.adapter.junie

import io.kotgent.tmux.ProcessResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [JunieCli] — version parsing off the real banner shape and the "no binary" degradation.
 * The runner is injected, so nothing here needs a live `junie`; the one test that does touch the real CLI
 * soft-skips when it is absent (junie is not a build dependency).
 */
class JunieCliTest {

    @Test
    fun parsesTheJunieVersionBanner() {
        // The real banner (junie 26.8.3 EAP): a label, the semantic triple, a build number, a channel.
        assertEquals(JunieVersion(26, 8, 3), JunieCli.parseVersion("Junie version: 26.8.3 (2548.3) eap\n"))
        assertEquals(JunieVersion(1, 2, 3), JunieCli.parseVersion("1.2.3"))
        assertNull(JunieCli.parseVersion("Junie version: unknown"), "no triple -> null, never a crash")
        assertNull(JunieCli.parseVersion(""), "empty output -> null")
    }

    @Test
    fun theBuildNumberDoesNotOutrankTheVersion() {
        // `(2548.3)` is only a pair, so it cannot match the triple — but the triple must also win by
        // POSITION, since a future banner could carry a three-part build number.
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
        // Soft-skip: this is the one test that touches the real CLI, and junie is not a build dependency.
        val cli = JunieCli()
        val version = cli.detectVersion() ?: return
        assertTrue(version.major > 0, "a real junie reports a non-zero major version: $version")
    }
}
