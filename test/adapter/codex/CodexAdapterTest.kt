package io.kotgent.adapter.codex

import io.kotgent.adapter.LaunchMode
import io.kotgent.core.ProviderSessionId
import io.kotgent.daemon.SessionManager
import io.kotgent.tmux.ProcessResult
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexAdapterTest {

    private val hookScript = "/home/u/.kotgent/codex-hook.sh"

    private fun adapter(binaryName: String = "codex") =
        CodexAdapter(cwd = "/work/repo", hookScriptPath = hookScript, events = emptyFlow(), binaryName = binaryName)


    @Test
    fun newLaunchInstallsHooksAndPreallocatesNothing() {
        val spec = adapter().buildLaunchSpec(LaunchMode.New)

        assertEquals("/work/repo", spec.cwd)
        assertEquals("codex", spec.command.first())
        assertFalse(spec.command.contains(CodexAdapter.RESUME_SUBCOMMAND), "a New launch is not a resume")
        assertNull(spec.preallocatedSessionId, "codex has no --session-id: nothing is preallocated")

        val overrides = spec.command.withIndex()
            .filter { it.value == CodexAdapter.CONFIG_FLAG }
            .map { spec.command[it.index + 1] }
        assertEquals(2, overrides.size, "exactly two -c overrides: ${spec.command}")
        assertTrue(overrides.any { it.startsWith("hooks=") }, "one -c carries the hooks: $overrides")
        assertContains(overrides, CodexAdapter.BYPASS_HOOK_TRUST)
    }

    @Test
    fun resumeLaunchPutsTheSubcommandAndIdBeforeTheOverrides() {
        val id = ProviderSessionId("019f8ea0-2548-7871-9835-947ff7623ccf")
        val spec = adapter().buildLaunchSpec(LaunchMode.Resume(id))

        assertEquals(listOf("codex", "resume", id.value), spec.command.take(3), "subcommand + id come first")
        assertNull(spec.preallocatedSessionId, "a resume never preallocates (the id already exists)")
        assertTrue(spec.command.any { it.startsWith("hooks=") }, "a resumed session is hooked too: ${spec.command}")
    }

    @Test
    fun launchUsesTheResolvedBinaryPath() {
        val spec = adapter(binaryName = "/opt/homebrew/bin/codex").buildLaunchSpec(LaunchMode.New)
        assertEquals("/opt/homebrew/bin/codex", spec.command.first())
    }

    @Test
    fun buildLaunchSpecCarriesTheCliVersionAndPath() {
        val withMeta = CodexAdapter(
            cwd = "/work/repo",
            hookScriptPath = hookScript,
            events = emptyFlow(),
            cliVersion = "0.145.0",
            cliPath = "/opt/homebrew/bin/codex",
        )
        val id = ProviderSessionId("019f8ea0-2548-7871-9835-947ff7623ccf")
        for (spec in listOf(withMeta.buildLaunchSpec(LaunchMode.New), withMeta.buildLaunchSpec(LaunchMode.Resume(id)))) {
            assertEquals("0.145.0", spec.cliVersion)
            assertEquals("/opt/homebrew/bin/codex", spec.cliPath)
        }

        val bare = adapter().buildLaunchSpec(LaunchMode.New)
        assertNull(bare.cliVersion)
        assertNull(bare.cliPath)
    }

    @Test
    fun theLaunchArgvSurvivesTmuxShellQuoting() {
        val spec = adapter().buildLaunchSpec(LaunchMode.New)
        val line = SessionManager.shellCommand(spec.command)
        val toml = spec.command.first { it.startsWith("hooks=") }

        assertTrue(line.contains("'" + toml.replace("'", "'\\''") + "'"))
        assertFalse(line.contains("\$TMUX_PANE"), "the launch line carries no live \$TMUX_PANE")
    }


    @Test
    fun hookScriptPostsToTheIngressWithTokenPaneAndEvent() {
        val script = CodexHookConfig.hookScript(port = 7777, headerFilePath = "/home/u/.kotgent/codex-hook-header")

        assertTrue(script.startsWith("#!/bin/sh"), "it is a shell script")
        assertEquals("/hooks/codex", CodexHookConfig.LEGACY_INGRESS_PATH)
        assertTrue(script.contains("http://127.0.0.1:7777/api/v1/hooks/codex?event="))
        assertTrue(script.contains("\"\$1\""), "the event name comes from the first argument")
        assertTrue(script.contains("-H '@/home/u/.kotgent/codex-hook-header'"))
        assertTrue(script.contains("X-Kotgent-Tmux-Pane: \$TMUX_PANE"))
        assertTrue(script.contains("--data-binary @-"), "the hook payload is forwarded from stdin unchanged")
    }

    @Test
    fun hookScriptNeverContainsTheToken() {
        val script = CodexHookConfig.hookScript(port = 7777, headerFilePath = "/home/u/.kotgent/codex-hook-header")
        assertFalse(script.contains("s3cr3t"), "the token itself is not in the script")
    }


    @Test
    fun hooksTomlWiresEveryEventToTheScript() {
        val toml = CodexHookConfig.hooksToml(hookScript)

        assertTrue(toml.startsWith("hooks={") && toml.endsWith("}"), "it is a `hooks=` TOML value: $toml")
        for (event in CodexHookConfig.HOOK_EVENTS) {
            assertTrue(toml.contains("$event=[{"), "every wired event appears: $event")
            assertTrue(toml.contains("/bin/sh '$hookScript' $event"), "…invoking the script with its own name")
        }
        assertEquals(6, CodexHookConfig.HOOK_EVENTS.size, "the six events the normalizer maps")
    }

    @Test
    fun onlyPostToolUseCarriesAMatcher() {
        val toml = CodexHookConfig.hooksToml(hookScript)
        assertTrue(toml.contains("${CodexHookConfig.POST_TOOL_USE}=[{matcher=\"*\""), "PostToolUse matches every tool")
        assertEquals(1, Regex("matcher=").findAll(toml).count(), "no other event takes a matcher: $toml")
    }

    @Test
    fun hooksTomlEscapesAPathWithQuotesAndBackslashes() {
        val toml = CodexHookConfig.hooksToml("""/home/u/we"ird\path/codex-hook.sh""")
        assertTrue(toml.contains("""\""""), "the embedded quote is TOML-escaped")
        assertTrue(toml.contains("""\\"""), "the embedded backslash is TOML-escaped")
    }


    @Test
    fun parsesTheCodexCliVersionBanner() {
        assertEquals(CodexVersion(0, 145, 0), CodexCli.parseVersion("codex-cli 0.145.0\n"))
        assertEquals(CodexVersion(1, 2, 3), CodexCli.parseVersion("1.2.3"))
        assertNull(CodexCli.parseVersion("codex-cli"), "no triple -> null, never a crash")
        assertNull(CodexCli.parseVersion(""), "empty output -> null")
    }

    @Test
    fun versionsCompareByComponent() {
        assertTrue(CodexVersion(0, 145, 0) > CodexVersion(0, 99, 9))
        assertTrue(CodexVersion(1, 0, 0) > CodexVersion(0, 145, 0))
        assertEquals("0.145.0", CodexVersion(0, 145, 0).toString())
    }

    @Test
    fun cliDegradesWhenTheBinaryIsMissing() {
        val cli = CodexCli(runner = { ProcessResult(127, ByteArray(0), "command not found".encodeToByteArray()) })
        assertNull(cli.locate(), "an absent binary locates to null")
        assertNull(cli.detectVersion(), "…and has no version")
        assertFalse(cli.isInstalled())
    }

    @Test
    fun cliReadsLocationAndVersionFromTheRunner() {
        val cli = CodexCli(
            runner = { argv ->
                when {
                    argv.contains("--version") -> ProcessResult(0, "codex-cli 0.145.0\n".encodeToByteArray(), ByteArray(0))
                    else -> ProcessResult(0, "/opt/homebrew/bin/codex\n".encodeToByteArray(), ByteArray(0))
                }
            },
        )
        assertEquals("/opt/homebrew/bin/codex", cli.locate())
        assertEquals(CodexVersion(0, 145, 0), cli.detectVersion())
        assertTrue(cli.isInstalled())
    }

    @Test
    fun realCodexIfInstalledReportsAParsableVersion() {
        val cli = CodexCli()
        val version = cli.detectVersion() ?: return
        assertTrue(version.major > 0 || version.minor > 0, "a real codex reports a non-zero version: $version")
    }
}
