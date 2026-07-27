package io.kotgent.buildinfo

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reports the root macOS application's linked executable for the requested build type.
 *
 * The path is printed for a human and written to [KEXE_PATH_FILE_NAME] for a script. A task action's
 * stdout is not a usable channel for the latter: the toolchain re-emits it through the build log, so
 * it always arrives prefixed and mixed with the log's own lines, `--log-level` silences the path
 * itself before it silences the noise around it, and on a cold cache the `./kotlin` wrapper prints
 * its download progress to the same stream. The record file has none of those problems.
 *
 * Kotlin Toolchain 0.11 exposes JVM compilation artifacts to plugins, but neither native linked
 * executables nor the build root, so both are derived from this task's own output directory: the
 * link task's directory is its sibling under `tasks/`, and the build root is that directory's
 * parent. Deriving them keeps `--build-dir` working, which a baked-in `<project root>/build` did not.
 */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun printKexePath(
    @Input(inferTaskDependency = false) outputDir: Path,
    moduleName: String,
    buildType: String,
) {
    val tasksDir = outputDir.toAbsolutePath().normalize().parent
    val executable = linkedExecutable(tasksDir, moduleName, buildType)
    val record = kexePathRecord(tasksDir)

    // A stale record must not outlive a failed lookup. Both commands write the same file, so a
    // script that skipped the exit code would otherwise read the previous run's executable — a
    // different build type, or one this build never produced — as if it were this run's answer.
    Files.deleteIfExists(record)
    if (!Files.isRegularFile(executable)) {
        throw MissingExecutableException(
            "no $buildType executable at $executable; run `./kotlin build` first",
        )
    }

    Files.writeString(record, "$executable\n")
    println(executable)
}

/**
 * The build root's record of the executable the last `kexePath` command resolved.
 *
 * It lives in the build root rather than the task's output directory so that a caller can name it
 * without reconstructing a task directory, and so `./kotlin clean` discards it with the binary it
 * describes.
 */
const val KEXE_PATH_FILE_NAME: String = "kexe-path"

/**
 * The native linker's output for [moduleName] and [buildType], as a sibling of the calling task.
 *
 * The root module's name follows the checkout/worktree directory and appears in both the directory
 * and the filename, so neither may be baked into the plugin configuration.
 */
fun linkedExecutable(tasksDir: Path, moduleName: String, buildType: String): Path =
    tasksDir.resolve("_${moduleName}_linkMacosArm64$buildType").resolve("$moduleName.kexe")

fun kexePathRecord(tasksDir: Path): Path = tasksDir.parent.resolve(KEXE_PATH_FILE_NAME)

/**
 * Reports a build that has not produced the requested executable.
 *
 * The toolchain renders an action's exception as `ERROR: <class>: <message>` followed by its stack
 * trace, and no user-facing error type is on a plugin's classpath. Suppressing the trace is what
 * keeps the actionable sentence readable, so this deliberately carries none.
 */
class MissingExecutableException(message: String) : RuntimeException(message, null, false, false)
