package io.kotgent.tmux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmuxOptionsTest {

    @Test
    fun isolationSuppressesTheUserConfigEntirely() {
        assertEquals(
            listOf("-f", "/dev/null"),
            TMUX_CONFIG_ISOLATION,
            "isolation is `-f /dev/null` — no generated file on disk to go stale",
        )
    }

    @Test
    fun serverOptionsHoldExactlyTheDocumentedTable() {
        assertEquals(
            listOf(
                TmuxOption("-g", "destroy-unattached", "off"),
                TmuxOption("-g", "default-terminal", "tmux-256color"),
                TmuxOption("-g", "mouse", "on"),
                TmuxOption("-g", "status", "off"),
                TmuxOption("-g", "history-limit", "10000"),
                TmuxOption("-s", "escape-time", "10"),
            ),
            TMUX_SERVER_OPTIONS,
            "the forced option set is exactly the six rows of the option table",
        )
    }

    @Test
    fun serverOptionsNeverForceFocusEvents() {
        assertFalse(
            TMUX_SERVER_OPTIONS.any { it.name == "focus-events" },
            "focus tracking is meaningless under the fan-out (one upstream, N subscribers) AND it is " +
                "the isolation test's decoy — forcing it here would silently make that test unfalsifiable",
        )
    }

    @Test
    fun serverOptionsForceMouseMode() {
        assertEquals(
            TmuxOption("-g", "mouse", "on"),
            TMUX_SERVER_OPTIONS.single { it.name == "mouse" },
            "the wheel must scroll the pane's tmux history in both viewers — safe because " +
                "Tmux.sendKeys cancels copy-mode and then proves the send landed",
        )
    }

    @Test
    fun forcesMouseOnReadsTheOptionTableRatherThanRestatingIt() {
        assertTrue(forcesMouseOn(TMUX_SERVER_OPTIONS), "the production set forces mouse mode")
        assertFalse(
            forcesMouseOn(TMUX_SERVER_OPTIONS.filterNot { it.name == "mouse" }),
            "drop the row and the predicate must say so — this is what the seed's mouse-enable is gated on",
        )
        assertFalse(
            forcesMouseOn(TMUX_SERVER_OPTIONS.map { if (it.name == "mouse") it.copy(value = "off") else it }),
            "`mouse off` is not `mouse on` — the value is read, not just the name",
        )
    }

    @Test
    fun optionCommandsAreChainableSetOptionCalls() {
        assertEquals(
            listOf(
                "set-option", "-g", "destroy-unattached", "off", ";",
                "set-option", "-g", "default-terminal", "tmux-256color", ";",
                "set-option", "-g", "mouse", "on", ";",
                "set-option", "-g", "status", "off", ";",
                "set-option", "-g", "history-limit", "10000", ";",
                "set-option", "-s", "escape-time", "10", ";",
            ),
            tmuxOptionCommands(TMUX_SERVER_OPTIONS),
            "each option is one `set-option <scope> <name> <value>` terminated by a `;` separator, so " +
                "`new-session` can follow in the SAME invocation (a standalone set-option starts no server)",
        )
    }

    @Test
    fun optionCommandsFollowTheListTheyAreGiven() {
        val custom = listOf(TmuxOption("-g", "default-terminal", "screen-256color"))
        assertEquals(
            listOf("set-option", "-g", "default-terminal", "screen-256color", ";"),
            tmuxOptionCommands(custom),
        )
        assertEquals(emptyList(), tmuxOptionCommands(emptyList()), "an empty set expands to no commands at all")
    }

    @Test
    fun newSessionArgvWithoutAHookIsByteIdenticalToTheOriginalOptionAndLaunchChain() {
        val options = listOf(TmuxOption("-g", "status", "off"))

        assertEquals(
            listOf(
                "set-option", "-g", "status", "off", ";",
                "new-session", "-d",
                "-s", "kt-abc123",
                "-c", "/work/a b",
                "-x", "132",
                "-y", "47",
                "-e", "KOTGENT_SESSION_ID=abc123",
                "-P", "-F", "#{pane_id}",
                "exec agent --flag",
            ),
            newSessionArgv(
                serverOptions = options,
                hookScriptPath = null,
                id = "abc123",
                cwd = "/work/a b",
                cmd = "exec agent --flag",
                cols = 132,
                rows = 47,
            ),
            "omitting the hook preserves every argument and its original order",
        )
    }

    @Test
    fun newSessionArgvInstallsTheCloseHookBeforeOptionsAndPaneCreation() {
        val options = listOf(TmuxOption("-s", "escape-time", "10"))
        val scriptPath = "/private/tmp/kotgent/tmux-hook.sh"
        val argv = newSessionArgv(
            serverOptions = options,
            hookScriptPath = scriptPath,
            id = "def456",
            cwd = "/work/repo",
            cmd = "cat",
            cols = 80,
            rows = 24,
        )

        assertEquals(
            listOf(
                "set-hook", "-g", "session-closed", TmuxHookConfig.hookCommand(scriptPath), ";",
            ),
            argv.take(5),
            "the hook is the first command in the chain that can start a server",
        )
        assertEquals(
            listOf("set-option", "-s", "escape-time", "10", ";"),
            argv.drop(5).take(5),
            "the forced option chain follows the hook",
        )
        assertTrue(argv.indexOf("set-hook") < argv.indexOf("set-option"))
        assertTrue(argv.indexOf("set-option") < argv.indexOf("new-session"))
    }

    @Test
    fun tmuxCommandCarriesTheIsolationBeforeTheSocketAndTheSubcommand() {
        val argv = tmuxCommand("/opt/homebrew/bin/tmux", "kotgent", listOf("kill-session", "-t", "kt-abc123"))
        assertEquals(
            listOf("/opt/homebrew/bin/tmux", "-f", "/dev/null", "-L", "kotgent", "kill-session", "-t", "kt-abc123"),
            argv,
            "every kotgent tmux call is `tmux -f /dev/null -L <socket> <sub …>`",
        )
        assertTrue(argv.indexOf("-f") < argv.indexOf("-L"), "-f precedes -L")
        assertTrue(argv.indexOf("/dev/null") < argv.indexOf("kill-session"), "the flags precede the subcommand")
    }
}
