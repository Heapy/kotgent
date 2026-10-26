package io.kotgent.daemon

import io.kotgent.cli.eprintln
import io.kotgent.core.ProjectId
import io.kotgent.task.ProjectFs
import io.kotgent.task.ProjectRecord
import io.kotgent.task.resolveProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ProjectLookupService(
    private val projectFs: ProjectFs,
    private val findProject: suspend (ProjectId) -> ProjectRecord?,
    private val reportFailure: (String) -> Unit = ::eprintln,
) {
    /** Background discovery never adopts, restores or updates a project's last-seen checkout. */
    suspend fun lookupProjects(paths: List<String>): List<ProjectLookup> {
        if (paths.size > PROJECT_LOOKUP_MAX_PATHS) {
            throw ProjectLookupValidationException("too many project paths")
        }
        if (paths.any { !it.startsWith('/') || it.length > PROJECT_LOOKUP_MAX_PATH_LENGTH || '\u0000' in it }) {
            throw ProjectLookupValidationException("project paths must be absolute, bounded and contain no NUL")
        }
        return withContext(Dispatchers.IO) {
            val fs = ProjectLookupFs(projectFs)
            var failures = 0
            var firstFailure: String? = null
            val results = paths.distinct().map { path ->
                coroutineContext.ensureActive()
                try {
                    // Startup/adoption still report malformed files. Background UI reads are quiet.
                    val owner = if (fs.isDirectory(path)) resolveProject(fs, path, reportInvalid = {}) else null
                    val project = owner?.let { findProject(it.id) }?.takeUnless { it.archived }
                    if (project == null) ProjectLookup.Absent(path)
                    else ProjectLookup.Found(path, project.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if (firstFailure == null) firstFailure = "'$path': $e"
                    ProjectLookup.Failed(path)
                }
            }
            if (failures > 0) {
                reportFailure("warning: project lookup failed for $failures folder(s); first failure at $firstFailure")
            }
            results
        }
    }
}

/** A request-local cache shares ancestor reads, including missing files, without retaining stale FS data. */
private class ProjectLookupFs(private val delegate: ProjectFs) : ProjectFs {
    private val directories = mutableMapOf<String, Boolean>()
    private val canonical = mutableMapOf<String, String?>()
    private val files = mutableMapOf<Pair<String, Int>, String?>()

    override fun isDirectory(path: String): Boolean = directories.getOrPut(path) { delegate.isDirectory(path) }

    override fun canonicalize(path: String): String? {
        if (path !in canonical) canonical[path] = delegate.canonicalize(path)
        return canonical[path]
    }

    override fun readFile(path: String, maxBytes: Int): String? {
        val key = path to maxBytes
        if (key !in files) files[key] = delegate.readFile(path, maxBytes)
        return files[key]
    }
}
