package io.kotgent.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.plan.*
import kotlin.test.assertFalse
import io.kotgent.db.KotgentDatabase
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import platform.posix.closedir
import platform.posix.getenv
import platform.posix.mkdtemp
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class SqlitePlanStoreTest {
    private val author = PlanActor.Session("author")
    private fun draft() = Plan("local:1", "Ship plans", sections = listOf(Section(kind = SectionKind.overview, body = "Original")),
        tasks = listOf(PlanTask(ordinal = 1, title = "Build it", steps = listOf(io.kotgent.plan.Step(text = "Check it")))))
    private fun PlanResult.accepted() = assertIs<PlanResult.Accepted>(this).document

    @Test
    fun aPutRacingTaskDeletionCannotLeaveAnOrphanPlan() = runBlocking {
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        try {
            var exists = true
            val store = SqlitePlanStore(driver, taskExists = { exists })
            val initial = store.put(draft(), 0).accepted()
            val deleting = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val deletion = async {
                store.deleteTask("local:1") {
                    deleting.complete(Unit)
                    proceed.await()
                    exists = false
                    true
                }
            }
            deleting.await()
            val writing = async { store.put(initial.plan, initial.plan.rev) }
            proceed.complete(Unit)
            assertTrue(deletion.await())
            assertIs<PlanResult.Missing>(writing.await())
            assertNull(store.get("local:1"))
        } finally { driver.close() }
    }

    @Test
    fun editsInvalidateOnlyTheirBlockAndJournalTheOperatorWithoutLosingAStaleDraft() = runBlocking {
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        try {
            val store = SqlitePlanStore(driver, now = { 100L })
            val initial = store.put(draft(), 0).accepted()
            val section = initial.plan.sections.single()
            val task = initial.plan.tasks.single()
            val _ = store.viewed("local:1", section.id!!, section.rev).accepted()
            val viewedBoth = store.viewed("local:1", task.id!!, task.rev).accepted()
            val _ = store.openReview("local:1").accepted()
            val edited = store.edit("local:1", section.id, section.rev, "Operator changes", PlanActor.Operator).accepted()
            assertFalse(isViewed(edited.plan.sections.single(), edited.review.viewMarks.first { it.blockId == section.id }))
            assertTrue(isViewed(edited.plan.tasks.single(), edited.review.viewMarks.first { it.blockId == task.id }))
            assertEquals("Original", edited.review.edits.single().before)
            assertEquals("Operator changes", edited.review.edits.single().after)
            assertEquals(1, edited.review.edits.single().reviewRound)
            assertIs<PlanResult.Conflict>(store.put(viewedBoth.plan, viewedBoth.plan.rev))
            assertIs<PlanResult.Conflict>(store.edit("local:1", section.id, section.rev, "stale", PlanActor.Operator))
            assertIs<PlanResult.Conflict>(store.viewed("local:1", section.id, section.rev))
            assertEquals("Operator changes", store.get("local:1")!!.plan.sections.single().body)
        } finally { driver.close() }
    }

    @Test
    fun deletingABlockRetainsItsTombstoneAndClosesItsThreads() = runBlocking {
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        try {
            val store = SqlitePlanStore(driver)
            val initial = store.put(draft(), 0).accepted()
            val id = initial.plan.sections.single().id!!
            val asked = store.thread("local:1", id, ThreadKind.question, "Why?", PlanActor.Operator).accepted()
            val thread = asked.review.threads.single().id
            val answered = store.reply("local:1", thread, "Because", author).accepted()
            assertEquals(ThreadStatus.answered, answered.review.threads.single().status)
            val deleted = store.put(answered.plan.copy(sections = emptyList()), answered.plan.rev).accepted()
            assertEquals(ThreadStatus.resolved, deleted.review.threads.single().status)
            assertEquals("block deleted", deleted.review.threads.single().messages.last().body)
            val conflict = assertIs<PlanResult.Conflict>(store.put(initial.plan, initial.plan.rev))
            assertTrue(id in conflict.changedBlockIds)
            assertIs<PlanResult.Invalid>(store.put(deleted.plan.copy(sections = initial.plan.sections), deleted.plan.rev))
            store.delete("local:1")
            assertNull(store.get("local:1"))
            for (table in listOf("plans", "plan_blocks", "plan_view_marks", "plan_threads", "plan_edits", "plan_rounds")) {
                assertEquals(0L, scalar(driver, "SELECT COUNT(*) FROM $table"))
            }
        } finally { driver.close() }
    }

    @Test
    fun roundsContinueAfterTimeoutAndRequireTheCurrentRoundToSubmit() = runBlocking {
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        try {
            val store = SqlitePlanStore(driver)
            val _ = store.put(draft(), 0).accepted()
            val opened = store.openReview("local:1").accepted()
            assertEquals(opened, store.openReview("local:1").accepted())
            assertEquals(1, store.reviews().size)
            assertIs<PlanResult.Conflict>(store.submitReview("local:1", 2, ReviewVerdict.approved))
            val submitted = store.submitReview("local:1", 1, ReviewVerdict.changes).accepted()
            assertTrue(store.reviews().isEmpty())
            assertEquals(submitted, store.openReview("local:1").accepted())
            val revised = store.put(submitted.plan.copy(title = "Revised"), submitted.plan.rev).accepted()
            assertEquals(PlanStatus.draft, revised.plan.status)
            val next = store.openReview("local:1").accepted()
            assertEquals(2, next.review.rounds.last().n)
            assertIs<PlanResult.Conflict>(store.submitReview("local:1", 1, ReviewVerdict.approved))
            assertEquals(PlanStatus.approved, store.submitReview("local:1", 2, ReviewVerdict.approved).accepted().plan.status)
        } finally { driver.close() }
    }

    @Test
    fun reviewAndBlockHistorySurviveReopeningALegacyDatabase() = runBlocking {
        withTempDbDir { directory ->
            val first = openDatabase(directory)
            val expected = try {
                first.execute(null, "INSERT INTO legacy_marker VALUES ('preserved')", 0)
                val store = SqlitePlanStore(first)
                val initial = store.put(draft(), 0).accepted()
                val section = initial.plan.sections.single()
                val _ = store.openReview("local:1").accepted()
                val _ = store.thread("local:1", section.id!!, ThreadKind.question, "How?", PlanActor.Operator).accepted()
                store.edit("local:1", section.id, section.rev, "Edited", PlanActor.Operator).accepted()
            } finally { first.close() }
            val second = openDatabase(directory)
            try {
                val store = SqlitePlanStore(second)
                assertEquals(expected, store.get("local:1"))
                assertEquals(expected, store.reviews().single())
                assertEquals(expected.plan.rev, store.revisions.value["local:1"])
                assertEquals(1L, scalar(second, "SELECT COUNT(*) FROM legacy_marker"))
                val _ = store.submitReview("local:1", 1, ReviewVerdict.approved).accepted()
            } finally { second.close() }
        }
    }

    private fun openDatabase(directory: String) = NativeSqliteDriver(
        schema = prePlanSchema,
        name = "plan-reopen.db",
        onConfiguration = { it.copy(extendedConfig = it.extendedConfig.copy(basePath = directory)) },
    )

    @Test
    fun executionAndFeedbackSurviveRestartAndAuthoredPuts() = runBlocking {
        withTempDbDir { directory ->
            val first = openDatabase(directory)
            val expected = try {
                val store = SqlitePlanStore(first)
                val initial = store.put(draft(), 0).accepted()
                val taskId = requireNotNull(initial.plan.tasks.single().id)
                val _ = store.openReview("local:1")
                val _ = store.submitReview("local:1", 1, ReviewVerdict.approved)
                val _ = store.execute("local:1", PlanAction.Claim(null), author).accepted()
                val _ = store.execute("local:1", PlanAction.Start(taskId), author).accepted()
                val _ = store.execute("local:1", PlanAction.Worker(taskId, PlanWorker("worker", "work/one", "/work/one")), author).accepted()
                val _ = store.execute("local:1", PlanAction.Status(taskId, PlanTaskStatus.in_review), PlanActor.Session("worker")).accepted()
                val added = store.execute("local:1", PlanAction.AddFinding(Finding(taskId = taskId, condition = "Empty rows", impact = "Export fails",
                    danger = FindingLevel.high, likelihood = FindingLevel.medium,
                    options = listOf(FindingOption("Guard", "Export works", FindingLevel.low, FindingLevel.high)), recommended = 0)), author).accepted()
                val finding = added.execution.findings.single()
                val verified = store.execute("local:1", PlanAction.Verify(finding.id!!, finding.rev,
                    FindingVerifier(FindingLevel.high, FindingLevel.medium, listOf(VerifierOption(FindingLevel.low, FindingLevel.high)), VerifierVerdict.confirmed, "Confirmed")),
                    PlanActor.Session("verifier")).accepted().execution.findings.single()
                val _ = store.execute("local:1", PlanAction.Decide(verified.id!!, verified.rev, FindingDecision(FindingDecisionKind.fix_now, 0)), PlanActor.Operator).accepted()
                val feedback = store.execute("local:1", PlanAction.Send(taskId), PlanActor.Operator).accepted()
                assertIs<PlanResult.Invalid>(store.put(feedback.plan.copy(tasks = emptyList()), feedback.plan.rev))
                // Editing authored content must never drop the independently stored execution state.
                store.put(feedback.plan.copy(title = "Updated title"), feedback.plan.rev).accepted()
            } finally { first.close() }
            val second = openDatabase(directory)
            try {
                val store = SqlitePlanStore(second)
                assertEquals(expected, store.get("local:1"))
                assertEquals("feedback", expected.execution.events.last().kind)
                assertEquals(PlanTaskStatus.running, expected.plan.tasks.single().status)
                store.workerEnded("worker")
                assertEquals("worker_lost", store.get("local:1")!!.execution.events.last().kind)
                store.delete("local:1")
                assertEquals(0L, scalar(second, "SELECT COUNT(*) FROM plan_execution"))
            } finally { second.close() }
        }
    }

    private fun scalar(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: error("no value"))
        },
        parameters = 0,
    ).value

    private val prePlanSchema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE legacy_marker (value TEXT NOT NULL)", 0)
            return QueryResult.Unit
        }
        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }

    private inline fun withTempDbDir(block: (String) -> Unit) {
        val directory = memScoped {
            val temporaryRoot = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
            val encoded = "$temporaryRoot/kotgent-plan-test-XXXXXX".encodeToByteArray()
            val chars = allocArray<ByteVar>(encoded.size + 1)
            encoded.forEachIndexed { index, byte -> chars[index] = byte }
            chars[encoded.size] = 0
            mkdtemp(chars)?.toKString() ?: error("could not create the plan-store test directory")
        }
        try {
            block(directory)
        } finally {
            val handle = opendir(directory)
            if (handle != null) {
                val names = buildList {
                    while (true) {
                        val entry = readdir(handle) ?: break
                        val name = entry.pointed.d_name.toKString()
                        if (name != "." && name != "..") add(name)
                    }
                }
                closedir(handle)
                for (name in names) unlink("$directory/$name")
            }
            rmdir(directory)
        }
    }
}
