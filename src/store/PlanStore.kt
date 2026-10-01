package io.kotgent.store

import io.kotgent.plan.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class PlanDocument(val plan: Plan, val review: PlanReviewState = PlanReviewState(), val execution: PlanExecutionState = PlanExecutionState())

data class StoredPlan(val snapshot: PlanSnapshot, val review: PlanReviewState, val execution: PlanExecutionState = PlanExecutionState()) {
    fun document(): PlanDocument = PlanDocument(snapshot.plan, review, execution)
}

sealed interface PlanResult {
    data class Accepted(val document: PlanDocument, val reviewOpened: Boolean = false) : PlanResult
    data class Invalid(val errors: List<FieldError>) : PlanResult
    data class Conflict(val rev: Long, val changedBlockIds: List<String> = emptyList(), val errors: List<FieldError> = emptyList()) : PlanResult
    data class Forbidden(val errors: List<FieldError>) : PlanResult
    data object Missing : PlanResult
}

interface PlanStore {
    val revisions: StateFlow<Map<String, Long>>
    suspend fun get(ref: String): PlanDocument?
    suspend fun reviews(): List<PlanDocument>
    suspend fun all(): List<PlanDocument>
    suspend fun execute(ref: String, action: PlanAction, actor: PlanActor): PlanResult
    suspend fun workerEnded(sessionId: String)
    suspend fun put(plan: Plan, baseRev: Long): PlanResult
    suspend fun edit(ref: String, id: String, rev: Long, body: String, actor: PlanActor): PlanResult
    suspend fun viewed(ref: String, id: String, rev: Long?): PlanResult
    suspend fun thread(ref: String, id: String, kind: ThreadKind, body: String, actor: PlanActor): PlanResult
    suspend fun reply(ref: String, id: String, body: String, actor: PlanActor): PlanResult
    suspend fun resolve(ref: String, id: String): PlanResult
    suspend fun openReview(ref: String, afterRound: Int? = null): PlanResult
    suspend fun submitReview(ref: String, round: Int, verdict: ReviewVerdict): PlanResult
    suspend fun delete(ref: String)
    suspend fun deleteTask(ref: String, delete: suspend () -> Boolean): Boolean
}

interface PlanRows {
    fun get(ref: String): StoredPlan?
    fun all(): List<StoredPlan>
    fun save(plan: StoredPlan)
    fun delete(ref: String)
}

