package io.kotgent.pty

import io.kotgent.sys.DEFAULT_UTF8_LOCALE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachEnvTest {

    @Test
    fun termIsPinnedAndNeverInherited() {
        val env = terminalAttachEnv(lang = "en_US.UTF-8", home = "/Users/tester", path = "/usr/bin")
        assertEquals(ATTACH_TERM, env["TERM"], "TERM is the portable system entry")
        assertEquals("xterm-256color", ATTACH_TERM, "and that entry is xterm-256color")
    }

    @Test
    fun langIsAlwaysPresentAndUtf8() {
        val none = terminalAttachEnv(lang = null, home = "/Users/tester", path = "/usr/bin")
        assertEquals(DEFAULT_UTF8_LOCALE, none["LANG"], "a missing LANG is substituted, not omitted")
        assertTrue("LANG" in none, "LANG is never left out of the attach env")

        val c = terminalAttachEnv(lang = "C", home = null, path = null)
        assertEquals(DEFAULT_UTF8_LOCALE, c["LANG"], "a non-UTF-8 LANG is replaced")

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
        assertTrue("/opt/homebrew/bin" in ATTACH_FALLBACK_PATH && "/usr/bin" in ATTACH_FALLBACK_PATH)
    }

    @Test
    fun theEnvCarriesNothingBeyondTheFourShapedKeys() {
        val env = terminalAttachEnv(lang = "en_US.UTF-8", home = "/Users/tester", path = "/usr/bin")
        assertEquals(setOf("TERM", "HOME", "PATH", "LANG"), env.keys.toSet())
    }

    @Test
    fun theAttachCommandCarriesIsolationAndForcesUtf8Output() {
        val argv = attachUpstreamCommand("/opt/homebrew/bin/tmux", "kotgent", "kt-abc123")
        assertEquals(
            listOf("/opt/homebrew/bin/tmux", "-f", "/dev/null", "-u", "-L", "kotgent", "attach", "-t", "kt-abc123"),
            argv,
            "the upstream is `tmux -f /dev/null -u -L <socket> attach -t <session>`",
        )
        val attach = argv.indexOf("attach")
        val f = argv.indexOf("-f")
        assertTrue(f >= 0, "the attach upstream passes -f")
        assertEquals("/dev/null", argv[f + 1], "-f is immediately followed by its value")
        assertTrue(f + 1 < attach, "-f /dev/null is global, before the subcommand")
        assertTrue(argv.indexOf("-u") < attach, "-u is a global flag, before the subcommand")
    }

    @Test
    fun theSeedRestoresBracketedPasteAndConditionallyArmsMouseReporting() {
        val esc = "\u001b"
        assertEquals(
            "$esc[?2004h",
            TERMINAL_BRACKETED_PASTE_ENABLE,
            "tmux's unconditional bracketed-paste mode is replayed to every joiner",
        )
        assertEquals(
            "$esc[?1006h$esc[?1000h$esc[?1002h",
            TERMINAL_MOUSE_ENABLE,
            "SGR encoding, then normal + button tracking — the order tmux emits for `mouse on`",
        )
        assertEquals(
            (TERMINAL_BRACKETED_PASTE_ENABLE + TERMINAL_MOUSE_ENABLE + "pane text").encodeToByteArray().toList(),
            terminalSeed("pane text", mouseForced = true).toList(),
            "bracketed paste and forced mouse reporting are armed before the repaint",
        )
        assertEquals(
            (TERMINAL_BRACKETED_PASTE_ENABLE + "pane text").encodeToByteArray().toList(),
            terminalSeed("pane text", mouseForced = false).toList(),
            "bracketed paste is tmux-owned and unconditional; mouse remains off without the forced option",
        )
    }

    @Test
    fun anEmptySeedStaysEmptyEvenWithMouseForced() {
        assertTrue(terminalSeed("", mouseForced = true).isEmpty(), "an empty capture yields an empty seed")
        assertTrue(terminalSeed("", mouseForced = false).isEmpty())
    }
}
