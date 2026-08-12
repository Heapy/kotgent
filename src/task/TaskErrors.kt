package io.kotgent.task

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef


class UnknownTaskException(val ref: TaskRef) :
    RuntimeException("no such task '${ref.value}'")

class MalformedTaskRefException(val value: String) :
    RuntimeException("malformed task ref '$value' — expected '<tracker>:<key>', e.g. 'local:42'")

class UnknownProjectException(val id: ProjectId) :
    RuntimeException("no such project '${id.value}'")

/**
 * The store refused a write because the project carries the delete tombstone, decided inside the same
 * transaction as the write it refused. It exists so the check and the insert cannot come apart: a route
 * that reads [io.kotgent.store.TaskStore.project] and then calls
 * [io.kotgent.task.TaskTracker.create] holds no lock between the two, so a `DELETE /projects/{id}`
 * landing in that gap would file a card into a project the board no longer lists.
 *
 * A caller is expected to catch it and phrase the refusal in its own terms — the message here is the
 * fallback, not the one an operator should normally read.
 */
class ArchivedProjectException(val id: ProjectId) :
    RuntimeException(
        "project '${id.value}' was deleted — bring it back with `kotgent project restore ${id.value}`",
    )

class NoProjectException(message: String) : RuntimeException(message)

class NoSessionException(message: String) : RuntimeException(message)

enum class DependencyRefusal {
    self,

    unknownRef,

    crossProject,

    cycle,
}

class DependencyRefusedException(
    val refusal: DependencyRefusal,
    val ref: TaskRef,
    val dependsOn: TaskRef,
    message: String,
) : RuntimeException(message)

class ProjectPathException(val path: String, message: String) : RuntimeException(message)
