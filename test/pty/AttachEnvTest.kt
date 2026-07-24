package io.kotgent.pty

import io.kotgent.sys.DEFAULT_UTF8_LOCALE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the PURE [terminalAttachEnv] overload — the environment shaping for the `tmux attach`
 * upstream. Only the `getenv` reads live outside this (the no-arg overload in `RealPtyHandle.kt`), so
 * every rule that matters is asserted here without touching the daemon's real environment.
 *
 * The rules: `TERM` is pinned (never inherited — a `xterm-ghostty` would need a `TERMINFO` launchd has
 * not got), `PATH` has a floor, and `LANG` is ALWAYS a UTF-8 locale — a tmux client that is not UTF-8
 * renders every non-ASCII cell as `_`, and under launchd nothing is inherited to fall back on.
 */
class AttachEnvTest {

    @Test
    fun termIsPinnedAndNeverInherited() {
        val env = terminalAttachEnv(lang = "en_US.UTF-8", home = "/Users/tester", path = "/usr/bin")
        assertEquals(ATTACH_TERM, env["TERM"], "TERM is the portable system entry")
        assertEquals("xterm-256color", ATTACH_TERM, "and that entry is xterm-256color")
    }

    @Test
    fun langIsAlwaysPresentAndUtf8() {
        // The launchd case: the daemon inherits no LANG whatsoever.
        val none = terminalAttachEnv(lang = null, home = "/Users/tester", path = "/usr/bin")
        assertEquals(DEFAULT_UTF8_LOCALE, none["LANG"], "a missing LANG is substituted, not omitted")
        assertTrue("LANG" in none, "LANG is never left out of the attach env")

        // Present but not UTF-8 — same failure mode, same substitution.
        val c = terminalAttachEnv(lang = "C", home = null, path = null)
        assertEquals(DEFAULT_UTF8_LOCALE, c["LANG"], "a non-UTF-8 LANG is replaced")

        // A real UTF-8 locale is respected (the user's own language survives).
        val ru = terminalAttachEnv(lang = "ru_RU.UTF-8", home = null, path = null)
        assertEquals("ru_RU.UTF-8", ru["LANG"], "an inherited UTF-8 locale is passed through")
    }

    @Test
    fun homeIsForwardedWhenSetAndOmittedWhenNot() {
        val set = terminalAttachEnv(lang = null, home = "/Users/tester", path = null)
        assertEquals("/Users/tester", set["HOME"])

        for (blank in listOf(null, "", "   ")) {
            val env = terminalAttachEnv(lang = null, home = blank, path = null)
            assertFalse("HOME" in env, "a blank HOME (<$blank>) is omitted rather than set empty")
        }
    }

    @Test
    fun pathFallsBackToTheFloorWhenBlank() {
        val set = terminalAttachEnv(lang = null, home = null, path = "/Users/tester/.local/bin:/usr/bin")
        assertEquals("/Users/tester/.local/bin:/usr/bin", set["PATH"], "a captured PATH is used verbatim")

        for (blank in listOf(null, "", "   ")) {
            val env = terminalAttachEnv(lang = null, home = null, path = blank)
            assertEquals(ATTACH_FALLBACK_PATH, env["PATH"], "a blank PATH (<$blank>) falls back to the floor")
        }
        // The floor must carry the locations tmux itself lives in.
        assertTrue("/opt/homebrew/bin" in ATTACH_FALLBACK_PATH && "/usr/bin" in ATTACH_FALLBACK_PATH)
    }

    @Test
    fun theEnvCarriesNothingBeyondTheFourShapedKeys() {
        // Identity is never derived from inherited env, so the upstream gets a deliberately tiny set.
        val env = terminalAttachEnv(lang = "en_US.UTF-8", home = "/Users/tester", path = "/usr/bin")
        assertEquals(setOf("TERM", "HOME", "PATH", "LANG"), env.keys.toSet())
    }

    @Test
    fun theAttachCommandForcesUtf8Output() {
        val argv = attachUpstreamCommand("/opt/homebrew/bin/tmux", "kotgent", "kt-abc123")
        assertEquals(
            listOf("/opt/homebrew/bin/tmux", "-u", "-L", "kotgent", "attach", "-t", "kt-abc123"),
            argv,
            "the upstream is `tmux -u -L <socket> attach -t <session>`",
        )
        // -u must precede the subcommand: it is a tmux global flag, not an `attach` flag.
        assertTrue(argv.indexOf("-u") < argv.indexOf("attach"), "-u is a global flag, before the subcommand")
    }
}
