package io.kotgent.diff

import io.kotgent.crypto.sha256
import io.kotgent.host.canonicalWorktreePath
import io.kotgent.host.readWorktreeFile
import io.kotgent.host.WorktreeFileKind
import io.kotgent.host.worktreeUserHome
import io.kotgent.proc.CapturedProcess
import io.kotgent.proc.CapturedProcessException
import io.kotgent.proc.CapturedProcessLimitException
import io.kotgent.proc.CapturedProcessResult
import io.kotgent.proc.CapturedProcessTimeoutException
import io.kotgent.proc.CapturedStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** Read-only Git adapter. The service bounds concurrent requests and owns snapshot expiry. */
class GitDiffReader(
    private val executable: String = "/usr/bin/git",
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Optional benchmark counter scoped to this reader; never receives paths or content. */
    private val measure: ((String, Long) -> Unit)? = null,
    private val diskCache: DiffDiskCache? = null,
) : DiffReader {
    init { require(executable.startsWith('/') && '\u0000' !in executable) }

    // Complete replacement: no inherited Git redirects, config injection, external diff or SSH commands.
    // System config remains enabled: in particular, do not bypass safe.directory.
    private val environment = mapOf(
        "PATH" to "/usr/bin:/bin", "LC_ALL" to "C", "GIT_TERMINAL_PROMPT" to "0",
        "GIT_OPTIONAL_LOCKS" to "0", "GIT_NO_LAZY_FETCH" to "1", "GIT_NO_REPLACE_OBJECTS" to "1",
        "GIT_CONFIG_NOSYSTEM" to "0", "GIT_ATTR_NOSYSTEM" to "1", "GIT_PAGER" to "cat",
        "GIT_LITERAL_PATHSPECS" to "1", "GIT_ALLOW_PROTOCOL" to "",
    ) + listOfNotNull(worktreeUserHome()?.let { "HOME" to it }).toMap()
    private val flags = listOf("--no-pager", "--no-optional-locks", "-c", "core.fsmonitor=false",
        "-c", "core.untrackedCache=false", "-c", "core.preloadIndex=false", "-c", "diff.autoRefreshIndex=false",
        "-c", "diff.external=", "-c", "core.pager=cat", "-c", "color.ui=false",
        "-c", "log.showSignature=false", "-c", "diff.suppressBlankEmpty=false",
        "-c", "diff.ignoreSubmodules=dirty", "-c", "submodule.recurse=false")
    private val diffFlags = listOf("--no-ext-diff", "--no-textconv", "--no-color", "--no-relative",
        "--ignore-submodules=dirty", "--find-renames=50%", "-l1000", "--word-diff=none",
        "--output-indicator-new=+", "--output-indicator-old=-", "--output-indicator-context= ")

    private suspend fun command(cwd: String, args: List<String>, extra: Map<String, String> = emptyMap()): CapturedProcessResult {
        if (!cwd.startsWith('/') || '\u0000' in cwd) gitFailure(DiffReason.invalid_request, "Invalid checkout", DiffErrorCode.invalid)
        // Keys and values are separate: a valid subsection name can itself contain '='.
        val configEnvironment = buildMap {
            put("GIT_CONFIG_COUNT", extra.size.toString())
            extra.entries.forEachIndexed { index, entry ->
                put("GIT_CONFIG_KEY_$index", entry.key)
                put("GIT_CONFIG_VALUE_$index", entry.value)
            }
        }
        measure?.invoke("gitCommands", 1)
        if (args.firstOrNull() in listOf("log", "diff")) measure?.invoke("${args.first()}Commands", 1)
        return CapturedProcess.run(listOf(executable) + flags + listOf("-C", cwd) + args,
            environment + configEnvironment, DIFF_PROCESS_STDOUT_BYTES, DIFF_PROCESS_STDERR_BYTES,
            DIFF_PROCESS_TIMEOUT_MILLIS.milliseconds).also {
                measure?.invoke("gitBytes", it.stdout.size.toLong() + it.stderr.size)
            }
    }

    private suspend fun read(cwd: String, args: List<String>, extra: Map<String, String> = emptyMap()): ByteArray {
        val result = command(cwd, args, extra)
        if (result.exitCode != 0) gitFailure(DiffReason.git_failed, "Git read failed")
        return result.stdout
    }

    private fun line(bytes: ByteArray): String {
        if (bytes.lastOrNull() != 10.toByte()) gitFailure(DiffReason.git_failed, "Missing Git record terminator")
        return gitText(bytes.copyOfRange(0, bytes.size - 1))
    }

    private suspend fun <T> response(block: suspend () -> T): DiffResponse<T> = try {
        DiffResponse(data = block())
    } catch (failure: GitReadFailure) { DiffResponse(error = failure.problem)
    } catch (_: CapturedProcessTimeoutException) {
        DiffResponse(error = DiffProblem(DiffErrorCode.limited, DiffReason.timeout, "Git read timed out"))
    } catch (_: CapturedProcessLimitException) {
        DiffResponse(error = DiffProblem(DiffErrorCode.limited, DiffReason.output_limit, "Git output limit reached"))
    } catch (_: CapturedProcessException) {
        DiffResponse(error = DiffProblem(DiffErrorCode.unavailable, DiffReason.git_failed, "Cannot run Git"))
    }

    private fun <T> DiffResponse<T>.value(): T = data ?: throw GitReadFailure(error!!)

    override suspend fun worktrees(cwd: String): DiffResponse<DiffWorktrees> = response {
        val root = canonicalWorktreePath(cwd) ?: gitFailure(DiffReason.missing_worktree, "Checkout is missing")
        val commonResult = command(root, listOf("rev-parse", "--path-format=absolute", "--git-common-dir", "--is-bare-repository"))
        if (commonResult.exitCode != 0) gitFailure(DiffReason.not_repository, "Not an accessible Git repository")
        // Split from the fixed boolean suffix, never on every LF: common-dir paths may contain LF.
        val discovery = line(commonResult.stdout)
        val bare = when (discovery.substringAfterLast('\n')) {
            "true" -> true
            "false" -> false
            else -> gitFailure(DiffReason.git_failed, "Malformed repository discovery")
        }
        if ('\n' !in discovery) gitFailure(DiffReason.git_failed, "Missing common directory")
        val common = canonicalWorktreePath(discovery.substringBeforeLast('\n')) ?: gitFailure(DiffReason.not_repository, "Missing common directory")
        val current = canonicalWorktreePath(line(read(root, listOf("rev-parse", if (bare) "--absolute-git-dir" else "--show-toplevel"))))
            ?: gitFailure(DiffReason.missing_worktree, "Checkout is missing")
        val trees = parseGitWorktrees(read(root, listOf("worktree", "list", "--porcelain", "-z"))).map { tree ->
            val canonical = canonicalWorktreePath(tree.path)
            val missing = canonical == null || tree.prunable
            tree.copy(id = canonical ?: tree.path, path = canonical ?: tree.path,
                current = canonical == current, unavailable = if (missing)
                    DiffProblem(DiffErrorCode.unavailable, DiffReason.missing_worktree, "Checkout is unavailable") else null)
        }
        DiffWorktrees(DiffRepository(common), current, trees, now())
    }

    private suspend fun verify(repository: DiffRepository, worktree: DiffWorktree): DiffWorktree {
        val inventory = worktrees(worktree.path).value()
        if (inventory.repository != repository) gitFailure(DiffReason.foreign_worktree, "Repository changed", DiffErrorCode.invalid)
        val fresh = inventory.worktrees.singleOrNull { it.id == worktree.id && it.path == worktree.path }
            ?: gitFailure(DiffReason.foreign_worktree, "Unknown worktree", DiffErrorCode.invalid)
        fresh.unavailable?.let { throw GitReadFailure(it) }
        return fresh
    }

    override suspend fun refs(repository: DiffRepository, worktree: DiffWorktree): DiffResponse<DiffRefs> = response {
        val tree = verify(repository, worktree)
        refsAt(repository, tree)
    }

    private suspend fun refsAt(repository: DiffRepository, tree: DiffWorktree): DiffRefs {
        val output = read(tree.path, listOf("for-each-ref", "--format=%(refname)%00%(objectname)%00%(symref)%00",
            "refs/heads/", "refs/remotes/"))
        val refs = mutableListOf<DiffRef>()
        val defaults = mutableSetOf<String>()
        // Ref names cannot contain LF. Each record has three NUL fields followed by one LF.
        for (record in gitFields(output, 10)) {
            val fields = gitFields(record).map(::gitText)
            if (fields.size != 3 || !isDiffOid(fields[1])) gitFailure(DiffReason.git_failed, "Malformed ref")
            if (fields[2].isNotEmpty()) {
                if (fields[0].startsWith("refs/remotes/") && fields[0].endsWith("/HEAD")) defaults += fields[2]
            } else if (isDiffBranchRef(fields[0])) refs += DiffRef(fields[0], fields[1],
                if (fields[0].startsWith("refs/heads/")) DiffRefKind.local else DiffRefKind.remote)
        }
        val remote = defaults.singleOrNull()?.takeIf { name -> refs.any { it.name == name } }
        return DiffRefs(repository, tree.id, refs, remote, defaultDiffBase(refs, null, remote), now())
    }

    private suspend fun commitObject(path: String, oid: String): ByteArray {
        if (!isDiffOid(oid)) gitFailure(DiffReason.invalid_request, "Expected full commit OID", DiffErrorCode.invalid)
        val type = command(path, listOf("cat-file", "-t", oid))
        if (type.exitCode != 0) gitFailure(DiffReason.missing_object, "Commit object is unavailable")
        if (line(type.stdout) != "commit") gitFailure(DiffReason.invalid_request, "Object is not a commit", DiffErrorCode.invalid)
        return read(path, listOf("cat-file", "commit", oid))
    }

    private suspend fun commit(path: String, oid: String): DiffCommit {
        // Raw object retains parents hidden by shallow grafts. Never mislabel a shallow boundary as root.
        val raw = gitText(commitObject(path, oid))
        val headers = raw.substringBefore("\n\n").split('\n')
        val parents = headers.filter { it.startsWith("parent ") }.map { it.removePrefix("parent ") }
        if (parents.any { !isDiffOid(it) }) gitFailure(DiffReason.git_failed, "Malformed commit parent")
        val author = headers.firstOrNull { it.startsWith("author ") }?.removePrefix("author ")
            ?: gitFailure(DiffReason.git_failed, "Commit author missing")
        val close = author.lastIndexOf('>')
        val timestamp = author.substring(close + 1).trim().substringBefore(' ').toLongOrNull()
            ?: gitFailure(DiffReason.git_failed, "Commit timestamp missing")
        return DiffCommit(oid, parents, author.substringBeforeLast(" <"), timestamp * 1000,
            raw.substringAfter("\n\n", "").substringBefore('\n'))
    }

    private suspend fun head(path: String): String? {
        val result = command(path, listOf("rev-parse", "--verify", "HEAD"))
        if (result.exitCode == 0) return line(result.stdout).also { val _ = commitObject(path, it) }
        val symbolic = command(path, listOf("symbolic-ref", "-q", "HEAD"))
        if (symbolic.exitCode == 0) {
            val exists = command(path, listOf("show-ref", "--verify", "--quiet", line(symbolic.stdout)))
            if (exists.exitCode == 1) return null
        }
        gitFailure(DiffReason.missing_object, "HEAD is unavailable")
    }

    override suspend fun commits(repository: DiffRepository, worktree: DiffWorktree, baseRef: String?, cursor: DiffCommitCursor?): DiffResponse<DiffCommits> = response {
        val tree = verify(repository, worktree)
        if (cursor != null && baseRef != null) gitFailure(DiffReason.invalid_request, "Cursor and baseRef are exclusive", DiffErrorCode.invalid)
        cursor?.let { validateDiffCursor(it)?.let { problem -> throw GitReadFailure(problem) } }
        // --skip still walks history: explicitly bound caller-controlled paging work.
        if (cursor != null && cursor.offset > 100_000) gitFailure(DiffReason.output_limit, "History paging limit reached", DiffErrorCode.limited)
        val pinned = cursor ?: run {
            val tip = head(tree.path) ?: return@response DiffCommits(repository, tree.id, null, emptyList(), readAt = now())
            DiffCommitCursor(tip, baseRef?.let { ref(repository, tree, it).oid })
        }
        // head() already validated the first page's exact OID in this request. Supplied cursors
        // still require fresh membership/type validation, including after refs move or objects vanish.
        if (cursor != null) { val _ = commitObject(tree.path, pinned.headOid) }
        pinned.baseOid?.let { commitObject(tree.path, it) }
        val args = listOf("log", "--topo-order", "--max-count=${DIFF_COMMIT_PAGE_SIZE + 1}", "--skip=${pinned.offset}",
            "-z", "--format=%H%x00%P%x00%an%x00%at%x00%s", "--encoding=UTF-8", pinned.headOid) +
            listOfNotNull(pinned.baseOid?.let { "^$it" }) + "--"
        val context = cacheContext(repository, tree, listOfNotNull(pinned.headOid, pinned.baseOid))
        val key = context?.let { "history-v1:$it:" + Json.encodeToString(pinned) }
        key?.let { diskCache?.get<HistoryPage>(it, now()) }?.let { page ->
            return@response DiffCommits(repository, tree.id, pinned, page.commits,
                if (page.more) pinned.copy(offset = pinned.offset + DIFF_COMMIT_PAGE_SIZE) else null, now())
        }
        val records = gitFields(read(tree.path, args))
        if (records.size % 5 != 0) gitFailure(DiffReason.git_failed, "Malformed commit page")
        val shallow = line(read(tree.path, listOf("rev-parse", "--is-shallow-repository"))) == "true"
        val commits = records.chunked(5).take(DIFF_COMMIT_PAGE_SIZE).map { record ->
            val oid = gitText(record[0])
            val parents = gitText(record[1]).split(' ').filter { it.isNotEmpty() }
            if (!isDiffOid(oid) || parents.any { !isDiffOid(it) }) gitFailure(DiffReason.git_failed, "Malformed commit OID")
            val authored = gitText(record[3]).toLongOrNull() ?: gitFailure(DiffReason.git_failed, "Malformed commit date")
            // Git log hides grafted shallow parents. Read that boundary's raw object to expose them.
            if (shallow && parents.isEmpty()) commit(tree.path, oid)
            else DiffCommit(oid, parents, gitText(record[2]), authored * 1000, gitText(record[4]))
        }
        val more = records.size / 5 > DIFF_COMMIT_PAGE_SIZE
        if (key != null && context == cacheContext(repository, tree, listOfNotNull(pinned.headOid, pinned.baseOid)))
            diskCache?.put(key, HistoryPage(commits, more), now())
        DiffCommits(repository, tree.id, pinned, commits,
            if (more) pinned.copy(offset = pinned.offset + DIFF_COMMIT_PAGE_SIZE) else null, now())
    }

    private suspend fun ref(repository: DiffRepository, tree: DiffWorktree, name: String): DiffRef =
        refsAt(repository, tree).refs.singleOrNull { it.name == name }
            ?: gitFailure(DiffReason.missing_ref, "Base branch is unavailable")

    private suspend fun comparison(repository: DiffRepository, tree: DiffWorktree, selection: DiffSelection): DiffComparison {
        validateDiffSelection(selection)?.let { throw GitReadFailure(it) }
        val tip = head(tree.path)
        return when (selection.mode) {
            DiffMode.local -> {
                if (tree.bare) gitFailure(DiffReason.bare_worktree, "Bare repositories have no local changes")
                localDiffComparison(repository, tree.id, tip, selection.scope ?: DiffLocalScope.all)
            }
            DiffMode.commit -> {
                val selected = commit(tree.path, selection.commitOid!!)
                selected.parents.forEach { parent ->
                    if (command(tree.path, listOf("cat-file", "-e", "$parent^{commit}")).exitCode != 0)
                        gitFailure(DiffReason.shallow_history, "Commit parent is unavailable")
                }
                commitDiffComparison(repository, tree.id, tip ?: selected.oid, selected, selection.parent).value()
            }
            DiffMode.branch -> {
                if (tip == null) gitFailure(DiffReason.unborn_head, "HEAD has no commits")
                val base = ref(repository, tree, selection.baseRef!!)
                val _ = commitObject(tree.path, base.oid)
                val result = command(tree.path, listOf("merge-base", "--all", base.oid, tip))
                if (result.exitCode !in 0..1) gitFailure(DiffReason.git_failed, "Cannot read merge base")
                val shallow = line(read(tree.path, listOf("rev-parse", "--is-shallow-repository"))) == "true"
                val mergeBases = gitFields(result.stdout, 10).map(::gitText)
                // An endpoint that is a merge base proves ancestry; omitted ancestors cannot improve it.
                val provenEndpoint = mergeBases.singleOrNull().let { it == base.oid || it == tip }
                branchDiffComparison(repository, tree.id, tip, base, mergeBases,
                    historyIncomplete = shallow && !provenEndpoint).value()
            }
        }
    }

    private suspend fun emptyTree(path: String): String {
        // hash-object without -w is a read-only calculation, even when the empty tree is not stored.
        return line(read(path, listOf("hash-object", "-t", "tree", "--stdin")))
    }

    private suspend fun diffArgs(path: String, comparison: DiffComparison): List<String> = when {
        comparison.mode != DiffMode.local -> listOf("diff") + diffFlags + listOf(comparison.leftOid ?: emptyTree(path), comparison.rightOid!!)
        comparison.scope == DiffLocalScope.staged -> listOf("diff", "--cached") + diffFlags + listOfNotNull(comparison.leftOid)
        comparison.scope == DiffLocalScope.unstaged -> listOf("diff") + diffFlags
        else -> listOf("diff") + diffFlags + (comparison.leftOid?.let(::listOf) ?: listOf(emptyTree(path)))
    }

    /** Disable every configured conversion driver before Git can inspect worktree content. */
    private suspend fun noFilters(path: String): Map<String, String> {
        val config = command(path, listOf("config", "--null", "--name-only", "--get-regexp", "^filter\\..*\\.(clean|smudge|process|required)$"))
        if (config.exitCode !in 0..1) gitFailure(DiffReason.git_failed, "Cannot inspect Git conversion settings")
        return gitFields(config.stdout).map(::gitText).associateWith { key ->
            if (key.endsWith(".required")) "false" else ""
        }
    }

    @Serializable private data class HistoryPage(val commits: List<DiffCommit>, val more: Boolean)
    @Serializable private data class PatchPayload(val hunks: List<DiffHunk>)
    @Serializable private data class Observation(val files: List<DiffFile>, val digest: String, val limited: DiffProblem?)

    private suspend fun observe(tree: DiffWorktree, comparison: DiffComparison, context: String?): Observation {
        val key = context?.let { "files-v1:$it:" + Json.encodeToString(comparison) }
        key?.let { diskCache?.get<Observation>(it, now()) }?.let { return it }
        val result = observeUncached(tree, comparison)
        if (key != null && result.limited == null && result.files.none { it.problem != null } &&
            context == comparisonContext(tree, comparison)) diskCache?.put(key, result, now())
        return result
    }

    private suspend fun observeUncached(tree: DiffWorktree, comparison: DiffComparison): Observation {
        val local = comparison.mode == DiffMode.local
        val filterFlags = if (local) noFilters(tree.path) else emptyMap()
        val args = diffArgs(tree.path, comparison)
        val metadata = try {
            read(tree.path, args + listOf("--raw", "--numstat", "--no-abbrev", "-z", "--"), filterFlags)
        } catch (failure: CapturedProcessLimitException) {
            // Combining two bounded outputs must not reduce the formerly supported inventory.
            // Each fallback command keeps the original cap; stderr limits and cancellation escape.
            if (failure.stream != CapturedStream.STDOUT) throw failure
            val raw = read(tree.path, args + listOf("--raw", "--no-abbrev", "-z", "--"), filterFlags)
            val stats = read(tree.path, args + listOf("--numstat", "-z", "--"), filterFlags)
            raw + stats
        }
        val parsed = parseGitRawNumstat(metadata)
        val stats = parsed.stats.associateBy { it.newPath }
        val statusBytes = if (local) read(tree.path, listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignore-submodules=dirty"), filterFlags) else byteArrayOf()
        val statuses = parseGitStatus(statusBytes)
        val trackedStatuses = statuses.filter { it.index != "?" }
        val statusByPath = trackedStatuses.associateBy { it.path }
        // Raw worktree diff can report stat-dirty entries whose bytes cancel the staged edit.
        // Numstat runs content comparison (and includes 0/0 mode-only records).
        val files = parsed.raw.filter { rawFile ->
            rawFile.file.kind == DiffFileKind.conflict || (rawFile.file.newPath ?: rawFile.file.oldPath) in stats
        }.map { rawFile ->
            val file = rawFile.file
            val stat = stats[file.newPath ?: file.oldPath]
            val status = statusByPath[file.newPath ?: file.oldPath] ?: trackedStatuses.firstOrNull { it.oldPath != null && it.oldPath == file.oldPath }
            val conflict = status != null && (status.index == "U" || status.worktree == "U" || status.index + status.worktree in listOf("AA", "DD"))
            file.copy(kind = when { conflict -> DiffFileKind.conflict
                file.kind != DiffFileKind.text -> file.kind
                stat != null && stat.added == null -> DiffFileKind.binary
                else -> file.kind }, change = if (conflict) DiffChange.conflicted else file.change,
                additions = stat?.added, deletions = stat?.deleted, indexStatus = status?.index, worktreeStatus = status?.worktree)
        }.distinctBy { it.newPath ?: it.oldPath }.toMutableList()
        if (local && comparison.scope != DiffLocalScope.staged) {
            statuses.filter { it.index == "?" }.forEach {
                files += DiffFile("", newPath = it.path, change = DiffChange.untracked, indexStatus = "?", worktreeStatus = "?")
            }
        }
        val identities = mutableListOf(digest(metadata), digest(statusBytes))
        if (local) {
            val observedHead = head(tree.path)
            if (observedHead != comparison.headOid) gitFailure(DiffReason.snapshot_changed, "Refresh", DiffErrorCode.stale)
            identities += observedHead.orEmpty()
            identities += digest(read(tree.path, listOf("ls-files", "--stage", "-z")))
            // Hash each bounded file independently; unrelated files must not consume a shared
            // patch budget. Retain identities, not content bytes, and check cancellation per entry.
            for (i in files.indices.take(DIFF_MAX_FILES)) {
                currentCoroutineContext().ensureActive()
                val file = files[i]
                if (comparison.scope == DiffLocalScope.staged || file.newPath == null) continue
                if (file.newMode == "160000") {
                    identities += file.newPath + ":gitlink:" + workingGitlink(tree.path, file.newPath)
                    continue
                }
                val content = localContent(tree.path, file.newPath)
                identities += file.newPath + ":" + content.identity
                if (file.kind == DiffFileKind.text || file.change == DiffChange.untracked) {
                    files[i] = file.copy(kind = content.kind, problem = content.problem,
                        newMode = file.newMode ?: when {
                            content.kind == DiffFileKind.symlink -> "120000"
                            content.bytes != null -> if (content.executable) "100755" else "100644"
                            else -> null
                        })
                }
            }
        }
        val limited = if (files.size > DIFF_MAX_FILES) DiffProblem(DiffErrorCode.limited, DiffReason.file_limit, "File list limit reached") else null
        val withIds = files.take(DIFF_MAX_FILES).map { it.copy(fileId = digest(Json.encodeToString(it).encodeToByteArray())) }
        return Observation(withIds, digest((Json.encodeToString(comparison) + identities.joinToString("\n")).encodeToByteArray()), limited)
    }

    override suspend fun files(repository: DiffRepository, worktree: DiffWorktree, selection: DiffSelection): DiffResponse<DiffFiles> = response {
        val tree = verify(repository, worktree)
        val comparison = comparison(repository, tree, selection)
        val observation = observe(tree, comparison, comparisonContext(tree, comparison))
        DiffFiles(DiffSnapshot(observation.digest, comparison, now()), observation.files, observation.limited)
    }

    override suspend fun patch(repository: DiffRepository, worktree: DiffWorktree, snapshot: DiffSnapshot, file: DiffFile): DiffResponse<DiffPatch> = response {
        val tree = verify(repository, worktree)
        val comparison = snapshot.comparison
        if (comparison.repository != repository || comparison.worktreeId != tree.id) gitFailure(DiffReason.snapshot_changed, "Refresh", DiffErrorCode.stale)
        suspend fun check(): String? {
            val context = comparisonContext(tree, comparison)
            val observation = observe(tree, comparison, context)
            if (observation.digest != snapshot.id) gitFailure(DiffReason.snapshot_changed, "Refresh", DiffErrorCode.stale)
            if (file !in observation.files) gitFailure(DiffReason.unknown_file, "Unknown snapshot file", DiffErrorCode.invalid)
            return context
        }
        val context = check()
        val key = context?.let { "patch-v1:$it:" + Json.encodeToString(comparison) + ":" + Json.encodeToString(file) }
        key?.let { diskCache?.get<PatchPayload>(it, now()) }?.let { payload ->
            if (check() != context) gitFailure(DiffReason.snapshot_changed, "Refresh", DiffErrorCode.stale)
            return@response DiffPatch(snapshot.copy(readAt = now()), file, payload.hunks)
        }
        val result = if (file.kind != DiffFileKind.text) DiffPatch(snapshot, file, limited = file.problem?.takeIf { it.code == DiffErrorCode.limited }) else if (file.change == DiffChange.untracked) {
            val content = localContent(tree.path, file.newPath!!)
            val bytes = content.bytes ?: gitFailure(DiffReason.snapshot_changed, "Refresh", DiffErrorCode.stale)
            val text = gitText(bytes)
            val lines = text.split('\n').let { if (text.endsWith('\n')) it.dropLast(1) else it }.let { if (text.isEmpty()) emptyList() else it }
            if (lines.size > DIFF_MAX_PATCH_LINES) DiffPatch(snapshot, file, limited = DiffProblem(DiffErrorCode.limited, DiffReason.patch_limit, "Patch limit reached"))
            else DiffPatch(snapshot, file, if (lines.isEmpty()) emptyList() else listOf(DiffHunk(0, 0, 1, lines.size,
                lines = lines.mapIndexed { i, line -> DiffLine(DiffLineKind.addition, line, newLine = i + 1,
                    noNewline = i == lines.lastIndex && !text.endsWith('\n')) })))
        } else {
            val output = read(tree.path, diffArgs(tree.path, comparison) + listOf("--raw", "--no-abbrev", "-z", "--patch", "--unified=3", "--") +
                listOfNotNull(file.oldPath, file.newPath).distinct(), if (comparison.mode == DiffMode.local) noFilters(tree.path) else emptyMap())
            val parsed = try { parseGitPatch(selectGitPatch(output, file), DIFF_MAX_PATCH_BYTES, DIFF_MAX_PATCH_LINES) }
            catch (failure: GitReadFailure) {
                if (failure.problem.reason != DiffReason.unsupported_encoding) throw failure
                val _ = check()
                return@response DiffPatch(snapshot, file.copy(kind = DiffFileKind.unavailable, problem = failure.problem))
            }
            DiffPatch(snapshot, file, parsed.hunks, parsed.limited)
        }
        if (check() != context) gitFailure(DiffReason.snapshot_changed, "Refresh", DiffErrorCode.stale)
        if (key != null && result.limited == null && result.file.problem == null)
            diskCache?.put(key, PatchPayload(result.hunks), now())
        result
    }

    private suspend fun comparisonContext(tree: DiffWorktree, comparison: DiffComparison): String? {
        if (comparison.mode == DiffMode.local) return null
        return cacheContext(comparison.repository, tree,
            listOfNotNull(comparison.headOid, comparison.baseOid, comparison.mergeBaseOid,
                comparison.commitOid, comparison.leftOid, comparison.rightOid).distinct(),
            listOfNotNull(comparison.leftOid, comparison.rightOid))
    }

    /**
     * Conservative, bounded proof of the inputs Git can consult. No stat/mtime validity cache.
     * Object inventory includes unreachable objects and alternates, so deletion cannot keep an old hit.
     * Large or unreadable contexts bypass storage; they do not narrow ordinary reader limits.
     */
    private suspend fun cacheContext(repository: DiffRepository, tree: DiffWorktree, pins: List<String>, endpoints: List<String>? = null): String? {
        if (diskCache == null || !diskCache.isOutside(repository.commonDir, tree.path)) return null
        return try {
            val parts = mutableListOf("reader-v1", repository.commonDir, tree.path, executable,
                digest(read(tree.path, listOf("--version"))),
                digest(read(tree.path, listOf("config", "--null", "--list"))))
            var dependencyBytes = 0L
            fun dependency(root: String, path: String): Boolean {
                val value = readWorktreeFile(root, path, DIFF_PROCESS_STDOUT_BYTES)
                if (value.kind !in listOf(WorktreeFileKind.regular, WorktreeFileKind.missing)) return false
                dependencyBytes += value.bytes.size
                if (dependencyBytes > DIFF_PROCESS_STDOUT_BYTES) return false
                parts += Json.encodeToString(listOf(root, path, value.kind.name, digest(value.bytes)))
                return true
            }
            for (path in listOf("shallow", "info/grafts")) if (!dependency(repository.commonDir, path)) return null
            val objects = read(tree.path, listOf("cat-file", "--batch-all-objects", "--batch-check=%(objectname) %(objecttype)"))
            val members = gitFields(objects, 10).map(::gitText).toSet()
            if (pins.any { "$it commit" !in members }) return null
            parts += digest(objects)
            if (endpoints != null) {
                // Index attributes are the fallback for absent checkout attributes, including split indexes.
                parts += digest(read(tree.path, listOf("ls-files", "--stage", "-z")))
                if (!dependency(repository.commonDir, "info/attributes")) return null
                val global = command(tree.path, listOf("config", "--path", "--get", "core.attributesFile"))
                if (global.exitCode !in 0..1) return null
                val attributeFile = if (global.exitCode == 0) line(global.stdout) else
                    worktreeUserHome()?.let { "$it/.config/git/attributes" }
                if (attributeFile != null && attributeFile.isNotEmpty()) {
                    if (attributeFile.startsWith('/')) {
                        if (!dependency("/", attributeFile.removePrefix("/"))) return null
                    } else if (!dependency(tree.path, attributeFile)) return null
                }
                val attributes = linkedSetOf(".gitattributes")
                for (oid in endpoints.distinct()) {
                    for (path in gitFields(read(tree.path, listOf("ls-tree", "-r", "--name-only", "-z", oid))).map(::gitText)) {
                        val components = path.split('/')
                        for (n in 1..<components.size) attributes += components.take(n).joinToString("/") + "/.gitattributes"
                        if (attributes.size > DIFF_MAX_FILES) return null
                    }
                }
                for (path in attributes) {
                    currentCoroutineContext().ensureActive()
                    if (!dependency(tree.path, path)) return null
                }
            }
            digest(Json.encodeToString(parts).encodeToByteArray())
        } catch (_: GitReadFailure) { null
        } catch (_: CapturedProcessLimitException) { null
        } catch (_: CapturedProcessTimeoutException) { null
        } catch (_: CapturedProcessException) { null }
    }

    /** Only the nested repository's root/HEAD metadata, never recursive content or status. */
    private suspend fun workingGitlink(root: String, path: String): String {
        val expectedRoot = "$root/$path"
        val canonical = canonicalWorktreePath(expectedRoot) ?: return "missing"
        if (canonical != expectedRoot) gitFailure(DiffReason.unsupported_path, "Submodule root traverses a symlink")
        val top = command(canonical, listOf("rev-parse", "--show-toplevel"))
        // An uninitialized submodule directory can resolve upward to the superproject.
        if (top.exitCode != 0 || canonicalWorktreePath(line(top.stdout)) != canonical) return "uninitialized"
        val result = command(canonical, listOf("rev-parse", "--verify", "HEAD"))
        if (result.exitCode != 0) return "missing-head"
        return line(result.stdout).also {
            if (!isDiffOid(it)) gitFailure(DiffReason.git_failed, "Malformed working gitlink OID")
        }
    }

    private data class LocalContent(val bytes: ByteArray?, val kind: DiffFileKind, val identity: String, val problem: DiffProblem? = null, val executable: Boolean = false)

    private fun localContent(root: String, path: String): LocalContent {
        val read = readWorktreeFile(root, path, DIFF_MAX_PATCH_BYTES)
        measure?.invoke("fileBytes", read.bytes.size.toLong())
        val identity = read.kind.name + ":" + read.executable + ":" + digest(read.bytes)
        return when (read.kind) {
            WorktreeFileKind.regular -> {
                val kind = if (0.toByte() in read.bytes) DiffFileKind.binary else try {
                    val _ = read.bytes.decodeToString(throwOnInvalidSequence = true)
                    DiffFileKind.text
                } catch (_: CharacterCodingException) { DiffFileKind.unavailable }
                LocalContent(read.bytes, kind, identity, if (kind == DiffFileKind.unavailable)
                    DiffProblem(DiffErrorCode.unavailable, DiffReason.unsupported_encoding, "File is not UTF-8") else null, read.executable)
            }
            WorktreeFileKind.symlink -> LocalContent(null, DiffFileKind.symlink, identity)
            WorktreeFileKind.limited -> LocalContent(null, DiffFileKind.limited, identity,
                DiffProblem(DiffErrorCode.limited, DiffReason.patch_limit, "File content limit reached"))
            else -> LocalContent(null, DiffFileKind.unavailable, identity,
                DiffProblem(DiffErrorCode.unavailable, DiffReason.unsupported_path, "File cannot be safely read"))
        }
    }

    private fun digest(bytes: ByteArray): String {
        measure?.invoke("hashBytes", bytes.size.toLong())
        return sha256(bytes).toHexString()
    }
}
