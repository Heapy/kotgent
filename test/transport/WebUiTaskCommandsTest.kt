package io.kotgent.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The task commands in the Web UI's ONE command registry (plan Task 27), and the screen-awareness the
 * board forced on it: `/tasks` replaces the session view, so which commands EXIST depends on which
 * screen the router has put on.
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

    /**
     * id → chord, the titles it can carry, and the app-owned callbacks it is required to reach.
     *
     * Two of the three carry one of each. `general.task-board` carries two, because it is ONE mnemonic
     * for "the other screen": the palette is screen-aware, and on the board `o` leads out rather than
     * spending the letter on a navigation that has already happened. Both arms are listed here so a
     * future edit cannot quietly drop one and leave the board with no palette way back.
     */
    private val taskCommands = listOf(
        TaskCommand(
            "general.task-board", "o",
            listOf("Open the task board", "Back to sessions"),
            listOf("actions.openBoard()", "actions.openSessions()"),
        ),
        TaskCommand("general.new-task", "w", listOf("New task"), listOf("actions.newTask()")),
        TaskCommand(
            "session.open-task", "j",
            listOf("Open this session's task"),
            listOf("actions.openSessionTask()"),
        ),
    )

    private class TaskCommand(
        val id: String,
        val chord: String,
        val titles: List<String>,
        val actions: List<String>,
    )

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
            for (title in command.titles) {
                assertTrue(
                    descriptor.contains("\"$title\""),
                    "${command.id} is titled '$title' in the palette and the leader grid",
                )
            }
            // The grid renders `chord`, the search list renders `hint`; a disagreement between them
            // teaches the operator a keystroke that does nothing.
            assertTrue(
                descriptor.contains("hint: \"⌘K ${command.chord}\""),
                "${command.id}'s search hint spells the same chord the leader grid draws",
            )
            assertTrue(descriptor.contains("run: () => "), "${command.id} runs something at all")
            for (action in command.actions) {
                assertTrue(
                    descriptor.contains(action),
                    "${command.id} delegates to the app-owned $action",
                )
            }
        }
    }

    /**
     * `/tasks` REPLACES the session view, so the palette has to answer for two screens.
     *
     * While the board was up the registry still offered nine commands aimed at a session nobody could
     * see: ⌘K a wrote `attachedId` with no `TerminalPane` mounted (visibly nothing at all happened),
     * ⌘K e announced a detach from a terminal that was not on screen, and Interrupt/Stop/Done acted on
     * whatever row was selected before the operator left for the backlog. The group is therefore BUILT
     * only for the session view, rather than each descriptor growing a disabled reason — a disabled row
     * is for a command that could apply here and does not right now, and none of these applies at all.
     */
    @Test
    fun theSessionGroupIsBuiltOnlyForTheScreenThatShowsASession() {
        val commands = webUiSource("lib/commands.js")
        assertTrue(
            commands.contains(
                "...(onBoard ? [] : sessionCommands(activeSession, attachedId, pendingAction, actions)),",
            ),
            "the session group is composed in only for the session view",
        )
        assertTrue(
            commands.contains("onBoard = false, actions,"),
            "…and a caller that says nothing gets the session view, the app's own default screen",
        )
        val session = sliceBetween(
            commands,
            "function sessionCommands(activeSession, attachedId, pendingAction, actions) {",
            "\n}",
            "the sessionCommands builder",
        )
        val general = sliceBetween(commands, "function generalCommands(onBoard, actions) {", "\n}",
            "the generalCommands builder")
        for (id in listOf(
            "session.interrupt", "session.resume", "session.attach", "session.detach", "session.stop",
            "session.done", "session.copy-tmux", "session.upload-files", "session.open-task",
        )) {
            assertTrue(session.contains("id: \"$id\""), "$id is built with the session group")
            assertFalse(general.contains("id: \"$id\""), "$id is not smuggled into the always-on group")
        }
        // The sidebar this one toggles is exactly what the board screen unmounts, so on the board it is
        // a command with nothing to act on. Filtered rather than conditionally spread: the descriptors
        // are read as TEXT from here, and moving one under an arm changes where its slice ends.
        assertTrue(
            commands.contains(
                "return onBoard ? commands.filter((command) => command.id !== \"general.show-done\") " +
                    ": commands;",
            ),
            "the sidebar-only toggle is dropped on the board, where there is no sidebar",
        )
        // Session ROWS stay on both screens: selecting one navigates to /s/{id}, so on the board the
        // search view is also the way back to a particular session.
        assertTrue(
            commands.contains("...sessionRows(sessions, actions),"),
            "the session rows are unconditional — they are navigation, not session controls",
        )

        val app = webUiSource("app.js")
        assertTrue(app.contains("onBoard: onBoard,"), "app.js tells the registry which screen is on")
        // The route is app state, and answering it here is what keeps the registry free of routing.
        val openSessions = sliceBetween(
            app,
            "const openSessions = useCallback(() => {",
            "\n  }, []);",
            "the openSessions action",
        )
        assertTrue(
            openSessions.contains("const id = activeRef.current;") &&
                openSessions.contains("navigate(routePath({ screen: SCREEN_SESSIONS, id: id }));"),
            "leaving the board names the selected session in the URL, and falls back to / with none",
        )
        assertFalse(
            openSessions.contains("showSession("),
            "the selection is unchanged, so it must not re-run the selection path and its attachment",
        )
    }

    /**
     * The board's own second form, reachable from the palette. It is chordless on purpose — the board
     * draws a "New project" button, and the leader grid is the small set worth memorising — so it lives
     * in the search list, and its wiring is the same one-shot counter "New task" already uses.
     */
    @Test
    fun theBoardsNewProjectFormIsReachableFromTheSearchList() {
        val commands = webUiSource("lib/commands.js")
        val descriptor = descriptorOf(commands, "general.new-project")
        assertTrue(
            descriptor.contains("chord: null") && descriptor.contains("hint: null"),
            "it claims no mnemonic, so it never grows the leader grid",
        )
        assertTrue(
            descriptor.contains("title: \"New project\"") &&
                descriptor.contains("run: () => actions.newProject()"),
            "it is titled for search and delegates to the app-owned action",
        )
        val app = webUiSource("app.js")
        assertTrue(
            app.contains("const [newProjectRequest, setNewProjectRequest] = useState(0);") &&
                app.contains("setNewProjectRequest((n) => n + 1);") &&
                app.contains("newProjectRequest=\${newProjectRequest}"),
            "the app bumps a one-shot counter and hands it to the board",
        )
        val board = webUiSource("components/Board.js")
        assertTrue(
            board.contains("const servedProjectRequestRef = useRef(0);") &&
                board.contains("if (newProjectRequest === servedProjectRequestRef.current) return;") &&
                board.contains("servedProjectRequestRef.current = newProjectRequest;"),
            "the board serves it with a ref of its own — `form` holds one value, so one counter cannot " +
                "say which form was asked for",
        )
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
        // The other end of that contract: the callbacks really are in app.js's one `actions` object.
        val app = webUiSource("app.js")
        for (key in listOf(
            "openBoard: openBoard", "openSessions: openSessions", "newTask: newTask",
            "newProject: newProject", "openSessionTask: openSessionTask",
        )) {
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
