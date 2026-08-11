package io.kotgent.sqldelight

import app.cash.sqldelight.core.SqlDelightCompilationUnit
import app.cash.sqldelight.core.SqlDelightDatabaseName
import app.cash.sqldelight.core.SqlDelightDatabaseProperties
import app.cash.sqldelight.core.SqlDelightEnvironment
import app.cash.sqldelight.core.SqlDelightSourceFolder
import app.cash.sqldelight.dialect.api.SqlDelightDialect
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.io.path.createDirectories

// SQLDelight ships code generation only through Gradle; this Toolchain plugin drives its compiler
// directly through the vendored, Gradle-free SqlDelightEnvironment.
@TaskAction
fun generateDatabase(
    @Input sqDir: Path,
    @Output generatedSourceDir: Path,
) {
    // The task output is reused, so deleted or renamed queries must not leave generated sources behind.
    val outDir = generatedSourceDir.toFile()
    outDir.deleteRecursively()
    outDir.mkdirs()

    val sqRoot = sqDir.toFile()
    val hasSqFiles = sqRoot.isDirectory &&
        sqRoot.walkTopDown().any { it.isFile && it.extension == "sq" }
    if (!hasSqFiles) return

    val compilationUnit = CompilationUnit(
        name = "main",
        sourceFolders = setOf(SourceFolder(folder = sqRoot, dependency = false)),
        outputDirectoryFile = outDir,
    )
    val properties = DatabaseProperties(
        packageName = DATABASE_PACKAGE,
        compilationUnits = listOf(compilationUnit),
        className = DATABASE_CLASS_NAME,
        dependencies = emptyList(),
        deriveSchemaFromMigrations = false,
        treatNullAsUnknownForEquality = false,
        rootDirectory = sqRoot,
        generateAsync = false,
        expandSelectStar = true,
    )

    // The dialect artifact registers its implementation through ServiceLoader.
    val dialect: SqlDelightDialect = ServiceLoader
        .load(SqlDelightDialect::class.java, SqlDelightDialect::class.java.classLoader)
        .firstOrNull()
        ?: error("No SqlDelightDialect found on the classpath (expected app.cash.sqldelight:sqlite-3-38-dialect)")

    val environment = SqlDelightEnvironment(
        properties = properties,
        compilationUnit = compilationUnit,
        verifyMigrations = false,
        dialect = dialect,
        moduleName = MODULE_NAME,
    )

    when (val status = environment.generateSqlDelightFiles { line -> println("[sqldelight-gen] $line") }) {
        is SqlDelightEnvironment.CompilationStatus.Failure ->
            error("SQLDelight code generation failed:\n" + status.errors.joinToString("\n"))
        SqlDelightEnvironment.CompilationStatus.Success -> Unit
    }
}

private const val DATABASE_PACKAGE = "io.kotgent.db"

private const val DATABASE_CLASS_NAME = "KotgentDatabase"

private const val MODULE_NAME = "kotgent"

private data class SourceFolder(
    override val folder: File,
    override val dependency: Boolean,
) : SqlDelightSourceFolder

private data class CompilationUnit(
    override val name: String,
    override val sourceFolders: Set<SqlDelightSourceFolder>,
    override val outputDirectoryFile: File,
) : SqlDelightCompilationUnit

private data class DatabaseProperties(
    override val packageName: String,
    override val compilationUnits: List<SqlDelightCompilationUnit>,
    override val className: String,
    override val dependencies: List<SqlDelightDatabaseName>,
    override val deriveSchemaFromMigrations: Boolean,
    override val treatNullAsUnknownForEquality: Boolean,
    override val rootDirectory: File,
    override val generateAsync: Boolean,
    override val expandSelectStar: Boolean,
) : SqlDelightDatabaseProperties
