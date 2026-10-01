package io.kotgent.cli

import io.kotgent.daemon.PlanReviewResponse
import io.kotgent.daemon.formatPlanReview
import io.kotgent.transport.TRANSPORT_JSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

sealed interface PlanCommand : CliCommand {
    val ref: String
    val session: String?
}

data class PlanPut(override val ref: String, val document: String, val baseRev: Long, override val session: String?) : PlanCommand
data class PlanShow(override val ref: String, override val session: String?) : PlanCommand
data class PlanReviewCommand(override val ref: String, val wait: Int, val json: Boolean, override val session: String?, val afterRound: Int? = null) : PlanCommand
data class PlanReply(override val ref: String, val thread: String, val message: String, override val session: String?) : PlanCommand

object PlanCommands {
    fun run(command: PlanCommand): Int = runBlocking {
        ApiClient(paneId = TmuxSelf.currentPane()).use { api ->
            runPlanCommand(command, { api.planRequest(command) }, ::println, ::eprintln)
        }
    }
}

suspend fun runPlanCommand(command: PlanCommand, request: suspend () -> String, stdout: (String) -> Unit, stderr: (String) -> Unit): Int {
    try {
        val result = request()
        if (command is PlanReviewCommand && !command.json) {
            stdout(formatPlanReview(TRANSPORT_JSON.decodeFromString(PlanReviewResponse.serializer(), result)))
        } else stdout(result)
        return 0
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (command is PlanReviewCommand && isInterruptedWait(e)) {
            stdout(if (command.json) "{\"verdict\":\"pending\"}" else "verdict: pending")
            return 0
        }
        stderr("kotgent: ${e.message ?: "plan request failed"}")
        return if (e is ApiException && e.status in listOf(409, 410)) 3 else 1
    }
}
