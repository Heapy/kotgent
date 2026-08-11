package io.kotgent.task

/** Read-only filesystem seam; inaccessible or malformed paths degrade to false/null rather than throwing. */
interface ProjectFs {

    fun isDirectory(path: String): Boolean

    fun readFile(path: String, maxBytes: Int): String?

    fun canonicalize(path: String): String?
}

const val PROJECT_FILE_NAME: String = ".kotgent.json"

const val PROJECT_FILE_MAX_BYTES: Int = 8 * 1024

const val GITDIR_FILE_MAX_BYTES: Int = 4 * 1024
