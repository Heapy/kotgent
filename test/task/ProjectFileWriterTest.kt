package io.kotgent.task

import io.kotgent.core.ProjectId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.S_IRGRP
import platform.posix.S_IROTH
import platform.posix.S_IRUSR
import platform.posix.S_IRWXG
import platform.posix.S_IRWXO
import platform.posix.S_IRWXU
import platform.posix.S_IWGRP
import platform.posix.S_IWOTH
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.chmod
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.umask
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectFileWriterTest {

    private val minted = ProjectId.of("0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34")


    @Test
    fun aFreshDirectoryGetsTheDocumentedShapeAndNoLeftoverTemp() {
        runBlocking {
            val dir = makeDir("${makeBase()}/repo")
            val written = writerMinting(minted).ensureProjectFile(dir, "kotgent")

            assertEquals(minted, written.id)
            assertEquals("kotgent", written.name)
            assertEquals(
                """
                {
                  "${'$'}schema": "https://raw.githubusercontent.com/Heapy/kotgent/main/schema/project.v1.json",
                  "id": "0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34",
                  "name": "kotgent"
                }

                """.trimIndent(),
                readAll(joinPath(dir, PROJECT_FILE_NAME)),
                "the committed file's exact bytes",
            )
            assertEquals(
                written,
                parseProjectFile(readAll(joinPath(dir, PROJECT_FILE_NAME))),
                "what the resolver reads back is what the writer returned",
            )
            assertEquals(listOf(PROJECT_FILE_NAME), entriesIn(dir), "no temp survives success")
        }
    }

    @Test
    fun theModeIs0666MinusTheUmaskRatherThanThe0600MkstempGaveIt() {
        runBlocking {
            val ordinary = makeDir("${makeBase()}/ordinary")
            withUmask(S_IWGRP or S_IWOTH) { writerMinting(minted).ensureProjectFile(ordinary, "kotgent") }
            assertEquals(
                S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH,
                modeOf(joinPath(ordinary, PROJECT_FILE_NAME)),
            )

            val private = makeDir("${makeBase()}/private")
            withUmask(S_IRWXG or S_IRWXO) { writerMinting(minted).ensureProjectFile(private, "kotgent") }
            assertEquals(S_IRUSR or S_IWUSR, modeOf(joinPath(private, PROJECT_FILE_NAME)))

            val shared = makeDir("${makeBase()}/shared")
            withUmask(S_IWOTH) { writerMinting(minted).ensureProjectFile(shared, "kotgent") }
            assertEquals(
                S_IRUSR or S_IWUSR or S_IRGRP or S_IWGRP or S_IROTH,
                modeOf(joinPath(shared, PROJECT_FILE_NAME)),
            )
        }
    }

    @Test
    fun aTrailingSlashNamesTheSameDirectory() {
        runBlocking {
            val dir = makeDir("${makeBase()}/repo")
            val written = writerMinting(minted).ensureProjectFile("$dir/", "kotgent")
            assertEquals(minted, written.id)
            assertEquals(listOf(PROJECT_FILE_NAME), entriesIn(dir))
        }
    }

    @Test
    fun theNameIsTrimmedSoTheFileRoundTripsToWhatTheCallerWasHanded() {
        runBlocking {
            val dir = makeDir("${makeBase()}/repo")
            val written = writerMinting(minted).ensureProjectFile(dir, "  kotgent\n")
            assertEquals("kotgent", written.name)
            assertEquals(written, parseProjectFile(readAll(joinPath(dir, PROJECT_FILE_NAME))))
        }
    }

    @Test
    fun aNameCarryingJsonPunctuationStillParsesBack() {
        runBlocking {
            val dir = makeDir("${makeBase()}/repo")
            val awkward = """a "quoted" \ name"""
            val written = writerMinting(minted).ensureProjectFile(dir, awkward)
            assertEquals(awkward, written.name)
            assertEquals(
                written,
                parseProjectFile(readAll(joinPath(dir, PROJECT_FILE_NAME))),
                "a name is serialized, not interpolated",
            )
        }
    }


    @Test
    fun aSecondCallReturnsTheFirstCallsIdAndMintsNothing() {
        runBlocking {
            val dir = makeDir("${makeBase()}/repo")
            val first = writerMinting(minted).ensureProjectFile(dir, "kotgent")

            var mints = 0
            val second = PosixProjectFileWriter {
                mints++
                ProjectId.of("99999999-8888-4777-a666-555544443333")
            }.ensureProjectFile(dir, "a different name")

            assertEquals(first, second, "the project's identity is the file's, not the caller's")
            assertEquals(0, mints, "adopting an existing project mints no uuid")
            assertEquals(listOf(PROJECT_FILE_NAME), entriesIn(dir))
        }
    }

    @Test
    fun aPreCreatedTargetKeepsItsContentByteForByte() {
        runBlocking {
            val dir = makeDir("${makeBase()}/repo")
            val theirs =
                """
                { "id": "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE", "name": "  theirs  " }
                """.trimIndent()
            writeFile(joinPath(dir, PROJECT_FILE_NAME), theirs)

            val adopted = writerMinting(minted).ensureProjectFile(dir, "kotgent")

            assertEquals(
                ProjectId.of("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
                adopted.id,
                "an existing id is adopted, lower-cased by ProjectId",
            )
            assertEquals("theirs", adopted.name)
            assertEquals(theirs, readAll(joinPath(dir, PROJECT_FILE_NAME)), "their file is not rewritten")
            assertEquals(listOf(PROJECT_FILE_NAME), entriesIn(dir))
        }
    }

    @Test
    fun aLostLinkRaceReReadsTheWinnersFileInsteadOfFailing() {
        runBlocking {
            val dir = makeDir("${makeBase()}/repo")
            val winner = ProjectId.of("11111111-2222-4333-8444-555555555555")
            val racing = PosixProjectFileWriter {
                writeFile(joinPath(dir, PROJECT_FILE_NAME), projectFileText(ProjectFile(winner, "theirs")))
                minted
            }

            val adopted = racing.ensureProjectFile(dir, "mine")

            assertEquals(winner, adopted.id, "an existing file always wins")
            assertEquals("theirs", adopted.name)
            assertEquals(listOf(PROJECT_FILE_NAME), entriesIn(dir), "no temp survives the EEXIST branch")
        }
    }

    @Test
    fun anUnparseableExistingFileIsRefusedRatherThanOverwritten() {
        runBlocking {
            val dir = makeDir("${makeBase()}/repo")
            writeFile(joinPath(dir, PROJECT_FILE_NAME), "{ this is not json")

            val failure = assertFailsWith<ProjectPathException> {
                writerMinting(minted).ensureProjectFile(dir, "kotgent")
            }
            assertEquals(dir, failure.path)
            assertTrue(
                failure.message.orEmpty().contains("already exists"),
                "the message says what is in the way: ${failure.message}",
            )
            assertEquals("{ this is not json", readAll(joinPath(dir, PROJECT_FILE_NAME)))
            assertEquals(listOf(PROJECT_FILE_NAME), entriesIn(dir))
        }
    }


    @Test
    fun aRelativeTargetIsRefused() {
        runBlocking {
            for (relative in listOf("repo", "./repo", "", "~/repo")) {
                val failure = assertFailsWith<ProjectPathException>("'$relative' is not absolute") {
                    writerMinting(minted).ensureProjectFile(relative, "kotgent")
                }
                assertEquals(relative, failure.path)
                assertTrue(failure.message.orEmpty().contains("absolute"), failure.message.orEmpty())
            }
        }
    }

    @Test
    fun aNonDirectoryTargetIsRefused() {
        runBlocking {
            val base = makeBase()
            val file = joinPath(base, "a-file")
            writeFile(file, "not a directory\n")

            for (path in listOf(file, joinPath(base, "absent"))) {
                val failure = assertFailsWith<ProjectPathException>(path) {
                    writerMinting(minted).ensureProjectFile(path, "kotgent")
                }
                assertEquals(path, failure.path)
                assertTrue(
                    failure.message.orEmpty().contains("not an existing directory"),
                    failure.message.orEmpty(),
                )
            }
            assertEquals("not a directory\n", readAll(file), "a file in the way is left alone")
            assertEquals(listOf("a-file"), entriesIn(base))
        }
    }

    @Test
    fun aNameTheResolverWouldRejectNeverReachesTheFilesystem() {
        runBlocking {
            val refused = listOf(
                "" to "blank",
                "   \t \n " to "whitespace only",
                "x".repeat(PROJECT_NAME_MAX_LENGTH + 1) to "too long",
                "one\ttwo" to "a tab",
                "be\u0007ll" to "a bell",
                "de\u007Fl" to "DEL, which JSON does not escape at all",
            )
            for ((name, why) in refused) {
                val dir = makeDir("${makeBase()}/repo")
                val failure = assertFailsWith<ProjectPathException>(why) {
                    writerMinting(minted).ensureProjectFile(dir, name)
                }
                assertEquals(dir, failure.path)
                assertTrue(failure.message.orEmpty().contains("project name"), failure.message.orEmpty())
                assertEquals(emptyList(), entriesIn(dir), "$why left something behind")
            }
        }
    }

    @Test
    fun aNameOfExactlyTheLimitIsAccepted() {
        runBlocking {
            val dir = makeDir("${makeBase()}/repo")
            val name = "x".repeat(PROJECT_NAME_MAX_LENGTH)
            val written = writerMinting(minted).ensureProjectFile(dir, name)
            assertEquals(name, written.name)
            assertEquals(written, parseProjectFile(readAll(joinPath(dir, PROJECT_FILE_NAME))))
        }
    }

    @Test
    @OptIn(ExperimentalForeignApi::class)
    fun anUnwritableDirectoryFailsLoudlyAndLeavesNoTemp() {
        runBlocking {
            val dir = makeDir("${makeBase()}/read-only")
            chmod(dir, (S_IRUSR or S_IXUSR).convert())

            val failure = assertFailsWith<ProjectPathException> {
                writerMinting(minted).ensureProjectFile(dir, "kotgent")
            }
            assertEquals(dir, failure.path)
            assertTrue(
                failure.message.orEmpty().contains("no temporary file"),
                "the message names the stage that failed: ${failure.message}",
            )

            chmod(dir, S_IRWXU.convert())
            assertEquals(emptyList(), entriesIn(dir))
        }
    }


    private fun writerMinting(id: ProjectId) = PosixProjectFileWriter { id }

    @OptIn(ExperimentalForeignApi::class)
    private inline fun <T> withUmask(mask: Int, block: () -> T): T {
        val previous = umask(mask.convert())
        try {
            return block()
        } finally {
            umask(previous)
        }
    }

    private val dirs = mutableListOf<String>()

    @AfterTest
    @OptIn(ExperimentalForeignApi::class)
    fun cleanUp() {
        for (d in dirs) chmod(d, S_IRWXU.convert())
        for (d in dirs.asReversed()) {
            for (name in entriesIn(d)) unlink(joinPath(d, name))
            rmdir(d)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun makeBase(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val made = makeDir("$tmp/kotgent-project-writer-${getpid()}-${counter++}")
        return PosixProjectFs().canonicalize(made) ?: made
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun makeDir(path: String): String {
        mkdir(path, S_IRWXU.convert())
        if (!dirs.contains(path)) dirs += path
        return path
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeFile(path: String, content: String) {
        val fp = fopen(path, "wb") ?: error("cannot create $path")
        try {
            val bytes = content.encodeToByteArray()
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp) }
        } finally {
            fclose(fp)
        }
    }

    private fun readAll(path: String): String =
        assertNotNull(PosixProjectFs().readFile(path, PROJECT_FILE_MAX_BYTES), "cannot read $path")

    @OptIn(ExperimentalForeignApi::class)
    private fun modeOf(path: String): Int = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) error("cannot stat $path")
        st.st_mode.toInt() and (S_IRWXU or S_IRWXG or S_IRWXO)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun entriesIn(path: String): List<String> {
        val handle = opendir(path) ?: return emptyList()
        try {
            val names = ArrayList<String>()
            while (true) {
                val entry = readdir(handle) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name != "." && name != "..") names += name
            }
            return names.sorted()
        } finally {
            closedir(handle)
        }
    }

    private fun joinPath(directory: String, name: String): String =
        if (directory == "/") "/$name" else "${directory.trimEnd('/')}/$name"

    private companion object {
        var counter = 0
    }
}
