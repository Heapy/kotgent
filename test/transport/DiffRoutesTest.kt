@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.kotgent.transport

import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.diff.*
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.store.FakePreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import io.kotgent.proc.CapturedProcess
import io.kotgent.store.FakeEventStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.symlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO

class DiffRoutesTest {
    private val token = "diff-route-test-master-token"
    private val env = mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/nonexistent", "GIT_CONFIG_NOSYSTEM" to "1",
        "GIT_AUTHOR_NAME" to "Test", "GIT_AUTHOR_EMAIL" to "test@example.test", "GIT_COMMITTER_NAME" to "Test",
        "GIT_COMMITTER_EMAIL" to "test@example.test")
    private inner class Fixture(val dir: String, val client: HttpClient, val port: Int, val store: FakeEventStore) {
        val root = assertNotNull(io.kotgent.host.canonicalWorktreePath("$dir/repo"))
        @IgnorableReturnValue
        suspend fun git(vararg args: String): String {
            val r = CapturedProcess.run(listOf("/usr/bin/git", "-C", root) + args, env)
            assertEquals(0, r.exitCode, r.stderr.decodeToString())
            return r.stdout.decodeToString().removeSuffix("\n")
        }
        fun write(name: String, content: String) {
            val bytes = content.encodeToByteArray()
            val file = assertNotNull(fopen("$root/$name", "wb"))
            try { if (bytes.isNotEmpty()) bytes.usePinned { assertEquals(bytes.size.toULong(), fwrite(it.addressOf(0), 1u, bytes.size.convert(), file)) } }
            finally { fclose(file) }
        }
        suspend fun get(route: String, vararg params: Pair<String, String>, session: String = "diff", bearer: Boolean = true,
                        cookie: Boolean = false, origin: String? = null, keepAlive: Boolean = false): HttpResponse =
            client.get("http://127.0.0.1:$port$API_PREFIX/sessions/$session/diff/$route") {
                if (bearer) header(HttpHeaders.Authorization, "Bearer $token")
                if (cookie) header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=${issueSessionCookie(token, 1_700_000_000_000)}")
                origin?.let { header(HttpHeaders.Origin, it) }
                if (keepAlive) header(HttpHeaders.Connection, "keep-alive")
                params.forEach { [key, value] -> parameter(key, value) }
            }
        suspend fun files(vararg fields: Pair<String, String>): DiffFiles = get("files", "worktreeId" to root, *fields).data()
        suspend fun patch(files: DiffFiles, id: String = files.files.first().fileId): HttpResponse =
            get("patch", "worktreeId" to root, "snapshot" to files.snapshot.id, "fileId" to id)
    }
    @IgnorableReturnValue
    private suspend inline fun <reified T> HttpResponse.data(): T {
        val text = bodyAsText()
        assertEquals(HttpStatusCode.OK, status, text)
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        return assertNotNull(TRANSPORT_JSON.decodeFromString<DiffResponse<T>>(text).data)
    }
    private suspend fun HttpResponse.problem(status: HttpStatusCode, reason: DiffReason? = null) {
        val text = bodyAsText()
        assertEquals(status, this.status, text)
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        val error = assertNotNull(TRANSPORT_JSON.decodeFromString<DiffResponse<DiffFiles>>(text).error)
        if (reason != null) assertEquals(reason, error.reason)
    }
    private fun fixture(reader: DiffReader = GitDiffReader(), limits: DiffServiceLimits = DiffServiceLimits(), production: Boolean = false, diskCache: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        withTimeout(60_000) {
            val temp = CapturedProcess.run(listOf("/usr/bin/mktemp", "-d", "/tmp/kotgent-diff-http.XXXXXX"), env)
            assertEquals(0, temp.exitCode)
            val dir = temp.stdout.decodeToString().trim()
            val store = FakeEventStore()
            store.upsertSession(SessionMeta(SessionId("diff"), name = "", agent = "shell", cwd = "$dir/repo", tmuxSession = "unused",
                state = SessionState.running, createdAt = 0, updatedAt = 0))
            val effectiveReader = if (diskCache) GitDiffReader(diskCache = DiffDiskCache(io.kotgent.host.DiskCacheFiles("$dir/cache"))) else reader
            val service = DiffService(store, effectiveReader, limits)
            val server = if (production) null else embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
                routing { val _ = authenticated({ token }) { route(API_PREFIX) { diffRoutes(service) } } }
            }
            val scope = CoroutineScope(SupervisorJob())
            val tmux = FakeTmux()
            val productionServer = if (production) KotgentServer.production(
                sessionManager = SessionManager(tmux, store, PaneRegistry(), { _, _ -> error("No provider is launched") },
                    ProviderIdCapture(store, scope), { _, _, _ -> false }, { _, _ -> null }, setOf("shell")),
                eventStore = store, preferencesStore = FakePreferencesStore(), tokens = TokenHolder(token),
                tmux = io.kotgent.tmux.Tmux(socket = "kotgent-diff-routes-unused", tmuxPath = "/usr/bin/false"),
                webUiDir = null, diffReader = effectiveReader,
            ) else null
            val client = routeTestClient()
            try {
                assertEquals(0, CapturedProcess.run(listOf("/usr/bin/git", "init", "-b", "main", "$dir/repo"), env).exitCode)
                val port = if (productionServer != null) {
                    withContext(Dispatchers.Default) { productionServer.start() }.port()
                } else {
                    requireNotNull(server).start(wait = false)
                    server.engine.resolvedConnectors().first().port
                }
                val f = Fixture(dir, client, port, store)
                f.write("a", "first\n"); f.git("add", "."); f.git("commit", "-m", "initial")
                f.block()
            } finally {
                client.close()
                withContext(NonCancellable + Dispatchers.Default) {
                    if (productionServer != null) productionServer.stop() else server?.stop(100, 1_000)
                }
                scope.cancel()
                assertEquals(0, CapturedProcess.run(listOf("/bin/rm", "-rf", dir), env).exitCode)
            }
        }
    }

    @Test fun populatedDiskCacheStillRequiresAuthenticationAndCurrentSessionSource() = fixture(diskCache = true, production = true) {
        write("a", "second\n"); git("add", "."); git("commit", "-m", "second")
        val oid = git("rev-parse", "HEAD")
        val files = files("mode" to "commit", "commitOid" to oid)
        patch(files).data<DiffPatch>()
        val _ = files("mode" to "commit", "commitOid" to oid)
        patch(files).data<DiffPatch>()
        assertEquals(HttpStatusCode.Unauthorized, get("files", "worktreeId" to root, "mode" to "commit", "commitOid" to oid, bearer = false).status)
        assertEquals(HttpStatusCode.Forbidden, get("files", "worktreeId" to root, "mode" to "commit", "commitOid" to oid,
            bearer = false, cookie = true, origin = "https://evil.example").status)
        val original = assertNotNull(store.getSession(SessionId("diff")))
        store.upsertSession(original.copy(id = SessionId("other")))
        get("patch", "worktreeId" to root, "snapshot" to files.snapshot.id, "fileId" to files.files.single().fileId,
            session = "other").problem(HttpStatusCode.Conflict, DiffReason.snapshot_expired)
        get("files", "worktreeId" to root, "mode" to "commit", "commitOid" to oid, session = "other").data<DiffFiles>()
        get("patch", "worktreeId" to root, "snapshot" to files.snapshot.id, "fileId" to files.files.single().fileId,
            session = "other").data<DiffPatch>()
        assertEquals(0, CapturedProcess.run(listOf("/usr/bin/git", "init", "$dir/foreign"), env).exitCode)
        store.upsertSession(original.copy(cwd = "$dir/foreign"))
        patch(files).problem(HttpStatusCode.BadRequest, DiffReason.foreign_worktree)
        get("files", "worktreeId" to root, "mode" to "commit", "commitOid" to oid).problem(HttpStatusCode.BadRequest, DiffReason.foreign_worktree)
    }

    @Test fun authenticationAndOriginPrecedeReaderAccess() {
        var reads = 0
        val real = GitDiffReader()
        val reader = object : DiffReader by real {
            override suspend fun worktrees(cwd: String): DiffResponse<DiffWorktrees> { reads++; return real.worktrees(cwd) }
        }
        fixture(reader, production = true) {
            assertEquals(HttpStatusCode.Unauthorized, get("worktrees", bearer = false).status)
            assertEquals(HttpStatusCode.Forbidden, get("worktrees", bearer = false, cookie = true, origin = "https://evil.example").status)
            assertEquals(0, reads)
            get("worktrees", session = "unknown").problem(HttpStatusCode.NotFound, DiffReason.unknown_session)
            assertEquals(0, reads)
            get("worktrees", bearer = false, cookie = true, origin = "http://127.0.0.1:$port").data<DiffWorktrees>()
            assertTrue(reads > 0)
        }
    }

    @Test fun rejectsHostileQueriesAndForeignWorktreesAndRecovers() = fixture {
        assertEquals(0, CapturedProcess.run(listOf("/usr/bin/git", "init", "$dir/foreign"), env).exitCode)
        for (worktree in listOf("$dir/foreign", "$root/../foreign", "/etc", "../repo")) {
            get("files", "worktreeId" to worktree).problem(HttpStatusCode.BadRequest, DiffReason.foreign_worktree)
        }
        for (fields in listOf(
            arrayOf("cwd" to root), arrayOf("worktreeId" to root), arrayOf("mode" to "invalid"),
            arrayOf("parent" to "1"), arrayOf("scope" to "bad"), arrayOf("mode" to "commit", "commitOid" to "HEAD"),
            arrayOf("mode" to "branch", "baseRef" to "--exec=evil"), arrayOf("mode" to "commit", "commitOid" to "a".repeat(40), "parent" to "0"),
        )) get("files", "worktreeId" to root, *fields).problem(HttpStatusCode.BadRequest)
        for (cursor in listOf("{}", "not json", """{"headOid":"HEAD","offset":0}""",
            """{"headOid":"${git("rev-parse", "HEAD")}","offset":-1}""",
            """{"headOid":"${git("rev-parse", "HEAD")}","unknown":1}""")) {
            get("commits", "worktreeId" to root, "cursor" to cursor).problem(HttpStatusCode.BadRequest)
        }
        get("files", "worktreeId" to root, "mode" to "branch", "baseRef" to "refs/heads/missing").problem(HttpStatusCode.UnprocessableEntity, DiffReason.missing_ref)
        get("files", "worktreeId" to root, "mode" to "commit", "commitOid" to "a".repeat(40)).problem(HttpStatusCode.UnprocessableEntity, DiffReason.missing_object)
        get("files", "worktreeId" to root, "mode" to "commit", "commitOid" to git("rev-parse", "HEAD:a")).problem(HttpStatusCode.BadRequest)
        get("patch", "worktreeId" to root, "snapshot" to "../a", "fileId" to "../a").problem(HttpStatusCode.Conflict)
        val cursor = TRANSPORT_JSON.encodeToString(DiffCommitCursor(git("rev-parse", "HEAD"), offset = 100_001))
        get("commits", "worktreeId" to root, "cursor" to cursor).problem(HttpStatusCode.PayloadTooLarge, DiffReason.output_limit)
        get("commits", "worktreeId" to root, "cursor" to cursor, "baseRef" to "refs/heads/main").problem(HttpStatusCode.BadRequest)
        assertTrue(files().files.isEmpty())
    }

    @Test fun contentIdentityRevalidatesAndSymlinksNeverExposeTargetBytes() = fixture {
        write("a", "other\n")
        val first = files()
        val refreshed = files()
        assertEquals(first.snapshot.id, refreshed.snapshot.id)
        patch(first, "../a").problem(HttpStatusCode.BadRequest, DiffReason.unknown_file)
        patch(first).data<DiffPatch>()
        write("a", "third\n") // same status and size
        patch(first).problem(HttpStatusCode.Conflict, DiffReason.snapshot_changed)
        val second = files()
        assertNotEquals(first.snapshot.id, second.snapshot.id)
        assertTrue(patch(second).data<DiffPatch>().hunks.isNotEmpty())
        assertEquals(0, symlink("/etc/passwd", "$root/link"))
        val linked = files()
        val link = linked.files.single { it.newPath == "link" }
        assertEquals(DiffFileKind.symlink, link.kind)
        assertTrue(patch(linked, link.fileId).data<DiffPatch>().hunks.isEmpty())
        git("worktree", "add", "-b", "topic", "$dir/linked")
        val inventory = get("worktrees").data<DiffWorktrees>()
        val tree = inventory.worktrees.single { !it.current }
        get("files", "worktreeId" to tree.id).data<DiffFiles>()
        assertEquals(0, CapturedProcess.run(listOf("/bin/rm", "-rf", tree.path), env).exitCode)
        get("files", "worktreeId" to tree.id).problem(HttpStatusCode.UnprocessableEntity, DiffReason.missing_worktree)
        assertNotNull(files())
    }

    @Test fun registeredWorktreeSymlinkCannotAuthorizeAnotherRepository() = fixture {
        git("worktree", "add", "-b", "topic", "$dir/linked")
        val original = get("worktrees").data<DiffWorktrees>().worktrees.single { !it.current }
        get("files", "worktreeId" to original.id).data<DiffFiles>()
        git("clone", root, "$dir/foreign")
        assertEquals(0, CapturedProcess.run(listOf("/bin/rm", "-rf", original.path), env).exitCode)
        assertEquals(0, symlink("$dir/foreign", original.path))
        get("files", "worktreeId" to original.id).problem(HttpStatusCode.BadRequest, DiffReason.foreign_worktree)
        // The refreshed inventory now canonicalizes this registered path to the foreign clone.
        // Membership alone must not authorize it; the selected common-dir is checked as well.
        val replacement = get("worktrees").data<DiffWorktrees>().worktrees.single { !it.current }
        get("files", "worktreeId" to replacement.id).problem(HttpStatusCode.BadRequest, DiffReason.foreign_worktree)
        assertTrue(files().files.isEmpty())
    }

    @Test fun unrelatedBinaryBytesAndIndexModesInvalidateSelectedPatchOverHttp() = fixture {
        write("a", "other\n")
        write("unrelated.bin", "\u0000first")
        val first = files()
        val selected = first.files.single { it.newPath == "a" }.fileId
        patch(first, selected).data<DiffPatch>()
        assertEquals(0, CapturedProcess.run(listOf("/bin/cp", "-p", "$root/unrelated.bin", "$dir/stamp"), env).exitCode)
        write("unrelated.bin", "\u0000other")
        assertEquals(0, CapturedProcess.run(listOf("/usr/bin/touch", "-r", "$dir/stamp", "$root/unrelated.bin"), env).exitCode)
        patch(first, selected).problem(HttpStatusCode.Conflict, DiffReason.snapshot_changed)
        val refreshed = files()
        assertNotEquals(first.snapshot.id, refreshed.snapshot.id)
        patch(refreshed, refreshed.files.single { it.newPath == "a" }.fileId).data<DiffPatch>()
        git("update-index", "--chmod=+x", "a")
        patch(refreshed, refreshed.files.single { it.newPath == "a" }.fileId)
            .problem(HttpStatusCode.Conflict, DiffReason.snapshot_changed)
    }

    @Test fun movedLinkedWorktreeCannotReuseOldSnapshot() = fixture {
        git("worktree", "add", "-b", "topic", "$dir/linked")
        val original = get("worktrees").data<DiffWorktrees>().worktrees.single { !it.current }
        val old = get("files", "worktreeId" to original.id, "mode" to "commit", "commitOid" to git("rev-parse", "HEAD")).data<DiffFiles>()
        git("worktree", "move", original.path, "$dir/moved\nworktree")
        get("patch", "worktreeId" to original.id, "snapshot" to old.snapshot.id, "fileId" to old.files.first().fileId)
            .problem(HttpStatusCode.BadRequest, DiffReason.foreign_worktree)
        val moved = get("worktrees").data<DiffWorktrees>().worktrees.single { !it.current }
        get("patch", "worktreeId" to moved.id, "snapshot" to old.snapshot.id, "fileId" to old.files.first().fileId)
            .problem(HttpStatusCode.Conflict, DiffReason.snapshot_expired)
        get("files", "worktreeId" to moved.id).data<DiffFiles>()
    }

    @Test fun refsAndCommitPagesRemainPinnedAndMissingCwdHasNoFallback() = fixture {
        val old = git("rev-parse", "HEAD")
        val history = get("commits", "worktreeId" to root).data<DiffCommits>()
        val first = files("mode" to "commit", "commitOid" to old)
        write("a", "second\n"); git("add", "."); git("commit", "-m", "second")
        patch(first).data<DiffPatch>()
        val pinned = get("commits", "worktreeId" to root, "cursor" to TRANSPORT_JSON.encodeToString(history.cursor!!)).data<DiffCommits>()
        assertEquals(old, pinned.commits.first().oid)
        assertNotEquals(old, get("commits", "worktreeId" to root).data<DiffCommits>().commits.first().oid)
        git("branch", "base", old)
        val branch = files("mode" to "branch", "baseRef" to "refs/heads/base")
        git("branch", "-f", "base", "HEAD")
        patch(branch).data<DiffPatch>()
        assertTrue(files("mode" to "branch", "baseRef" to "refs/heads/base").files.isEmpty())
        store.upsertSession(assertNotNull(store.getSession(SessionId("diff"))).copy(cwd = "$dir/missing"))
        get("worktrees").problem(HttpStatusCode.UnprocessableEntity, DiffReason.missing_worktree)
        store.upsertSession(assertNotNull(store.getSession(SessionId("diff"))).copy(cwd = root))
        patch(first).problem(HttpStatusCode.Conflict, DiffReason.snapshot_expired)
        assertNotNull(files())
    }

    @Test fun patchBudgetIsExplicitAndSubsequentReadSucceeds() = fixture {
        write("large", "new line\n".repeat(DIFF_MAX_PATCH_LINES + 1))
        val list = files()
        val large = list.files.single { it.newPath == "large" }
        val patch = patch(list, large.fileId).data<DiffPatch>()
        assertNotNull(patch.limited ?: patch.file.problem)
        write("large", "small\n")
        assertTrue(patch(files()).data<DiffPatch>().hunks.isNotEmpty())
    }

    @Test fun httpDisconnectReleasesActiveReadAndQueueRejectsExcess() {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var block = true
        val real = GitDiffReader()
        val reader = object : DiffReader by real {
            override suspend fun worktrees(cwd: String): DiffResponse<DiffWorktrees> {
                if (block) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                return real.worktrees(cwd)
            }
        }
        fixture(reader, DiffServiceLimits(activeReads = 1, queuedReads = 0, deadlineMillis = 10_000)) {
            kotlinx.coroutines.coroutineScope {
                val pending = async { get("worktrees", keepAlive = true) }
                entered.await()
                get("worktrees").problem(HttpStatusCode.TooManyRequests, DiffReason.queue_full)
                pending.cancelAndJoin()
                withTimeout(3_000) { cancelled.await() }
                block = false
                get("worktrees").data<DiffWorktrees>()
            }
        }
    }
}
