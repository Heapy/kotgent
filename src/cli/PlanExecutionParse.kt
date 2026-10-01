package io.kotgent.cli

import io.kotgent.core.TaskRef
import io.kotgent.plan.*
import io.kotgent.transport.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal val PLAN_EXECUTION_VERBS = setOf("claim", "complete", "set", "task", "step", "wait", "ask", "finding")
private class PlanUsage(message: String) : Exception(message)
private fun usage(message: String): Nothing = throw PlanUsage(message)

internal fun parsePlanExecution(rest: List<String>, readStdin: () -> String): CliCommand = try {
    ExecutionParser(rest, readStdin).parse()
} catch (e: PlanUsage) {
    CliCommand.Invalid("plan: ${e.message}")
}

private class ExecutionParser(rest: List<String>, private val readStdin: () -> String) {
    private val verb = rest.first()
    private val scan = when (val s = scanFlags("plan $verb", rest.drop(1), valueFlags(
        "--session", "--worker-session", "--branch", "--worktree", "--findings", "--rebase-onto", "--after", "--wait",
        "--kind", "--message", "--rev", "--option", "--note",
    ) + ("-m" to "--message"), setOf("--json"))) {
        is Scan.Bad -> usage(s.message)
        is Scan.Ok -> s
    }
    private val args = scan.positionals
    private val ref = args.firstOrNull()?.takeIf { TaskRef.parseOrNull(it) != null } ?: usage("$verb requires a task reference")
    private val session = scan.values["--session"]
    private fun arg(index: Int) = args.getOrNull(index) ?: usage("$verb: missing argument")
    private fun flag(name: String) = scan.values[name] ?: usage("$verb requires $name")
    private fun shape(count: Int, vararg flags: String) {
        if (args.size != count) usage("$verb: unexpected or missing argument")
        val allowed = flags.toSet() + "--session"
        (scan.values.keys + scan.switches).firstOrNull { it !in allowed }?.let { usage("$verb: unexpected flag $it") }
    }
    private fun id(value: String, prefix: String): String = value.takeIf { isPlanId(it, prefix) } ?: usage("expected a $prefix id")
    private fun revision() = flag("--rev").toLongOrNull()?.takeIf { it > 0 } ?: usage("--rev must be positive")
    private fun message(): String {
        val raw = flag("--message")
        val text = if (raw == "-") readStdin().trimEnd() else raw
        if (text.isBlank() || text.encodeToByteArray().size > MAX_THREAD_MESSAGE_BYTES) usage("message must be nonempty and at most 8192 UTF-8 bytes")
        return text
    }
    private inline fun <reified T> input(): T {
        val raw = readStdin()
        if (raw.encodeToByteArray().size > MAX_FINDING_BYTES) usage("stdin exceeds 32 KiB")
        return try { TRANSPORT_JSON.decodeFromString<T>(raw) } catch (_: SerializationException) { usage("stdin must contain valid JSON for this operation") }
    }
    private inline fun <reified T> mutation(path: String, body: T, patch: Boolean = false) =
        PlanMutation(ref, path, TRANSPORT_JSON.encodeToString(body), patch, session)
    private fun mutation(path: String) = PlanMutation(ref, path, session = session)
    private inline fun <reified T : Enum<T>> enum(value: String): T = enumValues<T>().firstOrNull { it.name == value }
        ?: usage("expected ${enumValues<T>().joinToString(" | ") { it.name }}")
    private fun wait(taskId: String?): PlanWait {
        val seconds = (scan.values["--wait"] ?: "90").toIntOrNull()?.takeIf { it in 0..540 } ?: usage("--wait must be 0–540 seconds")
        val after = (scan.values["--after"] ?: "0").toLongOrNull()?.takeIf { it >= 0 } ?: usage("--after must be non-negative")
        return PlanWait(ref, taskId, seconds, after, "--json" in scan.switches, session)
    }
    fun parse(): PlanCommand = when (verb) {
        "claim", "complete" -> { shape(1); mutation("/$verb") }
        "set" -> {
            shape(3)
            val settings = when (arg(1)) {
                "mode" -> PlanSettingsRequest(mode = enum<ExecutionMode>(arg(2)))
                "branch" -> PlanSettingsRequest(featureBranch = arg(2).takeUnless { it.isBlank() } ?: usage("branch must not be blank"))
                "concurrency" -> PlanSettingsRequest(concurrency = arg(2).toIntOrNull()?.takeIf { it > 0 } ?: usage("concurrency must be positive"))
                else -> usage("set requires mode | branch | concurrency")
            }
            mutation("/settings", settings, patch = true)
        }
        "task" -> task()
        "step" -> {
            shape(3)
            if (arg(2) != "done") usage("step requires done")
            mutation("/steps/${id(arg(1), "st_")}/done")
        }
        "wait" -> { shape(1, "--after", "--wait", "--json"); wait(null) }
        "ask" -> {
            shape(2, "--message", "--kind")
            val block = arg(1)
            if (listOf("s_", "d_", "t_", "st_").none { isPlanId(block, it) }) usage("ask requires a block id")
            mutation("/threads", PlanThreadRequest(block, enum<ThreadKind>(scan.values["--kind"] ?: "question"), message()))
        }
        "finding" -> finding()
        else -> usage("unknown command $verb")
    }
    private fun task(): PlanCommand {
        val taskId = id(arg(1), "t_")
        val action = arg(2)
        val path = "/tasks/$taskId"
        return when (action) {
            "start", "finish", "block" -> { shape(3); mutation("$path/$action") }
            "worker" -> {
                shape(3, "--worker-session", "--branch", "--worktree")
                mutation("$path/worker", PlanWorker(flag("--worker-session"), flag("--branch"), flag("--worktree")))
            }
            "status" -> {
                shape(4)
                val status = enum<PlanTaskStatus>(arg(3))
                if (status !in setOf(PlanTaskStatus.awaiting_decision, PlanTaskStatus.merging, PlanTaskStatus.done)) usage("status requires awaiting_decision | merging | done")
                mutation("$path/status", PlanTaskStatusRequest(status))
            }
            "feedback" -> {
                shape(3, "--findings", "--rebase-onto")
                val findings = scan.values["--findings"]?.split(',')?.map { id(it, "f_") }
                val rebase = scan.values["--rebase-onto"]
                if ((findings == null) == (rebase == null) || rebase?.isBlank() == true) usage("feedback requires exactly one of --findings or --rebase-onto")
                if (findings != null && findings.distinct().size != findings.size) usage("finding ids must be unique")
                mutation("$path/feedback", PlanFeedbackRequest(findings.orEmpty(), rebase))
            }
            "wait" -> { shape(3, "--after", "--wait", "--json"); wait(taskId) }
            else -> usage("unknown task operation '$action'")
        }
    }
    private fun finding(): PlanCommand {
        val action = arg(1)
        if (action == "add") { shape(2); return mutation("/findings", input<Finding>()) }
        val path = "/findings/${id(arg(2), "f_")}"
        return when (action) {
            "verify" -> { shape(3, "--rev"); mutation("$path/verify", PlanFindingVerifyRequest(revision(), input<FindingVerifier>())) }
            "amend" -> { shape(3, "--rev"); mutation("$path/amend", PlanFindingAmendRequest(revision(), input<Finding>())) }
            "note" -> { shape(3, "--message"); mutation("$path/note", PlanMessageRequest(message())) }
            "decide" -> {
                shape(3, "--rev", "--kind", "--option", "--note")
                val kind = enum<FindingDecisionKind>(flag("--kind"))
                val option = scan.values["--option"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: usage("--option must be a non-negative index") }
                if ((kind == FindingDecisionKind.fix_now) != (option != null)) usage("--option is required only for fix_now")
                mutation("$path/decide", PlanFindingDecisionRequest(revision(), FindingDecision(kind, option, scan.values["--note"])))
            }
            else -> usage("unknown finding operation '$action'")
        }
    }
}
