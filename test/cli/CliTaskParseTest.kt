package io.kotgent.cli

import io.kotgent.core.TaskRef
import io.kotgent.task.MoveTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The `task` / `project` argv grammar (plan Task 19) — the pure half of the backlog CLI.
 *
 * Everything here goes through [parseArgs], which is a pure `argv → CliCommand` mapping with exactly one
 * seam: the `-m -` stdin reader, injected as the second parameter so a piped message is testable without
 * a pipe. No test in this file performs I/O, starts a daemon, or reaches [TaskCommands] (Task 21 owns the
 * execution side, and its bodies are still stubs here).
 *
 * What is asserted, and why each group exists:
 *  - **every subcommand's happy parse**, because the dispatch table is the contract between this file and
 *    `TaskCliCommands.kt`, and a subcommand wired to the wrong variant compiles perfectly;
 *  - **the missing-argument errors**, because these commands are run unattended by an AGENT: a swallowed
 *    flag value would comment on the wrong task or start a session linked to nothing;
 *  - **the optional-ref forms**, which are what makes a ref-less `show`/`comment`/`review`/`done`/`unlink`
 *    resolve through `/whoami` instead of failing;
 *  - **the four flags** — `--task` on `start`, `--session` everywhere, `--project` on `add`/`list`/`next`,
 *    and `-m/--message` with its `-` stdin convention;
 *  - **an unknown subcommand**, which must name the alternatives rather than just refusing.
 */
class CliTaskParseTest {

    /** A reader that fails the test if the `-m -` path consults stdin when it should not. */
    private val neverReadsStdin: () -> String = { fail("stdin must not be read for this argv") }

    private fun invalidMessage(args: List<String>, stdin: () -> String = neverReadsStdin): String {
        val parsed = parseArgs(args, stdin)
        val invalid = assertIs<CliCommand.Invalid>(parsed, "expected a usage error for $args, got $parsed")
        return invalid.message
    }

    // ---- 1. happy parses, one per subcommand ------------------------------------------------------

    @Test
    fun parsesEveryTaskSubcommandsHappyForm() {
        assertEquals(
            TaskAdd("Ship the board", null, null, null),
            parseArgs(listOf("task", "add", "Ship the board"), neverReadsStdin),
        )
        assertEquals(TaskList(null, null), parseArgs(listOf("task", "list"), neverReadsStdin))
        assertEquals(TaskShow("local:42", null), parseArgs(listOf("task", "show", "local:42"), neverReadsStdin))
        assertEquals(TaskNext(null, null), parseArgs(listOf("task", "next"), neverReadsStdin))
        assertEquals(TaskClaim("local:42", null), parseArgs(listOf("task", "claim", "local:42"), neverReadsStdin))
        assertEquals(
            TaskComment("local:42", "looks good", null),
            parseArgs(listOf("task", "comment", "local:42", "-m", "looks good"), neverReadsStdin),
        )
        assertEquals(
            TaskReview("local:42", "ready", null),
            parseArgs(listOf("task", "review", "local:42", "-m", "ready"), neverReadsStdin),
        )
        assertEquals(
            TaskDone("local:42", null, null),
            parseArgs(listOf("task", "done", "local:42"), neverReadsStdin),
        )
        assertEquals(TaskUnlink("local:42", null), parseArgs(listOf("task", "unlink", "local:42"), neverReadsStdin))
        assertEquals(
            TaskMove("local:42", MoveTarget.Top, null),
            parseArgs(listOf("task", "move", "local:42", "--top"), neverReadsStdin),
        )
        assertEquals(
            TaskDep("local:42", "local:7", remove = false, session = null),
            parseArgs(listOf("task", "dep", "add", "local:42", "--on", "local:7"), neverReadsStdin),
        )
        assertEquals(TaskDelete("local:42", null), parseArgs(listOf("task", "delete", "local:42"), neverReadsStdin))
    }

    @Test
    fun parsesTaskAddWithABody() {
        assertEquals(
            TaskAdd("Title", "the long form", null, null),
            parseArgs(listOf("task", "add", "Title", "--body", "the long form"), neverReadsStdin),
        )
    }

