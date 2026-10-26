package io.kotgent.daemon

import io.kotgent.core.ProjectId

const val PROJECT_LOOKUP_MAX_PATHS: Int = 64
const val PROJECT_LOOKUP_MAX_PATH_LENGTH: Int = 4096

class ProjectLookupValidationException(message: String) : Exception(message)

sealed interface ProjectLookup {
    val path: String

    data class Found(override val path: String, val projectId: ProjectId) : ProjectLookup
    data class Absent(override val path: String) : ProjectLookup
    data class Failed(override val path: String) : ProjectLookup
}
