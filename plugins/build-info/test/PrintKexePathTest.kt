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
            Path.of("/w/out/tasks/_kotgent-macos_linkMacosArm64Debug/kotgent-macos.kexe"),
            linkedExecutable(tasksDir, "macosArm64", "Debug"),
        )
        assertEquals(
            Path.of("/w/out/tasks/_kotgent-linux_linkLinuxX64Release/kotgent-linux.kexe"),
            linkedExecutable(tasksDir, "linuxX64", "Release"),
        )
        assertEquals(
            Path.of("/w/out/tasks/_kotgent-linux_linkLinuxArm64Release/kotgent-linux.kexe"),
            linkedExecutable(tasksDir, "linuxArm64", "Release"),
        )
    }

    @Test
    fun hostAliasesSelectOnlyTheirOwnArchitecture() {
        assertEquals("macosArm64", hostPlatform("Mac OS X", "aarch64"))
        assertEquals("linuxX64", hostPlatform("Linux", "amd64"))
        assertEquals("linuxArm64", hostPlatform("Linux", "aarch64"))
        assertFailsWith<MissingExecutableException> { hostPlatform("Mac OS X", "x86_64") }
    }

    @Test
    fun theRecordSitsInTheBuildRootTheTaskRunsUnder() {
        assertEquals(Path.of("/w/out/kexe-path"), kexePathRecord(Path.of("/w/out/tasks")))
    }

    @Test
    fun aResolvedExecutableIsRecordedForScripts() = withBuildRoot { buildRoot ->
        val executable = linkedExecutable(buildRoot.resolve("tasks"), "linuxArm64", "Debug")
        executable.parent.createDirectories()
        executable.writeText("")

        recordKexePath(taskOutputDir(buildRoot, "printDebugKexePath"), "Debug") { "linuxArm64" }

        assertEquals("$executable\n", buildRoot.resolve(KEXE_PATH_FILE_NAME).readText())
    }

    @Test
    fun aFailedLookupRemovesAnEarlierRecord() = withBuildRoot { buildRoot ->
        val record = buildRoot.resolve(KEXE_PATH_FILE_NAME)
        record.writeText("/stale/kotgent.kexe\n")

        assertFailsWith<MissingExecutableException> {
            recordKexePath(taskOutputDir(buildRoot, "printReleaseKexePath"), "Release") { "linuxX64" }
        }

        assertFalse(record.exists())
    }

    @Test
    fun anUnsupportedTargetAlsoInvalidatesTheRecord() = withBuildRoot { buildRoot ->
        val record = buildRoot.resolve(KEXE_PATH_FILE_NAME)
        record.writeText("/stale/kotgent.kexe\n")
        assertFailsWith<MissingExecutableException> {
            recordKexePath(taskOutputDir(buildRoot, "printReleaseKexePath"), "Release") { "../bad" }
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
