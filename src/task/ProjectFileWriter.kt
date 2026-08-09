package io.kotgent.task

/**
 * Creating a `.kotgent.json`, atomically and never destructively.
 *
 * The publication sequence mirrors the upload path (`FileUploadRoutes.kt`): `mkstemp` sibling → write →
 * `fsync` → `chmod` → `link(2)`, unlinking the temp on EVERY path including success. Two details differ
 * on purpose:
 *
 *  - the mode is `0666 & ~umask`, **not** `0600`: this file is meant to be committed and read by every
 *    tool in the repository, unlike a token or a hook header;
 *  - a lost `link(2)` race is **not** an error. An existing file always wins, so the loser re-reads the
 *    winner's file and returns THAT descriptor — two agents running `kotgent task add` in a fresh
 *    repository at the same moment must converge on one project uuid, not fail one of them.
 *
 * The daemon writes the file and **never commits it**; the agent skill is told to mention it rather than
 * sweep it into an unrelated commit.
 *
 * Bodies are [TODO] here on purpose: Task 4 implements this file.
 */
interface ProjectFileWriter {

    /**
     * Ensure [dir] has a `.kotgent.json` and return its contents — the freshly written one, or the
     * existing/racing one. [name] is the project's display name; a fresh file gets a newly minted uuid.
     *
     * @throws ProjectPathException when [dir] is relative or is not an existing directory, or when the
     *   write itself fails. Never leaves a temp file behind, on any branch.
     */
    suspend fun ensureProjectFile(dir: String, name: String): ProjectFile
}

/**
 * The real writer. Stock `platform.posix` (`mkstemp`/`fsync`/`link`/`unlink`/`umask`) so it links into
 * the test binary (KT-78062) and the whole sequence — including "no temp survives any branch" — is
 * testable in `$TMPDIR`.
 */
class PosixProjectFileWriter : ProjectFileWriter {
    override suspend fun ensureProjectFile(dir: String, name: String): ProjectFile =
        TODO("Task 4: atomic .kotgent.json creation")
}
