package io.kotgent.launchd

import io.kotgent.sys.DEFAULT_UTF8_LOCALE
import io.kotgent.tmux.ProcessResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [LaunchdInstaller] (plan Task 16). These NEVER run real `launchctl` and NEVER touch
 * `~/Library`: the `launchctl` calls go through an injected [FakeRunner] (so the exact argv is asserted
 * without executing), and the plist / directories are written under a throwaway `$TMPDIR` path injected
 * as `launchAgentsDir` / `logDir`. The uid is injected too, so `gui/<uid>` is deterministic.
 */
@OptIn(ExperimentalForeignApi::class)
class InstallTest {

    private val bases = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        // Best-effort teardown of the throwaway temp trees (ignore every error).
        for (base in bases) {
            unlink("$base/LaunchAgents/$DAEMON_LABEL.plist")
            rmdir("$base/LaunchAgents")
            rmdir("$base/Logs/kotgent")
            rmdir("$base/Logs")
            rmdir(base)
        }
    }

    @Test
    fun installWritesThePlistToTheExpectedPathAndBootstrapsIt() {
        val fake = FakeRunner()
        val (installer, base) = newInstaller(fake, uid = 501u)
        val binary = "/opt/kotgent/build/kotgent"

        val plistPath = installer.install(binary)

        // 1. The plist lands at <launchAgentsDir>/io.kotgent.daemon.plist and actually exists on disk.
        assertEquals("$base/LaunchAgents/$DAEMON_LABEL.plist", plistPath)
        assertEquals(plistPath, installer.plistPath)
        assertTrue(fileExists(plistPath), "the plist file was written")

        // 2. Its content is the generated plist (Label + ProgramArguments = [binary, daemon]).
        val content = readFile(plistPath)
        assertTrue("<string>$DAEMON_LABEL</string>" in content, "Label written")
        assertTrue("<string>$binary</string>" in content, "binary path written")
        assertTrue("<string>daemon</string>" in content, "daemon arg written")

        // 3. launchctl was driven: bootout (best-effort) THEN bootstrap, both `gui/501 <plist>`.
        assertEquals(2, fake.calls.size, "exactly bootout + bootstrap")
        assertEquals(listOf("launchctl", "bootout", "gui/501", plistPath), fake.calls[0])
        assertEquals(listOf("launchctl", "bootstrap", "gui/501", plistPath), fake.calls[1])
    }

    @Test
    fun theDomainTargetUsesTheInjectedUid() {
        val fake = FakeRunner()
        val (installer, _) = newInstaller(fake, uid = 777u)
        installer.install("/bin/kotgent")
        assertTrue(fake.calls.all { it.contains("gui/777") }, "every launchctl call targets gui/777")
    }

    @Test
    fun installIsIdempotent_secondInstallOverwritesWithoutError() {
        val fake = FakeRunner()
        val (installer, _) = newInstaller(fake, uid = 501u)

        val first = installer.install("/v1/kotgent")
        val second = installer.install("/v2/kotgent") // must not throw, must overwrite in place

        assertEquals(first, second, "same plist path both times")
        assertTrue(fileExists(second), "the plist still exists")
        val content = readFile(second)
        assertTrue("<string>/v2/kotgent</string>" in content, "the second install's binary overwrote the first")
        assertTrue("/v1/kotgent" !in content, "no stale content from the first install remains")
        assertEquals(4, fake.calls.size, "each install did its own bootout + bootstrap")
    }

    @Test
    fun bootstrapFailureIsSurfacedAsAnError() {
        val fake = FakeRunner { argv ->
            // bootout succeeds; bootstrap fails (e.g. the job is genuinely broken).
            if (argv.getOrNull(1) == "bootstrap") ProcessResult(5, ByteArray(0), "Bootstrap failed: 5".encodeToByteArray())
            else ProcessResult(0, ByteArray(0), ByteArray(0))
        }
        val (installer, _) = newInstaller(fake, uid = 501u)
        val threw = runCatching { installer.install("/bin/kotgent") }.isFailure
        assertTrue(threw, "a non-zero bootstrap exit is surfaced (not swallowed)")
    }

    @Test
    fun uninstallBootsOutAndRemovesThePlist() {
        val fake = FakeRunner()
        val (installer, _) = newInstaller(fake, uid = 501u)
        val plistPath = installer.install("/bin/kotgent")
        assertTrue(fileExists(plistPath))
        fake.calls.clear()

        installer.uninstall()

        assertEquals(1, fake.calls.size, "uninstall issues exactly one launchctl call")
        assertEquals(listOf("launchctl", "bootout", "gui/501", plistPath), fake.calls[0])
        assertFalse(fileExists(plistPath), "the plist file was removed")
    }

    @Test
    fun uninstallIsIdempotent_whenNothingIsInstalled() {
        val fake = FakeRunner()
        val (installer, _) = newInstaller(fake, uid = 501u)
        // No prior install: bootout is best-effort, and removing a missing file must not throw.
        installer.uninstall()
        assertEquals(1, fake.calls.size)
        assertEquals("bootout", fake.calls[0][1])
    }

    @Test
    fun installSnapshotsTheCallersPathIntoThePlist() {
        val fake = FakeRunner()
        val customDir = "/Users/tester/.local/bin"
        val captured = "$customDir:/usr/local/bin"
        val (installer, _) = newInstaller(fake, uid = 501u, pathProvider = { captured })

        val plistPath = installer.install("/bin/kotgent")
        val content = readFile(plistPath)

        // The plist PATH is exactly the merge of the captured PATH with the default floor.
        val expected = mergedDaemonPath(captured)
        assertTrue("<string>${expected}</string>" in content, "the merged PATH is written verbatim")
        // The caller's custom dir is present in the written plist, and the default entries are retained.
        assertTrue(customDir in content, "custom dir survives the merge into the plist")
        assertTrue("/opt/homebrew/bin" in content, "default homebrew bin retained in the plist")
        assertTrue("/usr/bin" in content, "default /usr/bin retained in the plist")
    }

    @Test
    fun installFallsBackToTheDefaultPathWhenNoPathIsCaptured() {
        val fake = FakeRunner()
        val (installer, _) = newInstaller(fake, uid = 501u, pathProvider = { null })

        val plistPath = installer.install("/bin/kotgent")
        val content = readFile(plistPath)

        // A null captured PATH → the plist PATH is exactly DAEMON_DEFAULT_PATH (backward compatible).
        assertTrue("<string>$DAEMON_DEFAULT_PATH</string>" in content, "falls back to DAEMON_DEFAULT_PATH")
    }

    @Test
    fun installSnapshotsTheCallersUtf8LangIntoThePlist() {
        val fake = FakeRunner()
        val (installer, _) = newInstaller(fake, uid = 501u, langProvider = { "ru_RU.UTF-8" })

        val content = readFile(installer.install("/bin/kotgent"))

        assertTrue("<string>ru_RU.UTF-8</string>" in content, "the caller's UTF-8 locale is written verbatim")
    }

    @Test
    fun installSubstitutesAUtf8LangWhenTheCallerHasNoneOrANonUtf8One() {
        // The launchd failure mode: no LANG (or a C/POSIX one) means every tmux client the daemon opens
        // renders non-ASCII cells as `_`, so the plist must carry a UTF-8 locale regardless.
        for (captured in listOf(null, "C")) {
            val fake = FakeRunner()
            val (installer, _) = newInstaller(fake, uid = 501u, langProvider = { captured })

            val content = readFile(installer.install("/bin/kotgent"))

            assertTrue(
                "<string>$DEFAULT_UTF8_LOCALE</string>" in content,
                "a captured LANG of <$captured> falls back to $DEFAULT_UTF8_LOCALE",
            )
        }
    }

    // --- harness -------------------------------------------------------------------------------------

    /** A [LaunchdInstaller] wired to [fake], writing under a fresh throwaway temp base (also returned). */
    private fun newInstaller(
        fake: FakeRunner,
        uid: UInt,
        pathProvider: () -> String? = { null },
        langProvider: () -> String? = { null },
    ): Pair<LaunchdInstaller, String> {
        val base = uniqueTempBase()
        bases += base
        val installer = LaunchdInstaller(
            runner = fake::run,
            launchAgentsDir = "$base/LaunchAgents",
            logDir = "$base/Logs/kotgent",
            uid = uid,
            pathProvider = pathProvider,
            langProvider = langProvider,
        )
        return installer to base
    }

    /** Records every argv it is handed and returns a canned [ProcessResult] (success by default). */
    private class FakeRunner(private val respond: (List<String>) -> ProcessResult = { ok() }) {
        val calls = mutableListOf<List<String>>()
        fun run(argv: List<String>): ProcessResult {
            calls += argv
            return respond(argv)
        }

        companion object {
            fun ok() = ProcessResult(0, ByteArray(0), ByteArray(0))
        }
    }

    private fun uniqueTempBase(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        return "$tmp/kotgent-launchd-test-${getpid()}-${counter++}"
    }

    private fun fileExists(path: String): Boolean {
        val fp = fopen(path, "rb") ?: return false
        fclose(fp)
        return true
    }

    private fun readFile(path: String): String {
        val fp = fopen(path, "rb") ?: error("cannot open $path")
        try {
            fseek(fp, 0, SEEK_END)
            val size = ftell(fp)
            fseek(fp, 0, SEEK_SET)
            if (size <= 0L) return ""
            val buf = ByteArray(size.toInt())
            buf.usePinned { fread(it.addressOf(0), 1.convert(), size.convert(), fp) }
            return buf.decodeToString()
        } finally {
            fclose(fp)
        }
    }

    private companion object {
        var counter = 0
    }
}
