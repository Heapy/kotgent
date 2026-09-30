package io.kotgent.plan

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanValidationTest {

    private fun plan() = Plan(
        taskRef = "local:42",
        title = "Structured plans",
        sections = listOf(Section(kind = SectionKind.overview, body = "Overview")),
        decisions = listOf(Decision(title = "Keep it pure", body = "No host dependencies")),
        tasks = listOf(
            PlanTask(
                ordinal = 1,
                title = "Implement core",
                files = listOf(PlanFile("src/plan/Plan.kt", FileAction.create)),
                steps = listOf(Step(text = "Write tests")),
            ),
        ),
    )

    private fun paths(plan: Plan) = validatePlan(plan).map { it.path }.toSet()

    @Test
    fun aCompleteDocumentAndEmptyOptionalListsAreValid() {
        assertEquals(emptyList(), validatePlan(plan()))
        assertEquals(emptyList(), validatePlan(Plan(taskRef = "local:42", title = "Plan")))
        assertEquals(3, plan().concurrency)
    }

    @Test
    fun requiredFieldsReturnTheirOwnPathsTogether() {
        val document = plan().copy(
            taskRef = "invalid",
            title = " ",
            rev = -1,
            sections = listOf(Section(kind = SectionKind.overview, body = " ", rev = -1)),
            decisions = listOf(Decision(title = "", body = "")),
            tasks = listOf(
                PlanTask(
                    ordinal = 0,
                    title = "",
                    files = listOf(PlanFile(" ", FileAction.modify)),
                    steps = listOf(Step(text = "")),
                ),
            ),
        )
        assertEquals(
            setOf(
                "taskRef", "title", "rev", "sections[0].body", "sections[0].rev",
                "decisions[0].title", "decisions[0].body", "tasks[0].ordinal", "tasks[0].title",
                "tasks[0].files[0].path", "tasks[0].steps[0].text",
            ),
            paths(document),
        )
        assertTrue(validatePlan(document).all { it.message.isNotBlank() })
    }

    @Test
    fun suppliedBlockIdsHaveTheirOwnPrefixesAndAreUnique() {
        val document = plan().copy(
            sections = listOf(
                Section("s_shared", SectionKind.overview, "A"),
                Section("s_shared", SectionKind.context, "B"),
                Section("d_wrong", SectionKind.solution, "C"),
            ),
            decisions = listOf(Decision("d_", "Title", "Body")),
            tasks = listOf(
                PlanTask("t_one", 1, "First", steps = listOf(Step("st_same", "One"))),
                PlanTask("t_two", 2, "Second", steps = listOf(Step("st_same", "Two"))),
                PlanTask("st_wrong", 3, "Third"),
            ),
        )
        assertEquals(
            setOf(
                "sections[1].id", "sections[2].id", "decisions[0].id",
                "tasks[1].steps[0].id", "tasks[2].id",
            ),
            paths(document),
        )
    }

    @Test
    fun blockBodyBoundsCountUtf8BytesAndIncludeSteps() {
        val exact = "é".repeat(MAX_BLOCK_BODY_BYTES / 2)
        assertFalse("sections[0].body" in paths(plan().copy(
            sections = listOf(Section(kind = SectionKind.overview, body = exact)),
        )))
        val oversized = exact + "a"
        assertEquals(
            setOf("sections[0].body", "decisions[0].body", "tasks[0].steps[0].text"),
            paths(plan().copy(
                sections = listOf(Section(kind = SectionKind.overview, body = oversized)),
                decisions = listOf(Decision(title = "Title", body = oversized)),
                tasks = listOf(PlanTask(ordinal = 1, title = "Task", steps = listOf(Step(text = oversized)))),
            )),
        )
    }

    @Test
    fun documentBoundIncludesJsonEscapingAndMetadata() {
        val json = Json { encodeDefaults = true }
        val document = Plan(taskRef = "local:42", title = "x")
        val overhead = json.encodeToString(document).encodeToByteArray().size - 1
        val exact = document.copy(title = "a".repeat(MAX_PLAN_DOCUMENT_BYTES - overhead))
        assertEquals(emptyList(), validatePlan(exact))
        assertEquals(setOf("document"), paths(exact.copy(title = exact.title + "a")))
        assertEquals(setOf("document"), paths(document.copy(title = "\"".repeat(MAX_PLAN_DOCUMENT_BYTES / 2))))
    }

    @Test
    fun threadMessagesHaveRequiredAuthorsAndTheirOwnByteBound() {
        val exact = ThreadMessage(PlanActor.Operator, "é".repeat(MAX_THREAD_MESSAGE_BYTES / 2), 0)
        assertEquals(emptyList(), validateThreadMessage(exact))
        assertEquals(listOf("body"), validateThreadMessage(exact.copy(body = exact.body + "a")).map { it.path })
        assertEquals(
            setOf("author.sessionId", "body", "at"),
            validateThreadMessage(ThreadMessage(PlanActor.Session(""), " ", -1)).map { it.path }.toSet(),
        )
    }

    @Test
    fun threadsValidateTheirIdentityAndNestedMessages() {
        val thread = PlanThread("th_one", "st_one", ThreadKind.question, messages = listOf(
            ThreadMessage(PlanActor.Session("worker"), "Question", 1),
        ))
        assertEquals(emptyList(), validateThread(thread))
        assertEquals(
            setOf("id", "blockId", "messages[0].body"),
            validateThread(thread.copy(id = "wrong", blockId = "f_one", messages = listOf(
                ThreadMessage(PlanActor.Operator, "", 1),
            ))).map { it.path }.toSet(),
        )
        assertEquals(listOf("messages"), validateThread(thread.copy(messages = emptyList())).map { it.path })
    }

    @Test
    fun documentAndReviewShapesRoundTripThroughSerialization() {
        val document = plan().copy(
            status = PlanStatus.executing,
            featureBranch = "feature/plans",
            concurrency = 2,
            mode = ExecutionMode.autonomous,
        )
        assertEquals(document, Json.decodeFromString<Plan>(Json.encodeToString(document)))
        val state = PlanReviewState(
            viewMarks = listOf(ViewMark("s_one", 2)),
            threads = listOf(PlanThread("th_one", "s_one", ThreadKind.review, messages = listOf(
                ThreadMessage(PlanActor.Operator, "Question", 1),
            ))),
            edits = listOf(EditRecord("s_one", 1, 2, "Before", "After", PlanActor.Session("author"), 1)),
            rounds = listOf(ReviewRound(1, 1, 2, ReviewVerdict.changes)),
        )
        assertEquals(state, Json.decodeFromString<PlanReviewState>(Json.encodeToString(state)))
    }

    @Test
    fun aViewMarkMatchesBothBlockIdentityAndRevision() {
        val block = Section("s_one", SectionKind.overview, "Body", 2)
        assertTrue(isViewed(block, ViewMark("s_one", 2)))
        assertFalse(isViewed(block, ViewMark("s_one", 1)))
        assertFalse(isViewed(block, ViewMark("s_two", 2)))
        assertFalse(isViewed(block, null))
        assertFalse(isViewed(block.copy(id = null), ViewMark("s_one", 2)))
    }
}
