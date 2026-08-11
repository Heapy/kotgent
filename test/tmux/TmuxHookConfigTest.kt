package io.kotgent.tmux

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmuxHookConfigTest {

    @Test
    fun headerAndIngressUseThePinnedWireNamesAndRequestedPort() {
        assertEquals("/api/v1/hooks/tmux", TmuxHookConfig.INGRESS_PATH)
        assertEquals("/hooks/tmux", TmuxHookConfig.LEGACY_INGRESS_PATH)
        assertEquals("X-Kotgent-Hook-Token", TmuxHookConfig.HOOK_TOKEN_HEADER)
        assertEquals("X-Kotgent-Tmux-Session", TmuxHookConfig.SESSION_HEADER)
        assertEquals("http://127.0.0.1:7419/api/v1/hooks/tmux", TmuxHookConfig.ingressUrl(7419))
        assertEquals(
            "X-Kotgent-Hook-Token: s3cr3t\n",
            TmuxHookConfig.headerFileContent("s3cr3t"),
            "the secret occupies one curl-compatible, newline-terminated header line",
        )
    }

    @Test
    fun hookScriptUsesOnlyTheHeaderPathAndPostsAnEmptyBoundedRequest() {
        val headerPath = "/private/tmp/kotgent/tmux-hook-header"
        val script = TmuxHookConfig.hookScript(port = 7419, headerFilePath = headerPath)

        assertTrue(script.startsWith("#!/bin/sh"))
        assertContains(script, "exec /usr/bin/curl -sS -o /dev/null -X POST")
        assertContains(script, "http://127.0.0.1:7419/api/v1/hooks/tmux")
        assertContains(script, "--connect-timeout 2 --max-time 5")
        assertContains(script, "-H '@$headerPath'", message = "curl reads the private header file")
        assertContains(script, "-H \"X-Kotgent-Tmux-Session: \$1\"")
        assertContains(script, "--data ''", message = "the trigger carries no event payload")
        assertFalse(script.contains("s3cr3t"), "the token itself never enters the generated script")
        assertEquals(1, Regex(Regex.escape(headerPath)).findAll(script).count(), "only the header path is embedded")
    }

    @Test
    fun hookCommandQuotesTheScriptPathAndTheTmuxSuppliedSessionName() {
        val command = TmuxHookConfig.hookCommand("/private/tmp/kot gent's/tmux-hook.sh")

        assertEquals(
            "run-shell \"/bin/sh '/private/tmp/kot gent'\\''s/tmux-hook.sh' '#{q:hook_session_name}'\"",
            command,
        )
        assertContains(command, "#{q:hook_session_name}", message = "tmux quotes the substituted session name")
    }

    @Test
    fun generatedScriptParsesUnderTheSystemPosixShell() {
        val script = TmuxHookConfig.hookScript(
            port = 7419,
            headerFilePath = "/private/tmp/a header's file",
        )

        val result = ProcessRunner.run(listOf("/bin/sh", "-n", "-c", script))
        assertEquals(0, result.exitCode, "stderr=${result.stderr}")
        assertEquals("", result.stdout)
    }
}
