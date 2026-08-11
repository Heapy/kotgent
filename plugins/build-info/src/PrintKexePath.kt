package io.kotgent.buildinfo

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Files
import java.nio.file.Path

// Toolchain 0.11 exposes neither native artifacts nor the build root. Deriving both from outputDir
// preserves --build-dir, and the record file avoids build-log prefixes that make stdout unsafe to parse.
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun printKexePath(
    @Input(inferTaskDependency = false) outputDir: Path,
    moduleName: String,
    buildType: String,
) {
    val tasksDir = outputDir.toAbsolutePath().normalize().parent
    val executable = linkedExecutable(tasksDir, moduleName, buildType)
    val record = kexePathRecord(tasksDir)

    // Never let a failed lookup leave a previous build type's executable as the answer.
    Files.deleteIfExists(record)
    if (!Files.isRegularFile(executable)) {
        throw MissingExecutableException(
            "no $buildType executable at $executable; run `./kotlin build` first",
        )
    }

    Files.writeString(record, "$executable\n")
    println(executable)
}

/** Kept in the build root so callers can find it and `clean` removes it with the binary. */
const val KEXE_PATH_FILE_NAME: String = "kexe-path"

fun linkedExecutable(tasksDir: Path, moduleName: String, buildType: String): Path =
    tasksDir.resolve("_${moduleName}_linkMacosArm64$buildType").resolve("$moduleName.kexe")

fun kexePathRecord(tasksDir: Path): Path = tasksDir.parent.resolve(KEXE_PATH_FILE_NAME)

/** Suppresses the stack trace because Toolchain renders plugin action failures directly to users. */
class MissingExecutableException(message: String) : RuntimeException(message, null, false, false)
