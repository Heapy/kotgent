package io.kotgent.cli

import io.kotgent.core.PaneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmuxSelfTest {

    private val uid = 501

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { name -> map[name] }
    }


    @Test
    fun theSocketPathDefaultsToTmpWhenTmuxTmpdirIsUnset() {
        assertEquals("/tmp/tmux-501/kotgent", TmuxSelf.kotgentSocketPath(env(), uid))
    }

    @Test
    fun theSocketPathCarriesTheLabelNotSomeHardCodedName() {
        assertEquals("/tmp/tmux-501/$TMUX_SOCKET", TmuxSelf.kotgentSocketPath(env(), uid))
    }

    @Test
    fun theSocketPathHonoursTmuxTmpdir() {
        assertEquals(
            "/var/folders/ab/T/tmux-501/kotgent",
            TmuxSelf.kotgentSocketPath(env("TMUX_TMPDIR" to "/var/folders/ab/T"), uid),
        )
    }

    @Test
    fun theSocketPathCollapsesATrailingSlashOnTmuxTmpdir() {
        assertEquals(
            "/var/folders/ab/T/tmux-501/kotgent",
            TmuxSelf.kotgentSocketPath(env("TMUX_TMPDIR" to "/var/folders/ab/T///"), uid),
        )
    }

    @Test
    fun anEmptyTmuxTmpdirReadsAsUnset() {
        assertEquals("/tmp/tmux-501/kotgent", TmuxSelf.kotgentSocketPath(env("TMUX_TMPDIR" to ""), uid))
    }

    @Test
    fun theSocketPathCarriesTheUidItWasAsked() {
        assertEquals("/tmp/tmux-0/kotgent", TmuxSelf.kotgentSocketPath(env(), 0))
        assertEquals("/tmp/tmux-1234/kotgent", TmuxSelf.kotgentSocketPath(env(), 1234))
    }


    @Test
    fun aPaneOnKotgentsSocketIsReported() {
        val pane = TmuxSelf.currentPane(
            env("TMUX" to "/tmp/tmux-501/kotgent,4242,0", "TMUX_PANE" to "%7"),
            uid,
        )
        assertEquals(PaneId("%7"), pane)
    }

    @Test
    fun theRealpathedPrivateSpellingOfTheSameSocketIsReported() {
        val pane = TmuxSelf.currentPane(
            env("TMUX" to "/private/tmp/tmux-501/kotgent,4242,0", "TMUX_PANE" to "%2"),
            uid,
        )
        assertEquals(PaneId("%2"), pane)
    }

    @Test
    fun theRealpathedPrivateSpellingOfATmuxTmpdirUnderVarIsReported() {
        val pane = TmuxSelf.currentPane(
            env(
                "TMUX_TMPDIR" to "/var/folders/ab/T",
                "TMUX" to "/private/var/folders/ab/T/tmux-501/kotgent,4242,0",
                "TMUX_PANE" to "%11",
            ),
            uid,
        )
        assertEquals(PaneId("%11"), pane)
    }

    @Test
    fun aPaneUnderAHonouredTmuxTmpdirIsReported() {
        val pane = TmuxSelf.currentPane(
            env(
                "TMUX_TMPDIR" to "/var/folders/ab/T/",
                "TMUX" to "/var/folders/ab/T/tmux-501/kotgent,4242,3",
                "TMUX_PANE" to "%0",
            ),
            uid,
        )
        assertEquals(PaneId("%0"), pane)
    }


    @Test
    fun aPaneOnTheOperatorsOwnTmuxIsNotReported() {
        assertNull(
            TmuxSelf.currentPane(
                env("TMUX" to "/private/tmp/tmux-501/default,999,0", "TMUX_PANE" to "%2"),
                uid,
            ),
        )
    }

    @Test
    fun aKotgentLabelledSocketBelongingToAnotherUserIsNotReported() {
        assertNull(
            TmuxSelf.currentPane(
                env("TMUX" to "/tmp/tmux-502/kotgent,4242,0", "TMUX_PANE" to "%7"),
                uid,
            ),
        )
    }

    @Test
    fun theDefaultTmpdirIsNotAcceptedWhenTmuxTmpdirMovedTheSocket() {
        assertNull(
            TmuxSelf.currentPane(
                env(
                    "TMUX_TMPDIR" to "/var/folders/ab/T",
                    "TMUX" to "/tmp/tmux-501/kotgent,4242,0",
                    "TMUX_PANE" to "%1",
                ),
                uid,
            ),
        )
    }

    @Test
    fun anOrdinaryPrivateDirectoryIsNotFoldedIntoARootPath() {
        assertNull(
            TmuxSelf.currentPane(
                env(
                    "TMUX_TMPDIR" to "/opt",
                    "TMUX" to "/private/opt/tmux-501/kotgent,4242,0",
                    "TMUX_PANE" to "%1",
                ),
                uid,
            ),
        )
    }

    @Test
    fun aSocketWhoseNameOnlyStartsLikeKotgentsIsNotReported() {
        assertNull(
            TmuxSelf.currentPane(
                env("TMUX" to "/tmp/tmux-501/kotgent-test,4242,0", "TMUX_PANE" to "%3"),
                uid,
            ),
        )
    }

    @Test
    fun anAbsentTmuxIsNotReported() {
        assertNull(TmuxSelf.currentPane(env("TMUX_PANE" to "%4"), uid))
    }

    @Test
    fun anEmptyTmuxIsNotReported() {
        assertNull(TmuxSelf.currentPane(env("TMUX" to "", "TMUX_PANE" to "%4"), uid))
    }

    @Test
    fun anAbsentPaneOnKotgentsSocketIsNotReported() {
        assertNull(TmuxSelf.currentPane(env("TMUX" to "/tmp/tmux-501/kotgent,4242,0"), uid))
    }

    @Test
    fun anEmptyPaneOnKotgentsSocketIsNotReported() {
        assertNull(
            TmuxSelf.currentPane(
                env("TMUX" to "/tmp/tmux-501/kotgent,4242,0", "TMUX_PANE" to ""),
                uid,
            ),
        )
    }

    @Test
    fun aMalformedPaneIsNotReported() {
        for (raw in listOf("2", "%", "%%2", "pane-2", "%2a", " %2", "%2 ", "%-1")) {
            assertNull(
                TmuxSelf.currentPane(
                    env("TMUX" to "/tmp/tmux-501/kotgent,4242,0", "TMUX_PANE" to raw),
                    uid,
                ),
                "expected '$raw' to be refused as a pane id",
            )
        }
    }

    @Test
    fun bothAbsentIsNotReported() {
        assertNull(TmuxSelf.currentPane(env(), uid))
    }

    @Test
    fun aTmuxWithoutTheServerSuffixStillGatesOnTheSocketPath() {
        assertNull(TmuxSelf.currentPane(env("TMUX" to "/tmp/tmux-501/default", "TMUX_PANE" to "%1"), uid))
        assertEquals(
            PaneId("%1"),
            TmuxSelf.currentPane(env("TMUX" to "/tmp/tmux-501/kotgent", "TMUX_PANE" to "%1"), uid),
        )
    }
}