    @Test
    fun parsesEveryMoveTarget() {
        val ref = TaskRef("local:7")
        assertEquals(
            TaskMove("local:42", MoveTarget.Bottom, null),
            parseArgs(listOf("task", "move", "local:42", "--bottom"), neverReadsStdin),
        )
        assertEquals(
            TaskMove("local:42", MoveTarget.Before(ref), null),
            parseArgs(listOf("task", "move", "local:42", "--before", "local:7"), neverReadsStdin),
        )
        assertEquals(
            TaskMove("local:42", MoveTarget.After(ref), null),
            parseArgs(listOf("task", "move", "local:42", "--after", "local:7"), neverReadsStdin),
        )
    }

    /** `rm` is the only spelling of the removing half — `add|rm`, per the command's own KDoc. */
    @Test
    fun parsesBothDependencyActions() {
        assertEquals(
            TaskDep("local:42", "local:7", remove = true, session = null),
            parseArgs(listOf("task", "dep", "rm", "local:42", "--on", "local:7"), neverReadsStdin),
        )
        assertEquals(
            "task dep: unknown action 'remove' (use: add | rm)",
            invalidMessage(listOf("task", "dep", "remove", "local:42", "--on", "local:7")),
        )
    }

    @Test
    fun parsesBothProjectSubcommands() {
        assertEquals(ProjectList, parseArgs(listOf("project", "list"), neverReadsStdin))
        assertEquals(ProjectInit(null, null), parseArgs(listOf("project", "init"), neverReadsStdin))
        assertEquals(
            ProjectInit("/repo", "kotgent"),
            parseArgs(listOf("project", "init", "/repo", "--name", "kotgent"), neverReadsStdin),
        )
    }

    // ---- 2. the optional-ref forms ----------------------------------------------------------------

    /**
     * A ref-less `show`/`comment`/`review`/`done`/`unlink` is the AGENT's form: it knows its pane and
     * nothing else, so a null ref here is what makes the CLI resolve the subject through `GET /whoami`.
     * If any of these parsed as an error the agent-side workflow would not exist at all.
     */
    @Test
    fun theFiveOptionalRefSubcommandsParseWithNoRef() {
        assertEquals(TaskShow(null, null), parseArgs(listOf("task", "show"), neverReadsStdin))
        assertEquals(TaskUnlink(null, null), parseArgs(listOf("task", "unlink"), neverReadsStdin))
        assertEquals(TaskReview(null, null, null), parseArgs(listOf("task", "review"), neverReadsStdin))
        assertEquals(TaskDone(null, null, null), parseArgs(listOf("task", "done"), neverReadsStdin))
        assertEquals(
            TaskComment(null, "note", null),
            parseArgs(listOf("task", "comment", "-m", "note"), neverReadsStdin),
        )
    }

    /** The optional ref is still VALIDATED when given: a typo must not travel to the daemon as a 400. */
    @Test
    fun anOptionalRefIsStillCheckedAgainstTheRefGrammar() {
        val message = invalidMessage(listOf("task", "show", "local-42"))
        assertTrue("not a task ref" in message, message)
        assertTrue("local:42" in message, "the error shows the shape it wanted: $message")
    }

    /**
     * The mandatory `:` is what keeps a bare word from ever parsing as a ref — the same property that
     * keeps `POST /api/v1/tasks/claim` from being shadowed by `/tasks/{ref}/…` on the server. Pinned here
     * because the CLI is the other end of it: `task show claim` must be a ref error, not a subcommand.
     */
    @Test
    fun aBareWordIsNeverARef() {
        assertNull(TaskRef.parseOrNull("claim"))
        assertTrue("not a task ref" in invalidMessage(listOf("task", "show", "claim")))
    }

    // ---- 3. the flags -----------------------------------------------------------------------------

