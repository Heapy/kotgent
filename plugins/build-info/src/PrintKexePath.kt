package io.kotgent.buildinfo

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Files
import java.nio.file.Path

// Toolchain exposes neither native artifacts nor the build root. Deriving both from outputDir
// preserves --build-dir, and the record file avoids build-log prefixes that make stdout unsafe to parse.
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun printKexePath(
    @Input(inferTaskDependency = false) outputDir: Path,
    buildType: String,
) {
    recordKexePath(outputDir, buildType) {
        System.getenv("KOTGENT_TARGET_PLATFORM")?.takeIf(String::isNotBlank)
            ?: hostPlatform(System.getProperty("os.name"), System.getProperty("os.arch"))
    }
}

fun recordKexePath(outputDir: Path, buildType: String, platform: () -> String) {
    val tasksDir = outputDir.toAbsolutePath().normalize().parent
    val record = kexePathRecord(tasksDir)

    // Never let a failed lookup leave a previous build type's executable as the answer.
    Files.deleteIfExists(record)
    val executable = linkedExecutable(tasksDir, platform(), buildType)
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

fun hostPlatform(os: String, arch: String): String = when {
    os == "Mac OS X" && arch in setOf("aarch64", "arm64") -> "macosArm64"
    os == "Linux" && arch in setOf("amd64", "x86_64") -> "linuxX64"
    os == "Linux" && arch in setOf("aarch64", "arm64") -> "linuxArm64"
    else -> throw MissingExecutableException("unsupported host: $os/$arch; set KOTGENT_TARGET_PLATFORM for a cross build")
}

fun linkedExecutable(tasksDir: Path, platform: String, buildType: String): Path {
    require(buildType in setOf("Debug", "Release")) { "unknown build variant: $buildType" }
    val moduleName = when (platform) {
        "macosArm64" -> "kotgent-macos"
        "linuxX64", "linuxArm64" -> "kotgent-linux"
        else -> throw MissingExecutableException("unsupported KOTGENT_TARGET_PLATFORM: $platform")
    }
    val target = platform.replaceFirstChar(Char::uppercaseChar)
    return tasksDir.resolve("_${moduleName}_link$target$buildType").resolve("$moduleName.kexe")
}

fun kexePathRecord(tasksDir: Path): Path = tasksDir.parent.resolve(KEXE_PATH_FILE_NAME)

/** Suppresses the stack trace because Toolchain renders plugin action failures directly to users. */
class MissingExecutableException(message: String) : RuntimeException(message, null, false, false)
