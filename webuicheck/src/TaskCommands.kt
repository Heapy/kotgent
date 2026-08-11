package io.kotgent.webuicheck

import io.kotgent.core.TaskRef
import io.kotgent.daemon.TaskService
import io.kotgent.task.TaskState
import kotlinx.coroutines.runBlocking

/*
 * The task half of the harness's stdin protocol.
 *
 * Four commands, and each one exists because it is the ONLY way a browser tier can observe a particular
 * frame of the global `/events` socket. The socket's task branch decides the frame kind entirely from
 * whether IT has carried the ref before (`EventsWs.kt`), so a driver cannot ask for a `task_row` — it can
 * only produce a ref the socket has never carried and let the sender reach that conclusion:
 *
 * | command                    | store call                 | frame the socket derives |
 * |----------------------------|----------------------------|--------------------------|
 * | `task <ref> <state>`       | `TaskService.transition`   | `task_update`            |
 * | `task-add <ref> [position]`| `FakeTaskStore.addTask`    | `task_row`               |
 * | `task-del <ref>`           | `TaskService.delete`       | `task_removed`           |
 * | `task-race <ref>`          | `FakeTaskStore.transition` | `task_update` (see below)|
 *
 * ## Two of them go through `TaskService` and two through the store, and the split is the contract
 * `task` and `task-del` are the operator-shaped ones: they stand for "somebody outside this browser
 * closed/removed the card", so they run the PRODUCTION path, side effects included — closing a task
 * unlinks every session holding it and deleting one unlinks them first, which is what makes a badge
 * disappear in a connected sidebar with no reload. `task-race` is the opposite: it must move exactly one
 * ref and disturb nothing else, so it calls the store directly and never reaches the unlink loop.
 * `task-add` has no service counterpart at all — `TaskService` does not create — and it deliberately uses
 * the fake's caller-chosen-ref door rather than `create`, which mints its own ref and would leave the
 * driver with nothing to name.
 *
 * ## Why a failure is `false` rather than an exception
 * The seam says `false` means "not a task command", and the harness turns an unhandled line into a stderr
 * message and a non-zero exit. A malformed argument — an unparseable ref, an unknown state word, a ref
 * that does not exist — takes the same exit, which is the right outcome for a fixture ("fail loudly").
 * The alternative, throwing across the seam, would put the harness's exit behaviour in this file's hands
 * rather than in the one place that owns it.
 *
 * What each arm below owes on the way out is the REASON, printed by [reject] before the `false` — the
 * contract `Commands.kt` states for the whole protocol. Answering a bare `false` costs the same exit but
 * hands the driver the wrong cause: `task local:1 dune` reached it as "unrecognised command", naming the
 * verb rather than the state word that was actually wrong.
 */

/**
 * Run [words] if they are a task command. Answers `false` for anything else — including a task command
 * whose arguments do not parse or do not name a live row (see the file header).
 *
 * The line arrives ALREADY SPLIT, by the one splitter in `Commands.kt`. Re-splitting here would be a
 * second, independently-maintained definition of "a word" over the same protocol.
 *
 * ## The `runBlocking` bridge
 * The seam is non-suspend while every store call under it suspends, so each arm owns one `runBlocking` —
 * the same shape the sibling dispatcher uses, and for the same reason: the stdin loop must stay OUT of
 * coroutine context, because `restart` reaches a `KotgentServer.stop()` that runs a nested `runBlocking`
 * of its own. The arms below are safe under either arrangement anyway: their only suspension points are
 * the fake stores' own `Mutex` — taken and released by the server's engine threads, never by the caller
 * blocked here — and a `SharedFlow.emit` into a buffered `DROP_OLDEST` flow, which does not suspend at
 * all. Nothing here waits on work scheduled on the caller's dispatcher, which is the shape that deadlocks
 * a nested `runBlocking`.
 */
fun handleTaskCommand(words: List<String>, ctx: HarnessContext): Boolean {
    return when (words.firstOrNull()) {
        "task" -> runBlocking { applyTaskState(ctx, words) }
        "task-add" -> runBlocking { addTask(ctx, words) }
        "task-del" -> runBlocking { deleteTask(ctx, words) }
        "task-race" -> runBlocking { raceTask(ctx, words) }
        else -> false
    }
}

