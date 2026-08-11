package io.kotgent.sys

import kotlin.test.Test
import kotlin.test.assertEquals

class LocaleTest {

    @Test
    fun aUtf8LocaleIsPassedThroughVerbatim() {
        assertEquals("en_US.UTF-8", utf8LocaleOrDefault("en_US.UTF-8"), "the common macOS value survives")
        assertEquals("ru_RU.UTF-8", utf8LocaleOrDefault("ru_RU.UTF-8"), "a non-English UTF-8 locale survives")
        assertEquals("C.UTF-8", utf8LocaleOrDefault("C.UTF-8"), "C.UTF-8 is a UTF-8 locale")
    }

    @Test
    fun theCodesetSuffixIsMatchedCaseAndHyphenInsensitively() {
        assertEquals("en_US.utf8", utf8LocaleOrDefault("en_US.utf8"))
        assertEquals("en_US.UTF8", utf8LocaleOrDefault("en_US.UTF8"))
        assertEquals("en_US.Utf-8", utf8LocaleOrDefault("en_US.Utf-8"))
    }

    @Test
    fun missingOrBlankFallsBackToTheDefault() {
        assertEquals(DEFAULT_UTF8_LOCALE, utf8LocaleOrDefault(null), "null → the UTF-8 default")
        assertEquals(DEFAULT_UTF8_LOCALE, utf8LocaleOrDefault(""), "empty → the UTF-8 default")
        assertEquals(DEFAULT_UTF8_LOCALE, utf8LocaleOrDefault("   "), "blank → the UTF-8 default")
    }

    @Test
    fun aPresentButNonUtf8LocaleIsReplaced() {
        assertEquals(DEFAULT_UTF8_LOCALE, utf8LocaleOrDefault("C"), "C is not UTF-8")
        assertEquals(DEFAULT_UTF8_LOCALE, utf8LocaleOrDefault("POSIX"), "POSIX is not UTF-8")
        assertEquals(DEFAULT_UTF8_LOCALE, utf8LocaleOrDefault("en_US"), "no codeset suffix at all")
        assertEquals(DEFAULT_UTF8_LOCALE, utf8LocaleOrDefault("en_US.ISO8859-1"), "a non-UTF-8 codeset")
    }

    @Test
    fun theDefaultIsItselfAUtf8LocaleAndStable() {
        assertEquals("en_US.UTF-8", DEFAULT_UTF8_LOCALE, "always present on macOS, unlike glibc's C.UTF-8")
        assertEquals(DEFAULT_UTF8_LOCALE, utf8LocaleOrDefault(DEFAULT_UTF8_LOCALE), "the default is a fixed point")
    }
}
