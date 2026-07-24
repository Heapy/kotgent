package io.kotgent.transport

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.symlink
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class DirectoryCompletionTest {

    @Test
    fun relativeInputCompletesOnlyItsFinalSegmentUnderBasePath() {
        var listed: String? = null
        val paths = completeDirectoryPaths("/Users/me/dev", "pet/kot") { parent ->
            listed = parent
            listOf("kotlin", "other", "Kotgent", ".kot-secret", "kotbot")
        }

        assertEquals("/Users/me/dev/pet", listed)
        assertEquals(
            listOf(
                "/Users/me/dev/pet/kotbot",
                "/Users/me/dev/pet/Kotgent",
                "/Users/me/dev/pet/kotlin",
            ),
            paths,
        )
    }

    @Test
    fun absoluteInputIgnoresBaseAndADotPrefixIncludesHiddenDirectories() {
        assertEquals(
            listOf("/srv/work/Alpha", "/srv/work/alpine"),
            completeDirectoryPaths("/ignored", "/srv/work/al") {
                assertEquals("/srv/work", it)
                listOf("alpine", ".also-hidden", "Beta", "Alpha")
            },
        )

        assertEquals(
            listOf("/work/.cache", "/work/.Config"),
            completeDirectoryPaths(null, "/work/.") {
                listOf("visible", ".Config", ".cache")
            },
        )
    }

    @Test
    fun resultsAreAlphabeticalAndCappedAtTwenty() {
        val descending = (25 downTo 1).map { "dir${it.toString().padStart(2, '0')}" }
        val paths = completeDirectoryPaths(null, "/") { descending }

        assertEquals(20, paths.size)
        assertEquals("/dir01", paths.first())
        assertEquals("/dir20", paths.last())
    }

    @Test
    fun relativeInputWithoutAnAbsoluteBaseHasNoCompletionTarget() {
        assertTrue(completeDirectoryPaths(null, "kot") { error("must not list") }.isEmpty())
        assertTrue(completeDirectoryPaths("relative", "kot") { error("must not list") }.isEmpty())
    }

    @Test
    fun posixListingReturnsDirectoriesAndDirectorySymlinksButNotFiles() {
        val root = makeTestDirectory()
        val directory = "$root/project"
        val link = "$root/project-link"
        val file = "$root/project.txt"
        try {
            assertEquals(0, mkdir(directory, MODE_0700.convert()))
            assertEquals(0, symlink(directory, link))
            fclose(fopen(file, "wb") ?: error("cannot create $file"))

            assertEquals(
                listOf("project", "project-link"),
                posixChildDirectoryNames(root).sorted(),
            )
        } finally {
            unlink(file)
            unlink(link)
            rmdir(directory)
            rmdir(root)
        }
    }

    private fun makeTestDirectory(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val path = "$tmp/kotgent-directory-completion-${getpid()}-${counter++}"
        assertEquals(0, mkdir(path, MODE_0700.convert()))
        return path
    }

    private companion object {
        const val MODE_0700: Int = S_IRUSR or S_IWUSR or S_IXUSR
        var counter: Int = 0
    }
}
