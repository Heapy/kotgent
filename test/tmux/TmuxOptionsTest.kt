package io.kotgent.tmux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the PURE tmux configuration surfaces — the isolation flags, the forced option set,
 * and the argv builder every kotgent tmux invocation goes through.
 *
 * These are the record of two decisions:
 *  - **isolation, not inheritance**: `-L` isolates the SOCKET, not the CONFIG — a server on
 *    `-L kotgent` still parses `~/.tmux.conf`, so an operator's `destroy-unattached on` would kill
 *    the agent the moment kotgent's single upstream attach detaches. `-f /dev/null` is what closes
 *    that, and it must ride on every argv kotgent builds.
 *  - **`focus-events` is deliberately absent** (see [TMUX_SERVER_OPTIONS]'s KDoc): focus has no
 *    single answer under a one-upstream/N-subscriber fan-out. It doubles as the decoy of the
 *    integration isolation test, which only stays falsifiable while kotgent never forces it.
 */
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
                TmuxOption("-g", "status", "off"),
                TmuxOption("-g", "history-limit", "10000"),
                TmuxOption("-s", "escape-time", "10"),
            ),
            TMUX_SERVER_OPTIONS,
            "the forced option set is exactly the five rows of the option table",
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

    /**
     * `mouse on` was measured to break [io.kotgent.tmux.Tmux.sendKeys]: a wheel scroll from ANY
     * subscriber puts the shared pane into copy-mode, and every later `send-keys` — including the
     * `0x03` that `SessionManager.interrupt` sends — is then routed to the copy-mode key table and
     * silently dropped while tmux still exits 0. Wheel-scrollback is left to the clients' own
     * scrollback instead.
     */
    @Test
    fun serverOptionsNeverForceMouseMode() {
        assertFalse(
            TMUX_SERVER_OPTIONS.any { it.name == "mouse" },
            "`mouse on` makes one subscriber's wheel put the shared pane into copy-mode, where " +
                "send-keys (Interrupt's Ctrl-C included) is swallowed — see TMUX_SERVER_OPTIONS's KDoc",
        )
    }

    @Test
    fun optionCommandsAreChainableSetOptionCalls() {
        assertEquals(
            listOf(
                "set-option", "-g", "destroy-unattached", "off", ";",
                "set-option", "-g", "default-terminal", "tmux-256color", ";",
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
        // The integration test drives a non-default option set through Tmux(serverOptions = …).
        val custom = listOf(TmuxOption("-g", "default-terminal", "screen-256color"))
        assertEquals(
            listOf("set-option", "-g", "default-terminal", "screen-256color", ";"),
            tmuxOptionCommands(custom),
        )
        assertEquals(emptyList(), tmuxOptionCommands(emptyList()), "an empty set expands to no commands at all")
    }

    @Test
    fun tmuxCommandCarriesTheIsolationBeforeTheSocketAndTheSubcommand() {
        val argv = tmuxCommand("/opt/homebrew/bin/tmux", "kotgent", listOf("kill-session", "-t", "kt-abc123"))
        assertEquals(
            listOf("/opt/homebrew/bin/tmux", "-f", "/dev/null", "-L", "kotgent", "kill-session", "-t", "kt-abc123"),
            argv,
            "every kotgent tmux call is `tmux -f /dev/null -L <socket> <sub …>`",
        )
        // tmux global flags must precede the subcommand, so `-f` cannot be appended anywhere later.
        assertTrue(argv.indexOf("-f") < argv.indexOf("-L"), "-f precedes -L")
        assertTrue(argv.indexOf("/dev/null") < argv.indexOf("kill-session"), "the flags precede the subcommand")
    }
}
