package io.kotgent.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The three task commands in the Web UI's ONE command registry (plan Task 27).
 *
 * `resources/webui/lib/commands.js` is the single registry of commands and leader mnemonics, so
 * "the board is reachable from the palette" is a source contract about that file and nothing else —
 * there is no JavaScript test harness, and a second list anywhere would be the defect this file exists
 * to catch. The assertions therefore read that module's source and check three separate things:
 *
 *  1. the three descriptors exist, carry a mnemonic, and delegate to the app-owned action;
 *  2. no mnemonic they claim is one another command already owns — `leaderKeyDown` resolves a letter
 *     first-match-wins, so a duplicate silently makes one visible grid row unreachable by the very key
 *     it displays;
 *  3. the registry stays a registry — the run bodies reach `actions.*` and never a route helper or the
 *     session list, which would make this file a second holder of app state.
 *
 * Reading the file from disk rather than over HTTP is deliberate: `WebUiServingTest` already proves
 * `/lib/commands.js` is served, and duplicating its sixty-line server harness here would give two
 * copies of a fixture to keep in step for no additional coverage.
 */
class WebUiTaskCommandsTest {

    /** id → chord, title, and the app-owned callback the descriptor is required to call. */
    private val taskCommands = listOf(
        TaskCommand("general.task-board", "o", "Open the task board", "actions.openBoard()"),
        TaskCommand("general.new-task", "w", "New task", "actions.newTask()"),
        TaskCommand("session.open-task", "j", "Open this session's task", "actions.openSessionTask()"),
    )

    private class TaskCommand(val id: String, val chord: String, val title: String, val action: String)

