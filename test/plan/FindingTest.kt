package io.kotgent.plan

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindingTest {

    private fun finding() = Finding(
        id = "f_one",
        taskId = "t_one",
        location = "src/plan/Plan.kt:1",
        condition = "A stale document overwrites newer content",
        impact = "Operator changes are lost",
        danger = FindingLevel.high,
        likelihood = FindingLevel.medium,
        options = listOf(FindingOption("Reject stale writes", "Newer content is preserved", FindingLevel.low, FindingLevel.high)),
        recommended = 0,
    )

    private fun verifier() = FindingVerifier(
        FindingLevel.high,
        FindingLevel.low,
        listOf(VerifierOption(FindingLevel.low, FindingLevel.high)),
        VerifierVerdict.confirmed,
        "Reproduced with two documents",
    )

    private fun decision(
        kind: FindingDecisionKind = FindingDecisionKind.fix_now,
        optionIndex: Int? = 0,
        note: String? = null,
        actor: PlanActor = PlanActor.Operator,
    ) = FindingDecision(kind, optionIndex, note, actor)

    @Test
    fun aCompleteFindingAndAnUnissuedFindingAreValid() {
        assertEquals(emptyList(), validateFinding(finding()))
        assertEquals(emptyList(), validateFinding(finding().copy(id = null, location = null)))
        assertEquals(emptyList(), validateFinding(finding().copy(verifier = verifier())))
    }

    @Test
    fun allMissingRequiredFieldsReturnAFieldError() {
        val missing = Json.decodeFromString<Finding>("{}")
        assertEquals(
            setOf("taskId", "condition", "impact", "danger", "likelihood", "options", "recommended"),
            validateFinding(missing).map { it.path }.toSet(),
        )
        assertTrue(validateFinding(missing).all { it.message.isNotBlank() })
    }

    @Test
    fun idsOptionalFieldsAndOptionFieldsHavePreciseErrors() {
        val invalid = finding().copy(
            id = "t_wrong", taskId = "s_wrong", location = " ", investigatorSessionId = "",
            condition = "", impact = " ",
            options = listOf(FindingOption()),
        )
        assertEquals(
            setOf("id", "taskId", "location", "investigatorSessionId", "condition", "impact",
                "options[0].fix", "options[0].outcome", "options[0].cost", "options[0].fit"),
            validateFinding(invalid).map { it.path }.toSet(),
        )
        assertEquals(listOf("location"), validateFinding(finding().copy(location = "file:0")).map { it.path })
    }

    @Test
    fun optionsCannotBeEmptyAndRecommendationMustIndexAnOption() {
        assertEquals(
            setOf("options", "recommended"),
            validateFinding(finding().copy(options = emptyList())).map { it.path }.toSet(),
        )
        for (index in listOf(-1, 1, Int.MAX_VALUE)) {
            assertEquals(listOf("recommended"), validateFinding(finding().copy(recommended = index)).map { it.path })
        }
        val two = finding().copy(options = finding().options + finding().options, recommended = 1)
        assertEquals(emptyList(), validateFinding(two))
    }

    @Test
    fun findingBoundCountsAllFieldsAndJsonEscapingInUtf8() {
        val json = Json { encodeDefaults = true }
        val original = finding().copy(condition = "x")
        val overhead = json.encodeToString(original).encodeToByteArray().size - 1
        val exact = original.copy(condition = "a".repeat(MAX_FINDING_BYTES - overhead))
        assertEquals(emptyList(), validateFinding(exact))
        assertEquals(listOf("finding"), validateFinding(exact.copy(condition = exact.condition + "a")).map { it.path })
        assertEquals(listOf("finding"), validateFinding(original.copy(condition = "é".repeat(MAX_FINDING_BYTES / 2))).map { it.path })
        assertEquals(listOf("finding"), validateFinding(original.copy(condition = "\"".repeat(MAX_FINDING_BYTES / 2))).map { it.path })
    }

    @Test
    fun verifierFieldsAreRequiredAndScoresMatchTheFindingOptions() {
        assertEquals(
            setOf("verifier.danger", "verifier.likelihood", "verifier.options", "verifier.verdict", "verifier.reason"),
            validateFinding(finding().copy(verifier = FindingVerifier())).map { it.path }.toSet(),
        )
        assertEquals(
            setOf("verifier.options[0].cost", "verifier.options[0].fit"),
            validateFinding(finding().copy(verifier = verifier().copy(options = listOf(VerifierOption())))).map { it.path }.toSet(),
        )
        assertEquals(listOf("verifier.options"), validateFinding(finding().copy(
            verifier = verifier().copy(options = verifier().options + verifier().options),
        )).map { it.path })
        assertEquals(emptyList(), validateFinding(finding().copy(verifier = verifier().copy(verdict = VerifierVerdict.rejected))))
    }

    @Test
    fun eachDecisionKindHasAnExplicitOptionShape() {
        assertEquals(emptyList(), validateFinding(finding().copy(decision = decision())))
        for (kind in listOf(FindingDecisionKind.fix_later, FindingDecisionKind.wont_fix)) {
            assertEquals(emptyList(), validateFinding(finding().copy(decision = decision(kind, optionIndex = null))))
            assertEquals(listOf("decision.optionIndex"), validateFinding(finding().copy(decision = decision(kind))).map { it.path })
        }
        for (index in listOf(null, -1, 1)) {
            assertEquals(listOf("decision.optionIndex"), validateFinding(finding().copy(decision = decision(optionIndex = index))).map { it.path })
        }
        assertEquals(
            setOf("decision.kind", "decision.decidedBy"),
            validateFinding(finding().copy(decision = FindingDecision())).map { it.path }.toSet(),
        )
    }

    @Test
    fun onlyTheOperatorCanDecideInSupervisedMode() {
        assertEquals(emptyList(), validateFindingDecision(finding(), decision(), ExecutionMode.supervised, "worker"))
        assertEquals(listOf("decision.decidedBy"), validateFindingDecision(
            finding(), decision(actor = PlanActor.Session("worker")), ExecutionMode.supervised, "worker",
        ).map { it.path })
    }

    @Test
    fun autonomousDecisionsRequireTheAssignedWorkerAndAnExplanation() {
        val valid = decision(note = "Confirmed by a failing test", actor = PlanActor.Session("worker"))
        assertEquals(emptyList(), validateFindingDecision(finding(), valid, ExecutionMode.autonomous, "worker"))
        assertEquals(listOf("decision.decidedBy"), validateFindingDecision(finding(), valid, ExecutionMode.autonomous, "other").map { it.path })
        assertEquals(listOf("decision.decidedBy"), validateFindingDecision(finding(), valid, ExecutionMode.autonomous, null).map { it.path })
        assertEquals(listOf("decision.decidedBy"), validateFindingDecision(
            finding(), valid.copy(decidedBy = PlanActor.Operator), ExecutionMode.autonomous, "worker",
        ).map { it.path })
        for (note in listOf(null, "", " ")) {
            assertEquals(listOf("decision.note"), validateFindingDecision(finding(), valid.copy(note = note), ExecutionMode.autonomous, "worker").map { it.path })
        }
        for (kind in FindingDecisionKind.entries) {
            val chosen = valid.copy(kind = kind, optionIndex = if (kind == FindingDecisionKind.fix_now) 0 else null)
            assertEquals(emptyList(), validateFindingDecision(finding(), chosen, ExecutionMode.autonomous, "worker"))
        }
    }

    @Test
    fun decisionValidationAlsoChecksOptionAndAuthorShape() {
        assertEquals(
            setOf("decision.optionIndex", "decision.decidedBy.sessionId"),
            validateFindingDecision(finding(), decision(optionIndex = 9, note = "Reason", actor = PlanActor.Session("")), ExecutionMode.autonomous, "").map { it.path }.toSet(),
        )
    }

    @Test
    fun decisionNotesCannotPushTheFindingPastItsSizeBound() {
        val chosen = decision(note = "a".repeat(MAX_FINDING_BYTES), actor = PlanActor.Session("worker"))
        assertEquals(listOf("finding"), validateFindingDecision(finding(), chosen, ExecutionMode.autonomous, "worker").map { it.path })
    }

    @Test
    fun switchingModesDoesNotChangeDecisionAuthorityWithinTheCurrentReview() {
        val review = switchReviewMode(TaskReview("t_one", mode = ExecutionMode.autonomous), ExecutionMode.supervised)
        val chosen = decision(note = "Reason", actor = PlanActor.Session("worker"))
        assertEquals(emptyList(), validateFindingDecision(finding(), chosen, review.mode, "worker"))
        assertEquals(listOf("decision.decidedBy"), validateFindingDecision(finding(), chosen, nextTaskReview(review).mode, "worker").map { it.path })
    }

    @Test
    fun sendRequiresEveryFindingOfTheTaskToHaveADecision() {
        val decided = finding().copy(decision = decision())
        val pending = finding().copy(id = "f_two")
        val unrelated = finding().copy(id = "f_other", taskId = "t_other")
        assertEquals(
            FindingBatchResult.Undecided(listOf("f_two")),
            sendFindingBatch("t_one", listOf(decided, pending, unrelated), PlanActor.Operator),
        )
        assertEquals(
            FindingBatchResult.Accepted(listOf(decided)),
            sendFindingBatch("t_one", listOf(decided, unrelated), PlanActor.Operator),
        )
        assertEquals(FindingBatchResult.Accepted(emptyList()), sendFindingBatch("t_one", emptyList(), PlanActor.Operator))
    }

    @Test
    fun onlyTheOperatorMaySendABatch() {
        assertEquals(FindingBatchResult.OperatorRequired, sendFindingBatch("t_one", listOf(finding()), PlanActor.Session("worker")))
    }

    @Test
    fun revisionsAreValidatedAndCountTowardTheFindingBound() {
        val revision = FindingRevision(
            condition = "Earlier condition", impact = "Earlier impact", danger = FindingLevel.low,
            likelihood = FindingLevel.low, options = finding().options, recommended = 0,
            author = PlanActor.Session("investigator"), at = 1,
        )
        assertEquals(emptyList(), validateFinding(finding().copy(revisions = listOf(revision))))
        assertEquals(
            setOf("revisions[0].condition", "revisions[0].author.sessionId", "revisions[0].at"),
            validateFinding(finding().copy(revisions = listOf(revision.copy(condition = "", author = PlanActor.Session(""), at = -1)))).map { it.path }.toSet(),
        )
        assertEquals(listOf("finding"), validateFinding(finding().copy(
            revisions = listOf(revision.copy(condition = "a".repeat(MAX_FINDING_BYTES))),
        )).map { it.path })
    }

    @Test
    fun aFindingWithVerifierDecisionAndRevisionsRoundTrips() {
        val original = finding().copy(verifier = verifier(), decision = decision(), investigatorSessionId = "investigator", revisions = listOf(
            FindingRevision("Old", "Impact", FindingLevel.low, FindingLevel.low, finding().options, 0, author = PlanActor.Session("investigator"), at = 1),
        ))
        assertEquals(original, Json.decodeFromString<Finding>(Json.encodeToString(original)))
    }
}
