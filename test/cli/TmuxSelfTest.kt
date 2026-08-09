package io.kotgent.cli

import io.kotgent.core.PaneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [TmuxSelf] (plan Task 18): the socket-path gate that decides whether this process may
 * report its `$TMUX_PANE` to the daemon at all.
 *
 * Everything runs against an injected environment map and an injected uid — no tmux server, no real
 * `getenv`, no filesystem. The gate itself is a pure string comparison for exactly that reason.
 */
class TmuxSelfTest {

    private val uid = 501

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { name -> map[name] }
    }

    // --- kotgentSocketPath ------------------------------------------------------------------------

    @Test
    fun theSocketPathDefaultsToTmpWhenTmuxTmpdirIsUnset() {
        assertEquals("/tmp/tmux-501/kotgent", TmuxSelf.kotgentSocketPath(env(), uid))
    }

    @Test
    fun theSocketPathCarriesTheLabelNotSomeHardCodedName() {
        // The `-L` label is the one thing the path shares with the rest of the CLI; if TMUX_SOCKET is ever
        // renamed, this path must follow it rather than keeping a stale literal.
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
        // tmux's own guard is `*s != '\0'`, and the shell form `${TMUX_TMPDIR:-/tmp}` agrees.
        assertEquals("/tmp/tmux-501/kotgent", TmuxSelf.kotgentSocketPath(env("TMUX_TMPDIR" to ""), uid))
    }

    @Test
    fun theSocketPathCarriesTheUidItWasAsked() {
        assertEquals("/tmp/tmux-0/kotgent", TmuxSelf.kotgentSocketPath(env(), 0))
        assertEquals("/tmp/tmux-1234/kotgent", TmuxSelf.kotgentSocketPath(env(), 1234))
    }

    // --- currentPane: accepted --------------------------------------------------------------------

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
        // Measured on tmux 3.7b: tmux realpath(3)s its socket DIRECTORY, and macOS's /tmp is a symlink to
        // /private/tmp, so this is the spelling a real kotgent pane actually carries.
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

    // --- currentPane: refused ---------------------------------------------------------------------

    @Test
    fun aPaneOnTheOperatorsOwnTmuxIsNotReported() {
        // The whole point: `%2` here belongs to the default server, and resolving it against kotgent's
        // server would attribute the link to an unrelated session.
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
        // `/private/tmp` is a second spelling of `/tmp`; `/private/opt` is not a second spelling of `/opt`.
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
        // PaneId's own rule: `%` then digits, nothing else. A throw here would take the CLI down.
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
        // No comma at all: the value is compared whole, so a foreign path is still refused...
        assertNull(TmuxSelf.currentPane(env("TMUX" to "/tmp/tmux-501/default", "TMUX_PANE" to "%1"), uid))
        // ...and kotgent's own path is still recognised rather than rejected on a formatting technicality.
        assertEquals(
            PaneId("%1"),
            TmuxSelf.currentPane(env("TMUX" to "/tmp/tmux-501/kotgent", "TMUX_PANE" to "%1"), uid),
        )
    }
}