    @Test
    fun sessionIsAcceptedOnEveryTaskSubcommand() {
        val s = "sess-1"
        assertEquals(TaskAdd("T", null, null, s), parseArgs(listOf("task", "add", "T", "--session", s), neverReadsStdin))
        assertEquals(TaskList(null, s), parseArgs(listOf("task", "list", "--session", s), neverReadsStdin))
        assertEquals(TaskShow(null, s), parseArgs(listOf("task", "show", "--session", s), neverReadsStdin))
        assertEquals(TaskNext(null, s), parseArgs(listOf("task", "next", "--session", s), neverReadsStdin))
        assertEquals(
            TaskClaim("local:1", s),
            parseArgs(listOf("task", "claim", "local:1", "--session", s), neverReadsStdin),
        )
        assertEquals(
            TaskComment(null, "n", s),
            parseArgs(listOf("task", "comment", "-m", "n", "--session", s), neverReadsStdin),
        )
        assertEquals(TaskReview(null, null, s), parseArgs(listOf("task", "review", "--session", s), neverReadsStdin))
        assertEquals(TaskDone(null, null, s), parseArgs(listOf("task", "done", "--session", s), neverReadsStdin))
        assertEquals(TaskUnlink(null, s), parseArgs(listOf("task", "unlink", "--session", s), neverReadsStdin))
        assertEquals(
            TaskMove("local:1", MoveTarget.Top, s),
            parseArgs(listOf("task", "move", "local:1", "--top", "--session", s), neverReadsStdin),
        )
        assertEquals(
            TaskDep("local:1", "local:2", remove = false, session = s),
            parseArgs(listOf("task", "dep", "add", "local:1", "--on", "local:2", "--session", s), neverReadsStdin),
        )
        assertEquals(
            TaskDelete("local:1", s),
            parseArgs(listOf("task", "delete", "local:1", "--session", s), neverReadsStdin),
        )
    }

    @Test
    fun projectIsAcceptedOnAddListAndNext() {
        val p = "0f4e2b1c-9a8d-4c7e-b6f5-1234567890ab"
        assertEquals(TaskAdd("T", null, p, null), parseArgs(listOf("task", "add", "T", "--project", p), neverReadsStdin))
        assertEquals(TaskList(p, null), parseArgs(listOf("task", "list", "--project", p), neverReadsStdin))
        assertEquals(TaskNext(p, null), parseArgs(listOf("task", "next", "--project", p), neverReadsStdin))
    }

    /**
     * `--project` is not one of the flags `show`/`claim`/`move`/… take, and an unknown flag is refused
     * rather than ignored: a `task show --project X` that quietly dropped the flag would look like it
     * had scoped the lookup.
     */
    @Test
    fun projectIsRejectedWhereItHasNoMeaning() {
        assertEquals(
            "task show: unknown flag '--project'",
            invalidMessage(listOf("task", "show", "--project", "0f4e2b1c-9a8d-4c7e-b6f5-1234567890ab")),
        )
    }

    /** A `--project` that is not a canonical uuid is a usage error here, not a daemon round trip. */
    @Test
    fun aMalformedProjectIdIsRefusedAtParseTime() {
        val message = invalidMessage(listOf("task", "list", "--project", "kotgent"))
        assertTrue("not a project id" in message, message)
    }

    @Test
    fun startAcceptsATaskRefAndKeepsTheRestOfItsGrammar() {
        assertEquals(
            CliCommand.Start("claude", "/tmp/p", "n", listOf("a"), "local:42"),
            parseArgs(
                listOf("start", "claude", "/tmp/p", "--name", "n", "--tag", "a", "--task", "local:42"),
                neverReadsStdin,
            ),
        )
        assertEquals(
            CliCommand.Start("claude", null, null, emptyList(), null),
            parseArgs(listOf("start", "claude"), neverReadsStdin),
            "a start without --task still parses to a null task",
        )
    }

    /**
     * A swallowed `--task` value would start the session and silently NOT link it — the launch succeeds
     * and the one thing the flag was for is missing, with nothing anywhere saying so. Both a missing
     * value and a malformed ref are therefore refused before anything is started.
     */
    @Test
    fun startRefusesATaskFlagItCannotHonour() {
        assertEquals("start: --task requires a task ref", invalidMessage(listOf("start", "claude", "--task")))
        assertEquals(
            "start: --task requires a task ref",
            invalidMessage(listOf("start", "claude", "--task", "--name", "n")),
        )
        assertTrue("not a task ref" in invalidMessage(listOf("start", "claude", "--task", "nope")))
    }

    // ---- 4. -m / --message, and its `-` stdin convention ------------------------------------------

    @Test
    fun bothMessageSpellingsMeanTheSameFlag() {
        assertEquals(
            TaskComment(null, "hello", null),
            parseArgs(listOf("task", "comment", "-m", "hello"), neverReadsStdin),
        )
        assertEquals(
            TaskComment(null, "hello", null),
            parseArgs(listOf("task", "comment", "--message", "hello"), neverReadsStdin),
        )
    }

