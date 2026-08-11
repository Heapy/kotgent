package io.kotgent.task

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
/**
 * Mutable lexical filesystem for the live harness. Symlink substitution and `..` collapsing are a
 * deliberate simplification of `realpath`; production canonicalization is tested on the real filesystem.
 */
class FakeProjectFs(
    dirs: List<String> = emptyList(),
    files: Map<String, String> = emptyMap(),
    private val symlinks: Map<String, String> = emptyMap(),
) : ProjectFs {

    private data class Tree(val directories: Set<String>, val files: Map<String, String>)

    private val tree = AtomicReference(Tree(setOf("/"), emptyMap()))

    init {
        dirs.forEach { addDirectory(it) }
        files.forEach { (path, body) -> writeFile(path, body) }
    }

    val written: Map<String, String> get() = tree.load().files

    // A live snapshot: browser scenarios may add directories after construction.
    val directories: Set<String> get() = tree.load().directories

    fun addDirectory(path: String) {
        val normalized = normalize(path)
        mutate { it.copy(directories = it.directories + ancestry(normalized, includeSelf = true)) }
    }

    fun writeFile(path: String, body: String) {
        val normalized = normalize(path)
        mutate {
            it.copy(
                directories = it.directories + ancestry(normalized, includeSelf = false),
                files = it.files + (normalized to body),
            )
        }
    }

    fun deleteFile(path: String): Boolean {
        val normalized = normalize(path)
        if (normalized !in tree.load().files) return false
        mutate { it.copy(files = it.files - normalized) }
        return true
    }

    override fun isDirectory(path: String): Boolean = normalize(path) in tree.load().directories

    override fun readFile(path: String, maxBytes: Int): String? {
        val text = tree.load().files[normalize(path)] ?: return null
        if (maxBytes <= 0) return null
        // The production intake bound is bytes, not Unicode characters.
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
        val snapshot = tree.load()
        return if (resolved in snapshot.directories || resolved in snapshot.files) resolved else null
    }

    private fun mutate(edit: (Tree) -> Tree) {
        while (true) {
            val current = tree.load()
            if (tree.compareAndSet(current, edit(current))) return
        }
    }

    private fun ancestry(normalized: String, includeSelf: Boolean): List<String> {
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        val upTo = if (includeSelf) segments.size else segments.size - 1
        val out = ArrayList<String>(if (upTo > 0) upTo else 0)
        var current = ""
        for (i in 0 until upTo) {
            current += "/" + segments[i]
            out += current
        }
        return out
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
