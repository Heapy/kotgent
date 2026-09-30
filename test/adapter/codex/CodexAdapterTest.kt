package io.kotgent.adapter.codex

import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchOptions
import io.kotgent.adapter.initialPromptInstruction
import io.kotgent.core.ProviderSessionId
import io.kotgent.daemon.SessionManager
import io.kotgent.tmux.ProcessResult
import io.kotgent.tmux.ProcessRunner
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
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

        assertEquals(
            listOf("-c", CodexHookConfig.hooksToml(hookScript)),
            spec.command.drop(1),
            "hook trust travels in the -c hooks value, never as a flag that trusts every hook",
        )
    }

    @Test
    fun resumeLaunchPutsTheSubcommandAndIdBeforeTheOverrides() {
        val id = ProviderSessionId("019f8ea0-2548-7871-9835-947ff7623ccf")
        val spec = adapter().buildLaunchSpec(LaunchMode.Resume(id))

        assertEquals(listOf("codex", "resume", id.value), spec.command.take(3), "subcommand + id come first")
        assertNull(spec.preallocatedSessionId, "a resume never preallocates (the id already exists)")
        assertEquals(
            listOf("-c", CodexHookConfig.hooksToml(hookScript)),
            spec.command.drop(3),
            "resumed sessions carry the same hooks and trust",
        )
    }

    @Test
    fun readOnlyNewLaunchSandboxesAndPassesTheInitialPromptAfterTheOptions() {
        val spec = adapter().buildLaunchSpec(
            LaunchMode.New,
            LaunchOptions(readOnly = true, promptPath = "/home/u/.kotgent/prompts/s1.md"),
        )

        assertEquals(
            listOf(
                "codex", "-c", CodexHookConfig.hooksToml(hookScript),
                "--sandbox", "read-only",
                "--", initialPromptInstruction("/home/u/.kotgent/prompts/s1.md"),
            ),
            spec.command,
        )
    }

    @Test
    fun promptWithoutReadOnlyKeepsTheConfiguredSandbox() {
        val spec = adapter().buildLaunchSpec(LaunchMode.New, LaunchOptions(promptPath = "/p.md"))

        assertEquals(
            listOf("codex", "-c", CodexHookConfig.hooksToml(hookScript), "--", initialPromptInstruction("/p.md")),
            spec.command,
        )
    }

    @Test
    fun readOnlyResumeSandboxesAndIgnoresTheInitialPrompt() {
        val id = ProviderSessionId("019f8ea0-2548-7871-9835-947ff7623ccf")
        val spec = adapter().buildLaunchSpec(LaunchMode.Resume(id), LaunchOptions(readOnly = true, promptPath = "/p.md"))

        assertEquals(
            listOf("codex", "resume", id.value, "-c", CodexHookConfig.hooksToml(hookScript), "--sandbox", "read-only"),
            spec.command,
        )
    }

    @Test
    fun aPromptPathStartingWithADashStaysAnOperandAfterTheEndOfOptions() {
        val spec = adapter().buildLaunchSpec(LaunchMode.New, LaunchOptions(promptPath = "--dangerously-bypass-hook-trust"))

        assertEquals(spec.command.size - 2, spec.command.indexOf("--"), "only the prompt follows the marker")
        val prompt = spec.command.last()
        assertFalse(prompt.startsWith("-"), "the prompt operand is an instruction, never flag-shaped: $prompt")
        assertTrue("--dangerously-bypass-hook-trust" in prompt)
        assertFalse(spec.command.contains("--dangerously-bypass-hook-trust"), "the path never becomes its own argument")
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
        assertTrue(script.contains("?event='\"\$1\""), "the event name is the first argument, appended to the ingress URL")
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
    fun hooksTomlTrustsEachHandlerAtItsCodexKey() {
        val toml = CodexHookConfig.hooksToml(hookScript)

        assertTrue(toml.endsWith(",state={" + CodexHookConfig.HOOK_EVENTS.joinToString(",") { event ->
            "\"${CodexHookConfig.trustKey(event)}\"={trusted_hash=\"${CodexHookConfig.trustedHash(hookScript, event)}\"}"
        } + "}}"), "state lives inside the same hooks value: $toml")
        assertEquals(
            "/<session-flags>/config.toml:user_prompt_submit:0:0",
            CodexHookConfig.trustKey(CodexHookConfig.USER_PROMPT_SUBMIT),
        )
        assertTrue(toml.contains("SessionEnd\",timeout=1}"), "SessionEnd pins Codex's one-second timeout")
        assertTrue(toml.contains("Stop\",timeout=600}"), "other events pin Codex's ten-minute timeout")
    }

    /** Expected values are Codex 0.156.1 `hooks/list` `currentHash` output for these exact handlers. */
    @Test
    fun trustedHashMatchesCodexHookIdentity() {
        val expected = mapOf(
            CodexHookConfig.USER_PROMPT_SUBMIT to "209f659556a4464dbf57df77d888681ab48effbc4877aeb083a51086345b16d8",
            CodexHookConfig.POST_TOOL_USE to "e486caa28fbfa4a5a2f1d5aa8c84777226fe1d47afd9cb21d39a6f1f9aca281e",
            CodexHookConfig.PERMISSION_REQUEST to "5c4b0f1b489b5cdc75db59d6801bf09aa3a899270ddfaca02859834cffb69db8",
            CodexHookConfig.STOP to "80dc8a00aa3301fe3c832820048aef3227f72185a611928f51cfcd85dbb7fdf8",
            CodexHookConfig.SESSION_START to "c8c94548cf13e9cbe5928b7d20ac831c8dc1e30b258cd70be020718fc6dce581",
            CodexHookConfig.SESSION_END to "0c631650c5e671d24b13f13107f72994a67d2fc74a63fc20a9fc55a5fef108b4",
        )
        assertEquals(CodexHookConfig.HOOK_EVENTS.toSet(), expected.keys)
        for ([event, hash] in expected) {
            assertEquals("sha256:$hash", CodexHookConfig.trustedHash(hookScript, event), event)
        }

        val escaped = """/home/u/we"ird\path/Юзер it's/codex-hook.sh"""
        assertEquals(
            "sha256:33a2d77cfb9dabfefb46378f10f2fa3bf32c546b19770f613c1ebdf045a25915",
            CodexHookConfig.trustedHash(escaped, CodexHookConfig.POST_TOOL_USE),
            "JSON escapes quotes and backslashes but keeps non-ASCII text raw",
        )
        assertEquals(
            "sha256:39a575936b9a1a30e1e4614d63465f18671798e463c10ee04b715255a1eed7cc",
            CodexHookConfig.trustedHash(escaped, CodexHookConfig.SESSION_END),
        )
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

    @Test
    fun realCodexIfInstalledTrustsExactlyTheGeneratedHooks() {
        val cli = CodexCli()
        cli.detectVersion() ?: return
        val result = ProcessRunner.run(
            listOf(
                "/bin/sh", "-c", HOOKS_LIST_PROBE, "kotgent-codex-probe",
                cli.binaryName, CodexHookConfig.hooksToml(hookScript),
            ),
        )
        assertTrue(result.isSuccess && result.stdout.isNotBlank(), "codex app-server answered hooks/list: $result")

        val hooks = Json.parseToJsonElement(result.stdout).jsonObject.getValue("result").jsonObject.getValue("data")
            .jsonArray.flatMap { it.jsonObject.getValue("hooks").jsonArray }
        val statusByKey = hooks.associate {
            it.jsonObject.getValue("key").jsonPrimitive.content to it.jsonObject.getValue("trustStatus").jsonPrimitive.content
        }
        assertEquals(CodexHookConfig.HOOK_EVENTS.associate { CodexHookConfig.trustKey(it) to "trusted" }, statusByKey)
    }

    private companion object {
        /**
         * An empty `CODEX_HOME` hides the operator's hooks, stored trust and MCP servers. app-server exits at
         * end of input before answering, so stdin stays open until the answer or a 30-second limit.
         */
        val HOOKS_LIST_PROBE: String = $$"""
            dir=$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/kotgent-codex-probe.XXXXXX") || exit 1
            trap '/bin/rm -rf "$dir"' EXIT
            mkdir "$dir/home" && cd "$dir" || exit 1
            {
              printf '%s\n' '{"id":1,"method":"initialize","params":{"clientInfo":{"name":"kotgent-test","version":"0"}}}' \
                '{"method":"initialized"}' '{"id":2,"method":"hooks/list","params":{}}'
              i=0
              while [ "$i" -lt 300 ] && ! grep -q '^{"id":2,' out 2>/dev/null; do sleep 0.1; i=$((i + 1)); done
            } | CODEX_HOME="$dir/home" "$1" -c "$2" app-server > out 2> /dev/null
            grep '^{"id":2,' out
        """.trimIndent()
    }
}
