package io.kotgent.adapter.junie

import io.kotgent.adapter.LaunchMode
import io.kotgent.core.ProviderSessionId
import io.kotgent.daemon.SessionManager
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JunieAdapterTest {

    private val hookConfig = "/home/u/.kotgent/junie-hooks.json"

    private fun adapter(binaryName: String = "junie") =
        JunieAdapter(cwd = "/work/repo", hookConfigPath = hookConfig, events = emptyFlow(), binaryName = binaryName)

    @Test
    fun newLaunchInstallsHooksAndPreallocatesNothing() {
        val spec = adapter().buildLaunchSpec(LaunchMode.New)

        assertEquals(listOf("junie", JunieAdapter.CONFIG_LOCATION_FLAG, hookConfig), spec.command)
        assertEquals("/work/repo", spec.cwd)
        assertFalse(spec.command.contains(JunieAdapter.RESUME_FLAG), "a New launch is not a resume")
        assertNull(spec.preallocatedSessionId, "junie's --session-id only addresses an EXISTING session")
    }

    @Test
    fun resumeLaunchAddressesTheRecordedSessionById() {
        val id = ProviderSessionId("session-260730-015553-1j1h")
        val spec = adapter().buildLaunchSpec(LaunchMode.Resume(id))

        assertEquals(
            listOf(
                "junie",
                JunieAdapter.RESUME_FLAG,
                JunieAdapter.SESSION_ID_FLAG,
                "session-260730-015553-1j1h",
                JunieAdapter.CONFIG_LOCATION_FLAG,
                hookConfig,
            ),
            spec.command,
        )
        assertNull(spec.preallocatedSessionId, "a resume never preallocates (the id already exists)")
    }

    @Test
    fun theCwdIsNotPassedAsAProjectFlag() {
        val spec = adapter().buildLaunchSpec(LaunchMode.New)
        assertFalse(spec.command.contains("--project"), "the cwd comes from tmux, not from an argv flag")
        assertFalse(spec.command.contains("/work/repo"), "…so it never appears in the argv: ${spec.command}")
    }

    @Test
    fun launchUsesTheResolvedBinaryPath() {
        val spec = adapter(binaryName = "/Users/u/.local/bin/junie").buildLaunchSpec(LaunchMode.New)
        assertEquals("/Users/u/.local/bin/junie", spec.command.first())
    }

    @Test
    fun buildLaunchSpecCarriesTheCliVersionAndPath() {
        val withMeta = JunieAdapter(
            cwd = "/work/repo",
            hookConfigPath = hookConfig,
            events = emptyFlow(),
            cliVersion = "26.8.3",
            cliPath = "/Users/u/.local/bin/junie",
        )
        val id = ProviderSessionId("session-260730-015553-1j1h")
        for (spec in listOf(withMeta.buildLaunchSpec(LaunchMode.New), withMeta.buildLaunchSpec(LaunchMode.Resume(id)))) {
            assertEquals("26.8.3", spec.cliVersion)
            assertEquals("/Users/u/.local/bin/junie", spec.cliPath)
        }

        val bare = adapter().buildLaunchSpec(LaunchMode.New)
        assertNull(bare.cliVersion)
        assertNull(bare.cliPath)
    }

    @Test
    fun theLaunchArgvSurvivesTmuxShellQuoting() {
        val spec = adapter().buildLaunchSpec(LaunchMode.New)
        val line = SessionManager.shellCommand(spec.command)

        assertTrue(line.contains("'$hookConfig'"), "the config path is one quoted word: $line")
        assertFalse(line.contains("\$TMUX_PANE"), "the launch line carries no live \$TMUX_PANE")
    }
}
