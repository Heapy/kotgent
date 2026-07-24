package io.kotgent.tmux

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * INTEGRATION tests for the [Tmux] wrapper, driven against a **throwaway** server
 * `tmux -L kotgent-test` — never the real `-L kotgent`. Each test spawns a real `tmux` from the
 * TEST binary (only possible because [ProcessRunner] is built on stock `platform.posix`
 * `popen`/`pclose`, not our own cinterop — KT-78062 keeps custom cinterop out of test binaries).
 *
 * Isolation & leak-safety: [BeforeTest]/[AfterTest] both `kill-server` the `kotgent-test` socket
 * (idempotent — "no server running" is fine), so every test starts clean and no tmux server
 * leaks after the suite regardless of outcome.
 *
 * If `tmux` is not runnable every test skip-guards via [tmuxAvailable] and returns. kotlin.test on
 * native has no "skipped" outcome, so such a test is reported as PASSED — [skipped] therefore prints
 * a marker, otherwise a host without tmux reads as a fully green integration suite that never ran a
 * single tmux command. Each body is additionally wrapped in a bounded [withTimeout] tripwire; the
 * real anti-hang guarantee is that tmux control commands terminate in milliseconds and
 * [ProcessRunner]'s single stdout pipe cannot deadlock.
 */
class TmuxTest {

    private val tmux = Tmux(socket = "kotgent-test")

    private fun tmuxAvailable(): Boolean = tmux.isAvailable()

    /** Print a skip marker: the suite counts a skip-guarded test as passed, so silence would lie. */
    private fun skipped(reason: String = "tmux is not runnable") {
        println("SKIP  TmuxTest — $reason")
    }

    private fun killServer() {
        // Best-effort teardown; a missing server just returns non-zero, which we ignore.
        ProcessRunner.run(listOf(tmux.tmuxPath, "-L", tmux.socket, "kill-server"))
    }

