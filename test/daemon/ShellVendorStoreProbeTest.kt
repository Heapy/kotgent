package io.kotgent.daemon

import io.kotgent.core.ProviderSessionId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class ShellVendorStoreProbeTest {

    private val files = mutableListOf<String>()
    private val dirs = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        files.forEach { unlink(it) }
        dirs.asReversed().forEach { rmdir(it) }
    }

    @Test
    fun existingDirectoryIsResumableButMissingPathAndRegularFileAreNot() = runBlocking {
        withTimeout(10_000) {
            val base = makeBase()
            val regularFile = "$base/not-a-directory"
            fclose(fopen(regularFile, "wb") ?: error("cannot create $regularFile"))
            files += regularFile

            val probe = shellVendorStoreProbe()
            val syntheticId = ProviderSessionId("synthetic-shell-id")
            assertTrue(probe.hasTranscript(SHELL_AGENT_KIND, base, syntheticId))
            assertFalse(probe.hasTranscript(SHELL_AGENT_KIND, "$base/missing", syntheticId))
            assertFalse(probe.hasTranscript(SHELL_AGENT_KIND, regularFile, syntheticId))
        }
    }

    @Test
    fun productionDispatchRoutesShellToTheCwdProbeWithoutAVendorHome() = runBlocking {
        withTimeout(10_000) {
            val base = makeBase()
            val probe = productionVendorStoreProbe(
                claudeDir = "$base/no-claude",
                codexDir = "$base/no-codex",
                junieDir = "$base/no-junie",
            )

            assertTrue(
                probe.hasTranscript(SHELL_AGENT_KIND, base, ProviderSessionId("nothing-at-a-vendor")),
                "a live cwd is enough for a shell even though no vendor directory exists",
            )
        }
    }

    private fun makeBase(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val base = "$tmp/kotgent-shell-probe-${getpid()}-${counter++}"
        check(mkdir(base, mode0700.convert()) == 0) { "cannot create $base" }
        dirs += base
        return base
    }

    private val mode0700: Int get() = S_IRUSR or S_IWUSR or S_IXUSR

    private companion object {
        var counter: Int = 0
    }
}
