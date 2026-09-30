package io.kotgent.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliMutexParseTest {
    private fun parse(vararg args: String): CliCommand = parseArgs(listOf("mutex", *args))

    private fun invalid(vararg args: String): String = assertIs<CliCommand.Invalid>(parse(*args)).message

    @Test
    fun acquireDefaultsToTheServerWaitAndCarriesItsFlags() {
        assertEquals(
            MutexAcquire("kotlin-build", null, 90, json = false, session = null),
            parse("acquire", "kotlin-build"),
        )
        assertEquals(
            MutexAcquire("kotlin-build", "tkt1", 30, json = true, session = "s1"),
            parse("acquire", "kotlin-build", "--ticket", "tkt1", "--wait", "30", "--json", "--session", "s1"),
        )
        assertEquals(0, assertIs<MutexAcquire>(parse("acquire", "k", "--wait", "0")).wait)
        assertEquals(540, assertIs<MutexAcquire>(parse("acquire", "k", "--wait", "540")).wait)
    }

    @Test
    fun acquireRefusesWhatTheDaemonWouldRefuse() {
        assertTrue(invalid("acquire").contains("requires a key"))
        assertTrue(invalid("acquire", "a/b").contains("not a mutex key"))
        assertTrue(invalid("acquire", "k", "--wait", "541").contains("--wait"))
        assertTrue(invalid("acquire", "k", "--wait", "soon").contains("--wait"))
        assertTrue(invalid("acquire", "k", "--ticket", "a b").contains("not a wait ticket"))
        assertTrue(invalid("acquire", "k", "extra").contains("unexpected argument"))
        assertTrue(invalid("acquire", "k", "--force").contains("unknown flag"))
    }

    @Test
    fun runKeepsEverythingAfterTheSeparatorForTheCommand() {
        assertEquals(
            MutexRun("kotlin-build", listOf("./kotlin", "test", "-p", "jvm", "--session", "x", "--"), session = null),
            parse("run", "kotlin-build", "--", "./kotlin", "test", "-p", "jvm", "--session", "x", "--"),
        )
        assertEquals(
            MutexRun("kotlin-build", listOf("make"), session = "s1"),
            parse("run", "--session", "s1", "kotlin-build", "--", "make"),
        )
    }

    @Test
    fun runNeedsAKeyASeparatorAndACommand() {
        assertTrue(invalid("run", "kotlin-build", "make").contains("'--'"))
        assertTrue(invalid("run", "kotlin-build", "--").contains("command after"))
        assertTrue(invalid("run", "--", "make").contains("requires a key"))
        assertTrue(invalid("run", "bad key", "--", "make").contains("not a mutex key"))
    }

    @Test
    fun releaseAndListTakeTheirOwnArguments() {
        assertEquals(MutexRelease("tok1", json = false, session = null), parse("release", "tok1"))
        assertEquals(
            MutexRelease("tok1", json = true, session = "s1"),
            parse("release", "tok1", "--json", "--session", "s1"),
        )
        assertTrue(invalid("release").contains("requires the token"))
        assertTrue(invalid("release", "a:b").contains("not a mutex token"))
        assertEquals(MutexList(json = false), parse("list"))
        assertEquals(MutexList(json = true), parse("list", "--json"))
        assertTrue(invalid("list", "extra").contains("unexpected argument"))
    }

    @Test
    fun anUnknownOrMissingSubcommandNamesTheVerbs() {
        assertTrue(invalid().contains("acquire | release | run | list"))
        assertTrue(invalid("steal").contains("unknown subcommand 'steal'"))
    }
}
