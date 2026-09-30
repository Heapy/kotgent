package io.kotgent.plan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PlanStatus { draft, in_review, approved, executing, done }

@Serializable
enum class SectionKind { overview, context, solution, technical, post }

@Serializable
enum class FileAction { create, modify, delete }

@Serializable
enum class PlanAgent { claude, codex, any }

@Serializable
enum class PlanTaskStatus { pending, running, in_review, awaiting_decision, merging, done, blocked }

@Serializable
enum class ExecutionMode { autonomous, supervised }

@Serializable
data class Plan(
    val taskRef: String,
    val title: String,
    val status: PlanStatus = PlanStatus.draft,
    val rev: Long = 0,
    val sections: List<Section> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    val tasks: List<PlanTask> = emptyList(),
    val featureBranch: String? = null,
    val concurrency: Int = 3,
    val mode: ExecutionMode = ExecutionMode.supervised,
)

sealed interface PlanBlock {
    val id: String?
    val rev: Long
}

@Serializable
data class Section(
    override val id: String? = null,
    val kind: SectionKind,
    val body: String,
    override val rev: Long = 0,
) : PlanBlock

@Serializable
data class Decision(
    override val id: String? = null,
    val title: String,
    val body: String,
    override val rev: Long = 0,
) : PlanBlock

@Serializable
data class PlanFile(val path: String, val action: FileAction)

@Serializable
data class PlanTask(
    override val id: String? = null,
    val ordinal: Int,
    val title: String,
    val files: List<PlanFile> = emptyList(),
    override val rev: Long = 0,
    val steps: List<Step> = emptyList(),
    val dependsOn: List<String> = emptyList(),
    val agent: PlanAgent = PlanAgent.any,
    val status: PlanTaskStatus = PlanTaskStatus.pending,
    val worker: PlanWorker? = null,
) : PlanBlock

@Serializable
data class PlanWorker(val sessionId: String, val branch: String, val worktree: String)

@Serializable
data class Step(
    override val id: String? = null,
    val text: String,
    val done: Boolean = false,
    override val rev: Long = 0,
) : PlanBlock

@Serializable
data class ViewMark(val blockId: String, val viewedAtRev: Long)

@Serializable
sealed interface PlanActor {
    @Serializable
    @SerialName("operator")
    data object Operator : PlanActor

    @Serializable
    @SerialName("session")
    data class Session(val sessionId: String) : PlanActor
}

@Serializable
enum class ThreadKind { question, decision, review }

@Serializable
enum class ThreadStatus { open, answered, resolved }

@Serializable
data class ThreadMessage(val author: PlanActor, val body: String, val at: Long)

@Serializable
data class PlanThread(
    val id: String,
    val blockId: String,
    val kind: ThreadKind,
    val status: ThreadStatus = ThreadStatus.open,
    val messages: List<ThreadMessage> = emptyList(),
)

@Serializable
data class EditRecord(
    val blockId: String,
    val fromRev: Long,
    val toRev: Long,
    val before: String,
    val after: String,
    val author: PlanActor,
    val reviewRound: Int,
)

@Serializable
enum class ReviewVerdict { changes, approved }

@Serializable
data class ReviewRound(
    val n: Int,
    val openedAt: Long,
    val submittedAt: Long? = null,
    val verdict: ReviewVerdict? = null,
)

@Serializable
data class PlanReviewState(
    val viewMarks: List<ViewMark> = emptyList(),
    val threads: List<PlanThread> = emptyList(),
    val edits: List<EditRecord> = emptyList(),
    val rounds: List<ReviewRound> = emptyList(),
)

fun Plan.blocks(): List<PlanBlock> = buildList {
    addAll(sections)
    addAll(decisions)
    for (task in tasks) {
        add(task)
        addAll(task.steps)
    }
}

fun isViewed(block: PlanBlock, mark: ViewMark?): Boolean =
    block.id != null && mark != null && mark.blockId == block.id && mark.viewedAtRev == block.rev
