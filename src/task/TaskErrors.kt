package io.kotgent.task

import io.kotgent.core.ProjectId
import io.kotgent.core.TaskRef


class UnknownTaskException(val ref: TaskRef) :
    RuntimeException("no such task '${ref.value}'")

class MalformedTaskRefException(val value: String) :
    RuntimeException("malformed task ref '$value' — expected '<tracker>:<key>', e.g. 'local:42'")

class UnknownProjectException(val id: ProjectId) :
    RuntimeException("no such project '${id.value}'")

/** Raised when an atomic store write refuses an archived project. */
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
