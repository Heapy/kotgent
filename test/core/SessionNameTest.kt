package io.kotgent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionNameTest {

    @Test
    fun anOrdinaryNameIsAccepted() {
        assertNull(sessionNameProblem("refactor the reducer"))
    }

    @Test
    fun theEmptyNameIsAcceptedAndMeansTheAutomaticLabel() {
        assertNull(sessionNameProblem(""), "clearing a name resets it, it is not a validation failure")
    }

    @Test
    fun theBoundItselfIsAcceptedAndOneUnitPastItIsNot() {
        assertNull(sessionNameProblem("a".repeat(MAX_SESSION_NAME_LENGTH)), "the bound is inclusive")
        val problem = sessionNameProblem("a".repeat(MAX_SESSION_NAME_LENGTH + 1))
        assertNotNull(problem)
        assertTrue(
            problem.contains(MAX_SESSION_NAME_LENGTH.toString()),
            "the refusal names the bound so a caller can trim: $problem",
        )
    }

    @Test
    fun theBoundCountsUtf16UnitsSoASurrogatePairCostsTwo() {
        val half = MAX_SESSION_NAME_LENGTH / 2
        assertNull(
            sessionNameProblem("😀".repeat(half)),
            "$half astral code points are exactly $MAX_SESSION_NAME_LENGTH UTF-16 units",
        )
        assertNotNull(
            sessionNameProblem("😀".repeat(half + 1)),
            "one more emoji is two more units, not one, matching the browser's maxlength",
        )
    }

    @Test
    fun controlCharactersAreRefused() {
        val bad = listOf("two\nlines", "carriage\rreturn", "tab\tstop", "delete\u007F", "bell\u0007")
        for (name in bad) {
            val problem = sessionNameProblem(name)
            assertNotNull(problem, "a control character misrenders in a sidebar row and a CLI table")
            assertTrue(problem.contains("control"), "the refusal says why: $problem")
        }
    }

    @Test
    fun aWhitespaceOnlyNameNormalizesToTheEmptyOneRatherThanToABlankLabel() {
        assertEquals("", normalizeSessionName("   "), "a blank name is the automatic label, not a blank row")
        assertNull(sessionNameProblem(normalizeSessionName("   ")))
    }

    @Test
    fun surroundingWhitespaceIsTrimmedSoEveryClientStoresTheSameName() {
        assertEquals("x", normalizeSessionName("  x  "))
        assertEquals("two words", normalizeSessionName("\ttwo words\n"), "the CLI and the browser agree")
    }

    @Test
    fun ordinaryNonAsciiTextIsNotMistakenForAControlCharacter() {
        assertNull(sessionNameProblem("ремонт café 日本語"))
    }
}
