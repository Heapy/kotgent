package io.kotgent.adapter.claude

import io.kotgent.adapter.LaunchMode
import io.kotgent.core.AgentEvent
import io.kotgent.core.ProviderSessionId
import io.kotgent.tmux.ProcessResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the Claude adapter (plan Task 11) — the OUTGOING side: launch/resume spec, hook
 * config generation, session-id preallocation, and the pure `--session-id` version gate. All pure
 * Kotlin (no cinterop, no live binary), so they run for real in the test binary. One extra test
 * additionally probes the *real* installed claude, guarded so it soft-skips when claude is absent.
 */
class ClaudeAdapterTest {

    private val uuidRe = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    // ---- buildLaunchSpec: New (preallocation) ----

    @Test
    fun buildLaunchSpecNewPreallocatesSessionIdAndInstallsSettings() {
        val adapter = ClaudeAdapter(cwd = "/work/repo", settingsPath = "/tmp/kt-hooks.json", events = emptyFlow())
        val spec = adapter.buildLaunchSpec(LaunchMode.New)

        assertEquals("/work/repo", spec.cwd)
        assertEquals("claude", spec.command.first())

        val sidIdx = spec.command.indexOf("--session-id")
        assertTrue(sidIdx >= 0, "New spec passes --session-id: ${spec.command}")
        val uuid = spec.command[sidIdx + 1]
        assertTrue(uuidRe.matches(uuid), "the --session-id value is a valid UUID: $uuid")

        val setIdx = spec.command.indexOf("--settings")
        assertTrue(setIdx >= 0, "New spec installs the hook settings: ${spec.command}")
        assertEquals("/tmp/kt-hooks.json", spec.command[setIdx + 1])

        assertNotNull(spec.preallocatedSessionId, "New surfaces the preallocated id")
        assertEquals(uuid, spec.preallocatedSessionId!!.value, "argv --session-id matches preallocatedSessionId")
    }

    @Test
    fun buildLaunchSpecNewFullArgvIsClaudeSessionIdSettings() {
        val fixedId = ProviderSessionId("12345678-1234-4234-8234-1234567890ab")
        val adapter = ClaudeAdapter(
            cwd = "/w",
            settingsPath = "/s.json",
            events = emptyFlow(),
            generateSessionId = { fixedId },
        )
        val spec = adapter.buildLaunchSpec(LaunchMode.New)
        assertEquals(
            listOf("claude", "--session-id", fixedId.value, "--settings", "/s.json"),
            spec.command,
        )
        assertEquals(fixedId, spec.preallocatedSessionId)
    }

    // ---- buildLaunchSpec: Resume ----

    @Test
    fun buildLaunchSpecResumeAddressesExistingConversationWithNoPrealloc() {
        val id = ProviderSessionId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val adapter = ClaudeAdapter(cwd = "/work", settingsPath = "/s.json", events = emptyFlow())
        val spec = adapter.buildLaunchSpec(LaunchMode.Resume(id))

        assertEquals(listOf("claude", "--resume", id.value, "--settings", "/s.json"), spec.command)
        assertNull(spec.preallocatedSessionId, "Resume carries the id in argv, preallocates nothing")
        assertEquals("/work", spec.cwd)
    }

    // ---- version gate: fallback path ----

    @Test
    fun buildLaunchSpecNewFallsBackWhenSessionIdUnsupported() {
        val adapter = ClaudeAdapter(
            cwd = "/w",
            settingsPath = "/s.json",
            events = emptyFlow(),
            sessionIdSupported = false,
        )
        val spec = adapter.buildLaunchSpec(LaunchMode.New)

        assertFalse(spec.command.contains("--session-id"), "fallback omits --session-id: ${spec.command}")
        assertNull(spec.preallocatedSessionId, "fallback preallocates nothing; id comes from SessionStart hook")
        assertTrue(spec.command.contains("--settings"), "fallback still installs the hook settings")
        assertEquals(listOf("claude", "--settings", "/s.json"), spec.command)
    }

    // ---- events seam is wired straight through ----

    @Test
    fun theInjectedEventStreamIsExposedUnchanged() = runBlocking {
        withTimeout(5_000) {
            val injected = listOf<AgentEvent>(AgentEvent.TurnStarted, AgentEvent.ToolCall("Bash"), AgentEvent.Exited(0))
            val adapter = ClaudeAdapter(
                cwd = "/w",
                settingsPath = "/s.json",
                events = flowOf(*injected.toTypedArray()),
            )
            assertEquals(injected, adapter.events.toList(), "events is the injected seam, wired through unchanged")
        }
    }

