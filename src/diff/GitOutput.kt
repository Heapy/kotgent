package io.kotgent.diff

internal class GitReadFailure(val problem: DiffProblem) : RuntimeException(problem.message)

internal fun gitFailure(reason: DiffReason, message: String, code: DiffErrorCode = DiffErrorCode.unavailable): Nothing =
    throw GitReadFailure(DiffProblem(code, reason, message))

/** Split delimiters as bytes first. Replacement decoding must never invent a filesystem path. */
internal fun gitText(bytes: ByteArray): String = try {
    bytes.decodeToString(throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    gitFailure(DiffReason.unsupported_encoding, "Git output is not UTF-8")
}

internal fun gitFields(bytes: ByteArray, delimiter: Byte = 0): List<ByteArray> {
    val result = mutableListOf<ByteArray>()
    var start = 0
    for (i in bytes.indices) if (bytes[i] == delimiter) {
        result += bytes.copyOfRange(start, i)
        start = i + 1
    }
    if (start != bytes.size) gitFailure(DiffReason.git_failed, "Unterminated Git machine output")
    return result
}

internal fun gitPath(bytes: ByteArray): String {
    val path = try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) {
        gitFailure(DiffReason.unsupported_path, "Filename is not UTF-8")
    }
    if (path.isEmpty() || path.startsWith('/') || '\u0000' in path ||
        path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
        gitFailure(DiffReason.unsupported_path, "Unsupported repository-relative path")
    }
    return path
}

internal data class GitRawFile(val file: DiffFile, val oldOid: String, val newOid: String)

internal fun parseGitRaw(bytes: ByteArray): List<GitRawFile> {
    val fields = gitFields(bytes)
    val result = mutableListOf<GitRawFile>()
    var i = 0
    while (i < fields.size) {
        val header = gitText(fields[i++]).removePrefix(":").split(' ')
        if (header.size != 5 || header[4].isEmpty()) gitFailure(DiffReason.git_failed, "Malformed raw diff")
        val oldMode = header[0].takeUnless { it == "000000" }
        val newMode = header[1].takeUnless { it == "000000" }
        if (!isDiffOid(header[2]) || !isDiffOid(header[3]) || i >= fields.size) {
            gitFailure(DiffReason.git_failed, "Malformed raw diff object")
        }
        val first = gitPath(fields[i++])
        val status = header[4][0]
        val second = if (status == 'R' || status == 'C') {
            if (i >= fields.size) gitFailure(DiffReason.git_failed, "Missing rename path")
            gitPath(fields[i++])
        } else first
        val change = when (status) {
            'A' -> DiffChange.added
            'D' -> DiffChange.deleted
            'M' -> DiffChange.modified
            'R' -> DiffChange.renamed
            'C' -> DiffChange.copied
            'T' -> DiffChange.type_changed
            'U' -> DiffChange.conflicted
            else -> gitFailure(DiffReason.git_failed, "Unsupported raw diff status")
        }
        val kind = when {
            status == 'U' -> DiffFileKind.conflict
            oldMode == "160000" || newMode == "160000" -> DiffFileKind.submodule
            oldMode == "120000" || newMode == "120000" -> DiffFileKind.symlink
            else -> DiffFileKind.text
        }
        result += GitRawFile(DiffFile("", first.takeUnless { status == 'A' }, second.takeUnless { status == 'D' },
            change, kind, oldMode, newMode), header[2], header[3])
    }
    return result
}

internal data class GitStat(val oldPath: String, val newPath: String, val added: Int?, val deleted: Int?)

internal fun parseGitNumstat(bytes: ByteArray): List<GitStat> {
    val fields = gitFields(bytes)
    val result = mutableListOf<GitStat>()
    var i = 0
    while (i < fields.size) {
        val field = fields[i++]
        val firstTab = field.indexOf(9)
        val secondTab = (firstTab + 1..<field.size).firstOrNull { field[it] == 9.toByte() } ?: -1
        if (firstTab < 0 || secondTab < 0) gitFailure(DiffReason.git_failed, "Malformed numstat")
        fun count(start: Int, end: Int): Int? {
            val text = gitText(field.copyOfRange(start, end))
            return if (text == "-") null else text.toIntOrNull()?.takeIf { it >= 0 }
                ?: gitFailure(DiffReason.git_failed, "Malformed numstat count")
        }
        val added = count(0, firstTab)
        val deleted = count(firstTab + 1, secondTab)
        val suffix = field.copyOfRange(secondTab + 1, field.size)
        val old: String
        val new: String
        if (suffix.isEmpty()) {
            if (i + 1 >= fields.size) gitFailure(DiffReason.git_failed, "Missing numstat rename")
            old = gitPath(fields[i++]); new = gitPath(fields[i++])
        } else { old = gitPath(suffix); new = old }
        result += GitStat(old, new, added, deleted)
    }
    return result
}

internal data class GitStatus(val path: String, val oldPath: String?, val index: String, val worktree: String)

internal fun parseGitStatus(bytes: ByteArray): List<GitStatus> {
    val fields = gitFields(bytes)
    val result = mutableListOf<GitStatus>()
    var i = 0
    while (i < fields.size) {
        val field = fields[i++]
        if (field.size < 4 || field[2] != 32.toByte()) gitFailure(DiffReason.git_failed, "Malformed status")
        val index = field[0].toInt().toChar().toString()
        val worktree = field[1].toInt().toChar().toString()
        val path = gitPath(field.copyOfRange(3, field.size))
        val old = if (index in listOf("R", "C") || worktree in listOf("R", "C")) {
            if (i >= fields.size) gitFailure(DiffReason.git_failed, "Missing status rename")
            gitPath(fields[i++])
        } else null
        result += GitStatus(path, old, index, worktree)
    }
    return result
}

