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

class TmuxTest {

    private val tmux = Tmux(socket = "kotgent-test")

    private fun tmuxAvailable(): Boolean = tmux.isAvailable()

    private fun skipped(reason: String = "tmux is not runnable") {
        println("SKIP  TmuxTest — $reason")
    }

    private fun killServer() {
        ProcessRunner.run(listOf(tmux.tmuxPath, "-L", tmux.socket, "kill-server"))
    }

    private fun clearSessionClosedHook() {
        ProcessRunner.run(tmuxCommand(tmux.tmuxPath, tmux.socket, listOf("set-hook", "-gu", "session-closed")))
    }

    private suspend fun killServerAndWait() {
        killServer()
        var last: ProcessResult? = null
        repeat(40) {
            val r = ProcessRunner.run(tmuxCommand(tmux.tmuxPath, tmux.socket, listOf("has-session", "-t", "kt-none")))
            last = r
            if ("no server running" in r.stderr) return
            delay(50)
        }
        error("tmux server '${tmux.socket}' did not exit after 40 probes; last result: $last")
    }

    @BeforeTest
    fun setUp() = runBlocking {
        if (tmuxAvailable()) killServerAndWait()
    }

    @AfterTest
    fun tearDown() {
        if (tmuxAvailable()) {
            clearSessionClosedHook()
            killServer()
        }
    }

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
            tmux.sendKeys("cap", "KOTGENT-MARKER\n".encodeToByteArray())
            val out = captureUntil("cap", "KOTGENT-MARKER")
            assertTrue("KOTGENT-MARKER" in out, "capture-pane should return the echoed marker, got:\n<$out>")
        }
    }

    @Test
    fun sendKeysReachesTheProcessEvenFromCopyMode() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(20_000) {
            tmux.newSession(id = "cm1", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            assertTrue(rawOnTestSocket("copy-mode", "-t", "kt-cm1").isSuccess, "could not enter copy-mode")
            assertEquals("1", paneFormat("kt-cm1", "#{pane_in_mode}"), "the pane must be in copy-mode first")

            tmux.sendKeys("cm1", "COPYMODE-MARKER\n".encodeToByteArray())

            val out = captureUntil("cm1", "COPYMODE-MARKER")
            assertTrue("COPYMODE-MARKER" in out, "send-keys must reach the process from copy-mode, got:\n<$out>")
            assertEquals("0", paneFormat("kt-cm1", "#{pane_in_mode}"), "the cancel left copy-mode")
        }
    }

    @Test
    fun sendKeysFailsLoudlyWhenTheCopyModeCancelIsDefeated() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(30_000) {
            val dir = makeTempDir()
            try {
                tmux.newSession(id = "cm2", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
                assertTrue(rawOnTestSocket("copy-mode", "-t", "kt-cm2").isSuccess, "could not enter copy-mode")
                assertEquals("1", paneFormat("kt-cm2", "#{pane_in_mode}"), "the pane must be in copy-mode first")

                val defeated = Tmux(socket = tmux.socket, tmuxPath = writeCancelDroppingWrapper(dir))
                val thrown = assertFailsWith<TmuxCopyModeException> {
                    defeated.sendKeys("cm2", "COPYMODE-LOST\n".encodeToByteArray())
                }
                assertTrue(
                    "copy-mode" in thrown.message.orEmpty(),
                    "the failure must name copy-mode as the reason, was <${thrown.message}>",
                )
                delay(300)
                assertFalse(
                    "COPYMODE-LOST" in tmux.capturePane("cm2"),
                    "the positive control: with the cancel defeated the bytes really are swallowed",
                )
            } finally {
                removeTempDir(dir)
            }
        }
    }

    @Test
    fun leaveCopyModeReportsWhetherThePaneIsClear() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(20_000) {
            assertTrue(tmux.leaveCopyMode("never-existed"), "no server at all: nothing to refuse over")

            tmux.newSession(id = "lcm", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
            assertTrue(tmux.leaveCopyMode("lcm"), "a pane in no mode is already clear")
            assertEquals("0", paneFormat("kt-lcm", "#{pane_in_mode}"), "and `copy-mode -q` left it alone")

            assertTrue(rawOnTestSocket("copy-mode", "-t", "kt-lcm").isSuccess, "could not enter copy-mode")
            assertEquals("1", paneFormat("kt-lcm", "#{pane_in_mode}"), "the pane must really be in copy-mode")
            assertTrue(tmux.leaveCopyMode("lcm"), "the cancel takes, so the pane reports clear")
            assertEquals("0", paneFormat("kt-lcm", "#{pane_in_mode}"), "and it really left copy-mode")

            assertTrue(tmux.leaveCopyMode("no-such-session"), "an unknown session on a live server is graceful")
        }
    }

    @Test
    fun leaveCopyModeRefusesWhenTheCancelWasNotAnswered() {
        val dir = makeTempDir()
        try {
            val hardFailure = "echo 'tmux: connection failed' >&2\nexit 1\n"
            assertFalse(
                Tmux(socket = tmux.socket, tmuxPath = writeStubTmux(dir, "tmux-broken", hardFailure))
                    .leaveCopyMode("lcm-fail"),
                "a real failure proves nothing about the pane and must not be reported as clear",
            )

            assertFalse(
                Tmux(socket = tmux.socket, tmuxPath = writeStubTmux(dir, "tmux-mute", "exit 0\n"))
                    .leaveCopyMode("lcm-mute"),
                "exit 0 with no #{pane_in_mode} answer is unanswered, not clear",
            )

            assertFalse(
                Tmux(socket = tmux.socket, tmuxPath = writeStubTmux(dir, "tmux-noise", "echo not-a-mode\nexit 0\n"))
                    .leaveCopyMode("lcm-noise"),
                "unparseable read-back output is unanswered, not clear",
            )

            val absence = "echo 'no server running on /tmp/tmux-501/kotgent-test' >&2\nexit 1\n"
            assertTrue(
                Tmux(socket = tmux.socket, tmuxPath = writeStubTmux(dir, "tmux-absent", absence))
                    .leaveCopyMode("lcm-gone"),
                "a soft absence stays graceful — there is no pane left to swallow anything",
            )

            assertTrue(
                Tmux(socket = tmux.socket, tmuxPath = writeStubTmux(dir, "tmux-clear", "echo 0\nexit 0\n"))
                    .leaveCopyMode("lcm-clear"),
                "an ANSWERED 0 is the one positive case",
            )
        } finally {
            removeTempDir(dir)
        }
    }

    @Test
    fun sendKeysRefusesWhenTheDeliveryReadBackIsUnanswered() {
        val dir = makeTempDir()
        try {
            val emptySend = assertFailsWith<TmuxException> {
                Tmux(socket = tmux.socket, tmuxPath = writeStubTmux(dir, "tmux-send-mute", "exit 0\n"))
                    .sendKeys("send-mute", byteArrayOf(0x03))
            }
            assertFalse(
                emptySend is TmuxCopyModeException,
                "an empty read-back is unanswered, not proof that the pane remains in copy-mode",
            )
            assertTrue(
                "could not verify delivery" in emptySend.message.orEmpty(),
                "the empty read-back failure must explain that delivery was unverified: ${emptySend.message}",
            )

            val noisySend = assertFailsWith<TmuxException> {
                Tmux(
                    socket = tmux.socket,
                    tmuxPath = writeStubTmux(dir, "tmux-send-noise", "echo not-a-mode\nexit 0\n"),
                ).sendKeys("send-noise", byteArrayOf(0x03))
            }
            assertFalse(
                noisySend is TmuxCopyModeException,
                "an unparseable read-back is unanswered, not proof that the pane remains in copy-mode",
            )
            assertTrue(
                "could not verify delivery" in noisySend.message.orEmpty(),
                "the noisy read-back failure must explain that delivery was unverified: ${noisySend.message}",
            )

            val absenceShapes = listOf(
                "server" to "echo 'no server running on /tmp/tmux-501/kotgent-test' >&2\nexit 1\n",
                "session" to "echo \"can't find session: kt-send-session\" >&2\nexit 1\n",
                "pane" to "echo \"can't find pane: kt-send-pane\" >&2\nexit 1\n",
            )
            for ((shape, body) in absenceShapes) {
                val absentSend = assertFailsWith<TmuxException> {
                    Tmux(
                        socket = tmux.socket,
                        tmuxPath = writeStubTmux(dir, "tmux-send-absent-$shape", body),
                    ).sendKeys("send-$shape", byteArrayOf(0x03))
                }
                assertFalse(
                    absentSend is TmuxCopyModeException,
                    "an absent $shape is not the transient copy-mode condition",
                )
                assertTrue(
                    "was not delivered" in absentSend.message.orEmpty() &&
                        "kt-send-$shape" in absentSend.message.orEmpty(),
                    "$shape absence must fail the delivery contract and name the target: ${absentSend.message}",
                )
            }
        } finally {
            removeTempDir(dir)
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
    fun sessionClosedHookReportsAnOrdinaryAndTheLastSession() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(30_000) {
            val dir = makeTempDir()
            try {
                val log = "$dir/closed-sessions"
                val script = "$dir/session-closed.sh"
                writeFile(script, "#!/bin/sh\nprintf '%s\\n' \"\$1\" >> ${shq(log)}\n")

                val hooked = Tmux(
                    socket = tmux.socket,
                    tmuxPath = tmux.tmuxPath,
                    hookScriptPath = script,
                )
                hooked.newSession(id = "hooka", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
                hooked.newSession(id = "hookb", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)

                assertTrue(hooked.killSession("hooka"), "the first hooked session should close")
                assertEquals(
                    listOf("kt-hooka"),
                    waitForHookLines(log, 1),
                    "the hook reports an ordinary session close",
                )

                assertTrue(hooked.killSession("hookb"), "the final hooked session should close")
                assertEquals(
                    listOf("kt-hooka", "kt-hookb"),
                    waitForHookLines(log, 2),
                    "the last-session hook runs before the tmux server dies",
                )
            } finally {
                clearSessionClosedHook()
                killServer()
                removeTempDir(dir)
            }
        }
    }

    @Test
    fun killingANonexistentSessionIsGraceful() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(20_000) {
            assertFalse(tmux.killSession("never-existed"), "killing a nonexistent session returns false")
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
            assertEquals(emptyList(), tmux.listPanes())
        }
    }

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
                killServerAndWait()
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

    @Test
    fun productionNewSessionCarriesTheConfigIsolation() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(30_000) {
            val home = makeFakeHome()
            try {
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
            custom.newSession(id = "term1", cwd = "/tmp", cmd = "sh -c 'echo T=\$TERM; cat'", cols = 80, rows = 24)
            val out = captureUntil("term1", "T=")
            assertTrue(
                "T=screen-256color" in out,
                "the pane's TERM comes from the forced default-terminal, captured:\n<$out>",
            )
        }
    }

    @Test
    fun aRejectedOptionFailsSessionCreationLoudly() = runBlocking {
        if (!tmuxAvailable()) return@runBlocking skipped()
        withTimeout(20_000) {
            tmux.newSession(id = "rejctl", cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)
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

    private fun rawOnTestSocket(vararg args: String): ProcessResult =
        ProcessRunner.run(tmuxCommand(tmux.tmuxPath, tmux.socket, args.toList()))

    private fun showOption(scopeFlag: String, name: String): String =
        rawOnTestSocket("show-options", scopeFlag, name).stdout.trim()

    private fun paneFormat(target: String, format: String): String =
        rawOnTestSocket("display-message", "-p", "-t", target, format).stdout.trim()

    private suspend fun waitForHookLines(path: String, count: Int): List<String> {
        var last = emptyList<String>()
        repeat(40) {
            val result = ProcessRunner.run(listOf("/bin/cat", path))
            last = if (result.isSuccess) {
                result.stdout.lineSequence().filter { it.isNotEmpty() }.toList()
            } else {
                emptyList()
            }
            if (last.size >= count) return last
            delay(50)
        }
        return last
    }


    private fun decoyOptionsAfterFirstServerStart(home: String, isolate: Boolean): Map<String, String> {
        val started = rawTmux(home, isolate, "new-session", "-d", "-s", "decoy", "cat")
        assertTrue(started.isSuccess, "decoy new-session failed: ${started.stderr.trim()}")
        return listOf("focus-events", "display-time").associateWith { name ->
            val shown = rawTmux(home, isolate, "show-options", "-gv", name)
            assertTrue(shown.isSuccess, "show-options -gv $name failed: ${shown.stderr.trim()}")
            shown.stdout.trim()
        }
    }

    private fun rawTmux(home: String, isolate: Boolean, vararg args: String): ProcessResult {
        val globals = if (isolate) TMUX_CONFIG_ISOLATION else emptyList()
        return ProcessRunner.run(
            listOf("/usr/bin/env", "HOME=$home", tmux.tmuxPath) + globals +
                listOf("-L", tmux.socket) + args.toList(),
        )
    }

    private fun writeTmuxWrapper(home: String): String {
        val path = "$home/tmux-under-fake-home"
        val script = "#!/bin/sh\nexec /usr/bin/env HOME=${shq(home)} ${shq(tmux.tmuxPath)} \"\$@\"\n"
        writeFile(path, script)
        assertTrue(ProcessRunner.run(listOf("chmod", "0700", path)).isSuccess, "chmod on the tmux wrapper failed")
        return path
    }

    private fun writeCancelDroppingWrapper(dir: String): String {
        val path = "$dir/tmux-without-cancel"
        val real = shq(tmux.tmuxPath)
        val globals = (TMUX_CONFIG_ISOLATION + listOf("-L", tmux.socket)).joinToString(" ") { shq(it) }
        val script = "#!/bin/sh\n" +
            "if [ \"$5\" = copy-mode ]; then\n" +
            "  shift 9\n" +
            "  exec $real $globals \"$@\"\n" +
            "fi\n" +
            "exec $real \"$@\"\n"
        writeFile(path, script)
        assertTrue(ProcessRunner.run(listOf("chmod", "0700", path)).isSuccess, "chmod on the tmux wrapper failed")
        return path
    }

    private fun writeStubTmux(dir: String, name: String, body: String): String {
        val path = "$dir/$name"
        writeFile(path, "#!/bin/sh\n$body")
        assertTrue(ProcessRunner.run(listOf("chmod", "0700", path)).isSuccess, "chmod on the tmux stub failed")
        return path
    }

    private fun makeTempDir(): String {
        val r = ProcessRunner.run(listOf("/bin/sh", "-c", "mktemp -d \"\${TMPDIR:-/tmp}/kotgent-tmux-conf.XXXXXX\""))
        val dir = r.stdout.trim()
        assertTrue(r.isSuccess && dir.isNotEmpty(), "could not create a throwaway directory: ${r.stderr.trim()}")
        return dir
    }

    private fun removeTempDir(dir: String) {
        ProcessRunner.run(listOf("rm", "-rf", dir))
    }

    private fun makeFakeHome(): String {
        val home = makeTempDir()
        writeFile("$home/.tmux.conf", "set -g focus-events on\nset -g display-time 4321\n")
        return home
    }

    private fun removeFakeHome(home: String) {
        removeTempDir(home)
    }

    private fun writeFile(path: String, content: String) {
        val r = ProcessRunner.run(
            listOf("/bin/sh", "-c", "printf '%s' \"\$1\" > \"\$2\"", "sh", content, path),
        )
        assertTrue(r.isSuccess, "could not write $path: ${r.stderr.trim()}")
    }

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
