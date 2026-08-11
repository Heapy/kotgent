package io.kotgent.daemon

import io.kotgent.cli.Commands
import io.kotgent.tmux.TmuxHookConfig
import io.kotgent.transport.readFileTextOrNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(ExperimentalForeignApi::class)
class TmuxHookWiringTest {

    @Test
    fun generatedArtifactsMatchTheIngressContractAndAreBothPrivate() {
        val home = makeTempHome()
        val headerPath = "$home/tmux-hook-header"
        val scriptPath = "$home/tmux-hook.sh"
        val port = 7_419
        val token = "tmux-hook-secret-0123456789"
        try {
            assertEquals(
                scriptPath,
                Commands.writeTmuxHookScript(port, token, home),
                "the returned path is the script Tmux installs",
            )

            val header = assertNotNull(readFileTextOrNull(headerPath))
            val script = assertNotNull(readFileTextOrNull(scriptPath))
            assertEquals(TmuxHookConfig.headerFileContent(token), header)
            assertContains(
                script,
                TmuxHookConfig.ingressUrl(port),
                message = "the script targets the ingress port and path",
            )
            assertContains(
                header,
                TmuxHookConfig.HOOK_TOKEN_HEADER,
                message = "the private file carries the header the route validates",
            )
            assertContains(
                script,
                TmuxHookConfig.SESSION_HEADER,
                message = "curl names the closed tmux session",
            )
            assertContains(script, "@$headerPath", message = "the script reads this throwaway header file")
            assertFalse(token in script, "the token must never be embedded in the script or tmux command")

            assertEquals(MODE_0600, fileMode(headerPath), "the token header is 0600")
            assertEquals(MODE_0600, fileMode(scriptPath), "the non-executable script is 0600")
        } finally {
            unlink(headerPath)
            unlink(scriptPath)
            rmdir(home)
        }
    }

    private fun makeTempHome(): String {
        val root = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        repeat(20) {
            val suffix = Random.nextInt().toUInt().toString(16)
            val path = "$root/kotgent-tmux-hook-${getpid()}-$suffix"
            if (mkdir(path, MODE_0700.convert()) == 0) return path
        }
        error("could not create a throwaway tmux-hook home under $root")
    }

    private fun fileMode(path: String): Int = memScoped {
        val st = alloc<platform.posix.stat>()
        assertEquals(0, platform.posix.stat(path, st.ptr), "stat failed for $path")
        st.st_mode.toInt() and 0b111_111_111
    }

    private companion object {
        const val MODE_0600: Int = 0b110_000_000
        val MODE_0700: Int get() = S_IRUSR or S_IWUSR or S_IXUSR
    }
}
