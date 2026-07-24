package io.kotgent.tmux

import io.kotgent.core.PaneId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * INTEGRATION tests for the [Tmux] wrapper (Task 8), driven against a **throwaway** server
 * `tmux -L kotgent-test` — never the real `-L kotgent`. Each test spawns a real `tmux` from the
 * TEST binary (only possible because [ProcessRunner] is built on stock `platform.posix`
 * `popen`/`pclose`, not our own cinterop — KT-78062 keeps custom cinterop out of test binaries).
 *
 * Isolation & leak-safety: [BeforeTest]/[AfterTest] both `kill-server` the `kotgent-test` socket
 * (idempotent — "no server running" is fine), so every test starts clean and no tmux server
 * leaks after the suite regardless of outcome.
 *
 * If `tmux` is not runnable, every test skip-guards via [tmuxAvailable] and returns (passes
 * trivially) so the suite stays green on a host without tmux. Each body is additionally wrapped
 * in a bounded [withTimeout] tripwire; the real anti-hang guarantee is that tmux control commands
 * terminate in milliseconds and [ProcessRunner]'s single stdout pipe cannot deadlock.
 */
@OptIn(ExperimentalForeignApi::class)
class TmuxTest {

    private val tmux = Tmux(socket = "kotgent-test")

    private fun tmuxAvailable(): Boolean = tmux.isAvailable()

    private fun killServer() {
        // Best-effort teardown; a missing server just returns non-zero, which we ignore.
        ProcessRunner.run(listOf(tmux.tmuxPath, "-L", "kotgent-test", "kill-server"))
    }

    @BeforeTest
    fun setUp() {
        if (tmuxAvailable()) killServer()
    }

    @AfterTest
    fun tearDown() {
        if (tmuxAvailable()) killServer()
    }

    /** Poll capture-pane until [needle] renders (tmux draws asynchronously), bounded. */
    private suspend fun captureUntil(id: String, needle: String): String {
        var last = ""
        repeat(20) {
            last = tmux.capturePane(id)
            if (needle in last) return last
            delay(150)
        }
        return last
    }

