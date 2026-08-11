package io.kotgent.task

import io.kotgent.core.ProjectId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectFileTest {

    private val uuid = "0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34"
    private val id = ProjectId.of(uuid)

    private fun projectJson(id: String = uuid, name: String = "kotgent"): String =
        """
        {
          "${'$'}schema": "https://raw.githubusercontent.com/Heapy/kotgent/main/schema/project.v1.json",
          "id": "$id",
          "name": "$name"
        }
        """.trimIndent()


    @Test
    fun aWellFormedFileParsesAndItsSchemaKeyIsIgnored() {
        val parsed = assertNotNull(parseProjectFile(projectJson()))
        assertEquals(id, parsed.id)
        assertEquals("kotgent", parsed.name)
    }

    @Test
    fun anUpperCaseIdParsesToTheSameProjectIdAsItsLowerCaseSpelling() {
        val upper = assertNotNull(parseProjectFile(projectJson(id = uuid.uppercase())))
        val lower = assertNotNull(parseProjectFile(projectJson(id = uuid)))
        assertEquals(lower.id, upper.id)
        assertEquals(uuid, upper.id.value, "the stored spelling is lower case — TEXT compares binary")
    }

    @Test
    fun malformedJsonIsNullRatherThanAThrow() {
        assertNull(parseProjectFile("{"))
        assertNull(parseProjectFile(""))
        assertNull(parseProjectFile("not json at all"))
        assertNull(parseProjectFile("""{"id": "$uuid", "name": }"""))
    }

    @Test
    fun aNonUuidIdIsRejected() {
        assertNull(parseProjectFile(projectJson(id = "kotgent")))
        assertNull(parseProjectFile(projectJson(id = "0f2c7a4e1c3d4f7a9b216f0a2d9c1e34")))
        assertNull(parseProjectFile(projectJson(id = " $uuid ")), "a padded uuid is a broken file")
        assertNull(parseProjectFile("""{"name": "kotgent"}"""), "a missing id is no project")
    }

    @Test
    fun aNameWithControlCharactersIsRejected() {
        assertNull(parseProjectFile("""{"id": "$uuid", "name": "kot\u0000gent"}"""))
        assertNull(parseProjectFile("""{"id": "$uuid", "name": "kot\ngent"}"""))
        assertNull(parseProjectFile("""{"id": "$uuid", "name": "kot\u007fgent"}"""))
    }

    @Test
    fun aBlankOrOverlongNameIsRejectedAndPaddingIsTrimmed() {
        assertNull(parseProjectFile(projectJson(name = "   ")))
        assertNull(parseProjectFile("""{"id": "$uuid"}"""), "a missing name is no project")
        assertNull(
            parseProjectFile(projectJson(name = "n".repeat(200))),
            "200 characters is past the $PROJECT_NAME_MAX_LENGTH cap — rejected, never truncated",
        )
        assertEquals(
            PROJECT_NAME_MAX_LENGTH,
            assertNotNull(parseProjectFile(projectJson(name = "n".repeat(PROJECT_NAME_MAX_LENGTH)))).name.length,
            "exactly the cap is still accepted",
        )
        assertEquals("kotgent", assertNotNull(parseProjectFile(projectJson(name = "  kotgent  "))).name)
    }

    @Test
    fun aHugeFileFailsBecauseTheReadIsCappedAndTheTruncatedTextIsNotJson() {
        val huge = """{"id": "$uuid", "name": "kotgent", "padding": "${"p".repeat(1024 * 1024)}"}"""
        val fs = FakeProjectFs(files = mapOf("/repo/$PROJECT_FILE_NAME" to huge))
        val capped = assertNotNull(fs.readFile("/repo/$PROJECT_FILE_NAME", PROJECT_FILE_MAX_BYTES))
        assertEquals(PROJECT_FILE_MAX_BYTES, capped.length, "the read is capped at 8 KiB")
        assertNull(parseProjectFile(capped))
        assertNull(resolveProject(fs, "/repo"), "and the resolver reads it as 'no project'")
    }


    @Test
    fun theFileInTheCurrentDirectoryWins() {
        val fs = FakeProjectFs(files = mapOf("/repo/$PROJECT_FILE_NAME" to projectJson()))
        val resolved = assertNotNull(resolveProject(fs, "/repo"))
        assertEquals(id, resolved.id)
        assertEquals("kotgent", resolved.name)
        assertEquals("/repo", resolved.root)
    }

    @Test
    fun anAncestorsFileIsFoundFromASubdirectory() {
        val fs = FakeProjectFs(
            dirs = listOf("/repo/src/task/deep"),
            files = mapOf("/repo/$PROJECT_FILE_NAME" to projectJson()),
        )
        assertEquals("/repo", assertNotNull(resolveProject(fs, "/repo/src/task/deep")).root)
    }

    @Test
    fun theNearestFileWinsInAMonorepo() {
        val inner = "9a1e6d2b-0000-4000-8000-000000000001"
        val fs = FakeProjectFs(
            files = mapOf(
                "/mono/$PROJECT_FILE_NAME" to projectJson(name = "mono"),
                "/mono/apps/web/$PROJECT_FILE_NAME" to projectJson(id = inner, name = "web"),
            ),
        )
        val resolved = assertNotNull(resolveProject(fs, "/mono/apps/web"))
        assertEquals(ProjectId.of(inner), resolved.id)
        assertEquals("/mono/apps/web", resolved.root)
        assertEquals("/mono", assertNotNull(resolveProject(fs, "/mono/apps")).root, "the sibling is outside it")
    }

    @Test
    fun aCwdThatDoesNotResolveIsNullRatherThanAThrow() {
        val fs = FakeProjectFs(files = mapOf("/repo/$PROJECT_FILE_NAME" to projectJson()))
        assertNull(resolveProject(fs, "/repo/gone"))
    }

    @Test
    fun aNonCanonicalCwdConvergesOnTheSameAnswer() {
        val fs = FakeProjectFs(
            dirs = listOf("/repo/src"),
            files = mapOf("/repo/$PROJECT_FILE_NAME" to projectJson()),
            symlinks = mapOf("/link" to "/repo"),
        )
        assertEquals("/repo", assertNotNull(resolveProject(fs, "/repo/src/../src/.")).root)
        assertEquals("/repo", assertNotNull(resolveProject(fs, "/link/src")).root)
    }

    @Test
    fun anUnparseableFileWarnsAndTheWalkContinuesUpward() {
        val fs = FakeProjectFs(
            files = mapOf(
                "/mono/$PROJECT_FILE_NAME" to projectJson(name = "mono"),
                "/mono/apps/web/$PROJECT_FILE_NAME" to "{ this is not json",
            ),
        )
        val resolved = assertNotNull(resolveProject(fs, "/mono/apps/web"))
        assertEquals("/mono", resolved.root, "a broken nearest file reads as 'no project', not as a stop")
        assertEquals("mono", resolved.name)
    }


    @Test
    fun aGitDirectoryMakesItsHolderTheRoot() {
        val fs = FakeProjectFs(dirs = listOf("/repo/.git/objects", "/repo/src"))
        assertEquals("/repo", mainCheckoutRoot(fs, "/repo"))
        assertEquals("/repo", mainCheckoutRoot(fs, "/repo/src"))
    }

    @Test
    fun anOrdinaryLinkedWorktreeReachesTheMainCheckoutRoot() {
        val fs = worktreeFs(gitdir = "/repo/.git/worktrees/feature")
        assertEquals("/repo", mainCheckoutRoot(fs, "/wt/feature"))
        val resolved = assertNotNull(resolveProject(fs, "/wt/feature/src"))
        assertEquals("/repo", resolved.root, "one uuid across worktrees is the whole point of the file")
        assertEquals(id, resolved.id)
    }

    @Test
    fun aRelativeGitdirIsResolvedAgainstTheGitFilesDirectory() {
        val fs = worktreeFs(gitdir = "../../repo/.git/worktrees/feature")
        assertEquals("/repo", mainCheckoutRoot(fs, "/wt/feature"))
        assertEquals("/repo", assertNotNull(resolveProject(fs, "/wt/feature")).root)
    }

    @Test
    fun aSymlinkedCommonDirIsCanonicalizedBeforeItsSegmentsAreExamined() {
        val fs = FakeProjectFs(
            dirs = listOf("/real/repo/.git/worktrees/feature", "/wt/feature"),
            files = mapOf(
                "/real/repo/$PROJECT_FILE_NAME" to projectJson(),
                "/wt/feature/.git" to "gitdir: /link/.git/worktrees/feature\n",
            ),
            symlinks = mapOf("/link" to "/real/repo"),
        )
        assertEquals("/real/repo", mainCheckoutRoot(fs, "/wt/feature"))
        assertEquals("/real/repo", assertNotNull(resolveProject(fs, "/wt/feature")).root)
    }

    @Test
    fun theInnermostRepositoryWins() {
        val fs = FakeProjectFs(dirs = listOf("/outer/.git", "/outer/inner/.git/objects"))
        assertEquals("/outer/inner", mainCheckoutRoot(fs, "/outer/inner"))
        assertEquals("/outer", mainCheckoutRoot(fs, "/outer"))
    }


    @Test
    fun separateGitDirDegradesToTheCheckoutInsteadOfTheMetadataDirectorysParent() {
        val fs = FakeProjectFs(
            dirs = listOf("/meta/checkout-git/objects"),
            files = mapOf(
                "/checkout/.git" to "gitdir: /meta/checkout-git\n",
                "/meta/$PROJECT_FILE_NAME" to projectJson(name = "not this one"),
            ),
        )
        assertEquals("/checkout", mainCheckoutRoot(fs, "/checkout"))
        assertNull(resolveProject(fs, "/checkout"), "the metadata directory's neighbour is not the project")
    }

    @Test
    fun aWorktreeOfASeparateGitDirCheckoutDegradesToTheWorktree() {
        val fs = FakeProjectFs(
            dirs = listOf("/meta/checkout-git/worktrees/feature", "/wt/feature"),
            files = mapOf(
                "/wt/feature/.git" to "gitdir: /meta/checkout-git/worktrees/feature\n",
                "/meta/$PROJECT_FILE_NAME" to projectJson(name = "not this one"),
            ),
        )
        assertEquals("/wt/feature", mainCheckoutRoot(fs, "/wt/feature"))
        assertNull(resolveProject(fs, "/wt/feature"))
    }

    @Test
    fun aSubmoduleDegradesToTheSubmoduleCheckout() {
        val fs = FakeProjectFs(
            dirs = listOf("/super/.git/modules/sub", "/super/sub/src"),
            files = mapOf("/super/sub/.git" to "gitdir: /super/.git/modules/sub\n"),
        )
        assertEquals("/super/sub", mainCheckoutRoot(fs, "/super/sub"))
        assertEquals("/super/sub", mainCheckoutRoot(fs, "/super/sub/src"))
    }

    @Test
    fun aWorktreeOfABareRepositoryDegradesToTheWorktree() {
        val fs = FakeProjectFs(
            dirs = listOf("/srv/repo.git/worktrees/feature", "/wt/feature"),
            files = mapOf(
                "/wt/feature/.git" to "gitdir: /srv/repo.git/worktrees/feature\n",
                "/srv/$PROJECT_FILE_NAME" to projectJson(name = "not this one"),
            ),
        )
        assertEquals("/wt/feature", mainCheckoutRoot(fs, "/wt/feature"))
        assertNull(resolveProject(fs, "/wt/feature"))
    }

    @Test
    fun aBareRepositoryItselfLooksLikeNoRepositoryAtAll() {
        val fs = FakeProjectFs(
            dirs = listOf("/srv/repo.git/objects", "/srv/repo.git/refs"),
            files = mapOf("/srv/repo.git/HEAD" to "ref: refs/heads/main\n"),
        )
        assertNull(mainCheckoutRoot(fs, "/srv/repo.git"))
        assertNull(resolveProject(fs, "/srv/repo.git"))
    }

    @Test
    fun noGitAtAllIsNoRoot() {
        val fs = FakeProjectFs(dirs = listOf("/home/me/notes"))
        assertNull(mainCheckoutRoot(fs, "/home/me/notes"))
        assertNull(resolveProject(fs, "/home/me/notes"))
    }

    @Test
    fun aBrokenGitFileDegradesToItsHolder() {
        val fs = FakeProjectFs(
            dirs = listOf("/wt/feature"),
            files = mapOf("/wt/feature/.git" to "this file says nothing about a gitdir\n"),
        )
        assertEquals("/wt/feature", mainCheckoutRoot(fs, "/wt/feature"))

        val dangling = FakeProjectFs(
            dirs = listOf("/wt/feature"),
            files = mapOf("/wt/feature/.git" to "gitdir: /gone/.git/worktrees/feature\n"),
        )
        assertEquals("/wt/feature", mainCheckoutRoot(dangling, "/wt/feature"), "an unresolvable target too")

        val empty = FakeProjectFs(dirs = listOf("/wt/feature"), files = mapOf("/wt/feature/.git" to "gitdir:  \n"))
        assertEquals("/wt/feature", mainCheckoutRoot(empty, "/wt/feature"))
    }

    @Test
    fun aRelativeDirIsRefusedRatherThanWalkedApart() {
        val fs = FakeProjectFs(dirs = listOf("/repo/.git"))
        assertNull(mainCheckoutRoot(fs, "repo"), "every caller's value comes from canonicalize")
    }

    @Test
    fun aWorktreeWhoseMainRootHoldsNoProjectFileResolvesToNothing() {
        val fs = FakeProjectFs(
            dirs = listOf("/repo/.git/worktrees/feature", "/wt/feature"),
            files = mapOf("/wt/feature/.git" to "gitdir: /repo/.git/worktrees/feature\n"),
        )
        assertEquals("/repo", mainCheckoutRoot(fs, "/wt/feature"))
        assertNull(resolveProject(fs, "/wt/feature"))
    }


    @Test
    fun theRealPosixFsResolvesARealWorktreeInTmpdir() {
        val fs = PosixProjectFs()
        val base = makeBase()
        val repo = makeDir("$base/repo")
        makeDir("$repo/.git")
        makeDir("$repo/.git/worktrees")
        val worktreeMeta = makeDir("$repo/.git/worktrees/feature")
        writeFile("$repo/$PROJECT_FILE_NAME", projectJson() + "\n")
        val src = makeDir("$repo/src")
        val wt = makeDir("$base/wt")
        val feature = makeDir("$wt/feature")
        writeFile("$feature/.git", "gitdir: $worktreeMeta\n")

        assertTrue(fs.isDirectory(repo))
        assertFalse(fs.isDirectory("$feature/.git"), "a linked worktree's .git is a FILE")
        assertFalse(fs.isDirectory("$base/nope"))
        assertEquals(repo, fs.canonicalize("$repo/src/.."))
        assertNull(fs.canonicalize("$base/nope"))
        assertNull(fs.readFile("$base/nope", PROJECT_FILE_MAX_BYTES))

        assertEquals(repo, mainCheckoutRoot(fs, repo))
        assertEquals(repo, mainCheckoutRoot(fs, src))
        assertEquals(repo, mainCheckoutRoot(fs, feature))

        val fromMain = assertNotNull(resolveProject(fs, src))
        assertEquals(id, fromMain.id)
        assertEquals(repo, fromMain.root)

        val fromWorktree = assertNotNull(resolveProject(fs, feature))
        assertEquals(id, fromWorktree.id, "both checkouts are one project")
        assertEquals(repo, fromWorktree.root)
    }


    private fun worktreeFs(gitdir: String) = FakeProjectFs(
        dirs = listOf("/repo/.git/worktrees/feature", "/wt/feature/src"),
        files = mapOf(
            "/repo/$PROJECT_FILE_NAME" to projectJson(),
            "/wt/feature/.git" to "gitdir: $gitdir\n",
        ),
    )

    private val files = mutableListOf<String>()
    private val dirs = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        for (f in files) unlink(f)
        for (d in dirs.asReversed()) rmdir(d)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun makeBase(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val made = makeDir("$tmp/kotgent-project-file-${getpid()}-${counter++}")
        return PosixProjectFs().canonicalize(made) ?: made
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun makeDir(path: String): String {
        mkdir(path, (S_IRUSR or S_IWUSR or S_IXUSR).convert())
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
        if (!files.contains(path)) files += path
    }

    private companion object {
        var counter = 0
    }
}

private class FakeProjectFs(
    dirs: List<String> = emptyList(),
    private val files: Map<String, String> = emptyMap(),
    private val symlinks: Map<String, String> = emptyMap(),
) : ProjectFs {

    private val directories: Set<String> = buildSet {
        add("/")
        for (d in dirs) addAncestry(d, includeSelf = true)
        for (f in files.keys) addAncestry(f, includeSelf = false)
    }

    override fun isDirectory(path: String): Boolean = normalize(path) in directories

    override fun readFile(path: String, maxBytes: Int): String? {
        val text = files[normalize(path)] ?: return null
        if (maxBytes <= 0) return null
        val bytes = text.encodeToByteArray()
        val n = if (bytes.size < maxBytes) bytes.size else maxBytes
        if (n <= 0) return null
        return bytes.decodeToString(0, n)
    }

    override fun canonicalize(path: String): String? {
        var resolved = normalize(path)
        for ((from, to) in symlinks) {
            if (resolved == from) resolved = to
            else if (resolved.startsWith("$from/")) resolved = to + resolved.removePrefix(from)
        }
        resolved = normalize(resolved)
        return if (resolved in directories || resolved in files) resolved else null
    }

    private fun MutableSet<String>.addAncestry(path: String, includeSelf: Boolean) {
        val segments = normalize(path).split('/').filter { it.isNotEmpty() }
        val upTo = if (includeSelf) segments.size else segments.size - 1
        var current = ""
        for (i in 0 until upTo) {
            current += "/" + segments[i]
            add(current)
        }
    }

    private fun normalize(path: String): String {
        val stack = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(segment)
            }
        }
        return if (stack.isEmpty()) "/" else stack.joinToString("/", prefix = "/")
    }
}