/**
 * `task <ref> <state>` — move a card through the production [TaskService], which is what makes a
 * `done` also unlink every session holding it.
 *
 * The state word is the enum name exactly (`todo`, `in_progress`, `review`, `done`) — the same spelling
 * the wire and the DTOs use. No hyphenated alias is accepted: a fixture that quietly forgives a typo is
 * a fixture that quietly does nothing when a driver sends the wrong word.
 */
private suspend fun applyTaskState(ctx: HarnessContext, words: List<String>): Boolean {
    if (words.size != 3) return reject("usage: task <ref> <state>; states: ${taskStateNames()}")
    val ref = TaskRef.parseOrNull(words[1]) ?: return rejectRef(words[1])
    val state = taskStateOrNull(words[2])
        ?: return reject("task: '${words[2]}' is not a task state; expected one of ${taskStateNames()}")
    return taskService(ctx).transition(ref, state, TASK_COMMAND_AUTHOR) != null ||
        reject("task: no task '${ref.value}' in this scenario")
}

/**
 * `task-add <ref> [position]` — file a brand-new card at a ref the driver names, so the socket ships it
 * as a `task_row` rather than a patch.
 *
 * **A ref that already exists is refused**, and that refusal is the whole point: the socket carries every
 * seeded ref in its opening `tasks_snapshot`, so re-adding one would emit a `task_update` and the test
 * that thought it was proving the full-row path would pass against the wrong frame.
 *
 * The project is the first one the store knows, which is also the one the board has selected: `app.js`
 * picks `projects[0]` of the name-sorted `GET /projects`, and [io.kotgent.store.FakeTaskStore.listProjects]
 * sorts by the same key. Every scenario here registers exactly one project, so the two can only agree.
 *
 * ## Why the optional rank
 * Without it a new card always lands at the END of the project's column, and a board that ignored
 * `position` entirely — appending each `task_row` frame to the list as it arrives — would render it in
 * exactly the same place. So an ordering assertion built on the default is unfalsifiable. Naming a rank
 * that falls BETWEEN two seeded neighbours is what separates the two behaviours: the card must appear
 * between them, and an appending board puts it last. The value is a plain double in the same gap-based
 * space the seeds use (`1.0, 2.0, 3.0, …`), so `2.5` means "third slot".
 */
private suspend fun addTask(ctx: HarnessContext, words: List<String>): Boolean {
    if (words.size != 2 && words.size != 3) return reject("usage: task-add <ref> [position]")
    val ref = TaskRef.parseOrNull(words[1]) ?: return rejectRef(words[1])
    val position = if (words.size == 3) {
        words[2].toDoubleOrNull()
            ?: return reject("task-add: '${words[2]}' is not a rank; expected a number such as 2.5")
    } else {
        null
    }
    val tasks = ctx.fakes.tasks
    if (tasks.entry(ref) != null) {
        return reject(
            "task-add: '${ref.value}' already exists — the socket has carried it in its opening " +
                "snapshot, so re-adding it would emit a `task_update` and not the `task_row` you asked for",
        )
    }
    val project = tasks.listProjects().firstOrNull()?.id
        ?: return reject("task-add: this scenario registers no project to file '${ref.value}' under")
    // `${ref.value}`, never `$ref`: a value class with no `toString` renders as `TaskRef(value=local:42)`,
    // and that string would go straight onto a card.
    tasks.addTask(ref, project, title = "Added ${ref.value}", position = position)
    return true
}

/**
 * `task-del <ref>` — delete through [TaskService], so every holder is unlinked BEFORE the row goes and no
 * dangling badge is left behind. The store's own emission (`TaskUpdate` with a null entry) is what the
 * socket turns into `task_removed`.
 */
private suspend fun deleteTask(ctx: HarnessContext, words: List<String>): Boolean {
    if (words.size != 2) return reject("usage: task-del <ref>")
    val ref = TaskRef.parseOrNull(words[1]) ?: return rejectRef(words[1])
    return taskService(ctx).delete(ref) || reject("task-del: no task '${ref.value}' in this scenario")
}

