package io.kotgent.diff

/** OIDs are full SHA-1 or SHA-256, never rev expressions, abbreviations, or flags. */
fun isDiffOid(value: String): Boolean =
    (value.length == 40 || value.length == 64) && value.all { it in '0'..'9' || it in 'a'..'f' }

/** Request refs must also match an entry from refs(); syntactic validity alone grants nothing. */
fun isDiffBranchRef(value: String): Boolean {
    if (!value.startsWith("refs/heads/") && !value.startsWith("refs/remotes/")) return false
    if (value.any { it <= ' ' || it == '\u007f' || it in "~^:?*[\\" }) return false
    if (".." in value || "@{" in value || value.endsWith('.')) return false
    return value.split('/').all { it.isNotEmpty() && !it.startsWith('.') && !it.endsWith(".lock") }
}

fun validateDiffSelection(selection: DiffSelection): DiffProblem? {
    val valid = when (selection.mode) {
        DiffMode.local -> selection.commitOid == null && selection.parent == null && selection.baseRef == null
        DiffMode.commit -> selection.scope == null && selection.baseRef == null &&
            selection.commitOid?.let(::isDiffOid) == true && (selection.parent == null || selection.parent > 0)
        DiffMode.branch -> selection.scope == null && selection.commitOid == null && selection.parent == null &&
            selection.baseRef?.let(::isDiffBranchRef) == true
    }
    return if (valid) null else DiffProblem(DiffErrorCode.invalid, DiffReason.invalid_request, "Invalid comparison fields")
}

fun validateDiffCursor(cursor: DiffCommitCursor): DiffProblem? =
    if (isDiffOid(cursor.headOid) && (cursor.baseOid == null || isDiffOid(cursor.baseOid)) && cursor.offset >= 0) null
    else DiffProblem(DiffErrorCode.invalid, DiffReason.invalid_request, "Invalid commit cursor")

/** A unique advertised remote default is supplied by the adapter; never infer from feature upstream. */
fun defaultDiffBase(refs: List<DiffRef>, savedBase: String?, remoteDefault: String?): String? {
    val available = refs.map { it.name }.filter(::isDiffBranchRef).toSet()
    return listOfNotNull(savedBase, remoteDefault, "refs/heads/main", "refs/heads/master")
        .firstOrNull { it in available }
}

fun localDiffComparison(
    repository: DiffRepository,
    worktreeId: String,
    headOid: String?,
    scope: DiffLocalScope = DiffLocalScope.all,
): DiffComparison {
    require(headOid == null || isDiffOid(headOid))
    return DiffComparison(repository, worktreeId, DiffMode.local, scope, headOid,
        leftOid = headOid.takeUnless { scope == DiffLocalScope.unstaged })
}

/** A true root has no parents; a shallow boundary must be reported by the adapter, not called a root. */
fun commitDiffComparison(
    repository: DiffRepository,
    worktreeId: String,
    headOid: String,
    commit: DiffCommit,
    parent: Int? = null,
): DiffResponse<DiffComparison> {
    if (!isDiffOid(headOid) || !isDiffOid(commit.oid) || commit.parents.any { !isDiffOid(it) } ||
        (parent != null && parent !in 1..commit.parents.size)) {
        return DiffResponse(error = DiffProblem(DiffErrorCode.invalid, DiffReason.invalid_request, "Invalid commit or parent"))
    }
    val selected = if (commit.parents.isEmpty()) null else parent ?: 1
    return DiffResponse(data = DiffComparison(repository, worktreeId, DiffMode.commit, headOid = headOid,
        commitOid = commit.oid, parent = selected, leftOid = selected?.let { commit.parents[it - 1] }, rightOid = commit.oid))
}

/** mergeBases is Git's --all result; missing shallow history must never silently choose a base. */
fun branchDiffComparison(
    repository: DiffRepository,
    worktreeId: String,
    headOid: String,
    base: DiffRef,
    mergeBases: List<String>,
    historyIncomplete: Boolean = false,
): DiffResponse<DiffComparison> {
    if (!isDiffOid(headOid) || !isDiffOid(base.oid) || !isDiffBranchRef(base.name) || mergeBases.any { !isDiffOid(it) }) {
        return DiffResponse(error = DiffProblem(DiffErrorCode.invalid, DiffReason.invalid_request, "Invalid branch comparison"))
    }
    val reason = when {
        historyIncomplete -> DiffReason.shallow_history
        mergeBases.isEmpty() -> DiffReason.no_merge_base
        mergeBases.distinct().size > 1 -> DiffReason.ambiguous_merge_base
        else -> null
    }
    if (reason != null) return DiffResponse(error = DiffProblem(DiffErrorCode.unavailable, reason, "Cannot determine a unique merge base"))
    val mergeBase = mergeBases.first()
    return DiffResponse(data = DiffComparison(repository, worktreeId, DiffMode.branch, headOid = headOid,
        baseRef = base.name, baseOid = base.oid, mergeBaseOid = mergeBase, leftOid = mergeBase, rightOid = headOid))
}