    @Test
    fun newSessionReturnsAPaneId() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking
        withTimeout(20_000) {
            tmux.ensureServer()
            val pane = tmux.newSession(id = "new1", cwd = "/tmp", cmd = "cat", cols = 100, rows = 40)
            // new-session -P -F '#{pane_id}' yields a `%<n>` pane id (validated by the PaneId ctor).
            assertTrue(Regex("^%\\d+$").matches(pane.value), "pane id should look like %<n>, was <${pane.value}>")
            assertTrue(tmux.listPanes().any { it.session == "kt-new1" }, "the new session must show up in list-panes")
        }
    }

    @Test
    fun listPanesParse() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking
        withTimeout(20_000) {
            val paneA = tmux.newSession(id = "la", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            val paneB = tmux.newSession(id = "lb", cwd = "/tmp", cmd = "cat", cols = 90, rows = 30)

            val panes = tmux.listPanes().filter { it.session == "kt-la" || it.session == "kt-lb" }
            assertEquals(2, panes.size, "one pane per session parsed from list-panes -a")
            val byPane = panes.associateBy { it.paneId }
            assertTrue(paneA in byPane && paneB in byPane, "returned pane ids appear in list-panes")
            panes.forEach {
                assertTrue(it.pid > 0, "pane_pid is a real pid, was ${it.pid}")
                assertFalse(it.dead, "a fresh `cat` pane is not dead")
                assertTrue(it.width > 0 && it.height > 0, "window dimensions parsed, was ${it.width}x${it.height}")
            }
            assertEquals(80, panes.first { it.session == "kt-la" }.width, "kt-la kept its -x 80 width")
        }
    }

    @Test
    fun capturePaneReturnsRenderedContent() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking
        withTimeout(20_000) {
            tmux.newSession(id = "cap", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            // `cat` echoes stdin: feed a marker as raw bytes via send-keys -H and read it back.
            tmux.sendKeys("cap", "KOTGENT-MARKER\n".encodeToByteArray())
            val out = captureUntil("cap", "KOTGENT-MARKER")
            assertTrue("KOTGENT-MARKER" in out, "capture-pane should return the echoed marker, got:\n<$out>")
        }
    }

    @Test
    fun killSessionRemovesTheSession() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking
        withTimeout(20_000) {
            tmux.newSession(id = "kill1", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            assertTrue(tmux.listPanes().any { it.session == "kt-kill1" }, "session exists before kill")
            assertTrue(tmux.killSession("kill1"), "killSession returns true when it removed a session")
            assertFalse(tmux.listPanes().any { it.session == "kt-kill1" }, "session is gone after kill")
        }
    }

    @Test
    fun killingANonexistentSessionIsGraceful() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking
        withTimeout(20_000) {
            // No server at all (fresh teardown) — killing must not throw, just report "nothing killed".
            assertFalse(tmux.killSession("never-existed"), "killing a nonexistent session returns false")
            // And once a server is up but the target is unknown, still graceful.
            tmux.newSession(id = "other", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            assertFalse(tmux.killSession("still-nope"), "unknown target on a live server returns false")
        }
    }

    @Test
    fun doubleKillIsGraceful() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking
        withTimeout(20_000) {
            tmux.newSession(id = "dbl", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            assertTrue(tmux.killSession("dbl"), "first kill removes the session")
            assertFalse(tmux.killSession("dbl"), "second kill of the same session returns false, not an error")
        }
    }

    @Test
    fun listPanesOnAFreshSocketIsEmptyNotAnError() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking
        withTimeout(20_000) {
            // BeforeTest killed the server; an empty tmux server does not persist, so there is
            // literally "no server running". That must read as an empty list, not an exception.
            assertEquals(emptyList(), tmux.listPanes())
        }
    }

    /**
     * The isolation probe: `-L` isolates the SOCKET, not the CONFIG, and [TMUX_CONFIG_ISOLATION]
     * is what closes that gap. Both halves run, in this order, and **the negative half is the point**
     * — it proves the decoy is genuinely loadable, so a green result from the positive half means
     * `-f /dev/null` actually suppressed something rather than that nothing was ever there.
     *
     * The decoy is `focus-events`, and it must stay an option kotgent NEVER forces (a unit test in
     * `TmuxOptionsTest` pins its absence from [TMUX_SERVER_OPTIONS]). An option from the forced set
     * would be pinned by the `new-session` chain with or without `-f`, and deleting the isolation
     * would leave this test green.
     *
     * This runs raw argv, not [Tmux]: [ProcessRunner] takes no env map and hands the child the test
     * process's own environment, so a planted `~/.tmux.conf` can only be reached by running tmux
     * through `/usr/bin/env HOME=<tmp>`. The link from this measured fact to production code is the
     * unit test on [tmuxCommand] plus [Tmux]'s use of it — not this test.
     */
    @Test
    fun theUserConfigLeaksWithoutIsolationAndIsSuppressedByIt() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking
        withTimeout(30_000) {
            val home = makeFakeHome()
            try {
                assertEquals(
                    "on",
                    focusEventsAfterFirstServerStart(home, isolate = false),
                    "without -f, <tmp>/.tmux.conf leaks into the server — the decoy must be loadable, " +
                        "or the other half of this test proves nothing",
                )
                killServer() // -f only applies to the invocation that STARTS a server
                assertEquals(
                    "off",
                    focusEventsAfterFirstServerStart(home, isolate = true),
                    "with -f /dev/null the same config is suppressed and focus-events falls back to its built-in",
                )
            } finally {
                killServer()
                removeFakeHome(home)
            }
        }
    }

    /**
     * The forced options are in effect on the server the moment the first session exists — because
     * they ride in the SAME invocation as `new-session` (a standalone `set-option` cannot start a
     * server at all: `error connecting to …`, exit 1).
     *
     * Driven off [TMUX_SERVER_OPTIONS] rather than a hardcoded copy, so adding an option to the list
     * extends this assertion for free. Two of the six equal tmux's own built-in default today, so
     * this test is only partly falsifiable by construction — [theForcedOptionsApplyBeforeThePaneExists]
     * and the `mouse`/`status`/`history-limit`/`escape-time` rows carry the real signal.
     */
    @Test
    fun newSessionForcesEveryServerOption() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking
        withTimeout(20_000) {
            tmux.newSession(id = "opt1", cwd = "/tmp", cmd = "cat", cols = 100, rows = 40)
            assertEquals(
                emptyList(),
                mismatchedOptions(),
                "every option of TMUX_SERVER_OPTIONS must read back from the live server",
            )
        }
    }

    /**
     * Prefixing the option chain must not disturb `new-session -P -F '#{pane_id}'`: the pane id is
     * still the ONLY thing on stdout (a `set-option` that printed would land in the same capture and
     * be rejected by [PaneId]'s `%<n>` format check).
     */
    @Test
    fun theOptionChainLeavesThePaneIdAloneOnStdout() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking
        withTimeout(20_000) {
            val pane = tmux.newSession(id = "opt2", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            assertTrue(pane.value.isNotBlank(), "the chained form must still print a pane id")
            assertTrue(
                Regex("^%\\d+$").matches(pane.value),
                "stdout carries the pane id and nothing the option chain added, was <${pane.value}>",
            )
        }
    }

    /**
     * Re-applying the chain on every [Tmux.newSession] is intended and idempotent: the second
     * session lands on a server that already has the options, succeeds, and leaves them unchanged.
     */
    @Test
    fun aSecondSessionSucceedsAndLeavesTheOptionsIntact() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking
        withTimeout(20_000) {
            tmux.newSession(id = "opt3a", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            tmux.newSession(id = "opt3b", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            val sessions = tmux.listPanes().map { it.session }
            assertTrue("kt-opt3a" in sessions && "kt-opt3b" in sessions, "both sessions exist, was $sessions")
            assertEquals(emptyList(), mismatchedOptions(), "the re-applied chain converges, it does not drift")
        }
    }

    /**
     * The evidence for the whole "chain, don't set afterwards" design: `default-terminal` is read
     * when a pane is CREATED, so the pane's `$TERM` proves the option was already in effect before
     * the agent process existed. Setting it after `new-session` would be too late for exactly this.
     */
    @Test
    fun theForcedOptionsApplyBeforeThePaneExists() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking
        withTimeout(20_000) {
            // `cat` keeps the pane alive after the echo so capture-pane still has something to read.
            tmux.newSession(id = "term1", cwd = "/tmp", cmd = "sh -c 'echo T=\$TERM; cat'", cols = 80, rows = 24)
            val out = captureUntil("term1", "T=")
            assertTrue(
                "T=tmux-256color" in out,
                "the pane's TERM comes from the forced default-terminal, captured:\n<$out>",
            )
        }
    }

    /**
     * Degradation: every command in a tmux chain must succeed or the WHOLE invocation fails, so one
     * option name a different tmux build rejects would take `new-session` down with it and no session
     * could be created at all. [Tmux] therefore retries once, bare, then applies the options
     * best-effort on the now-running server.
     *
     * The bogus option stands in for that foreign tmux build (measured: `invalid option: …`, exit 1,
     * and — load-bearing for the retry — **no session is created**, so the bare retry cannot collide
     * with a half-created one). The valid option alongside it proves the best-effort second half
     * still lands.
     */
    @Test
    fun aRejectedOptionChainDegradesToABareNewSession() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking
        withTimeout(20_000) {
            val survivor = TmuxOption("-g", "history-limit", "12345")
            val degraded = Tmux(
                socket = "kotgent-test",
                serverOptions = listOf(TmuxOption("-g", "kotgent-no-such-option", "on"), survivor),
            )
            val pane = degraded.newSession(id = "deg1", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            assertTrue(
                Regex("^%\\d+$").matches(pane.value),
                "a rejected option must not cost the session, was <${pane.value}>",
            )
            assertTrue(degraded.listPanes().any { it.session == "kt-deg1" }, "the degraded session really exists")
            assertEquals(
                emptyList(),
                mismatchedOptions(listOf(survivor)),
                "the options that tmux does accept are still applied, best-effort, after the retry",
            )
        }
    }

    /**
     * Read every option in [options] back off the live throwaway server (`show-options <scope>v
     * <name>`, the same scope flag the option is set with) and report the ones that do not match.
     * Returns an empty list when all agree, so a failure message names the culprits.
     */
    private fun mismatchedOptions(options: List<TmuxOption> = TMUX_SERVER_OPTIONS): List<String> =
        options.mapNotNull { opt ->
            val r = ProcessRunner.run(
                tmuxCommand(tmux.tmuxPath, "kotgent-test", listOf("show-options", "${opt.scope}v", opt.name)),
            )
            val actual = r.stdout.trim()
            if (r.isSuccess && actual == opt.value) {
                null
            } else {
                "${opt.scope} ${opt.name} is <$actual> want <${opt.value}> ${r.stderr.trim()}".trim()
            }
        }

    // --- isolation-probe harness (throwaway $TMPDIR fake $HOME; NEVER the operator's real one) -------

    /**
     * Start the first server on the throwaway socket under [home] and report the resulting global
     * `focus-events` value. `new-session` is what brings the server up (a standalone `set-option`
     * or `show-options` cannot), so it is also the only invocation whose `-f` matters; the read-back
     * runs under the same [home] and [isolate] purely so a lost server can never make tmux fall back
     * to the operator's real `~/.tmux.conf`.
     */
    private fun focusEventsAfterFirstServerStart(home: String, isolate: Boolean): String {
        val started = rawTmux(home, isolate, "new-session", "-d", "-s", "decoy", "cat")
        assertTrue(started.isSuccess, "decoy new-session failed: ${started.stderr.trim()}")
        val shown = rawTmux(home, isolate, "show-options", "-gv", "focus-events")
        assertTrue(shown.isSuccess, "show-options -gv focus-events failed: ${shown.stderr.trim()}")
        return shown.stdout.trim()
    }

    /** `[/usr/bin/env HOME=<home>] tmux [-f /dev/null] -L kotgent-test <args…>`. */
    private fun rawTmux(home: String, isolate: Boolean, vararg args: String): ProcessResult {
        val globals = if (isolate) TMUX_CONFIG_ISOLATION else emptyList()
        return ProcessRunner.run(
            listOf("/usr/bin/env", "HOME=$home", tmux.tmuxPath) + globals +
                listOf("-L", "kotgent-test") + args.toList(),
        )
    }

    /** A fresh throwaway `$HOME` holding nothing but the decoy `.tmux.conf`. */
    private fun makeFakeHome(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val home = "$tmp/kotgent-tmux-conf-${getpid()}-${counter++}"
        mkdir(home, (S_IRUSR or S_IWUSR or S_IXUSR).convert())
        val bytes = "set -g focus-events on\n".encodeToByteArray()
        val fp = fopen("$home/.tmux.conf", "wb") ?: error("cannot write the decoy config under $home")
        try {
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp) }
        } finally {
            fclose(fp)
        }
        return home
    }

    /** Best-effort teardown of [makeFakeHome]'s tree; runs on every path, ignores every error. */
    private fun removeFakeHome(home: String) {
        unlink("$home/.tmux.conf")
        rmdir(home)
    }

    private companion object {
        var counter = 0
    }
}
