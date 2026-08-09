package io.kotgent.cli

import io.kotgent.task.MoveTarget

/*
 * The `task` and `project` families of [CliCommand].
 *
 * They live in their own file, in the same package, purely so Task 19 of the task-backlog plan can own
 * `Cli.kt` (the parser) alone: a sealed interface's implementations may be declared in any file of the
 * same package and module, so nothing about the hierarchy changes by moving them here.
 *
 * Three flags recur and mean the same thing everywhere:
 *  - `--session <id>` names the session explicitly. When it is given the CLI **skips `/whoami`
 *    entirely** — it already knows the id and sends it in the body; `/whoami` is pane resolution, not a
 *    session lookup. It is also the only way to run these commands from outside a kotgent pane.
 *  - `--project <uuid>` names the project for the commands that are not about one task.
 *  - `-m/--message <text>` carries a comment or a transition's explanation; `-` reads it from stdin, so
 *    an agent can pipe a long summary without quoting it.
 *
 * `show`, `comment`, `review`, `done` and `unlink` take an OPTIONAL ref: an agent inside a pane knows
 * only its pane, so a ref-less form resolves its subject through `GET /whoami`.
 *
 * The whole family prints **JSON only** — it is written for an agent to parse, not for a human to read.
 */

/** `task add <title> [--body B] [--project P] [--session S]` — create a task in a project. */
data class TaskAdd(
    val title: String,
    val body: String?,
    val project: String?,
    val session: String?,
) : CliCommand

/** `task list [--project P] [--session S]` — the project's backlog, in rank order. */
data class TaskList(val project: String?, val session: String?) : CliCommand

/** `task show [<ref>] [--session S]` — one task in full; a missing ref resolves through `/whoami`. */
data class TaskShow(val ref: String?, val session: String?) : CliCommand

/**
 * `task next [--project P] [--session S]` — take the next eligible task and link it to this session.
 * Exits `3`, and only `3`, when nothing is eligible.
 */
data class TaskNext(val project: String?, val session: String?) : CliCommand

/**
 * `task claim <ref> [--session S]` — link this session to a specific task. Allowed even when the task is
 * already `in_progress`: kotgent enforces no exclusivity, so this simply adds another link.
 */
data class TaskClaim(val ref: String, val session: String?) : CliCommand

/** `task comment [<ref>] -m <text> [--session S]`. */
data class TaskComment(val ref: String?, val message: String, val session: String?) : CliCommand

/** `task review [<ref>] [-m <text>] [--session S]` — move to `review`, optionally with an explanation. */
data class TaskReview(val ref: String?, val message: String?, val session: String?) : CliCommand

/**
 * `task done [<ref>] [-m <text>] [--session S]` — close the task, which unlinks every session holding it
 * and leaves them ALIVE. Archiving a session is the session's own `done`, not this.
 */
data class TaskDone(val ref: String?, val message: String?, val session: String?) : CliCommand

/** `task unlink [<ref>] [--session S]` — drop this session's link; the task's state is untouched. */
data class TaskUnlink(val ref: String?, val session: String?) : CliCommand

/** `task move <ref> (--top | --bottom | --before R | --after R)` — re-rank within the project. */
data class TaskMove(val ref: String, val target: MoveTarget, val session: String?) : CliCommand

/** `task dep add|rm <ref> --on <other>` — add or remove "ref depends on other". */
data class TaskDep(
    val ref: String,
    val on: String,
    val remove: Boolean,
    val session: String?,
) : CliCommand

/** `task delete <ref>` — unlink every holder, then remove the task, its dependencies and its feed. */
data class TaskDelete(val ref: String, val session: String?) : CliCommand

/** `project list` — every project the daemon knows. */
data object ProjectList : CliCommand

/**
 * `project init [<path>] [--name N]` — write `.kotgent.json` at the main checkout root for [path]
 * (default: the caller's cwd). The daemon writes the file and never commits it.
 */
data class ProjectInit(val path: String?, val name: String?) : CliCommand
