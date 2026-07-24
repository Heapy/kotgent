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
 *  - **`focus-events` is deliberately absent** (see [tmuxOptionCommands]'s KDoc): focus has no single
 *    answer under a one-upstream/N-subscriber fan-out. It doubles as the decoy of the integration
 *    isolation test, which only stays falsifiable while kotgent never forces it.
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
                TmuxOption("-g", "mouse", "on"),
                TmuxOption("-g", "status", "off"),
                TmuxOption("-g", "history-limit", "10000"),
                TmuxOption("-s", "escape-time", "0"),
            ),
            TMUX_SERVER_OPTIONS,
            "the forced option set is exactly the six rows of the plan's option table",
        )
    }

    @Test
    fun escapeTimeIsTheOnlyServerScopedOption() {
        val byScope = TMUX_SERVER_OPTIONS.groupBy { it.scope }
        assertEquals(
            setOf("-g", "-s"),
            byScope.keys,
            "only global (-g) and server (-s) scopes — kotgent owns the whole server on this socket",
        )
        assertEquals(
            listOf("escape-time"),
            byScope.getValue("-s").map { it.name },
            "`escape-time` is a SERVER option (`-s`); a `-g escape-time` is rejected by tmux",
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
    fun optionCommandsAreChainableSetOptionCalls() {
        assertEquals(
            listOf(
                "set-option", "-g", "destroy-unattached", "off", ";",
                "set-option", "-g", "default-terminal", "tmux-256color", ";",
                "set-option", "-g", "mouse", "on", ";",
                "set-option", "-g", "status", "off", ";",
                "set-option", "-g", "history-limit", "10000", ";",
                "set-option", "-s", "escape-time", "0", ";",
            ),
            tmuxOptionCommands(),
            "each option is one `set-option <scope> <name> <value>` terminated by a `;` separator",
        )
    }

    @Test
    fun optionCommandsEndWithASeparatorSoTheyCanPrefixAnySubcommand() {
        val chained = tmuxOptionCommands() + listOf("new-session", "-d")
        assertEquals(";", tmuxOptionCommands().last(), "the chain ends with a separator, not an option")
        assertEquals(
            listOf(";", "new-session", "-d"),
            chained.takeLast(3),
            "so `new-session` follows in the SAME invocation — a standalone set-option cannot start a server",
        )
    }

    @Test
    fun optionCommandsFollowTheListTheyAreGiven() {
        // Task 3's degradation test needs a deliberately bogus set; the expansion is list-driven.
        val bogus = listOf(TmuxOption("-g", "kotgent-no-such-option", "on"))
        assertEquals(
            listOf("set-option", "-g", "kotgent-no-such-option", "on", ";"),
            tmuxOptionCommands(bogus),
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

    @Test
    fun tmuxCommandKeepsTheSubcommandArgumentsVerbatim() {
        val args = listOf("send-keys", "-t", "kt-x", "-H", "0a")
        val argv = tmuxCommand("tmux", "kotgent-test", args)
        assertEquals(args, argv.takeLast(args.size), "subcommand argv is passed through unchanged")
        assertEquals(
            listOf("tmux", "-f", "/dev/null", "-L", "kotgent-test"),
            argv.take(5),
            "the prefix is fixed regardless of the subcommand",
        )
        assertEquals(
            listOf("tmux", "-f", "/dev/null", "-L", "kotgent-test"),
            tmuxCommand("tmux", "kotgent-test", emptyList()),
            "with no subcommand the argv is just the isolated prefix",
        )
    }
}
