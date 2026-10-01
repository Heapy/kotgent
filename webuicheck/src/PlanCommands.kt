package io.kotgent.webuicheck

import io.kotgent.plan.PlanActor
import io.kotgent.plan.blocks
import io.kotgent.store.PlanResult
import kotlinx.coroutines.runBlocking

fun handlePlanCommand(words: List<String>, ctx: HarnessContext): Boolean? {
    val verb = words.firstOrNull()
    if (verb !in setOf("plan-edit", "plan-reply", "plan-review")) return null
    return runBlocking {
        val ref = words.getOrNull(1) ?: return@runBlocking reject("$verb requires a task reference")
        val plans = ctx.fakes.planStore
        val doc = plans.get(ref) ?: return@runBlocking reject("$verb: no plan for $ref")
        val actor = PlanActor.Session("s-work")
        val result = when (verb) {
            "plan-edit" -> {
                val block = doc.plan.blocks().find { it.id == words.getOrNull(2) }
                    ?: return@runBlocking reject("plan-edit: unknown block")
                plans.edit(ref, requireNotNull(block.id), block.rev, words.drop(3).joinToString(" "), actor)
            }
            "plan-reply" -> {
                val thread = doc.review.threads.lastOrNull() ?: return@runBlocking reject("plan-reply: no thread")
                plans.reply(ref, thread.id, words.drop(2).joinToString(" "), actor)
            }
            else -> plans.openReview(ref, doc.review.rounds.lastOrNull()?.n)
        }
        if (result !is PlanResult.Accepted) return@runBlocking reject("$verb: $result")
        writeStdoutLine(COMMAND_ACK_PREFIX + verb)
        true
    }
}
