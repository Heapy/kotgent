package io.kotgent.store

import app.cash.sqldelight.db.SqlDriver
import io.kotgent.db.KotgentDatabase
import io.kotgent.plan.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

class SqlitePlanStore(
    driver: SqlDriver,
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    newId: (String) -> String = { it + randomOpaqueId() },
    taskExists: suspend (String) -> Boolean = { true },
) : PlanStore by PlanCoordinator(SqlitePlanRows(driver), now, newId, taskExists) {
    companion object {
        val CREATE_TABLES_IF_NOT_EXISTS: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS plans ( task_ref TEXT NOT NULL PRIMARY KEY, document TEXT NOT NULL )",
            "CREATE TABLE IF NOT EXISTS plan_blocks ( task_ref TEXT NOT NULL, block_id TEXT NOT NULL, changed_at_rev INTEGER NOT NULL, PRIMARY KEY (task_ref, block_id) )",
            "CREATE TABLE IF NOT EXISTS plan_view_marks ( task_ref TEXT NOT NULL, block_id TEXT NOT NULL, document TEXT NOT NULL, PRIMARY KEY (task_ref, block_id) )",
            "CREATE TABLE IF NOT EXISTS plan_threads ( task_ref TEXT NOT NULL, thread_id TEXT NOT NULL, document TEXT NOT NULL, PRIMARY KEY (task_ref, thread_id) )",
            "CREATE TABLE IF NOT EXISTS plan_edits ( task_ref TEXT NOT NULL, ordinal INTEGER NOT NULL, document TEXT NOT NULL, PRIMARY KEY (task_ref, ordinal) )",
            "CREATE TABLE IF NOT EXISTS plan_rounds ( task_ref TEXT NOT NULL, round INTEGER NOT NULL, document TEXT NOT NULL, PRIMARY KEY (task_ref, round) )"
        )
    }
}

private class SqlitePlanRows(driver: SqlDriver) : PlanRows {
    private val db = KotgentDatabase(driver)
    private val queries get() = db.plansQueries
    private val json = Json { encodeDefaults = true }

    init {
        for (statement in SqlitePlanStore.CREATE_TABLES_IF_NOT_EXISTS) driver.execute(null, statement, 0)
    }

    override fun get(ref: String): StoredPlan? = db.transactionWithResult {
        val document = queries.selectPlan(ref).executeAsOneOrNull() ?: return@transactionWithResult null
        val plan = json.decodeFromString<Plan>(document)
        StoredPlan(
            PlanSnapshot(plan, queries.selectBlocks(ref).executeAsList().associate { it.block_id to it.changed_at_rev }),
            PlanReviewState(
                viewMarks = queries.selectViewMarks(ref).executeAsList().map { json.decodeFromString<ViewMark>(it.document) },
                threads = queries.selectThreads(ref).executeAsList().map { json.decodeFromString<PlanThread>(it.document) },
                edits = queries.selectEdits(ref).executeAsList().map { json.decodeFromString<EditRecord>(it.document) },
                rounds = queries.selectRounds(ref).executeAsList().map { json.decodeFromString<ReviewRound>(it.document) },
            ),
        )
    }

    override fun all(): List<StoredPlan> = db.transactionWithResult {
        queries.selectPlans().executeAsList().mapNotNull { get(it.task_ref) }
    }

    override fun save(plan: StoredPlan) {
        db.transaction {
            val ref = plan.snapshot.plan.taskRef
            delete(ref)
            val _ = queries.putPlan(ref, json.encodeToString(plan.snapshot.plan))
            for (entry in plan.snapshot.blockChangedAtRev.entries) { val _ = queries.putBlocks(ref, entry.key, entry.value) }
            for (mark in plan.review.viewMarks) { val _ = queries.putViewMarks(ref, mark.blockId, json.encodeToString(mark)) }
            for (thread in plan.review.threads) { val _ = queries.putThreads(ref, thread.id, json.encodeToString(thread)) }
            plan.review.edits.forEachIndexed { index, edit -> val _ = queries.putEdits(ref, index.toLong(), json.encodeToString(edit)) }
            for (round in plan.review.rounds) { val _ = queries.putRounds(ref, round.n.toLong(), json.encodeToString(round)) }
        }
    }

    override fun delete(ref: String) {
        db.transaction {
            val _ = queries.deletePlanBlocks(ref)
            val _ = queries.deletePlanViewMarks(ref)
            val _ = queries.deletePlanThreads(ref)
            val _ = queries.deletePlanEdits(ref)
            val _ = queries.deletePlanRounds(ref)
            val _ = queries.deletePlans(ref)
        }
    }
}
