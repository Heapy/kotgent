package io.kotgent.buildinfo

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PrintKexePathTest {
    @Test
    fun theExecutableIsASiblingOfTheCallingTask() {
        val tasksDir = Path.of("/w/out/tasks")
        assertEquals(
            Path.of("/w/out/tasks/_kotgent_linkMacosArm64Debug/kotgent.kexe"),
            linkedExecutable(tasksDir, "kotgent", "Debug"),
        )
        // The module name follows the checkout directory and must reach both path segments.
        assertEquals(
            Path.of("/w/out/tasks/_review-wt_linkMacosArm64Release/review-wt.kexe"),
            linkedExecutable(tasksDir, "review-wt", "Release"),
        )
    }

    /** The record is what a script reads, so it must follow `--build-dir` like the executable does. */
    @Test
    fun theRecordSitsInTheBuildRootTheTaskRunsUnder() {
        assertEquals(Path.of("/w/out/kexe-path"), kexePathRecord(Path.of("/w/out/tasks")))
    }

    @Test
    fun aResolvedExecutableIsRecordedForScripts() = withBuildRoot { buildRoot ->
        val executable = linkedExecutable(buildRoot.resolve("tasks"), "kotgent", "Debug")
        executable.parent.createDirectories()
        executable.writeText("")

        printKexePath(taskOutputDir(buildRoot, "printDebugKexePath"), "kotgent", "Debug")

        assertEquals("$executable\n", buildRoot.resolve(KEXE_PATH_FILE_NAME).readText())
    }

    /**
     * Both commands write one record, so a lookup that fails must leave none behind: a script that
     * ignored the exit code would otherwise read an executable from an earlier, unrelated run.
     */
    @Test
    fun aFailedLookupRemovesAnEarlierRecord() = withBuildRoot { buildRoot ->
        val record = buildRoot.resolve(KEXE_PATH_FILE_NAME)
        record.writeText("/stale/kotgent.kexe\n")

        assertFailsWith<MissingExecutableException> {
            printKexePath(taskOutputDir(buildRoot, "printReleaseKexePath"), "kotgent", "Release")
        }

        assertFalse(record.exists())
    }

    private fun taskOutputDir(buildRoot: Path, task: String): Path =
        buildRoot.resolve("tasks").resolve("_kotgent_$task@build-info")

    private fun withBuildRoot(block: (Path) -> Unit) {
        val buildRoot = Files.createTempDirectory("build-info-kexe-path")
        try {
            block(buildRoot)
        } finally {
            buildRoot.toFile().deleteRecursively()
        }
    }
}
