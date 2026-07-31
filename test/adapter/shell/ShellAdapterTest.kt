package io.kotgent.adapter.shell

import io.kotgent.adapter.LaunchMode
import io.kotgent.core.ProviderSessionId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ShellAdapterTest {

    private val fixedId = ProviderSessionId("12345678-1234-4234-8234-1234567890ab")

    @Test
    fun newLaunchUsesTheLoginShellAndMintsTheInjectedSyntheticId() {
        val spec = ShellAdapter(
            cwd = "/work/repo",
            shell = "/bin/zsh",
            generateSessionId = { fixedId },
        ).buildLaunchSpec(LaunchMode.New)

        assertEquals(listOf("/bin/zsh", "-l"), spec.command)
        assertEquals("/work/repo", spec.cwd)
        assertEquals(emptyMap(), spec.env)
        assertEquals(fixedId, spec.preallocatedSessionId)
        assertEquals("/bin/zsh", spec.cliPath)
        assertNull(spec.cliVersion)
    }

    @Test
    fun resumeLaunchUsesTheSameArgvWithoutMintingOrEmbeddingAnId() {
        var generated = 0
        val existingId = ProviderSessionId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val spec = ShellAdapter(
            cwd = "/work/repo",
            shell = "/bin/fish",
            generateSessionId = {
                generated += 1
                fixedId
            },
        ).buildLaunchSpec(LaunchMode.Resume(existingId))

        assertEquals(listOf("/bin/fish", "-l"), spec.command)
        assertFalse(spec.command.contains(existingId.value), "a shell has no provider conversation to address")
        assertNull(spec.preallocatedSessionId)
        assertEquals(0, generated, "Resume must not mint a replacement synthetic id")
        assertEquals("/bin/fish", spec.cliPath)
    }

    @Test
    fun eventsCompletesWithoutEmittingAnything() = runBlocking {
        withTimeout(5_000) {
            val events = ShellAdapter(cwd = "/work", shell = "/bin/zsh").events.toList()
            assertEquals(emptyList(), events)
        }
    }
}
