package io.kotgent.task

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A lexical in-memory [ProjectFs]: every ancestor of a declared directory or file is itself a directory,
 * [symlinks] replace a path PREFIX (the shape `/tmp -> /private/tmp` and a symlinked checkout both take),
 * and [canonicalize] collapses `.`/`..` and then answers `null` for anything the tree does not hold — the
 * existence gate `realpath(3)` really applies.
 *
 * Every method degrades to "absent" instead of throwing, which is the interface's own rule: a resolver
 * must never throw into the daemon.
 *
 * ## Mutable, because one consumer WRITES into it
 * [MemoryProjectFileWriter] publishes a `.kotgent.json` here rather than onto a disk, so a second create
 * at the same directory adopts the first one's uuid — the convergence rule the real writer gets from
 * `link(2)`. That is the whole reason this is not a frozen snapshot.
 *
 * ## Why atomics and not a Mutex
 * [ProjectFs]'s three methods are NOT `suspend` (the resolver rules are pure and synchronous), so a
 * coroutine `Mutex` is unavailable to them — `TokenHolder`'s reason, in a smaller place. The state is
 * therefore one immutable snapshot swapped under a compare-and-set, which keeps a read on a server engine
 * thread from ever observing a half-built tree.
 *
 * ## The known simplification
 * `realpath(3)` resolves symlinks and `..` interleaved, left to right; this collapses `..` lexically
 * around a prefix substitution. It is exactly the simplification the suite's existing fake trees make,
 * and it is why the production rule for a session cwd is checked against the real filesystem instead.
 */
@OptIn(ExperimentalAtomicApi::class)
class FakeProjectFs(
    dirs: List<String> = emptyList(),
    files: Map<String, String> = emptyMap(),
    private val symlinks: Map<String, String> = emptyMap(),
) : ProjectFs {

    private data class Tree(val directories: Set<String>, val files: Map<String, String>)

    private val tree = AtomicReference(Tree(setOf("/"), emptyMap()))

    private val readLog = AtomicReference<List<String>>(emptyList())

    init {
        dirs.forEach { addDirectory(it) }
        files.forEach { (path, body) -> writeFile(path, body) }
    }

    /** Every path [readFile] was asked about, in order — the proof a branch consulted the tree, or did not. */
    val reads: List<String> get() = readLog.load()

    /** Every file the tree currently holds, by canonical path. */
    val written: Map<String, String> get() = tree.load().files

    /** Declare [path] and every ancestor of it a directory. */
    fun addDirectory(path: String) {
        val normalized = normalize(path)
        mutate { it.copy(directories = it.directories + ancestry(normalized, includeSelf = true)) }
    }

    /** Put [body] at [path], making every ancestor a directory — what a publish into this tree means. */
    fun writeFile(path: String, body: String) {
        val normalized = normalize(path)
        mutate {
            it.copy(
                directories = it.directories + ancestry(normalized, includeSelf = false),
                files = it.files + (normalized to body),
            )
        }
    }

    /** Remove [path] if it is there. Answers whether it was. */
    fun deleteFile(path: String): Boolean {
        val normalized = normalize(path)
        if (normalized !in tree.load().files) return false
        mutate { it.copy(files = it.files - normalized) }
        return true
    }

    override fun isDirectory(path: String): Boolean = normalize(path) in tree.load().directories

    override fun readFile(path: String, maxBytes: Int): String? {
        record(path)
        val text = tree.load().files[normalize(path)] ?: return null
        if (maxBytes <= 0) return null
        // Truncate by BYTES, like a bounded read of a file really would — a cap counted in characters
        // would let a multi-byte name slip past the intake bound the real reader enforces.
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

    /** Swap the snapshot, retrying until this caller's edit is the one that landed. */
    private fun mutate(edit: (Tree) -> Tree) {
        while (true) {
            val current = tree.load()
            if (tree.compareAndSet(current, edit(current))) return
        }
    }

    private fun record(path: String) {
        while (true) {
            val current = readLog.load()
            if (readLog.compareAndSet(current, current + path)) return
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

    /** Collapse `.`, `..` and duplicate slashes; the result is absolute and has no trailing slash. */
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
