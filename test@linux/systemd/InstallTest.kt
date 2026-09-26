package io.kotgent.systemd

import io.kotgent.tmux.ProcessResult
import io.kotgent.tmux.ProcessRunner
import io.kotgent.transport.readFileTextOrNull
import io.kotgent.transport.writePrivateFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstallTest {
    private class Fixture {
        val root = ProcessRunner.run(listOf("mktemp", "-d", "/tmp/kotgent-systemd-test-XXXXXX"))
            .also { assertEquals(0, it.exitCode, it.stderr) }.stdout.trim()
        val calls = mutableListOf<List<String>>()
        var fail: String? = null
        val installer = SystemdInstaller(
            unitDirectory = "$root/config/systemd/user",
            pathProvider = { "/custom/bin::relative:/usr/bin" },
            langProvider = { "C" },
            runner = { argv ->
                calls += argv
                if (argv.getOrNull(2) == fail) ProcessResult(1, ByteArray(0), "fixture failure".encodeToByteArray())
                else ProcessResult(0, ByteArray(0), ByteArray(0))
            },
        )

        fun close() {
            assertEquals(0, ProcessRunner.run(listOf("rm", "-rf", root)).exitCode)
        }
    }

    private inline fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }

    @Test
    fun installAndReinstallCaptureTheEnvironmentAndRestartTheUpdatedUnit() = fixture { f ->
        val path = f.installer.install("/one/kotgent")
        assertEquals(f.installer.definitionPath, path)
        assertEquals(listOf("show-environment", "daemon-reload", "enable", "restart"), f.calls.map { it[2] })
        assertTrue(f.calls.all { it.take(2) == listOf("systemctl", "--user") })
        assertEquals(listOf("systemctl", "--user", "enable", DAEMON_UNIT), f.calls[2])
        assertEquals(path, f.installer.install("/two/kotgent"))
        val unit = assertNotNull(readFileTextOrNull(path))
        assertTrue("ExecStart=:/usr/bin/env -- \"/two/kotgent\" daemon" in unit)
        assertTrue("/one/kotgent" !in unit)
        assertTrue("Environment=\"LANG=C.UTF-8\"" in unit)
        assertTrue("PATH=/custom/bin:/usr/bin:/usr/local/bin:" in unit)
        val mode = ProcessRunner.run(listOf("perl", "-e", "printf '%o', (stat(shift))[2] & 0777", path))
        assertEquals("600", mode.stdout)
    }

    @Test
    fun missingUserManagerLeavesNoUnitAndExplainsForegroundOperation() = fixture { f ->
        f.fail = "show-environment"
        val failure = assertFailsWith<SystemdException> { f.installer.install("/bin/kotgent") }
        assertTrue("kotgent daemon" in failure.message.orEmpty())
        assertNull(readFileTextOrNull(f.installer.definitionPath))
        assertEquals(1, f.calls.size)
    }

    @Test
    fun failedRestartIsReportedAndLeavesTheDefinitionForRecovery() = fixture { f ->
        f.fail = "restart"
        assertFailsWith<SystemdException> { f.installer.install("/bin/kotgent") }
        assertNotNull(readFileTextOrNull(f.installer.definitionPath))
    }

    @Test
    fun uninstallStopsAndDisablesBeforeRemovingTheUnitAndIsRepeatable() = fixture { f ->
        val _ = f.installer.install("/bin/kotgent")
        f.calls.clear()
        f.installer.uninstall()
        assertEquals(listOf(
            listOf("systemctl", "--user", "show-environment"),
            listOf("systemctl", "--user", "disable", "--now", DAEMON_UNIT),
            listOf("systemctl", "--user", "daemon-reload"),
        ), f.calls)
        assertNull(readFileTextOrNull(f.installer.definitionPath))
        f.calls.clear()
        f.installer.uninstall()
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun failedStopPreservesTheDefinition() = fixture { f ->
        val _ = f.installer.install("/bin/kotgent")
        f.fail = "disable"
        assertFailsWith<SystemdException> { f.installer.uninstall() }
        assertNotNull(readFileTextOrNull(f.installer.definitionPath))
    }

    @Test
    fun invalidValuesCannotChangeAnInstalledUnit() = fixture { f ->
        val path = f.installer.install("/original/kotgent")
        val before = readFileTextOrNull(path)
        f.calls.clear()
        for (invalid in listOf("relative", "/bad\n[Service]", "/bad\u0000name")) {
            assertFailsWith<IllegalArgumentException> { f.installer.install(invalid) }
        }
        assertEquals(before, readFileTextOrNull(path))
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun systemdAcceptsTheRenderedUnitWithLiteralSpecialCharacters() = fixture { f ->
        val executable = "${f.root}/a space's \"quote\" %h \$HOME"
        writePrivateFile(executable, "#!/bin/sh\nexit 0\n".encodeToByteArray())
        assertEquals(0, ProcessRunner.run(listOf("chmod", "700", executable)).exitCode)
        val unitPath = f.installer.install(executable)
        val result = ProcessRunner.run(listOf("systemd-analyze", "verify", "--man=no", unitPath))
        assertEquals(0, result.exitCode, result.stderr)
    }

    @Test
    fun xdgConfigurationHonorsOnlyAbsoluteRoots() {
        assertEquals("/home/person/.config/systemd/user", systemdUserDirectory("/home/person/", null))
        assertEquals("/custom/systemd/user", systemdUserDirectory(null, "/custom/"))
        assertEquals("/home/person/.config/systemd/user", systemdUserDirectory("/home/person", "relative"))
        assertFailsWith<SystemdException> { systemdUserDirectory(null, null) }
    }
}
