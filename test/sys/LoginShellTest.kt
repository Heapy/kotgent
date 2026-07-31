package io.kotgent.sys

import platform.posix.X_OK
import platform.posix.access
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoginShellTest {

    private data class Case(
        val name: String,
        val shellEnv: String?,
        val pwShell: String?,
        val executable: Set<String>,
        val expected: String,
    )

    @Test
    fun resolvesTheFirstAbsoluteExecutableCandidate() {
        val cases = listOf(
            Case("both present", "/env/shell", "/passwd/shell", setOf("/env/shell", "/passwd/shell"), "/env/shell"),
            Case("only env", "/env/shell", null, setOf("/env/shell"), "/env/shell"),
            Case("only passwd", null, "/passwd/shell", setOf("/passwd/shell"), "/passwd/shell"),
            Case("neither", null, null, emptySet(), DEFAULT_LOGIN_SHELL),
            Case("blank env", "  ", "/passwd/shell", setOf("/passwd/shell"), "/passwd/shell"),
            Case("relative env", "bin/fish", "/passwd/shell", setOf("bin/fish", "/passwd/shell"), "/passwd/shell"),
            Case("relative passwd", null, "bin/zsh", setOf("bin/zsh"), DEFAULT_LOGIN_SHELL),
            Case("stale env", "/gone/fish", "/passwd/shell", setOf("/passwd/shell"), "/passwd/shell"),
            Case("stale passwd", "/gone/fish", "/gone/zsh", emptySet(), DEFAULT_LOGIN_SHELL),
        )

        cases.forEach { case ->
            assertEquals(
                case.expected,
                resolveLoginShell(case.shellEnv, case.pwShell, case.executable::contains),
                case.name,
            )
        }
    }

    @Test
    fun currentLoginShellIsAbsoluteAndExecutableOnThisHost() {
        val shell = currentLoginShell()
        assertTrue(shell.startsWith('/'), "resolved shell must be absolute: $shell")
        assertEquals(0, access(shell, X_OK), "resolved shell must be executable: $shell")
    }
}