class PlanCoordinator(
    private val rows: PlanRows,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newId: (String) -> String = { it + randomOpaqueId() },
    private val taskExists: suspend (String) -> Boolean = { true },
) : PlanStore {
    private val lock = Mutex()
    override val revisions: StateFlow<Map<String, Long>>
        field = MutableStateFlow(rows.all().associate { it.snapshot.plan.taskRef to it.snapshot.plan.rev })

    override suspend fun get(ref: String): PlanDocument? = lock.withLock { rows.get(ref)?.document() }

    override suspend fun all(): List<PlanDocument> = lock.withLock { rows.all().map { it.document() } }

    override suspend fun execute(ref: String, action: PlanAction, actor: PlanActor): PlanResult = lock.withLock {
        val current = rows.get(ref) ?: return@withLock PlanResult.Missing
        applyExecution(current, action, actor)
    }

    override suspend fun workerEnded(sessionId: String): Unit = lock.withLock {
        for (current in rows.all()) {
            val result = applyExecution(current, PlanAction.WorkerEnded(sessionId), PlanActor.Operator)
            check(result is PlanResult.Accepted) { "could not record lost worker: $result" }
        }
    }

    private fun applyExecution(current: StoredPlan, action: PlanAction, actor: PlanActor): PlanResult {
        return when (val changed = applyPlanAction(current.snapshot.plan, current.execution, action, actor, now(), newId)) {
            is ExecutionChange.Rejected -> when (changed.failure) {
                ExecutionFailure.invalid -> PlanResult.Invalid(changed.errors)
                ExecutionFailure.forbidden -> PlanResult.Forbidden(changed.errors)
                ExecutionFailure.conflict -> PlanResult.Conflict(current.snapshot.plan.rev, errors = changed.errors)
            }
            is ExecutionChange.Accepted -> {
                if (changed.plan == current.snapshot.plan && changed.execution == current.execution) return PlanResult.Accepted(current.document())
                if (current.snapshot.plan.rev == Long.MAX_VALUE) return PlanResult.Invalid(listOf(FieldError("rev", "cannot increase the maximum revision")))
                val next = current.copy(snapshot = current.snapshot.copy(plan = changed.plan.copy(rev = current.snapshot.plan.rev + 1)), execution = changed.execution)
                save(next)
                PlanResult.Accepted(next.document())
            }
        }
    }

    override suspend fun reviews(): List<PlanDocument> = lock.withLock {
        rows.all().filter { it.review.rounds.lastOrNull()?.let { round -> round.verdict == null } == true }
            .map { it.document() }
    }

    override suspend fun put(plan: Plan, baseRev: Long): PlanResult = lock.withLock {
        if (!taskExists(plan.taskRef)) return@withLock PlanResult.Missing
        put(rows.get(plan.taskRef), plan, baseRev)
    }

    private fun put(current: StoredPlan?, plan: Plan, baseRev: Long): PlanResult =
        when (val merged = putPlan(current?.snapshot, plan, baseRev, newId)) {
            is PlanPutResult.Invalid -> PlanResult.Invalid(merged.errors)
            is PlanPutResult.Conflict -> PlanResult.Conflict(merged.currentRev, merged.changedBlockIds)
            is PlanPutResult.Accepted -> {
                var review = current?.review ?: PlanReviewState()
                if (merged.deletedBlockIds.isNotEmpty()) {
                    review = review.copy(
                        viewMarks = review.viewMarks.filter { it.blockId !in merged.deletedBlockIds },
                        threads = review.threads.map { thread ->
                            if (thread.blockId !in merged.deletedBlockIds || thread.status == ThreadStatus.resolved) thread
                            else thread.copy(status = ThreadStatus.resolved, messages = thread.messages +
                                ThreadMessage(PlanActor.Operator, "block deleted", now()))
                        },
                    )
                }
                val changed = current?.snapshot != merged.snapshot
                val status = merged.snapshot.plan.status
                val snapshot = if (changed && status in listOf(PlanStatus.approved, PlanStatus.in_review) &&
                    review.rounds.lastOrNull()?.verdict != null) {
                    merged.snapshot.copy(plan = merged.snapshot.plan.copy(status = PlanStatus.draft))
                } else merged.snapshot
                val stored = StoredPlan(snapshot, review, current?.execution ?: PlanExecutionState())
                val invariantErrors = current?.snapshot?.plan?.tasks.orEmpty().filter { it.status != PlanTaskStatus.pending }.mapNotNull { previous ->
                    val next = snapshot.plan.tasks.firstOrNull { it.id == previous.id }
                    when {
                        next == null -> FieldError("tasks", "cannot delete started task ${previous.id}")
                        next.dependsOn != previous.dependsOn -> FieldError("tasks", "cannot change dependencies of started task ${previous.id}")
                        else -> null
                    }
                }
                if (invariantErrors.isNotEmpty()) PlanResult.Invalid(invariantErrors)
                else {
                    if (changed) save(stored)
                    PlanResult.Accepted(stored.document())
                }
            }
        }

    override suspend fun edit(ref: String, id: String, rev: Long, body: String, actor: PlanActor): PlanResult = lock.withLock {
        val current = rows.get(ref) ?: return@withLock PlanResult.Missing
        val plan = current.snapshot.plan
        val block = plan.blocks().firstOrNull { it.id == id } ?: return@withLock PlanResult.Missing
        if (block.rev != rev) return@withLock PlanResult.Conflict(plan.rev, listOf(id))
        val edited = plan.copy(
            sections = plan.sections.map { if (it.id == id) it.copy(body = body) else it },
            decisions = plan.decisions.map { if (it.id == id) it.copy(body = body) else it },
            tasks = plan.tasks.map { task ->
                task.copy(title = if (task.id == id) body else task.title,
                    steps = task.steps.map { if (it.id == id) it.copy(text = body) else it })
            },
        )
        when (val merged = putPlan(current.snapshot, edited, plan.rev, newId)) {
            is PlanPutResult.Invalid -> PlanResult.Invalid(merged.errors)
            is PlanPutResult.Conflict -> PlanResult.Conflict(merged.currentRev, merged.changedBlockIds)
            is PlanPutResult.Accepted -> {
                if (merged.snapshot == current.snapshot) return@withLock PlanResult.Accepted(current.document())
                val changed = merged.snapshot.plan.blocks().first { it.id == id }
                val review = if (actor == PlanActor.Operator) current.review.copy(edits = current.review.edits +
                    EditRecord(id, block.rev, changed.rev, block.bodyText(), body, actor,
                        current.review.rounds.lastOrNull()?.let { if (it.verdict == null) it.n else it.n + 1 } ?: 1))
                    else current.review
                val snapshot = if (current.review.rounds.lastOrNull()?.verdict != null &&
                    plan.status in listOf(PlanStatus.approved, PlanStatus.in_review)) {
                    merged.snapshot.copy(plan = merged.snapshot.plan.copy(status = PlanStatus.draft))
                } else merged.snapshot
                val stored = StoredPlan(snapshot, review, current.execution)
                save(stored)
                PlanResult.Accepted(stored.document())
            }
        }
    }

    override suspend fun viewed(ref: String, id: String, rev: Long?): PlanResult = change(ref) { current ->
        val block = current.snapshot.plan.blocks().firstOrNull { it.id == id }
            ?: return@change PlanResult.Missing to null
        if (rev != null && block.rev != rev) return@change PlanResult.Conflict(current.snapshot.plan.rev, listOf(id)) to null
        val marks = current.review.viewMarks.filter { it.blockId != id } +
            if (rev == null) emptyList() else listOf(ViewMark(id, rev))
        null to current.review.copy(viewMarks = marks)
    }

    override suspend fun thread(ref: String, id: String, kind: ThreadKind, body: String, actor: PlanActor): PlanResult = change(ref) { current ->
        if (current.snapshot.plan.blocks().none { it.id == id }) return@change PlanResult.Missing to null
        val message = ThreadMessage(actor, body, now())
        val errors = validateThreadMessage(message)
        if (errors.isNotEmpty()) return@change PlanResult.Invalid(errors) to null
        null to current.review.copy(threads = current.review.threads + PlanThread(newId("th_"), id, kind, messages = listOf(message)))
    }

    override suspend fun reply(ref: String, id: String, body: String, actor: PlanActor): PlanResult = change(ref) { current ->
        val thread = current.review.threads.firstOrNull { it.id == id } ?: return@change PlanResult.Missing to null
        if (thread.status == ThreadStatus.resolved) return@change PlanResult.Conflict(current.snapshot.plan.rev) to null
        val message = ThreadMessage(actor, body, now())
        val errors = validateThreadMessage(message)
        if (errors.isNotEmpty()) return@change PlanResult.Invalid(errors) to null
        null to current.review.copy(threads = current.review.threads.map {
            if (it.id != id) it else it.copy(messages = it.messages + message,
                status = if (actor is PlanActor.Session) ThreadStatus.answered else ThreadStatus.open)
        })
    }

    override suspend fun resolve(ref: String, id: String): PlanResult = change(ref) { current ->
        if (current.review.threads.none { it.id == id }) return@change PlanResult.Missing to null
        null to current.review.copy(threads = current.review.threads.map { if (it.id == id) it.copy(status = ThreadStatus.resolved) else it })
    }

    override suspend fun openReview(ref: String, afterRound: Int?): PlanResult = lock.withLock {
        val current = rows.get(ref) ?: return@withLock PlanResult.Missing
        val plan = current.snapshot.plan
        if (plan.status in listOf(PlanStatus.executing, PlanStatus.done)) return@withLock PlanResult.Conflict(plan.rev)
        val last = current.review.rounds.lastOrNull()
        if (afterRound != null && (afterRound < 0 || afterRound > (last?.n ?: 0) ||
                (afterRound == last?.n && last.verdict == null))) return@withLock PlanResult.Conflict(plan.rev)
        if (afterRound != null && last != null && last.n > afterRound) return@withLock PlanResult.Accepted(current.document())
        val acknowledged = afterRound != null && afterRound == last?.n && last.verdict != null
        if (last != null && plan.status != PlanStatus.draft && !acknowledged) return@withLock PlanResult.Accepted(current.document())
        val next = current.copy(
            snapshot = current.snapshot.copy(plan = plan.copy(status = PlanStatus.in_review, rev = plan.rev + 1)),
            review = current.review.copy(rounds = current.review.rounds + ReviewRound((current.review.rounds.lastOrNull()?.n ?: 0) + 1, now())),
        )
        save(next)
        PlanResult.Accepted(next.document(), reviewOpened = true)
    }

    override suspend fun submitReview(ref: String, round: Int, verdict: ReviewVerdict): PlanResult = lock.withLock {
        val current = rows.get(ref) ?: return@withLock PlanResult.Missing
        val last = current.review.rounds.lastOrNull() ?: return@withLock PlanResult.Conflict(current.snapshot.plan.rev)
        if (last.n != round || last.verdict != null) return@withLock PlanResult.Conflict(current.snapshot.plan.rev)
        val next = current.copy(
            snapshot = current.snapshot.copy(plan = current.snapshot.plan.copy(
                status = if (verdict == ReviewVerdict.approved) PlanStatus.approved else PlanStatus.in_review,
                rev = current.snapshot.plan.rev + 1,
            )),
            review = current.review.copy(rounds = current.review.rounds.dropLast(1) + last.copy(submittedAt = now(), verdict = verdict)),
        )
        save(next)
        PlanResult.Accepted(next.document())
    }

    override suspend fun deleteTask(ref: String, delete: suspend () -> Boolean): Boolean = lock.withLock {
        if (!delete()) return@withLock false
        remove(ref)
        true
    }

    override suspend fun delete(ref: String): Unit = lock.withLock { remove(ref) }

    private fun remove(ref: String) {
        val old = rows.get(ref) ?: return
        rows.delete(ref)
        revisions.value = revisions.value + (ref to old.snapshot.plan.rev + 1)
    }

    private suspend fun change(ref: String, transform: (StoredPlan) -> Pair<PlanResult?, PlanReviewState?>): PlanResult = lock.withLock {
        val current = rows.get(ref) ?: return@withLock PlanResult.Missing
        val [failure, review] = transform(current)
        if (failure != null) return@withLock failure
        if (review == current.review) return@withLock PlanResult.Accepted(current.document())
        val next = current.copy(snapshot = current.snapshot.copy(plan = current.snapshot.plan.copy(rev = current.snapshot.plan.rev + 1)), review = requireNotNull(review))
        save(next)
        PlanResult.Accepted(next.document())
    }

    private fun save(plan: StoredPlan) {
        rows.save(plan)
        revisions.value = revisions.value + (plan.snapshot.plan.taskRef to plan.snapshot.plan.rev)
    }
}

fun PlanBlock.bodyText(): String = when (this) {
    is Section -> body
    is Decision -> body
    is PlanTask -> title
    is Step -> text
}
