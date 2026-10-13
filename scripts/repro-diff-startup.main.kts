#!/usr/bin/env kotlinr

/**
 * Stress only the disposable diff harness startup; never starts a daemon or provider.
 * Run from the repository root after building the Web UI and native harness:
 *   kotgent mutex run kotlin-build -- kotlinr scripts/repro-diff-startup.main.kts
 * Options: --parallelism=16 --rounds=1 --scenario=diff-history --binary=/absolute/path/to/harness
 * For a pre-fix binary, select --scenario=diff. First invocation compiles the script.
 */

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

val usage = "Usage: kotlinr scripts/repro-diff-startup.main.kts [--parallelism=16] [--rounds=1] " +
    "[--scenario=diff-history] [--binary=/absolute/path/to/harness]"
if (args.contentEquals(arrayOf("--help"))) {
    println(usage)
    exitProcess(0)
}
val options = mutableMapOf<String, String>()
for (argument in args) {
    val parts = argument.removePrefix("--").split('=', limit = 2)
    require(argument.startsWith("--") && parts.size == 2 && parts[0] in
        setOf("parallelism", "rounds", "scenario", "binary")) { usage }
    require(options.put(parts[0], parts[1]) == null) { "Duplicate option: ${parts[0]}" }
}
val parallelism = options["parallelism"]?.toInt() ?: 16
val rounds = options["rounds"]?.toInt() ?: 1
val scenario = options["scenario"] ?: "diff-history"
require(parallelism in 1..32 && rounds in 1..10) { "Use 1..32 workers and 1..10 rounds" }
require(scenario in setOf("diff", "diff-history")) { "Only disposable diff scenarios are allowed" }
val root = Path.of("").toAbsolutePath().normalize()
require(Files.isRegularFile(root.resolve("project.yaml"))) { "Run from the repository root" }
val defaultBinary = when {
    System.getProperty("os.name").startsWith("Mac") ->
        "build/tasks/_webuicheck-macos_linkMacosArm64Debug/webuicheck-macos.kexe"
    System.getProperty("os.name") == "Linux" ->
        "build/tasks/_webuicheck-linux_linkLinuxX64Debug/webuicheck-linux.kexe"
    else -> error("Pass --binary for this host")
}
val binary = root.resolve(options["binary"] ?: defaultBinary).normalize()
val webui = root.resolve("resources/webui")
require(Files.isExecutable(binary)) { "Build the harness first: $binary" }
require(Files.isRegularFile(webui.resolve("index.html"))) { "Build the Web UI first" }
val output = Files.createTempDirectory("kotgent-diff-startup.")
println("Diagnostics: $output")

data class Attempt(val id: Int, val millis: Long, val exit: Int?, val ready: Boolean, val timedOut: Boolean)

fun attempt(id: Int, gate: CountDownLatch): Attempt {
    gate.await()
    val stdout = output.resolve("$id.stdout")
    val stderr = output.resolve("$id.stderr")
    val started = System.nanoTime()
    val child = ProcessBuilder(binary.toString(), "--scenario=$scenario", "--webui-dir=$webui",
        "--exit-after-ms=30000")
        .directory(root.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
    var finished = false
    try {
        // EOF is consumed only after startup; it then triggers the harness's normal fixture cleanup.
        child.outputStream.close()
        finished = child.waitFor(45, TimeUnit.SECONDS)
    } finally {
        if (child.isAlive) {
            val descendants = child.descendants().use { it.toList() }
            descendants.asReversed().forEach { it.destroyForcibly() }
            child.destroyForcibly()
            check(child.waitFor(5, TimeUnit.SECONDS)) { "Harness ${child.pid()} did not terminate" }
        }
    }
    val ready = Files.readAllLines(stdout).let { lines ->
        lines.any { it.startsWith("PORT=") } && lines.any { it == "READY" }
    }
    return Attempt(id, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        if (finished) child.exitValue() else null, ready, !finished)
}

val attempts = mutableListOf<Attempt>()
val pool = Executors.newFixedThreadPool(parallelism)
try {
    repeat(rounds) { round ->
        val gate = CountDownLatch(1)
        val pending = (0..<parallelism).map { worker ->
            pool.submit(Callable { attempt(round * parallelism + worker, gate) })
        }
        gate.countDown()
        pending.forEach { future ->
            val result = future.get(55, TimeUnit.SECONDS)
            attempts += result
            println("${result.id}: ${result.millis} ms, exit=${result.exit}, ready=${result.ready}, timeout=${result.timedOut}")
        }
    }
} finally {
    pool.shutdownNow()
    check(pool.awaitTermination(10, TimeUnit.SECONDS)) { "Startup workers did not terminate" }
}
val failures = attempts.count { it.exit != 0 || !it.ready || it.timedOut }
Files.writeString(output.resolve("results.tsv"), "id\tmillis\texit\tready\ttimedOut\n" +
    attempts.joinToString("\n") { "${it.id}\t${it.millis}\t${it.exit}\t${it.ready}\t${it.timedOut}" } + "\n")
println("${attempts.size - failures}/${attempts.size} startups passed; diagnostics: $output")
exitProcess(if (failures == 0) 0 else 1)