    // ---- generated UUIDs ----

    @Test
    fun generatedSessionIdsAreValidV4Uuids() {
        val ids = (1..64).map { newUuidV4() }
        ids.forEach { assertTrue(uuidRe.matches(it), "not a uuid: $it") }
        ids.forEach {
            assertEquals('4', it[14], "version nibble must be 4: $it")
            assertTrue(it[19] in "89ab", "variant nibble must be one of 8..b: $it")
        }
        assertEquals(ids.size, ids.toSet().size, "generated ids must be unique")
        // Every generated id is accepted by ProviderSessionId's own UUID validation.
        ids.forEach { ProviderSessionId(it) }
    }

    @Test
    fun newUuidV4IsDeterministicUnderASeededRandom() {
        assertEquals(newUuidV4(Random(42)), newUuidV4(Random(42)), "same seed → same uuid")
    }

    // ---- hook config: well-formed, and every hook wires token + $TMUX_PANE + ingress ----

    @Test
    fun hookConfigIsWellFormedAndEveryHookWiresHeaderFilePaneAndIngress() {
        val port = 8765
        val headerFile = "/home/u/.kotgent/claude-hook-header"
        val token = "tok-abc123-XYZ"
        val settings = ClaudeHookConfig.generate(port, headerFile)

        // Parsing succeeds → the generated settings are well-formed JSON.
        val hooks = Json.parseToJsonElement(settings).jsonObject["hooks"]!!.jsonObject

        // Exactly the five v1 hook events are wired.
        assertEquals(ClaudeHookConfig.HOOK_EVENTS.toSet(), hooks.keys)

        // The secret token is NEVER embedded in the settings (it lives only in the 0600 header file), so it
        // cannot leak via `ps`/proc inspection of a hook's `curl`.
        assertFalse(token in settings, "the token must not appear in the generated hook settings")

        val ingress = ClaudeHookConfig.ingressUrl(port)
        for (event in ClaudeHookConfig.HOOK_EVENTS) {
            val blocks = hooks[event]!!.jsonArray
            assertTrue(blocks.isNotEmpty(), "$event has at least one hook block")
            val commands = blocks.flatMap { it.jsonObject["hooks"]!!.jsonArray }
            assertTrue(commands.isNotEmpty(), "$event has at least one command hook")
            for (hook in commands) {
                val obj = hook.jsonObject
                assertEquals("command", obj["type"]!!.jsonPrimitive.content, "$event hook is a command hook")
                val cmd = obj["command"]!!.jsonPrimitive.content
                assertTrue(cmd.contains("@$headerFile"), "$event command reads the token header from the file: $cmd")
                assertFalse(cmd.contains(token), "$event command must not embed the token in its argv: $cmd")
                assertTrue(cmd.contains("\$TMUX_PANE"), "$event command carries \$TMUX_PANE: $cmd")
                assertTrue(cmd.contains(ingress), "$event command posts to the ingress URL: $cmd")
                assertTrue(cmd.contains(event), "$event command tags the hook event name: $cmd")
            }
        }
    }

    @Test
    fun headerFileContentCarriesTheTokenHeaderLine() {
        assertEquals(
            "${ClaudeHookConfig.HOOK_TOKEN_HEADER}: sekret-123\n",
            ClaudeHookConfig.headerFileContent("sekret-123"),
            "the header file is a single curl-compatible header line carrying the token",
        )
    }

    @Test
    fun postToolUseHookCarriesAToolMatcher() {
        val settings = ClaudeHookConfig.generate(port = 9000, headerFilePath = "/h")
        val hooks = Json.parseToJsonElement(settings).jsonObject["hooks"]!!.jsonObject
        val block = hooks[ClaudeHookConfig.POST_TOOL_USE]!!.jsonArray.first().jsonObject
        assertTrue(block.containsKey("matcher"), "PostToolUse block carries a tool matcher")
        assertEquals("*", block["matcher"]!!.jsonPrimitive.content, "matcher '*' matches every tool")
    }

    @Test
    fun ingressUrlIsLocalOnlyLoopback() {
        assertEquals("http://127.0.0.1:41337/hooks/claude", ClaudeHookConfig.ingressUrl(41337))
    }