    @Test
    fun theBoardAndItsTasksAreReachableFromTheOneCommandRegistry() {
        val commands = webUiSource("lib/commands.js")
        for (command in taskCommands) {
            assertTrue(
                Regex("""id: "${command.id}", group: "\w+", chord: "${command.chord}"""")
                    .containsMatchIn(commands),
                "${command.id} is declared in the registry with the '${command.chord}' leader mnemonic",
            )
            val descriptor = descriptorOf(commands, command.id)
            assertTrue(
                descriptor.contains("title: \"${command.title}\""),
                "${command.id} is titled '${command.title}' in the palette and the leader grid",
            )
            // The grid renders `chord`, the search list renders `hint`; a disagreement between them
            // teaches the operator a keystroke that does nothing.
            assertTrue(
                descriptor.contains("hint: \"⌘K ${command.chord}\""),
                "${command.id}'s search hint spells the same chord the leader grid draws",
            )
            assertTrue(
                descriptor.contains("run: () => ${command.action}"),
                "${command.id} delegates to the app-owned ${command.action}",
            )
        }
    }

    @Test
    fun noTaskMnemonicShadowsACommandThatAlreadyOwnsThatLetter() {
        val commands = webUiSource("lib/commands.js")
        // Whatever the registry actually says, in either quote style and with any spacing: a spelling
        // this extraction cannot see is a chord this test cannot police.
        val declared = Regex("""chord\s*:\s*(["'])(.*?)\1""")
            .findAll(commands)
            .map { it.groupValues[2] }
            .toList()
        assertTrue(declared.isNotEmpty(), "the registry declares leader mnemonics at all")

        for (command in taskCommands) {
            // Case-INSENSITIVE, because the runtime key is `"Key" + chord.toUpperCase()`: an "O" beside
            // an "o" both resolve to KeyO and shadow each other.
            val claims = declared.count { it.equals(command.chord, ignoreCase = true) }
            assertEquals(
                1,
                claims,
                "'${command.chord}' is claimed once — ${command.id} would otherwise shadow, or be " +
                    "shadowed by, another visible grid row",
            )
            // `leaderKeyDown` answers K with `onModeChange("search")` before it consults the registry,
            // so a task command lettered 'k' would be permanently unreachable by its own key.
            assertFalse(
                command.chord.equals("k", ignoreCase = true),
                "${command.id} leaves 'k' to the leader grid's own way back to search (⌘K K)",
            )
            assertTrue(
                command.chord.length == 1 &&
                    (command.chord[0] in 'a'..'z' || command.chord[0] in 'A'..'Z'),
                "'${command.chord}' is one ASCII letter, or its \"Key\" + chord code is unreachable",
            )
        }
        // The three are distinct from each other too, which the per-chord count above would miss only if
        // two of them shared a letter AND that letter appeared once — impossible, but stated so the
        // intent survives a future edit of the extraction.
        val taskChords = taskCommands.map { it.chord.lowercase() }
        assertEquals(taskChords.size, taskChords.toSet().size, "the three task mnemonics are distinct")
    }

    @Test
    fun openingThisSessionsTaskIsRefusedForASessionThatCarriesNoTask() {
        val commands = webUiSource("lib/commands.js")
        val sessionTask = descriptorOf(commands, "session.open-task")
        assertTrue(
            sessionTask.contains("disabled: disabledWhenNoSessionTask(activeSession)"),
            "the session task command is refused on exactly the condition that makes it a no-op",
        )
        val helper = sliceBetween(
            commands,
            "function disabledWhenNoSessionTask(session) {",
            "\n}",
            "the disabledWhenNoSessionTask helper",
        )
        assertTrue(
            helper.contains("if (!session) return \"no session is selected\";") &&
                helper.contains("return session.taskRef ? null : "),
            "the reason is read off the session's own taskRef, the field the /events rows already carry",
        )
        // Liveness is deliberately NOT part of it: a stopped or archived session still points at the task
        // it was working on, and reading that task is exactly what happens after the agent finished.
        assertFalse(
            helper.contains("isAliveState"),
            "a finished session still names its task, so the command must not require a live pane",
        )
        // The board and the create form need no session at all — the board owns the project selector.
        for (id in listOf("general.task-board", "general.new-task")) {
            assertTrue(
                descriptorOf(commands, id).contains("disabled: null"),
                "$id is always available: it names a project, not a session",
            )
        }
    }

    @Test
    fun theRegistryStaysARegistryAndNeverHoldsRoutesOrTheSessionList() {
        val commands = webUiSource("lib/commands.js")
        val forbidden = listOf("routePath(", "taskPath(", "sessionPath(", "navigate(", "history.", "location.")
        for (needle in forbidden) {
            assertFalse(
                commands.contains(needle),
                "the registry reaches app-owned actions, never '$needle' — routing is app.js's state",
            )
        }
        // The other end of that contract: the three callbacks really are in app.js's one `actions` object.
        val app = webUiSource("app.js")
        for (key in listOf("openBoard: openBoard", "newTask: newTask", "openSessionTask: openSessionTask")) {
            assertTrue(app.contains(key), "app.js supplies '$key' to buildCommands")
        }
    }

    // --- harness -------------------------------------------------------------------------------------

    /**
     * Read a file out of `resources/webui`. `./kotlin test` runs from the module root, but the walk up
     * keeps the assertion from depending on where the runner happened to start.
     */
    private fun webUiSource(relative: String): String {
        var prefix = ""
        repeat(7) {
            val text = readFileTextOrNull(prefix + "resources/webui/" + relative)
            if (text != null) return text
            prefix += "../"
        }
        fail("cannot locate resources/webui/$relative from the test working directory")
    }

    private fun descriptorOf(commands: String, id: String): String =
        sliceBetween(commands, "id: \"$id\"", "\n    },", "the $id descriptor")

    private fun sliceBetween(source: String, open: String, close: String, what: String): String {
        val start = source.indexOf(open)
        assertTrue(start >= 0, "$what is present in the source")
        val end = source.indexOf(close, start + open.length)
        assertTrue(end > start, "$what is terminated")
        return source.substring(start, end)
    }
}