internal fun parseGitWorktrees(bytes: ByteArray): List<DiffWorktree> {
    val result = mutableListOf<DiffWorktree>()
    var fields = mutableMapOf<String, String>()
    for (part in gitFields(bytes)) {
        val text = gitText(part)
        if (text.isEmpty()) {
            val path = fields["worktree"] ?: gitFailure(DiffReason.git_failed, "Missing worktree root")
            result += DiffWorktree(path, path, fields["HEAD"]?.takeUnless { it.all { c -> c == '0' } },
                fields["branch"], detached = "detached" in fields, bare = "bare" in fields,
                locked = "locked" in fields, prunable = "prunable" in fields)
            fields = mutableMapOf()
        } else {
            val space = text.indexOf(' ')
            fields[if (space < 0) text else text.substring(0, space)] = if (space < 0) "" else text.substring(space + 1)
        }
    }
    if (fields.isNotEmpty()) gitFailure(DiffReason.git_failed, "Incomplete worktree entry")
    return result
}

internal data class GitParsedPatch(val hunks: List<DiffHunk>, val limited: DiffProblem? = null)

/** Only complete hunks are returned; filenames in patch headers are deliberately never interpreted. */
internal fun parseGitPatch(bytes: ByteArray, maxBytes: Int, maxLines: Int): GitParsedPatch {
    val byteLimited = bytes.size > maxBytes
    val prefix = bytes.copyOfRange(0, minOf(bytes.size, maxBytes))
    // A byte cap can bisect UTF-8; discard the unfinished line before strict decoding.
    val lastLf = prefix.indexOfLast { it == 10.toByte() }
    val text = gitText(if (byteLimited) prefix.copyOfRange(0, lastLf + 1) else prefix)
    val lines = text.split('\n')
    val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$")
    val hunks = mutableListOf<DiffHunk>()
    var current: DiffHunk? = null
    var content = mutableListOf<DiffLine>()
    var old = 0; var new = 0; var consumedOld = 0; var consumedNew = 0
    fun finish() {
        val hunk = current ?: return
        if (consumedOld == hunk.oldCount && consumedNew == hunk.newCount) hunks += hunk.copy(lines = content.toList())
        else gitFailure(DiffReason.git_failed, "Incomplete Git hunk")
    }
    val limit = minOf(lines.size, maxLines)
    for (i in 0..<limit) {
        val line = lines[i]
        val match = hunkHeader.matchEntire(line)
        if (match != null) {
            finish()
            old = match.groupValues[1].toInt(); new = match.groupValues[3].toInt()
            current = DiffHunk(old, match.groupValues[2].ifEmpty { "1" }.toInt(),
                new, match.groupValues[4].ifEmpty { "1" }.toInt(), match.groupValues[5].removePrefix(" "), emptyList())
            content = mutableListOf(); consumedOld = 0; consumedNew = 0
        } else if (current != null) {
            if (line == "\\ No newline at end of file") {
                if (content.isEmpty()) gitFailure(DiffReason.git_failed, "Missing final line")
                content[content.lastIndex] = content.last().copy(noNewline = true)
            } else if (line.isNotEmpty() && (consumedOld < current.oldCount || consumedNew < current.newCount)) {
                content += when (line[0]) {
                    ' ' -> { consumedOld++; consumedNew++; DiffLine(DiffLineKind.context, line.substring(1), old++, new++) }
                    '-' -> { consumedOld++; DiffLine(DiffLineKind.deletion, line.substring(1), oldLine = old++) }
                    '+' -> { consumedNew++; DiffLine(DiffLineKind.addition, line.substring(1), newLine = new++) }
                    else -> gitFailure(DiffReason.git_failed, "Malformed hunk line")
                }
            }
        }
    }
    val limited = byteLimited || lines.size > maxLines
    if (!limited) finish()
    // Even a complete last hunk is omitted on a cap: the omitted line might be a no-newline marker.
    return GitParsedPatch(hunks, if (limited) DiffProblem(DiffErrorCode.limited, DiffReason.patch_limit, "Patch limit reached") else null)
}

/** Raw records and patch blocks come from the same invocation; select without decoding quoted headers. */
internal fun selectGitPatch(bytes: ByteArray, file: DiffFile): ByteArray {
    if (bytes.isEmpty()) return bytes
    val separator = (0..<bytes.size - 1).firstOrNull { bytes[it] == 0.toByte() && bytes[it + 1] == 0.toByte() }
        ?: gitFailure(DiffReason.git_failed, "Missing raw/patch separator")
    val raw = parseGitRaw(bytes.copyOfRange(0, separator + 1))
    val selected = raw.indexOfFirst { it.file.oldPath == file.oldPath && it.file.newPath == file.newPath && it.file.change == file.change }
    if (selected < 0) gitFailure(DiffReason.snapshot_changed, "Refresh", DiffErrorCode.stale)
    val prefix = "diff --git ".encodeToByteArray()
    val starts = mutableListOf<Int>()
    for (i in separator + 2..bytes.size - prefix.size) {
        if ((i == separator + 2 || bytes[i - 1] == 10.toByte()) && prefix.indices.all { bytes[i + it] == prefix[it] }) starts += i
    }
    if (starts.size != raw.size) gitFailure(DiffReason.git_failed, "Cannot associate patch blocks with files")
    return bytes.copyOfRange(starts[selected], starts.getOrElse(selected + 1) { bytes.size })
}
