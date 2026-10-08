package io.heapy.ktc.plugins.sqldelight

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenerateTest {
    @Test
    fun generatesTheConfiguredDatabaseAndRemovesDeletedInputs() {
        val root = Files.createTempDirectory("ktc-sqldelight-test-")
        try {
            val source = root.resolve("schema/sample/db").createDirectories()
            val query = source.resolve("Person.sq")
            query.writeText("CREATE TABLE person (id INTEGER NOT NULL PRIMARY KEY);\nselectAll:\nSELECT * FROM person;\n")
            val output = root.resolve("generated")
            generateDatabase(root.resolve("schema"), output, "sample.db", "SampleDatabase", "sample")

            val database = output.resolve("sample/db/SampleDatabase.kt").toFile()
            assertTrue(database.isFile, "the configured database must be generated")
            assertTrue(database.readText().contains("interface SampleDatabase"))
            assertTrue(output.resolve("sample/db/PersonQueries.kt").toFile().isFile)

            Files.delete(query)
            generateDatabase(root.resolve("schema"), output, "sample.db", "SampleDatabase", "sample")
            assertFalse(database.exists(), "removing the last query must remove stale generated code")
            assertTrue(output.toFile().walkTopDown().none { it.isFile })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidSqlFailsGenerationWithCompilerDiagnostics() {
        val root = Files.createTempDirectory("ktc-sqldelight-invalid-")
        try {
            val source = root.resolve("schema/sample/db").createDirectories()
            source.resolve("Broken.sq").writeText("selectMissing:\nSELECT * FROM missing_table;\n")
            val error = assertFailsWith<IllegalStateException> {
                generateDatabase(root.resolve("schema"), root.resolve("generated"), "sample.db", "BrokenDatabase", "sample")
            }
            assertTrue(error.message.orEmpty().contains("missing_table"), "diagnostic identifies the invalid query: $error")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
