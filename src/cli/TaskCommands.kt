package io.kotgent.cli

import io.kotgent.task.MoveTarget

/**
 * The side-effecting half of the `task` / `project` CLI families — the [Commands] shape, one function per
 * [CliCommand] variant, each returning a process exit code.
 *
 * ## Two rules the whole family obeys
 *  1. **JSON only, on stdout.** These commands exist for an agent inside a pane to parse. A human-readable
 *     table would be a second format to keep in step with the DTOs for no reader.
 *  2. **`--session` short-circuits `/whoami`.** A ref-less subcommand resolves its subject through
 *     `GET /whoami` (pane → session), but when `--session <id>` was given the CLI already knows the id and
 *     **must not** make that call: `/whoami` is pane resolution, and asking it from outside a kotgent pane
 *     would fail for a request that has everything it needs.
 *
 * ## Exit codes
 *  - `0` success, `1` a daemon/API failure, `2` a usage error ([CliCommand.Invalid], handled in `Cli.kt`).
 *  - **`3` and only `3` means "nothing eligible"**, from [next]. It is a distinct code because a script
 *    that stops on an empty backlog must not also stop on a network error.
 *
 * ## `start --task`'s cwd
 * [startWithTask] chooses, in order: the caller's cwd when it resolves to the task's project; else the
 * project's stored `path`; else the caller's cwd again when that stored path no longer exists. Whichever
 * it used is named in the JSON output, because "kotgent started this somewhere else" is exactly the kind
 * of surprise a silent fallback creates.
 *
 * Bodies are [TODO] on purpose: Task 21 of the task-backlog plan implements this file.
 */
object TaskCommands {

    /** `task add`. */
    fun add(title: String, body: String?, project: String?, session: String?): Int =
        TODO("Task 21: task add")

    /** `task list`. */
    fun list(project: String?, session: String?): Int = TODO("Task 21: task list")

    /** `task show` — a null [ref] resolves through `/whoami`. */
    fun show(ref: String?, session: String?): Int = TODO("Task 21: task show")

    /** `task next` — exits `3` when nothing is eligible. */
    fun next(project: String?, session: String?): Int = TODO("Task 21: task next")

    /** `task claim`. */
    fun claim(ref: String, session: String?): Int = TODO("Task 21: task claim")

    /** `task comment`. */
    fun comment(ref: String?, message: String, session: String?): Int = TODO("Task 21: task comment")

    /** `task review`. */
    fun review(ref: String?, message: String?, session: String?): Int = TODO("Task 21: task review")

    /** `task done` — closes the task and unlinks every holder; the sessions stay alive. */
    fun done(ref: String?, message: String?, session: String?): Int = TODO("Task 21: task done")

    /** `task unlink` — drops this session's link and leaves the task's state alone. */
    fun unlink(ref: String?, session: String?): Int = TODO("Task 21: task unlink")

    /** `task move`. */
    fun move(ref: String, target: MoveTarget, session: String?): Int = TODO("Task 21: task move")

    /** `task dep add|rm`. */
    fun dep(ref: String, on: String, remove: Boolean, session: String?): Int = TODO("Task 21: task dep")

    /** `task delete`. */
    fun delete(ref: String, session: String?): Int = TODO("Task 21: task delete")

    /** `project list`. */
    fun projectList(): Int = TODO("Task 21: project list")

    /** `project init` — a null [path] means the caller's cwd. */
    fun projectInit(path: String?, name: String?): Int = TODO("Task 21: project init")

    /**
     * `start <agent> [cwd] --task <ref>` — one `POST /api/v1/sessions` carrying the `taskRef`, so the
     * session row and its link are written by the same request and a failed launch leaves no link behind.
     * [cwd] is the caller's already-resolved working directory; see the cwd rule in this object's KDoc for
     * when it is overridden by the project's stored path.
     */
    fun startWithTask(
        agent: String,
        cwd: String,
        taskRef: String,
        name: String?,
        tags: List<String>,
    ): Int = TODO("Task 21: start --task")
}