    /**
     * `-m -` reads stdin, and only TRAILING whitespace is stripped: that is the newline a pipe or a
     * heredoc adds, while the leading bytes are the operator's own content.
     */
    @Test
    fun aBareDashReadsTheMessageFromStdin() {
        assertEquals(
            TaskComment("local:42", "line one\nline two", null),
            parseArgs(listOf("task", "comment", "local:42", "-m", "-")) { "line one\nline two\n" },
        )
        assertEquals(
            TaskReview(null, "  indented first line", null),
            parseArgs(listOf("task", "review", "-m", "-")) { "  indented first line\n\n" },
        )
        assertEquals(
            TaskDone(null, "closed", null),
            parseArgs(listOf("task", "done", "-m", "-")) { "closed" },
        )
    }

    /** An empty pipe is the same usage error as a missing `-m` value: the operator asked to say something. */
    @Test
    fun anEmptyPipeIsAUsageErrorRatherThanAnEmptyComment() {
        assertEquals(
            "task comment: '-m -' read an empty message from stdin",
            invalidMessage(listOf("task", "comment", "-m", "-")) { "\n  \n" },
        )
        assertEquals(
            "task review: '-m -' read an empty message from stdin",
            invalidMessage(listOf("task", "review", "-m", "-")) { "" },
        )
    }

    /** Only `-m -` may consult stdin — an ordinary message must never block on a terminal. */
    @Test
    fun anOrdinaryMessageNeverTouchesStdin() {
        assertEquals(
            TaskComment(null, "typed", null),
            parseArgs(listOf("task", "comment", "-m", "typed"), neverReadsStdin),
        )
    }

    @Test
    fun commentRequiresAMessageButReviewAndDoneDoNot() {
        val message = invalidMessage(listOf("task", "comment", "local:42"))
        assertTrue("requires a message" in message, message)
        assertTrue("-m -" in message, "the error advertises the stdin convention: $message")
        assertEquals(TaskReview(null, null, null), parseArgs(listOf("task", "review"), neverReadsStdin))
        assertEquals(TaskDone(null, null, null), parseArgs(listOf("task", "done"), neverReadsStdin))
    }

    // ---- 5. missing-argument and other usage errors -----------------------------------------------

    @Test
    fun everySubcommandThatNeedsAnArgumentSaysSo() {
        assertTrue("requires a title" in invalidMessage(listOf("task", "add")))
        assertTrue("requires a task ref" in invalidMessage(listOf("task", "claim")))
        assertTrue("requires a task ref" in invalidMessage(listOf("task", "delete")))
        assertTrue("requires a task ref" in invalidMessage(listOf("task", "move", "--top")))
        assertTrue("requires an action" in invalidMessage(listOf("task", "dep")))
        assertTrue("requires a task ref" in invalidMessage(listOf("task", "dep", "add")))
        assertTrue("requires --on" in invalidMessage(listOf("task", "dep", "add", "local:1")))
    }

    /**
     * A value flag needs a REAL value: present, non-blank, and not itself a `--` flag. The last of the
     * three is the dangerous one — `--body --session s` would file the literal text "--session" as the
     * task's body AND drop the session the operator named.
     */
    @Test
    fun everyValueFlagRefusesAMissingBlankOrFlagShapedValue() {
        assertEquals("task add: --body requires a value", invalidMessage(listOf("task", "add", "T", "--body")))
        assertEquals(
            "task add: --body requires a value",
            invalidMessage(listOf("task", "add", "T", "--body", "--session", "s")),
        )
        assertEquals("task add: --body requires a value", invalidMessage(listOf("task", "add", "T", "--body", "   ")))
        assertEquals("task list: --session requires a value", invalidMessage(listOf("task", "list", "--session")))
        assertEquals("task comment: -m requires a value", invalidMessage(listOf("task", "comment", "-m")))
        assertEquals(
            "task move: --before requires a value",
            invalidMessage(listOf("task", "move", "local:1", "--before")),
        )
        assertEquals(
            "task dep: --on requires a value",
            invalidMessage(listOf("task", "dep", "add", "local:1", "--on")),
        )
    }