    @Test
    fun theClaudeHookEventNamesArePinnedToTheirWireLiterals() {
        // These are the exact Claude Code settings hook-event keys; the outgoing config writes them and
        // the incoming normalizer keys on the same strings. A silent rename would break the whole hook
        // wiring, so pin each to its literal (and the ordered HOOK_EVENTS list the settings iterate).
        assertEquals("UserPromptSubmit", ClaudeHookConfig.USER_PROMPT_SUBMIT)
        assertEquals("PostToolUse", ClaudeHookConfig.POST_TOOL_USE)
        assertEquals("Stop", ClaudeHookConfig.STOP)
        assertEquals("Notification", ClaudeHookConfig.NOTIFICATION)
        assertEquals("SessionStart", ClaudeHookConfig.SESSION_START)
        assertEquals(
            listOf("UserPromptSubmit", "PostToolUse", "Stop", "Notification", "SessionStart"),
            ClaudeHookConfig.HOOK_EVENTS,
        )
    }

    // ---- version gate: pure decision logic (no live binary) ----

    @Test
    fun versionParsingExtractsTheSemverTriple() {
        assertEquals(ClaudeVersion(2, 1, 218), ClaudeCli.parseVersion("2.1.218 (Claude Code)"))
        assertEquals(ClaudeVersion(2, 1, 217), ClaudeCli.parseVersion("2.1.217"))
        assertEquals(ClaudeVersion(0, 2, 9), ClaudeCli.parseVersion("claude/0.2.9"))
        assertNull(ClaudeCli.parseVersion("no version here"))
        assertNull(ClaudeCli.parseVersion(""))
    }

    @Test
    fun sessionIdVersionGateDecidesSupportVsFallback() {
        // Modern claude → supported.
        assertTrue(ClaudeCli.supportsSessionId(ClaudeVersion(2, 1, 218)))
        assertTrue(ClaudeCli.supportsSessionId(ClaudeVersion(2, 1, 217)))
        assertTrue(ClaudeCli.supportsSessionId(ClaudeCli.MIN_SESSION_ID_VERSION), "the floor itself is supported")
        // Old CLI → fallback (SessionStart-hook capture).
        assertFalse(ClaudeCli.supportsSessionId(ClaudeVersion(0, 2, 9)))
        assertFalse(ClaudeCli.supportsSessionId(ClaudeVersion(0, 9, 99)))
        // Unknown/unparseable → conservative false → fallback.
        assertFalse(ClaudeCli.supportsSessionId(null))
        assertFalse(ClaudeCli.supportsSessionId(ClaudeCli.parseVersion("garbage")))
    }

    @Test
    fun claudeVersionOrdersByMajorThenMinorThenPatch() {
        assertTrue(ClaudeVersion(2, 0, 0) > ClaudeVersion(1, 99, 99))
        assertTrue(ClaudeVersion(2, 1, 218) > ClaudeVersion(2, 1, 217))
        assertTrue(ClaudeVersion(2, 2, 0) > ClaudeVersion(2, 1, 999))
        assertEquals(ClaudeVersion(2, 1, 218), ClaudeVersion(2, 1, 218))
    }

    // ---- ClaudeCli against an injected (fake) runner — no real binary ----

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun detectVersionRunsTheBinaryAndParsesItsOutput() {
        val ok = ClaudeCli(runner = { ProcessResult(0, "2.1.218 (Claude Code)\n".encodeToByteArray(), ByteArray(0)) })
        assertEquals(ClaudeVersion(2, 1, 218), ok.detectVersion())
        assertTrue(ok.isInstalled())
        assertTrue(ok.supportsSessionId(), "a 2.1.x fake CLI supports --session-id")

        val absent = ClaudeCli(runner = { ProcessResult(127, ByteArray(0), "command not found".encodeToByteArray()) })
        assertNull(absent.detectVersion(), "a failing --version yields no version")
        assertFalse(absent.isInstalled())
        assertFalse(absent.supportsSessionId(), "an unusable CLI takes the fallback path")
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun locateReturnsThePathOnSuccessAndNullOnFailure() {
        val found = ClaudeCli(runner = { ProcessResult(0, "/usr/local/bin/claude\n".encodeToByteArray(), ByteArray(0)) })
        assertEquals("/usr/local/bin/claude", found.locate())

        val missing = ClaudeCli(runner = { ProcessResult(1, ByteArray(0), ByteArray(0)) })
        assertNull(missing.locate())
    }

    // ---- guarded probe of the REAL installed claude (soft-skips if absent) ----

    @Test
    fun realInstalledClaudeIsPresentAndSupportsSessionId() {
        val cli = ClaudeCli()
        val version = cli.detectVersion() ?: return // soft-skip: claude not installed / not runnable
        assertTrue(version.major >= 2, "installed claude should be 2.x+, was $version")
        assertTrue(cli.supportsSessionId(), "installed claude ($version) should support --session-id")
        assertNotNull(cli.locate(), "installed claude should resolve on PATH")
    }
}
