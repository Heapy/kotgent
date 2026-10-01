package io.kotgent.daemon

import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.plan.*
import io.kotgent.store.FakeEventStore
import io.kotgent.store.FakePlanStore
import io.kotgent.store.PlanResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        suspend fun start() {
            session("root"); session("worker", parent = "root"); session("stranger")
            val doc = assertIs<PlanResult.Accepted>(plans.put(Plan("local:1", "Execution", tasks = listOf(PlanTask(ordinal = 1, title = "Work"))), 0)).document
            task = requireNotNull(doc.plan.tasks.single().id)
            val _ = plans.openReview("local:1")
            val _ = plans.submitReview("local:1", 1, ReviewVerdict.approved)
            assertIs<PlanResult.Accepted>(service.claim("local:1", root))
            assertIs<PlanResult.Accepted>(service.execute("local:1", PlanAction.Start(task), root))
            assertIs<PlanResult.Accepted>(service.execute("local:1", PlanAction.Worker(task, PlanWorker("worker", "work/one", "/work/worker")), root))
        }
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
}
