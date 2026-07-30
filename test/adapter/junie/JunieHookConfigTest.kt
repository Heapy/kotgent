package io.kotgent.adapter.junie

import io.kotgent.tmux.ProcessRunner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [JunieHookConfig] — the `--config-location` config JSON and the hook script.
 *
 * The generation itself is pure, but the two rules that MUST NOT regress are behavioural, so they are
 * pinned by RUNNING the script through the very command line the config hands Junie, against a port
 * nothing listens on:
 *  - it writes nothing to stdout (Junie parses a hook's stdout as a decision object, and anything that
 *    is not valid JSON becomes `additionalContext` injected into the model's turn);
 *  - its exit status is a fixed function of the event: `1` for `PermissionRequest` (so Junie still shows
 *    its own dialog — `0` would AUTO-APPROVE and `2` would auto-DENY the action) and `0` for everything
 *    else even though curl failed.
 * Neither can be proven by string-matching the script text.
 */
@OptIn(ExperimentalForeignApi::class)
class JunieHookConfigTest {

    private val files = mutableListOf<String>()
    private val dirs = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        for (f in files) unlink(f)
        for (d in dirs.asReversed()) rmdir(d)
    }

    private val scriptPath = "/home/u/.kotgent/junie-hook.sh"
    private val headerPath = "/home/u/.kotgent/junie-hook-header"

    // ---- the config file for `junie --config-location <file>` ----

    @Test
    fun configJsonWiresEveryEventToTheScript() {
        val hooks = Json.parseToJsonElement(JunieHookConfig.configJson(scriptPath)).jsonObject["hooks"]!!.jsonObject

        assertEquals(JunieHookConfig.HOOK_EVENTS.toSet(), hooks.keys, "exactly the wired events, no others")
        assertEquals(7, JunieHookConfig.HOOK_EVENTS.size, "all seven junie hook events")
        for (event in JunieHookConfig.HOOK_EVENTS) {
            val entries = hooks[event]!!.jsonArray
            assertEquals(1, entries.size, "$event carries one matcher entry")
            val entry = entries[0].jsonObject
            assertFalse("matcher" in entry, "$event takes no matcher: absent means 'every value'")
            val commands = entry["hooks"]!!.jsonArray
            assertEquals(1, commands.size, "$event runs one command")
            assertEquals("command", commands[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(
                "/bin/sh '$scriptPath' $event",
                commands[0].jsonObject["command"]!!.jsonPrimitive.content,
                "…the generated script, invoked with its own event name",
            )
        }
    }

    @Test
    fun configJsonQuotesAPathWithASpaceOrAQuote() {
        // A path is attacker-free but not character-free, and Junie runs the command through `sh -c`:
        // a naive concatenation would re-split the path into several words.
        val json = JunieHookConfig.configJson("""/home/u/we ird's/junie-hook.sh""")
        val command = Json.parseToJsonElement(json).jsonObject["hooks"]!!.jsonObject
            .getValue(JunieHookConfig.STOP).jsonArray[0].jsonObject
            .getValue("hooks").jsonArray[0].jsonObject
            .getValue("command").jsonPrimitive.content
        assertEquals("""/bin/sh '/home/u/we ird'\''s/junie-hook.sh' Stop""", command)
    }

    // ---- the hook script ----

    @Test
    fun hookScriptPostsToTheIngressWithTokenPaneAndEvent() {
        val script = JunieHookConfig.hookScript(port = 7777, headerFilePath = headerPath)

        assertTrue(script.startsWith("#!/bin/sh"), "it is a shell script")
        assertTrue(script.contains("http://127.0.0.1:7777/hooks/junie?event="))
        assertTrue(script.contains("\"\$1\""), "the event name comes from the first argument")
        // The token is read from the 0600 header file — never inlined.
        assertTrue(script.contains("-H '@$headerPath'"), "the token is read from the header file: $script")
        assertTrue(script.contains("X-Kotgent-Tmux-Pane: \$TMUX_PANE"))
        assertTrue(script.contains("--data-binary @-"), "the hook payload is forwarded from stdin unchanged")
        assertTrue(script.contains("--max-time"), "an unanswered daemon must not block the action forever")
    }

    @Test
    fun hookScriptNeverContainsTheToken() {
        val content = JunieHookConfig.headerFileContent("s3cr3t")
        assertEquals("X-Kotgent-Hook-Token: s3cr3t\n", content, "one curl-compatible header line")

        val script = JunieHookConfig.hookScript(port = 7777, headerFilePath = headerPath)
        assertFalse(script.contains("s3cr3t"), "the token itself is not in the script")
    }

    @Test
    fun hookScriptHasNoStdoutProducingCommand() {
        val script = JunieHookConfig.hookScript(port = 7777, headerFilePath = headerPath)
        val body = script.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")

        for (chatty in listOf("echo", "printf", "cat ", "tee")) {
            assertFalse(body.contains(chatty), "the script must not write to stdout, found '$chatty': $body")
        }
        assertTrue(body.contains("-o /dev/null"), "the response body is discarded")
        assertTrue(body.contains(">/dev/null"), "…and stdout is redirected on top of that")
    }

    // ---- the behavioural contract: run the real script ----

    @Test
    fun everyEventExitsZeroExceptPermissionRequestEvenWhenTheDaemonIsUnreachable() {
        if (!curlAvailable()) return // soft-skip: the script's one dependency
        val dir = makeTempDir()
        val header = "$dir/junie-hook-header"
        writeFile(header, JunieHookConfig.headerFileContent("s3cr3t"))
        val script = "$dir/junie-hook.sh"
        // Port 1 is privileged and unbound: the connection is refused at once, so curl fails fast — which
        // is exactly the condition the exit contract has to survive. `--max-time` bounds the rest.
        writeFile(script, JunieHookConfig.hookScript(port = 1, headerFilePath = header))

        for (event in JunieHookConfig.HOOK_EVENTS) {
            // Run it EXACTLY as junie would: the command string from the config, through `sh -c`.
            // stdin is /dev/null because `--data-binary @-` reads the payload from it.
            val command = JunieHookConfig.hookCommand(script, event) + " < /dev/null"
            val result = ProcessRunner.run(listOf("/bin/sh", "-c", command))

            val expected = if (event == JunieHookConfig.PERMISSION_REQUEST) 1 else 0
            assertEquals(
                expected,
                result.exitCode,
                "$event must exit $expected regardless of curl's own failure (stderr=${result.stderr.trim()})",
            )
            assertEquals(
                "",
                result.stdout,
                "$event must write NOTHING to stdout — junie would read it as a hook decision",
            )
        }
    }

    // --- harness ------------------------------------------------------------------------------------

    private fun curlAvailable(): Boolean = ProcessRunner.run(listOf("command", "-v", "curl")).isSuccess

    private val mode0700: Int get() = S_IRUSR or S_IWUSR or S_IXUSR

    private fun makeTempDir(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val base = "$tmp/kotgent-junie-hook-test-${getpid()}-${counter++}"
        mkdir(base, mode0700.convert()).also { dirs += base }
        return base
    }

    private fun writeFile(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        val fp = fopen(path, "wb") ?: error("cannot write $path")
        try {
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp) }
        } finally {
            fclose(fp)
        }
        files += path
    }

    private companion object {
        var counter = 0
    }
}
