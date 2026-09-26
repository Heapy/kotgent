package io.kotgent.transport

import io.kotgent.push.VapidKey
import io.kotgent.push.VapidKeyException
import io.kotgent.tmux.ProcessRunner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.runBlocking
import platform.posix.UF_IMMUTABLE
import platform.posix.chflags
import platform.posix.chmod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** UF_IMMUTABLE is a Darwin filesystem boundary; shared tests still cover mode validation and repair. */
@OptIn(ExperimentalForeignApi::class)
class ImmutableSecretTest {
    @Test
    fun aTokenThatCannotBeHardenedIsNeverReturned() = runBlocking {
        immutable("fixture-token") { path ->
            assertFailsWith<TokenPermissionException> { readOrCreateToken(path) }
        }
    }

    @Test
    fun aKeyThatCannotBeHardenedPreservesTheSyscallFailure() = runBlocking {
        immutable("-----BEGIN EC PRIVATE KEY-----\nexisting\n-----END EC PRIVATE KEY-----\n") { path ->
            val failure = assertFailsWith<VapidKeyException> { VapidKey(keyPath = path).ensureKeyFile() }
            assertTrue("chmod 0600 failed:" in failure.message.orEmpty())
        }
    }

    private suspend fun immutable(content: String, check: suspend (String) -> Unit) {
        val path = ProcessRunner.run(listOf("mktemp", "/tmp/kotgent-immutable-XXXXXX"))
            .also { assertEquals(0, it.exitCode, it.stderr) }.stdout.trim()
        try {
            writePrivateFile(path, content.encodeToByteArray())
            assertEquals(0, chmod(path, 0b110100100.convert()))
            // Preserve the existing precondition: not every macOS filesystem supports this flag.
            if (chflags(path, UF_IMMUTABLE.convert()) != 0) return
            assertTrue(chmod(path, 0b110000000.convert()) != 0)
            check(path)
        } finally {
            chflags(path, 0.convert())
            val _ = ProcessRunner.run(listOf("rm", "-f", path))
        }
    }
}
