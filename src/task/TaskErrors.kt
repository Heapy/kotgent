package io.kotgent.task

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef

/*
 * The typed failures the task routes map to statuses.
 *
 * Deliberately **standalone and hierarchy-free**, exactly like the four import failures
 * (`UnknownAgentKindException`, `ImportCwdException`, `TranscriptNotFoundException`,
 * `DuplicateImportException`): with no common supertype, a route's `catch` clauses are order-free and a
 * new failure cannot be silently swallowed by an existing broader branch. That is the opposite of the
 * `TmuxCopyModeException extends TmuxException` pattern, where the SUBTYPE is what lets one call site
 * answer 409 instead of 400 — here nothing wants that, so nothing pays for it.
 *
 * Status mapping (the routes own it; recorded here so the three route files agree):
 *   UnknownTaskException      -> 404   (the ref addresses a resource that is not there)
 *   MalformedTaskRefException -> 400   ("local:42" is the shape; this was not it)
 *   UnknownProjectException   -> 404
 *   NoProjectException        -> 400   (names `--project`)
 *   NoSessionException        -> 400   (names `--session`)
 *   DependencyRefusedException-> 400   (carries which of the four refusals it was)
 *   ProjectPathException      -> 400
 */

/** The ref is well-formed but no such task exists. */
class UnknownTaskException(val ref: TaskRef) :
    RuntimeException("no such task '${ref.value}'")

/** The text is not a `<tracker>:<key>` ref (see [TaskRef]'s invariant). */
class MalformedTaskRefException(val value: String) :
    RuntimeException("malformed task ref '$value' — expected '<tracker>:<key>', e.g. 'local:42'")

/** The uuid is well-formed but the daemon has never seen that project. */
class UnknownProjectException(val id: ProjectId) :
    RuntimeException("no such project '${id.value}'")

/**
 * No project could be resolved and none could be created — the board path with nothing selected, or a
 * CLI call from outside any session. The message names `--project` because that is the fix.
 */
class NoProjectException(message: String) : RuntimeException(message)

/**
 * The request needs a session and none could be resolved: no `X-Kotgent-Tmux-Pane` header, a pane the
 * registry does not know, or no `sessionId` in the body. `link`, `unlink`, `comment` and `next` all
 * write `sessions.task_ref` or attribute an activity row, so none of them means anything without one.
 * The message names `--session` because that is the fix from outside a kotgent pane.
 */
class NoSessionException(message: String) : RuntimeException(message)

/** Why a dependency edge was refused — all four are validated on insert, all four answer `400`. */
enum class DependencyRefusal {
    /** `a depends on a`. */
    self,

    /** One of the two refs is not in `backlog_entries`. A dangling edge would read as "already satisfied". */
    unknownRef,

    /** The two refs belong to different projects; `nextCandidate` joins within a project only. */
    crossProject,

    /** The edge would close a cycle (a pure ancestor walk found the target above the source). */
    cycle,
}

/** A dependency edge was refused; [refusal] says which of the four rules rejected it. */
class DependencyRefusedException(
    val refusal: DependencyRefusal,
    val ref: TaskRef,
    val dependsOn: TaskRef,
    message: String,
) : RuntimeException(message)

/**
 * A project file could not be created at the requested location: the path is relative, is not an
 * existing directory, or the write itself failed. `POST /projects` writes at a browser-supplied absolute
 * path — a deliberate, bounded departure from the "session-cwd write, never an arbitrary-path API" rule
 * uploads follow (the path must be absolute and an existing directory, the only file written is
 * `.kotgent.json`, publication is `link(2)` so an existing file always wins, and the mode is
 * `0666 & ~umask` because the file is meant to be committed).
 */
class ProjectPathException(val path: String, message: String) : RuntimeException(message)
