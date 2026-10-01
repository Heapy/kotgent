package io.kotgent.daemon

import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.plan.*
import io.kotgent.store.EventStore
import io.kotgent.store.FakeEventStore
import io.kotgent.store.FakePlanStore
import io.kotgent.store.PlanResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PlanExecutionTest {
    private class Fixture {
        val plans = FakePlanStore()
        val sessions = FakeEventStore()
        val service = PlanExecution(plans, sessions)
        val root = PlanActor.Session("root")
        val worker = PlanActor.Session("worker")
        lateinit var task: String
        suspend fun session(id: String, state: SessionState = SessionState.running, parent: String? = null) {
            sessions.upsertSession(SessionMeta(id = SessionId(id), name = id, agent = "claude", cwd = "/work/$id", tmuxSession = "kt-$id",
                state = state, createdAt = 1, updatedAt = 1, parentSessionId = parent?.let(::SessionId)))
        }
        suspend fun start(assignWorker: Boolean = true) {
            session("root"); session("worker", parent = "root"); session("stranger")
            val doc = assertIs<PlanResult.Accepted>(plans.put(Plan("local:1", "Execution", tasks = listOf(PlanTask(ordinal = 1, title = "Work"))), 0)).document
            task = requireNotNull(doc.plan.tasks.single().id)
            val _ = plans.openReview("local:1")
            val _ = plans.submitReview("local:1", 1, ReviewVerdict.approved)
            assertIs<PlanResult.Accepted>(service.claim("local:1", root))
            assertIs<PlanResult.Accepted>(service.execute("local:1", PlanAction.Start(task), root))
            if (assignWorker) assertIs<PlanResult.Accepted>(service.execute("local:1", PlanAction.Worker(task, PlanWorker("worker", "work/one", "/work/worker")), root))
        }
    }

    @Test fun anEndCallbackBeforeAssignmentCannotLeaveADeadWorkerRunning() = runBlocking {
        val f = Fixture(); f.start(assignWorker = false)
        var race = true
        val sessions = object : EventStore by f.sessions {
            override suspend fun getSession(sessionId: SessionId): SessionMeta? {
                val row = f.sessions.getSession(sessionId)
                if (sessionId.value == "worker" && race) {
                    race = false
                    f.session("worker", SessionState.stopped, "root")
                    f.plans.workerEnded("worker") // There is no assigned worker yet, so this callback finds nothing.
                }
                return row
            }
        }
        val service = PlanExecution(f.plans, sessions)
        val result = assertIs<PlanResult.Accepted>(service.execute("local:1", PlanAction.Worker(f.task,
            PlanWorker("worker", "work/one", "/work/worker")), f.root))
        assertEquals(PlanTaskStatus.blocked, result.document.plan.tasks.single().status)
        assertEquals(1, result.document.execution.events.count { it.kind == "worker_lost" })
    }

    @Test fun feedbackWakesOnlyItsWorkerAndDroppedDeliveryCanBeReplayed() = runBlocking {
        val f = Fixture(); f.start()
        val pending = f.service.wait("local:1", f.task, f.worker, 0, Duration.ZERO).second!!
        assertEquals("pending", pending.event)
        assertIs<PlanResult.Forbidden>(f.service.wait("local:1", f.task, PlanActor.Session("stranger"), 0, Duration.ZERO).first)
        assertIs<PlanResult.Forbidden>(f.service.wait("local:1", null, f.worker, 0, Duration.ZERO).first)
        coroutineScope {
            val waiting = async { f.service.wait("local:1", f.task, f.worker, pending.cursor, 5.seconds) }
            assertIs<PlanResult.Accepted>(f.service.execute("local:1", PlanAction.Status(f.task, PlanTaskStatus.in_review), f.worker))
            assertIs<PlanResult.Accepted>(f.service.execute("local:1", PlanAction.Feedback(f.task, rebaseOnto = "feature/export"), f.root))
            val [failure, response] = waiting.await()
            assertNull(failure)
            assertEquals("rebase", response!!.event)
            assertEquals("feature/export", response.events.single().rebaseOnto)
            val replay = f.service.wait("local:1", f.task, f.worker, pending.cursor, Duration.ZERO).second!!
            assertEquals(response.events, replay.events)
            assertEquals("pending", f.service.wait("local:1", f.task, f.worker, response.cursor, Duration.ZERO).second!!.event)
        }
    }

    @Test fun startupRecoveryAndDuplicateCallbacksProduceOneDurableLostEvent() = runBlocking {
        val f = Fixture(); f.start()
        f.session("worker", SessionState.lost, "root")
        val monitor = PlanWorkerMonitor(f.plans, f.sessions)
        monitor.reconcile(); monitor.reconcile()
        val doc = f.plans.get("local:1")!!
        assertEquals(PlanTaskStatus.blocked, doc.plan.tasks.single().status)
        assertEquals(1, doc.execution.events.count { it.kind == "worker_lost" })
        assertEquals("worker_lost", f.service.wait("local:1", null, f.root, 1, Duration.ZERO).second!!.event)
    }

    @Test fun liveOrchestratorsCannotBeDisplacedAndWorkerAssignmentsUseRealChildMetadata() = runBlocking {
        val f = Fixture(); f.start()
        val other = PlanActor.Session("stranger")
        assertIs<PlanResult.Forbidden>(f.service.claim("local:1", other))
        assertIs<PlanResult.Invalid>(f.service.execute("local:1", PlanAction.Worker(f.task, PlanWorker("stranger", "branch", "/work/stranger")), f.root))
        assertIs<PlanResult.Invalid>(f.service.execute("local:1", PlanAction.Worker(f.task, PlanWorker("worker", "branch", "/wrong/path")), f.root))
        f.session("root", SessionState.stopped)
        assertIs<PlanResult.Accepted>(f.service.claim("local:1", other))
        assertIs<PlanResult.Forbidden>(f.service.execute("local:1", PlanAction.Start(f.task), f.root))
        assertEquals("stranger", f.plans.get("local:1")!!.execution.orchestratorSessionId)
    }
    private suspend fun Fixture.finding(): Finding {
        assertIs<PlanResult.Accepted>(plans.execute("local:1", PlanAction.Status(task, PlanTaskStatus.in_review), worker))
        val added = assertIs<PlanResult.Accepted>(plans.execute("local:1", PlanAction.AddFinding(Finding(taskId = task,
            condition = "A delayed reply overwrites new data", impact = "Edits disappear", danger = FindingLevel.high, likelihood = FindingLevel.medium,
            options = listOf(FindingOption("Check revision", "Keep the newest reply", FindingLevel.low, FindingLevel.high)), recommended = 0)), root)).document.execution.findings.single()
        val verified = assertIs<PlanResult.Accepted>(plans.execute("local:1", PlanAction.Verify(added.id!!, added.rev,
            FindingVerifier(FindingLevel.high, FindingLevel.medium, listOf(VerifierOption(FindingLevel.low, FindingLevel.high)),
                VerifierVerdict.confirmed, "Reproduced by delayed response")), root))
        return verified.document.execution.findings.single()
    }

    private class InvestigatorFixture(val f: Fixture) {
        val launches = mutableListOf<InvestigatorLaunch>()
        val archived = mutableListOf<SessionId>()
        var failArchive = false
        var duringLaunch: suspend () -> Unit = {}
        fun service() = PlanInvestigators(f.plans, f.sessions, launch = { request ->
            launches += request
            val child = SessionMeta(id = SessionId("investigator${launches.size}"), name = "Investigator", agent = "claude",
                cwd = request.worktree, tmuxSession = "fixture", state = SessionState.running, createdAt = 1, updatedAt = 1,
                parentSessionId = request.parent, readOnly = true, tags = listOf(PLAN_INVESTIGATOR_TAG))
            f.sessions.upsertSession(child)
            duringLaunch()
            child
        }, archive = { id ->
            if (failArchive) error("temporary archive failure")
            archived += id
            f.sessions.setArchived(id, true, 2)
        })
    }

    @Test fun investigatorsUseCurrentEvidenceReuseLiveChildrenAndRejectUnsupportedCallers() = runBlocking {
        val f = Fixture(); f.start(); val finding = f.finding()
        val ports = InvestigatorFixture(f); val investigators = ports.service()
        assertIs<PlanResult.Forbidden>(investigators.investigate("local:1", finding.id!!, finding.rev, "claude", f.root))
        assertIs<PlanResult.Invalid>(investigators.investigate("local:1", finding.id, finding.rev, "codex", PlanActor.Operator))
        val started = assertIs<PlanResult.Accepted>(investigators.investigate("local:1", finding.id, finding.rev, "claude", PlanActor.Operator))
        val linked = started.document.execution.findings.single()
        assertEquals("investigator1", linked.investigatorSessionId)
        assertIs<PlanResult.Accepted>(investigators.investigate("local:1", finding.id, linked.rev, "claude", PlanActor.Operator))
        assertEquals(1, ports.launches.size)
        assertEquals(SessionId("root"), ports.launches.single().parent)
        assertEquals("/work/worker", ports.launches.single().worktree)
        val prompt = ports.launches.single().prompt
        assertTrue(prompt.contains("Reproduced by delayed response"))
        assertTrue(prompt.contains("read-only child investigator"))
        assertTrue(prompt.contains("plan finding local:1 amend ${finding.id}"))
        assertTrue(prompt.contains("kotgent mutex run kotlin-build -- ./kotlin"))
        val _ = assertIs<PlanResult.Conflict>(investigators.investigate("local:1", finding.id, finding.rev, "claude", PlanActor.Operator))
    }

    @Test fun decisionCleanupSurvivesFailureAndStartupAlsoReapsUnlinkedLaunches() = runBlocking {
        val f = Fixture(); f.start(); val finding = f.finding()
        val ports = InvestigatorFixture(f); val investigators = ports.service()
        val started = assertIs<PlanResult.Accepted>(investigators.investigate("local:1", finding.id!!, finding.rev, "claude", PlanActor.Operator))
        val linked = started.document.execution.findings.single()
        assertIs<PlanResult.Accepted>(f.plans.execute("local:1", PlanAction.Decide(finding.id, linked.rev,
            FindingDecision(FindingDecisionKind.fix_later)), PlanActor.Operator))
        ports.failArchive = true
        assertFailsWith<IllegalStateException> { investigators.reconcile() }
        assertEquals("investigator1", f.plans.get("local:1")!!.execution.findings.single().investigatorSessionId)
        ports.failArchive = false
        ports.service().reconcile()
        assertEquals(true, f.sessions.getSession(SessionId("investigator1"))!!.archived)
        val orphan = f.sessions.getSession(SessionId("investigator1"))!!.copy(id = SessionId("orphan"), archived = false)
        f.sessions.upsertSession(orphan)
        ports.service().reconcile()
        assertEquals(listOf(SessionId("investigator1"), SessionId("orphan")), ports.archived)
        assertEquals(false, f.sessions.getSession(SessionId("root"))!!.archived)
    }

    @Test fun aDecisionRacingLaunchArchivesTheNewChildInsteadOfLinkingIt() = runBlocking {
        val f = Fixture(); f.start(); val finding = f.finding()
        val ports = InvestigatorFixture(f)
        ports.duringLaunch = {
            assertIs<PlanResult.Accepted>(f.plans.execute("local:1", PlanAction.Decide(finding.id!!, finding.rev,
                FindingDecision(FindingDecisionKind.wont_fix)), PlanActor.Operator))
        }
        assertIs<PlanResult.Conflict>(ports.service().investigate("local:1", finding.id!!, finding.rev, "claude", PlanActor.Operator))
        assertEquals(listOf(SessionId("investigator1")), ports.archived)
        assertNull(f.plans.get("local:1")!!.execution.findings.single().investigatorSessionId)
    }

    @Test fun aDisconnectedLaunchFinishesItsLinkAndBackgroundCleanupReactsToDecisions() = runBlocking {
        val f = Fixture(); f.start(); val finding = f.finding()
        val ports = InvestigatorFixture(f); val investigators = ports.service()
        val entered = CompletableDeferred<Unit>(); val continueLaunch = CompletableDeferred<Unit>()
        ports.duringLaunch = { entered.complete(Unit); continueLaunch.await() }
        val request = launch { val _ = investigators.investigate("local:1", finding.id!!, finding.rev, "claude", PlanActor.Operator) }
        entered.await(); request.cancel(); continueLaunch.complete(Unit); request.join()
        val linked = f.plans.get("local:1")!!.execution.findings.single()
        assertEquals("investigator1", linked.investigatorSessionId)
        val monitor = investigators.start(this)
        try {
            val stopped = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5.seconds) { f.sessions.sessionUpdates.first { it.sessionId.value == "investigator1" && it.archived } }
            }
            assertIs<PlanResult.Accepted>(f.plans.execute("local:1", PlanAction.Decide(finding.id!!, linked.rev,
                FindingDecision(FindingDecisionKind.fix_later)), PlanActor.Operator))
            stopped.await()
            assertEquals(listOf(SessionId("investigator1")), ports.archived)
        } finally { monitor.cancelAndJoin() }
    }

}
