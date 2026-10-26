package io.kotgent.daemon

import io.kotgent.core.ProjectId
import io.kotgent.store.FakeTaskStore
import io.kotgent.store.ForbiddingInterceptor
import io.kotgent.task.FakeProjectFs
import io.kotgent.task.ProjectFs
import io.kotgent.task.resolveProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectLookupServiceTest {
    private val alpha = ProjectId.of("33333333-3333-4333-8333-333333333333")
    private val archived = ProjectId.of("44444444-4444-4444-8444-444444444444")
    private val unregistered = ProjectId.of("55555555-5555-4555-8555-555555555555")

    private fun fixture(fs: ProjectFs, reportFailure: (String) -> Unit = {}): ProjectLookupService {
        val tasks = FakeTaskStore().apply {
            seedProject(alpha, "Alpha", "/last-seen")
            seedProject(archived, "Archived", "/archived", archived = true)
            interceptor = ForbiddingInterceptor(setOf("upsertProject", "setProjectArchived")) { _, method ->
                "lookup must not call $method"
            }
        }
        return ProjectLookupService(fs, tasks::project, reportFailure)
    }

    @Test
    fun successfulFoldersSurviveAnotherFoldersFailureAndNoProjectsAreAdopted() = runBlocking {
        val fs = FakeProjectFs(files = mapOf(
            "/repo/.kotgent.json" to """{"id":"${alpha.value}","name":"Alpha"}""",
            "/archived/.kotgent.json" to """{"id":"${archived.value}","name":"Archived"}""",
            "/unknown/.kotgent.json" to """{"id":"${unregistered.value}","name":"Unknown"}""",
        ))
        val faulty = object : ProjectFs by fs {
            override fun isDirectory(path: String): Boolean {
                if (path == "/broken") error("temporary I/O failure")
                return fs.isDirectory(path)
            }
        }
        val rows = fixture(faulty).lookupProjects(listOf("/broken", "/repo", "/archived", "/unknown", "/missing"))
        assertEquals(listOf(ProjectLookup.Failed("/broken"), ProjectLookup.Found("/repo", alpha),
            ProjectLookup.Absent("/archived"), ProjectLookup.Absent("/unknown"), ProjectLookup.Absent("/missing")), rows)
    }

    @Test
    fun oneBatchSharesParentReadsButNextBatchObservesFilesystemChanges() = runBlocking {
        val fs = FakeProjectFs(dirs = listOf("/repo/a", "/repo/b"), files = mapOf(
            "/repo/.kotgent.json" to """{"id":"${alpha.value}","name":"Alpha"}""",
        ))
        val service = fixture(fs)
        assertEquals(listOf(ProjectLookup.Found("/repo/a", alpha), ProjectLookup.Found("/repo/b", alpha)),
            service.lookupProjects(listOf("/repo/a", "/repo/b")))
        assertEquals(1, fs.reads.count { it == "/repo/.kotgent.json" })
        fs.writeFile("/repo/.kotgent.json", "invalid")
        assertEquals(listOf(ProjectLookup.Absent("/repo/a"), ProjectLookup.Absent("/repo/b")),
            service.lookupProjects(listOf("/repo/a", "/repo/b")))
        assertEquals(2, fs.reads.count { it == "/repo/.kotgent.json" })
        assertEquals(1, fs.reads.count { it == "/.kotgent.json" }, "negative reads are shared too")
    }

    @Test
    fun cancellationEscapesInsteadOfBecomingAFolderFailure() = runBlocking {
        val fs = object : ProjectFs by FakeProjectFs() {
            override fun isDirectory(path: String): Boolean = throw CancellationException("cancelled")
        }
        val warnings = mutableListOf<String>()
        assertFailsWith<CancellationException> {
            fixture(fs) { warnings.add(it) }.lookupProjects(listOf("/repo"))
        }
        assertEquals(emptyList(), warnings)
        Unit
    }

    @Test
    fun unexpectedFailuresReportOneDiagnosticPerBatchAndRemainRetryable() = runBlocking {
        var broken = true
        val failure = IllegalStateException("storage unavailable")
        val fs = object : ProjectFs by FakeProjectFs() {
            override fun isDirectory(path: String): Boolean {
                if (broken) throw failure
                return false
            }
        }
        val warnings = mutableListOf<String>()
        val service = fixture(fs) { warnings.add(it) }
        repeat(2) {
            assertEquals(listOf(ProjectLookup.Failed("/a"), ProjectLookup.Failed("/b")),
                service.lookupProjects(listOf("/a", "/b")))
        }
        assertEquals(List(2) {
            "warning: project lookup failed for 2 folder(s); first failure at '/a': $failure"
        }, warnings)
        broken = false
        assertEquals(listOf(ProjectLookup.Absent("/a")), service.lookupProjects(listOf("/a")))
        assertEquals(2, warnings.size)
    }

    @Test
    fun invalidBatchesAreRejectedBeforeFilesystemAccess() = runBlocking {
        val fs = object : ProjectFs by FakeProjectFs() {
            override fun isDirectory(path: String): Boolean = error("must not read")
        }
        for (paths in listOf(listOf("relative"), listOf("/bad\u0000path"),
            listOf("/" + "a".repeat(PROJECT_LOOKUP_MAX_PATH_LENGTH)), List(PROJECT_LOOKUP_MAX_PATHS + 1) { "/repo" })) {
            assertFailsWith<ProjectLookupValidationException> { fixture(fs).lookupProjects(paths) }
        }
    }

    @Test
    fun quietResolutionKeepsNearestValidAncestorSemanticsAndDiagnosticsRemainAvailable() {
        val fs = FakeProjectFs(files = mapOf(
            "/repo/.kotgent.json" to """{"id":"${alpha.value}","name":"Alpha"}""",
            "/repo/sub/.kotgent.json" to "invalid",
        ))
        val warnings = mutableListOf<String>()
        val reported = resolveProject(fs, "/repo/sub") { val _ = warnings.add(it) }
        val quiet = resolveProject(fs, "/repo/sub", reportInvalid = {})
        assertEquals(reported, quiet)
        assertEquals(alpha, quiet?.id)
        assertEquals(listOf("/repo/sub/.kotgent.json"), warnings)
    }
}