/**
 * `task-race <ref>` — stage the newest-rev-wins race: make ONE strictly newer observation of [ref] exist,
 * so a REST answer the driver captured before the call is now stale.
 *
 * ## Why the race is a command and not a scenario
 * A scenario's seed runs before the server binds and emits nothing, so it cannot produce an ordering at
 * all: an inversion is by definition two observations at two different times. The harness can supply only
 * the newer one — the older is the driver's own held REST response, and holding it is the browser tier's
 * job, because a `MutableSharedFlow` cannot be made to deliver a rev out of order and no route answers a
 * row it has already superseded.
 *
 * The five steps a browser test takes, in this order:
 *  1. intercept the detail `GET` for the ref and HOLD its body — Playwright's `route.fetch()` without a
 *     `fulfill`. That body is the older observation, and the panel publishes it into the shared list
 *     (`TaskDetail.js` hands every row it observes back through `onTaskRow`).
 *  2. `harness.send("task-race local:3")` — one strictly newer rev goes out on the socket.
 *  3. assert the card has moved, which proves the frame landed.
 *  4. fulfil the held response, so the STALE body arrives last.
 *  5. assert the card has NOT moved back — `upsertTaskIfNewer` compared revs and refused it.
 *
 * ## What it deliberately does not touch
 * It calls the STORE, not [TaskService], so closing a card cannot also unlink a session and put a second
 * kind of frame on the wire in the middle of the measurement. The state advances one step along the
 * board's column order and wraps, so the driver needs no knowledge of where the card currently sits — and
 * the cycle is read off [TaskState]'s declaration order, which IS the column order the board renders
 * (`BOARD_COLUMNS` in `Board.js`); reordering that enum would silently reorder this.
 *
 * The one thing a caller owes: point it at a ref with no reverse dependents. A dependency's state change
 * re-stamps and re-emits the rows that depend on it, and those extra frames would let an assertion pass
 * on a row the race never touched. `local:3` in the `board` scenario is seeded free of edges for exactly
 * this.
 */
private suspend fun raceTask(ctx: HarnessContext, words: List<String>): Boolean {
    if (words.size != 2) return reject("usage: task-race <ref>")
    val ref = TaskRef.parseOrNull(words[1]) ?: return rejectRef(words[1])
    val current = ctx.fakes.tasks.entry(ref)
        ?: return reject("task-race: no task '${ref.value}' in this scenario")
    val next = TaskState.entries[(current.state.ordinal + 1) % TaskState.entries.size]
    return ctx.fakes.tasks.transition(ref, next, TASK_COMMAND_AUTHOR, message = null) != null ||
        reject("task-race: '${ref.value}' refused the step to ${next.name}")
}

/**
 * The harness's ONE [TaskService] — the same instance the HTTP routes run on.
 *
 * It used to be built per command with its own pinned clock, on the argument that the class is stateless
 * so a second instance is free. It is not free: `TaskService` carries a `now`, and a second instance is
 * a second clock, so `task local:1 done` typed on stdin stamped a different `sessions.updated_at` than
 * the identical transition performed from the board. A fixture that answers differently depending on
 * which door a request came in by is the one thing a fixture may never do.
 */
private fun taskService(ctx: HarnessContext): TaskService = ctx.taskService

/** [word] as a [TaskState], or `null` — the enum name exactly, no aliases. */
private fun taskStateOrNull(word: String): TaskState? = TaskState.entries.firstOrNull { it.name == word }

/** Every accepted state word, for a refusal that can be acted on rather than guessed at. */
private fun taskStateNames(): String = TaskState.entries.joinToString(" ") { it.name }

/** The one refusal all four verbs share: a first word that is not a `<tracker>:<key>` at all. */
private fun rejectRef(word: String): Boolean =
    reject("'$word' is not a task ref; expected <tracker>:<key>, e.g. local:3")

/**
 * The `author` every harness-driven task write signs.
 *
 * Not `TaskService.BOARD_AUTHOR` and not a session id: a command stands for a change made outside the
 * browser under test, and an activity feed that attributed it to the board would make a row a test caused
 * indistinguishable from one the fixture shipped with.
 */
private const val TASK_COMMAND_AUTHOR: String = "webuicheck"
