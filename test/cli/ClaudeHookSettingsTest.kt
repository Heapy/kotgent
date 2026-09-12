package io.kotgent.cli

import io.kotgent.tmux.ProcessRunner
import io.kotgent.transport.readFileTextOrNull
import io.kotgent.transport.writePrivateFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ClaudeHookSettingsTest {
    @Test
    fun onlyAUserCommandStatusLineIsChainedWithoutChangingItsShellText() {
        val command = "printf '%s' '\$HOME `literal`'\n"
        assertEquals(command, operatorClaudeStatusLineCommand(settings(command)))
        for (invalid in listOf(null, "", "{", "[]", "{}", """{"statusLine":"cat"}""",
            """{"statusLine":{"type":"other","command":"cat"}}""",
            """{"statusLine":{"type":"command","command":123}}""",
            """{"statusLine":{"type":"command","command":"  "}}""")) {
            assertNull(operatorClaudeStatusLineCommand(invalid))
        }
    }

    @Test
    fun regeneratingLaunchSettingsPicksUpOperatorEditsWithoutOverwritingARotatedHeader() = runBlocking {
        withTimeout(20.seconds) {
            val temp = ProcessRunner.run(listOf("/usr/bin/mktemp", "-d", "/tmp/kotgent-claude-settings-XXXXXX"))
            assertEquals(0, temp.exitCode, temp.stderr)
            val directory = temp.stdout.trim()
            try {
                val operator = "$directory/operator.json"
                val input = "$directory/input.json"
                writePrivateFile(input, "{}\n\n".encodeToByteArray())
                writePrivateFile(operator, settings("printf 'first\\n'").encodeToByteArray())
                val _ = Commands.writeClaudeHookHeader("old-token", directory)
                val path = Commands.writeClaudeHookSettings(9000, directory, operator)
                assertEquals("first\n", executeStatusLine(path, input))

                val _ = Commands.writeClaudeHookHeader("new-token", directory)
                writePrivateFile(operator, settings("cat").encodeToByteArray())
                assertEquals(path, Commands.writeClaudeHookSettings(9000, directory, operator))
                assertEquals("{}\n\n", executeStatusLine(path, input), "the next launch carries the edited command")
                assertEquals("X-Kotgent-Hook-Token: new-token\n", readFileTextOrNull("$directory/claude-hook-header"))
                for (privatePath in listOf(path, "$directory/claude-hook-header")) {
                    val mode = ProcessRunner.run(listOf("/usr/bin/stat", "-f", "%Lp", privatePath))
                    assertEquals(0, mode.exitCode, mode.stderr)
                    assertEquals("600", mode.stdout.trim())
                }

                writePrivateFile(operator, "{}".encodeToByteArray())
                val _ = Commands.writeClaudeHookSettings(9000, directory, operator)
                assertEquals("", executeStatusLine(path, input), "removing the operator command leaves no stale output")
            } finally {
                val _ = ProcessRunner.run(listOf("/bin/rm", "-rf", directory))
            }
        }
    }

    private fun settings(command: String): String = buildJsonObject {
        putJsonObject("statusLine") {
            put("type", "command")
            put("command", command)
        }
    }.toString()

    private fun executeStatusLine(settingsPath: String, inputPath: String): String {
        val root = Json.parseToJsonElement(requireNotNull(readFileTextOrNull(settingsPath))).jsonObject
        val command = root.getValue("statusLine").jsonObject.getValue("command").jsonPrimitive.content
        val result = ProcessRunner.run(listOf("/bin/sh", "-c", "$command < ${ProcessRunner.shQuote(inputPath)}"))
        assertEquals(0, result.exitCode, result.stderr)
        return result.stdout
    }
}