    /**
     * `kill-server` returns as soon as the server acknowledges, not when it has exited. Anything that
     * then starts a *new* server (where `-f /dev/null` is the only invocation that matters) must wait
     * for the old one to be gone, or it races into the still-live one.
     */
    private suspend fun killServerAndWait() {
        killServer()
        repeat(40) {
            val r = ProcessRunner.run(tmuxCommand(tmux.tmuxPath, tmux.socket, listOf("has-session", "-t", "kt-none")))
            if ("no server running" in r.stderr) return
            delay(50)
        }
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
        if (!tmuxAvailable()) return@runBlocking skipped()
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
        if (!tmuxAvailable()) return@runBlocking skipped()
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
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(20_000) {
            tmux.newSession(id = "cap", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            // `cat` echoes stdin: feed a marker as raw bytes via send-keys -H and read it back.
            tmux.sendKeys("cap", "KOTGENT-MARKER\n".encodeToByteArray())
            val out = captureUntil("cap", "KOTGENT-MARKER")
            assertTrue("KOTGENT-MARKER" in out, "capture-pane should return the echoed marker, got:\n<$out>")
        }
    }

    /**
     * A pane in copy-mode routes `send-keys` to the **copy-mode key table**, not to the process:
     * measured, the bytes never reach `cat`, the pane is unchanged, and tmux still exits 0. The one
     * production caller is `SessionManager.interrupt` (`0x03`), which then reduces the session to
     * `ready` — so a silently swallowed send would record an interrupt that never happened.
     * [Tmux.sendKeys] cancels copy-mode first; this is the test that the cancel is really there.
     *
     * **This test is what licenses `mouse on` in [TMUX_SERVER_OPTIONS].** With mouse mode forced, a
     * pane reaches copy-mode from an ordinary wheel scroll by *any* subscriber — copy-mode is shared
     * pane state — so the swallowed-Interrupt path is no longer the rare prefix-typed accident it
     * would be with the built-in `mouse off`; it is one scroll away, on every session. If this test
     * is ever deleted or weakened, `mouse on` must be dropped in the same change.
     */
    @Test
    fun sendKeysReachesTheProcessEvenFromCopyMode() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(20_000) {
            tmux.newSession(id = "cm1", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            // Positive control: the pane really is in copy-mode, so a bare send-keys would be lost.
            assertTrue(rawOnTestSocket("copy-mode", "-t", "kt-cm1").isSuccess, "could not enter copy-mode")
            assertEquals("1", paneFormat("kt-cm1", "#{pane_in_mode}"), "the pane must be in copy-mode first")

            tmux.sendKeys("cm1", "COPYMODE-MARKER\n".encodeToByteArray())

            val out = captureUntil("cm1", "COPYMODE-MARKER")
            assertTrue("COPYMODE-MARKER" in out, "send-keys must reach the process from copy-mode, got:\n<$out>")
            assertEquals("0", paneFormat("kt-cm1", "#{pane_in_mode}"), "the cancel left copy-mode")
        }
    }

    @Test
    fun killSessionRemovesTheSession() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(20_000) {
            tmux.newSession(id = "kill1", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            assertTrue(tmux.listPanes().any { it.session == "kt-kill1" }, "session exists before kill")
            assertTrue(tmux.killSession("kill1"), "killSession returns true when it removed a session")
            assertFalse(tmux.listPanes().any { it.session == "kt-kill1" }, "session is gone after kill")
        }
    }

    @Test
    fun killingANonexistentSessionIsGraceful() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
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
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(20_000) {
            tmux.newSession(id = "dbl", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            assertTrue(tmux.killSession("dbl"), "first kill removes the session")
            assertFalse(tmux.killSession("dbl"), "second kill of the same session returns false, not an error")
        }
    }

    @Test
    fun listPanesOnAFreshSocketIsEmptyNotAnError() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
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
     * The decoy carries two lines. `focus-events on` is the semantic one: it must stay an option
     * kotgent NEVER forces (a unit test in `TmuxOptionsTest` pins its absence from
     * [TMUX_SERVER_OPTIONS]), because an option from the forced set would be pinned by the
     * `new-session` chain with or without `-f` and deleting the isolation would leave this test
     * green. `display-time 4321` is the tamper-evidence one: it is a value no real `~/.tmux.conf`
     * would hold, so if the `HOME=<tmp>` override ever stops taking effect this test fails instead of
     * quietly probing the developer's own dotfiles (which, on the machine this was written on,
     * contain literally `set -g focus-events on`).
     *
     * This runs raw argv, not [Tmux]: [ProcessRunner] takes no env map and hands the child the test
     * process's own environment, so a planted `~/.tmux.conf` can only be reached by running tmux
     * through `/usr/bin/env HOME=<tmp>`. [productionNewSessionCarriesTheConfigIsolation] is what
     * links the measurement to production code.
     */
    @Test
    fun theUserConfigLeaksWithoutIsolationAndIsSuppressedByIt() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(30_000) {
            val home = makeFakeHome()
            try {
                assertEquals(
                    mapOf("focus-events" to "on", "display-time" to "4321"),
                    decoyOptionsAfterFirstServerStart(home, isolate = false),
                    "without -f, <tmp>/.tmux.conf leaks into the server — the decoy must be loadable, " +
                        "or the other half of this test proves nothing",
                )
                killServerAndWait() // -f only applies to the invocation that STARTS a server
                assertEquals(
                    mapOf("focus-events" to "off", "display-time" to "750"),
                    decoyOptionsAfterFirstServerStart(home, isolate = true),
                    "with -f /dev/null the same config is suppressed and both options fall back to their built-ins",
                )
            } finally {
                killServer()
                removeFakeHome(home)
            }
        }
    }

    /**
     * The join between the measured isolation fact and production: that [Tmux] really assembles its
     * argv with [tmuxCommand] rather than hand-rolling `listOf(tmuxPath, "-L", socket, …)`.
     *
     * [ProcessRunner] takes no env map, but [Tmux.tmuxPath] is a public constructor parameter and
     * every call goes through `/bin/sh`, so a two-line wrapper script that re-execs the real tmux
     * under `HOME=<tmp>` is enough to run production code against the decoy config. If the isolation
     * were dropped from the builder, `Tmux.newSession` would start a server that loads the decoy and
     * `focus-events` would read `on`.
     */
    @Test
    fun productionNewSessionCarriesTheConfigIsolation() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(30_000) {
            val home = makeFakeHome()
            try {
                // Positive control: under this fake HOME an un-isolated server really does load the
                // decoy, so the `off` below means -f suppressed it rather than that it was never there.
                assertEquals(
                    mapOf("focus-events" to "on", "display-time" to "4321"),
                    decoyOptionsAfterFirstServerStart(home, isolate = false),
                    "the fake HOME must be live, or the assertion below proves nothing",
                )
                killServerAndWait()

                Tmux(socket = tmux.socket, tmuxPath = writeTmuxWrapper(home))
                    .newSession(id = "isoprod", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)

                assertEquals(
                    "off",
                    showOption("-gv", "focus-events"),
                    "Tmux.newSession must start its server through tmuxCommand()'s -f /dev/null, " +
                        "otherwise the decoy ~/.tmux.conf is loaded into kotgent's own server",
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
     * extends this assertion for free. Three of the six equal tmux's own built-in default today
     * (they are pins), so this test is only partly falsifiable by construction —
     * [theForcedOptionsApplyBeforeThePaneExists] carries the rest of the signal by driving a value
     * tmux would never choose itself. The other three do carry signal on their own: `mouse on` in
     * particular reads back as the built-in `off` if the chain never lands, which is exactly the
     * behaviour an operator would notice as "the wheel does nothing".
     */
    @Test
    fun newSessionForcesEveryServerOption() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
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
     * Re-applying the chain on every [Tmux.newSession] is intended and idempotent — and it
     * *converges*, which is only observable if something has drifted first. So an option is
     * deliberately perturbed between the two sessions, standing in for a server that came up some
     * other way or a stray `tmux set-option`.
     */
    @Test
    fun aSecondSessionReAppliesTheOptions() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(20_000) {
            tmux.newSession(id = "opt3a", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            assertTrue(rawOnTestSocket("set-option", "-g", "history-limit", "1").isSuccess, "could not perturb")
            assertTrue(
                mismatchedOptions().any { "history-limit" in it },
                "the perturbation must actually take, or the convergence assertion below is vacuous",
            )

            tmux.newSession(id = "opt3b", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)

            assertEquals(emptyList(), mismatchedOptions(), "the re-applied chain converges, it does not drift")
        }
    }

    /**
     * The evidence for the whole "chain, don't set afterwards" design: `default-terminal` is read
     * when a pane is CREATED, so the pane's `$TERM` proves the option was already in effect before
     * the agent process existed. Setting it after `new-session` would be too late for exactly this.
     *
     * Driven through a **non-default** value: tmux 3.7b's own built-in `default-terminal` is already
     * `tmux-256color`, so asserting the production value would pass with the entire option chain
     * deleted. `Tmux.serverOptions` exists as a seam for precisely this. (`#{history_limit}` is not
     * a usable substitute — it reports the current global value even for a pane created earlier.)
     */
    @Test
    fun theForcedOptionsApplyBeforeThePaneExists() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(20_000) {
            val custom = Tmux(
                socket = tmux.socket,
                serverOptions = TMUX_SERVER_OPTIONS.map {
                    if (it.name == "default-terminal") it.copy(value = "screen-256color") else it
                },
            )
            // `cat` keeps the pane alive after the echo so capture-pane still has something to read.
            custom.newSession(id = "term1", cwd = "/tmp", cmd = "sh -c 'echo T=\$TERM; cat'", cols = 80, rows = 24)
            val out = captureUntil("term1", "T=")
            assertTrue(
                "T=screen-256color" in out,
                "the pane's TERM comes from the forced default-terminal, captured:\n<$out>",
            )
        }
    }

    /**
     * Fail-fast, not degradation: every command in a tmux chain must succeed or the WHOLE invocation
     * fails, and [Tmux.newSession] deliberately lets that surface as a [TmuxException] carrying
     * tmux's own stderr (which names the rejected option). A bare-retry fallback was removed — it
     * fired on every failure, not just option rejection, and hid the real error.
     *
     * The chain aborts *before* `new-session` runs, so a rejected option leaves nothing half-created.
     */
    @Test
    fun aRejectedOptionFailsSessionCreationLoudly() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(20_000) {
            tmux.newSession(id = "rejctl", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            // Positive control: this tmux really does reject the probe option, on a LIVE server (so
            // the rejection is "invalid option", not "no server running").
            val probe = rawOnTestSocket("set-option", "-g", "kotgent-no-such-option", "on")
            assertFalse(probe.isSuccess, "the probe option must actually be rejected by this tmux build")

            val rejecting = Tmux(
                socket = tmux.socket,
                serverOptions = listOf(TmuxOption("-g", "kotgent-no-such-option", "on")),
            )
            val thrown = assertFailsWith<TmuxException> {
                rejecting.newSession(id = "rej1", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            }
            assertTrue(
                "kotgent-no-such-option" in thrown.message.orEmpty(),
                "the failure must name the culprit tmux reported, was <${thrown.message}>",
            )
            assertFalse(
                tmux.listPanes().any { it.session == "kt-rej1" },
                "a rejected chain aborts before new-session — nothing is left half-created",
            )
        }
    }

    /**
     * Read every option in [TMUX_SERVER_OPTIONS] back off the live throwaway server (`show-options
     * <scope>v <name>`, the same scope flag the option is set with) and report the ones that do not
     * match. Returns an empty list when all agree, so a failure message names the culprits.
     */
    private fun mismatchedOptions(): List<String> =
        TMUX_SERVER_OPTIONS.mapNotNull { opt ->
            val r = rawOnTestSocket("show-options", "${opt.scope}v", opt.name)
            val actual = r.stdout.trim()
            if (r.isSuccess && actual == opt.value) {
                null
            } else {
                "${opt.scope} ${opt.name} is <$actual> want <${opt.value}> ${r.stderr.trim()}".trim()
            }
        }

    /** An isolated tmux call on the throwaway socket, argv built exactly like production's. */
    private fun rawOnTestSocket(vararg args: String): ProcessResult =
        ProcessRunner.run(tmuxCommand(tmux.tmuxPath, tmux.socket, args.toList()))

    private fun showOption(scopeFlag: String, name: String): String =
        rawOnTestSocket("show-options", scopeFlag, name).stdout.trim()

    private fun paneFormat(target: String, format: String): String =
        rawOnTestSocket("display-message", "-p", "-t", target, format).stdout.trim()

    // --- isolation-probe harness (throwaway $TMPDIR fake $HOME; NEVER the operator's real one) -------

    /**
     * Start the first server on the throwaway socket under [home] and report the decoy's two options.
     * `new-session` is what brings the server up (a standalone `set-option` or `show-options`
     * cannot), so it is also the only invocation whose `-f` matters; the read-backs run under the
     * same [home] and [isolate] purely so a lost server can never make tmux fall back to the
     * operator's real `~/.tmux.conf`.
     */
    private fun decoyOptionsAfterFirstServerStart(home: String, isolate: Boolean): Map<String, String> {
        val started = rawTmux(home, isolate, "new-session", "-d", "-s", "decoy", "cat")
        assertTrue(started.isSuccess, "decoy new-session failed: ${started.stderr.trim()}")
        return listOf("focus-events", "display-time").associateWith { name ->
            val shown = rawTmux(home, isolate, "show-options", "-gv", name)
            assertTrue(shown.isSuccess, "show-options -gv $name failed: ${shown.stderr.trim()}")
            shown.stdout.trim()
        }
    }

    /** `/usr/bin/env HOME=<home> tmux [-f /dev/null] -L kotgent-test <args…>`. */
    private fun rawTmux(home: String, isolate: Boolean, vararg args: String): ProcessResult {
        val globals = if (isolate) TMUX_CONFIG_ISOLATION else emptyList()
        return ProcessRunner.run(
            listOf("/usr/bin/env", "HOME=$home", tmux.tmuxPath) + globals +
                listOf("-L", tmux.socket) + args.toList(),
        )
    }

    /**
     * A `tmux` stand-in that re-execs the real binary under [home] — the seam that lets production
     * [Tmux] code (which cannot be handed an env map) run against the decoy config.
     */
    private fun writeTmuxWrapper(home: String): String {
        val path = "$home/tmux-under-fake-home"
        val script = "#!/bin/sh\nexec /usr/bin/env HOME=${shq(home)} ${shq(tmux.tmuxPath)} \"\$@\"\n"
        writeFile(path, script)
        assertTrue(ProcessRunner.run(listOf("chmod", "0700", path)).isSuccess, "chmod on the tmux wrapper failed")
        return path
    }

    /** A fresh throwaway `$HOME` holding nothing but the decoy `.tmux.conf`. */
    private fun makeFakeHome(): String {
        val r = ProcessRunner.run(listOf("/bin/sh", "-c", "mktemp -d \"\${TMPDIR:-/tmp}/kotgent-tmux-conf.XXXXXX\""))
        val home = r.stdout.trim()
        assertTrue(r.isSuccess && home.isNotEmpty(), "could not create a throwaway HOME: ${r.stderr.trim()}")
        // focus-events: the semantic decoy (kotgent never forces it). display-time: a value no real
        // ~/.tmux.conf would carry, so a broken HOME override cannot masquerade as a passing probe.
        writeFile("$home/.tmux.conf", "set -g focus-events on\nset -g display-time 4321\n")
        return home
    }

    /** Teardown of [makeFakeHome]'s tree — the whole tree, so nothing planted inside it can leak. */
    private fun removeFakeHome(home: String) {
        ProcessRunner.run(listOf("rm", "-rf", home))
    }

    /**
     * Write [content] to [path] through `/bin/sh` rather than hand-rolled `fopen`/`fwrite`+`usePinned`
     * (which this file otherwise has no cinterop for). [ProcessRunner] quotes every argument, and the
     * payload rides as `$1` rather than being interpolated into the script, so it cannot be re-parsed.
     */
    private fun writeFile(path: String, content: String) {
        val r = ProcessRunner.run(
            listOf("/bin/sh", "-c", "printf '%s' \"\$1\" > \"\$2\"", "sh", content, path),
        )
        assertTrue(r.isSuccess, "could not write $path: ${r.stderr.trim()}")
    }

    /** POSIX single-quote quoting, for the two paths embedded in the wrapper script. */
    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