    /** Nothing in this family is repeatable, so a second one is a mistake — and last-wins would hide it. */
    @Test
    fun aRepeatedValueFlagIsAnErrorRatherThanLastWins() {
        assertEquals(
            "task list: --session was given more than once",
            invalidMessage(listOf("task", "list", "--session", "a", "--session", "b")),
        )
        assertEquals(
            "task comment: --message was given more than once",
            invalidMessage(listOf("task", "comment", "-m", "a", "--message", "b")),
            "the two spellings fold onto one canonical flag before the repeat is judged",
        )
    }

    /** A move with no target, or with two, is refused: neither has an answer the daemon could invent. */
    @Test
    fun aMoveNeedsExactlyOneTarget() {
        assertTrue("requires a target" in invalidMessage(listOf("task", "move", "local:1")))
        val both = invalidMessage(listOf("task", "move", "local:1", "--top", "--bottom"))
        assertTrue("exactly one" in both, both)
        assertTrue("--top" in both && "--bottom" in both, "the error names what it got: $both")
        assertTrue(
            "exactly one" in invalidMessage(listOf("task", "move", "local:1", "--top", "--after", "local:2")),
        )
    }

    /** A `--before`/`--after` neighbour is a ref too, and is held to the same grammar. */
    @Test
    fun aMoveNeighbourMustAlsoBeARef() {
        assertTrue("not a task ref" in invalidMessage(listOf("task", "move", "local:1", "--before", "seven")))
    }

    /** A typo'd single-dash flag must never be filed as a positional (a task title, a ref, a path). */
    @Test
    fun anUnknownFlagIsRefusedIncludingSingleDashSpellings() {
        assertEquals("task add: unknown flag '-s'", invalidMessage(listOf("task", "add", "T", "-s", "sess")))
        assertEquals("task list: unknown flag '--all'", invalidMessage(listOf("task", "list", "--all")))
        assertEquals("project list: unknown flag '--name'", invalidMessage(listOf("project", "list", "--name", "x")))
    }

    /** `--` is the escape hatch for the one real case: content that starts with a dash. */
    @Test
    fun endOfFlagsLetsATitleStartWithADash() {
        assertEquals(
            TaskAdd("-fix the leading dash", null, null, null),
            parseArgs(listOf("task", "add", "--", "-fix the leading dash"), neverReadsStdin),
        )
    }

    @Test
    fun anExtraPositionalIsRefusedRatherThanDropped() {
        assertTrue(
            "unexpected argument 'extra'" in invalidMessage(listOf("task", "add", "Title", "extra")),
            "an unquoted multi-word title is a mistake, not a silent truncation",
        )
        assertTrue("unexpected argument" in invalidMessage(listOf("task", "show", "local:1", "local:2")))
        assertTrue("unexpected argument" in invalidMessage(listOf("task", "list", "local:1")))
        assertTrue("unexpected argument" in invalidMessage(listOf("project", "list", "here")))
        assertTrue("unexpected argument" in invalidMessage(listOf("project", "init", "/a", "/b")))
    }

    // ---- 6. unknown and missing subcommands -------------------------------------------------------

    @Test
    fun anUnknownTaskSubcommandNamesTheAlternatives() {
        val message = invalidMessage(listOf("task", "finish", "local:42"))
        assertTrue("unknown subcommand 'finish'" in message, message)
        for (sub in listOf("add", "list", "show", "next", "claim", "comment", "review", "done", "unlink", "move", "dep", "delete")) {
            assertTrue(sub in message, "the error offers '$sub': $message")
        }
    }

    @Test
    fun anUnknownProjectSubcommandNamesTheAlternatives() {
        val message = invalidMessage(listOf("project", "create"))
        assertTrue("unknown subcommand 'create'" in message, message)
        assertTrue("list" in message && "init" in message, message)
    }

    @Test
    fun abareTaskOrProjectAsksForASubcommand() {
        assertTrue("requires a subcommand" in invalidMessage(listOf("task")))
        assertTrue("requires a subcommand" in invalidMessage(listOf("project")))
    }

    // ---- 7. the usage text advertises the family --------------------------------------------------

    @Test
    fun theUsageNamesTheTaskAndProjectFamilies() {
        for (line in listOf("task add", "task list", "task show", "task next", "task claim", "task comment",
            "task review", "task done", "task unlink", "task move", "task dep", "task delete",
            "project list", "project init", "--task")) {
            assertTrue(line in USAGE, "the usage mentions '$line': $USAGE")
        }
    }
}
