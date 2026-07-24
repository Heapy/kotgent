package io.kotgent.tmux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the PURE tmux configuration surfaces — the isolation flags, the forced option set,
 * and the argv builder every kotgent tmux invocation goes through.
 *
 * These are the record of three decisions:
 *  - **isolation, not inheritance**: `-L` isolates the SOCKET, not the CONFIG — a server on
 *    `-L kotgent` still parses `~/.tmux.conf`, so an operator's `destroy-unattached on` would kill
 *    the agent the moment kotgent's single upstream attach detaches. `-f /dev/null` is what closes
 *    that, and it must ride on every argv kotgent builds.
 *  - **`mouse on` is deliberately present** (see [TMUX_SERVER_OPTIONS]'s KDoc): it is the one forced
 *    row that changes a real tmux default, and it is what makes the wheel reach a pane's history
     *    from the browser and from `kotgent attach`. `Tmux.sendKeys`' atomic cancel→send→verify chain
     *    applies unconditionally; [forcesMouseOn] gates only the mouse part of a joining subscriber's
     *    seed ([io.kotgent.pty.terminalSeed]) because `capture-pane` emits no private-mode sequences.
     *    Bracketed paste is tmux-owned and therefore re-armed unconditionally by that seed.
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

    /**
     * `mouse on` is the only row that flips a real tmux default (built-in `off`), and the only one an
     * operator feels: the wheel scrolls the *pane's* history, which is where an agent transcript
     * actually lives — a subscriber joining an existing bridge is seeded from `capture-pane` and can
     * reach nothing older without it.
     *
     * It is safe only because copy-mode — shared *pane* state, so any subscriber's wheel puts *the*
     * pane into it — cannot silently swallow input: [io.kotgent.tmux.Tmux.sendKeys] chains
     * `copy-mode -q`, the send and a `#{pane_in_mode}` read-back into one invocation and fails loudly
     * if the keys were eaten, which is what keeps `SessionManager.interrupt`'s `0x03` honest.
     * `TmuxTest.sendKeysReachesTheProcessEvenFromCopyMode` and
     * `TmuxTest.sendKeysFailsLoudlyWhenTheCopyModeCancelIsDefeated` are the tests of the two halves;
     * if either is ever deleted, this option has to go with it.
     */
    @Test
    fun serverOptionsForceMouseMode() {
        assertEquals(
            TmuxOption("-g", "mouse", "on"),
            TMUX_SERVER_OPTIONS.single { it.name == "mouse" },
            "the wheel must scroll the pane's tmux history in both viewers — safe because " +
                "Tmux.sendKeys cancels copy-mode and then proves the send landed",
        )
    }

    /**
     * The mouse-enable a joining subscriber's seed carries ([io.kotgent.pty.terminalSeed]) is gated
     * through [forcesMouseOn], so it cannot drift from the option table. `Tmux.sendKeys`' verified
     * copy-mode cancel is intentionally unconditional and does not read this predicate.
     */
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
