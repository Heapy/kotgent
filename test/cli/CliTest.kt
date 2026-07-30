package io.kotgent.cli

import io.kotgent.core.SessionId
import io.kotgent.daemon.CLAUDE_AGENT_KIND
import io.kotgent.daemon.CODEX_AGENT_KIND
import io.kotgent.daemon.DuplicateImportException
import io.kotgent.daemon.JUNIE_AGENT_KIND
import io.kotgent.transport.AUTH_PAGE_PATH
import io.kotgent.transport.AUTH_ROTATE_PATH
import io.kotgent.transport.AUTH_TICKET_PATH
import io.kotgent.transport.ImportSessionRequest
import io.kotgent.transport.RotateResponse
import io.kotgent.transport.SessionDto
import io.kotgent.transport.StartSessionRequest
import io.kotgent.transport.TICKET_TTL_MILLIS
import io.kotgent.transport.TRANSPORT_JSON
import io.kotgent.transport.TicketResponse
import io.kotgent.transport.normalizeTicketCode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CLI tests (plan Task 15): the pure `argv → CliCommand` parser, [ApiClient] against an embedded stub
 * Ktor CIO server (list renders, start posts agent+cwd + surfaces the id, a control verb hits the right
 * path, a missing token fails fast), and the [AttachClient] smoke surface (URL construction, resize
 * frame encode/decode, raw-mode save/restore over an injected fake tty — never the real tty). Every
 * server-touching body is bounded by [withTimeout] (anti-hang).
 */
class CliTest {

    // ---- 1. arg / subcommand parsing (pure) -----------------------------------------------------

    @Test
    fun parsesVersionAndHelpAndNoArgs() {
        assertEquals(CliCommand.Version, parseArgs(listOf("--version")))
        assertEquals(CliCommand.Version, parseArgs(listOf("version")))
        assertEquals(CliCommand.Help, parseArgs(listOf("--help")))
        assertEquals(CliCommand.Help, parseArgs(emptyList()), "no args → usage help")
    }

    @Test
    fun parsesListAndControlVerbs() {
        assertEquals(CliCommand.ListSessions, parseArgs(listOf("list")))
        assertEquals(CliCommand.ListSessions, parseArgs(listOf("ls")))
        assertEquals(CliCommand.Stop("abc"), parseArgs(listOf("stop", "abc")))
        assertEquals(CliCommand.Resume("abc"), parseArgs(listOf("resume", "abc")))
        assertEquals(CliCommand.Interrupt("abc"), parseArgs(listOf("interrupt", "abc")))
        assertEquals(CliCommand.Attach("abc"), parseArgs(listOf("attach", "abc")))
    }

    @Test
    fun controlVerbsWithoutAnIdAreInvalid() {
        assertTrue(parseArgs(listOf("stop")) is CliCommand.Invalid)
        assertTrue(parseArgs(listOf("attach")) is CliCommand.Invalid)
    }

    @Test
    fun parsesStartWithAgentCwdNameAndTags() {
        assertEquals(
            CliCommand.Start("claude", "/tmp/p", "my-name", listOf("a", "b")),
            parseArgs(listOf("start", "claude", "/tmp/p", "--name", "my-name", "--tag", "a", "--tag", "b")),
        )
        // cwd omitted → null (resolved to the current dir at run time, not during the pure parse).
        assertEquals(CliCommand.Start("claude", null, null, emptyList()), parseArgs(listOf("start", "claude")))
        // The parser does not know the agent kinds — the daemon's builders map is the one gate — so every
        // supported kind parses the same way.
        assertEquals(CliCommand.Start("junie", null, null, emptyList()), parseArgs(listOf("start", "junie")))
    }

    @Test
    fun theUsageNamesEverySupportedAgentKind() {
        // The kinds are gated by the daemon's builders map, but this text is where an operator learns
        // they exist — a provider that ships without appearing here is invisible from the CLI.
        for (agent in listOf(CLAUDE_AGENT_KIND, CODEX_AGENT_KIND, JUNIE_AGENT_KIND)) {
            assertTrue("'$agent'" in USAGE, "the usage names the $agent agent: $USAGE")
        }
    }

    @Test
    fun startWithoutAnAgentIsInvalid() {
        assertTrue(parseArgs(listOf("start")) is CliCommand.Invalid)
    }

    @Test
    fun parsesImportWithAgentIdAndFlags() {
        assertEquals(
            CliCommand.Import("claude", PROVIDER_ID, cwd = null, name = null, tags = emptyList(), noStart = false),
            parseArgs(listOf("import", "claude", PROVIDER_ID)),
        )
        assertEquals(
            CliCommand.Import("codex", PROVIDER_ID, "/tmp/p", "my-name", listOf("a", "b"), noStart = true),
            parseArgs(
                listOf(
                    "import", "codex", PROVIDER_ID,
                    "--cwd", "/tmp/p", "--name", "my-name", "--tag", "a", "--tag", "b", "--no-start",
                ),
            ),
        )
    }

    @Test
    fun importMissingArgumentsOrUnknownFlagsAreInvalid() {
        assertTrue(parseArgs(listOf("import")) is CliCommand.Invalid, "import needs an agent")
        assertTrue(parseArgs(listOf("import", "claude")) is CliCommand.Invalid, "import needs the provider session id")
        assertTrue(parseArgs(listOf("import", "claude", PROVIDER_ID, "--bogus")) is CliCommand.Invalid, "unknown flag")
        assertTrue(parseArgs(listOf("import", "claude", PROVIDER_ID, "--cwd")) is CliCommand.Invalid, "--cwd needs a directory")
    }

    @Test
    fun importRejectsASurplusPositionalInsteadOfSilentlyDiscardingIt() {
        // `start <agent> [cwd]` trains `import claude <id> ~/proj`; silently dropping the path would
        // fall back to discovery and could register a DIFFERENT cwd than the one the operator typed.
        val result = parseArgs(listOf("import", "claude", PROVIDER_ID, "/tmp/project"))
        val invalid = assertIs<CliCommand.Invalid>(result, "a third positional is a usage error, not a discard")
        assertTrue(invalid.message.contains("--cwd"), "the error points at the flag form: ${invalid.message}")
    }

    @Test
    fun importValueFlagsRequireARealValueAndNeverSwallowAFlag() {
        // `--name --no-start` used to name the session "--no-start" AND silently drop the --no-start
        // semantics — the session was auto-resumed against the operator's stated intent. A missing or
        // `--`-prefixed value is a usage error for every import value flag (stricter than parseStart
        // on purpose: only import has a behavior-changing boolean flag that could be swallowed).
        assertTrue(
            parseArgs(listOf("import", "codex", PROVIDER_ID, "--name", "--no-start")) is CliCommand.Invalid,
            "--name must not swallow --no-start",
        )
        assertTrue(
            parseArgs(listOf("import", "codex", PROVIDER_ID, "--name")) is CliCommand.Invalid,
            "--name needs a value",
        )
        assertTrue(
            parseArgs(listOf("import", "codex", PROVIDER_ID, "--tag", "--no-start")) is CliCommand.Invalid,
            "--tag must not swallow --no-start",
        )
        assertTrue(
            parseArgs(listOf("import", "codex", PROVIDER_ID, "--tag")) is CliCommand.Invalid,
            "--tag needs a value",
        )
    }

    @Test
    fun importRejectsAnEmptyFlagValueInsteadOfResolvingItToTheCliCwd() {
        // `--cwd "$UNSET_VAR"` expands to an EMPTY string, which resolveCwdAgainst would silently turn
        // into the CLI's own cwd — sent as an EXPLICIT override of the daemon's transcript discovery.
        // The codex probe ignores cwd, so the session would be registered (and resumed) under the wrong
        // project with nothing downstream to catch it. Blank values are usage errors for every import
        // value flag.
        assertTrue(
            parseArgs(listOf("import", "codex", PROVIDER_ID, "--cwd", "")) is CliCommand.Invalid,
            "an empty --cwd must not silently become the CLI's cwd",
        )
        assertTrue(
            parseArgs(listOf("import", "codex", PROVIDER_ID, "--cwd", "  ")) is CliCommand.Invalid,
            "a whitespace-only --cwd is no more a directory than an empty one",
        )
        assertTrue(
            parseArgs(listOf("import", "codex", PROVIDER_ID, "--name", "")) is CliCommand.Invalid,
            "an empty --name is a usage error, not a session named ''",
        )
        assertTrue(
            parseArgs(listOf("import", "codex", PROVIDER_ID, "--tag", "")) is CliCommand.Invalid,
            "an empty --tag is a usage error, not an empty tag",
        )
    }

    @Test
    fun importResolvesAnExplicitCwdLikeStartButAnAbsentOneStaysAbsent() {
        // `--cwd` goes through the same resolution rule as `runStart` (relative → anchored at the CLI's
        // own cwd; absolute → untouched). The ONE deliberate difference: an absent `--cwd` stays null —
        // the daemon then discovers the project directory from the provider's on-disk store, and
        // defaulting it to the CLI's cwd would silently defeat that discovery.
        val seen = mutableListOf<String?>()
        assertEquals(0, runImportResolving(importCommand(cwd = null), "/base") { seen += it; 0 })
        assertEquals(0, runImportResolving(importCommand(cwd = "sub"), "/base") { seen += it; 0 })
        assertEquals(0, runImportResolving(importCommand(cwd = "/abs"), ".") { seen += it; 0 })
        assertEquals(listOf(null, "/base/sub", "/abs"), seen)
    }

    @Test
    fun importWithAnUnresolvableCwdExitsTwoWithoutRunningTheCommand() {
        // Same contract as runStart: a relative --cwd with no absolute base must fail LOUDLY (exit 2)
        // instead of sending the daemon (cwd `/` under launchd) a relative path it would mis-resolve.
        var ran = false
        val exit = runImportResolving(importCommand(cwd = "sub"), base = ".") { ran = true; 0 }
        assertEquals(2, exit, "UnresolvableCwdException → usage exit code 2, like runStart")
        assertFalse(ran, "the command must not run when the cwd cannot be resolved")
    }

    @Test
    fun resolveCwdAgainstMakesRelativePathsAbsoluteAgainstTheCliCwd() {
        val base = "/Users/me/project"
        // The daemon runs with cwd `/`, so the CLI must send an ABSOLUTE cwd; a relative one is anchored
        // at the CLI's own cwd here.
        assertEquals(base, resolveCwdAgainst(base, null), "omitted cwd → the CLI's own cwd")
        assertEquals(base, resolveCwdAgainst(base, "."), "'.' → the CLI's own cwd")
        assertEquals("$base/sub", resolveCwdAgainst(base, "sub"), "a relative cwd is anchored at the CLI cwd")
        assertEquals("$base/sub", resolveCwdAgainst(base, "./sub"), "a leading ./ is stripped")
        assertEquals("/abs/elsewhere", resolveCwdAgainst(base, "/abs/elsewhere"), "an absolute cwd passes through")
        assertEquals("/Users/me/project/x", resolveCwdAgainst("/Users/me/project/", "x"), "a trailing slash on base is handled")
        // "./" strips to an empty relative part — it must still resolve to the base, not to "".
        assertEquals(base, resolveCwdAgainst(base, "./"), "'./' → the CLI's own cwd")
        assertEquals(base, resolveCwdAgainst(base, "././"), "repeated './' segments collapse to the base")
    }

    @Test
    fun resolveCwdAgainstRootAlwaysYieldsAnAbsolutePath() {
        // Root is the degenerate base: it TRIMS to the empty string, so a naive join produced "" — not a
        // path at all, which tmux would reject or resolve against its own cwd. Every combination below
        // must stay absolute.
        assertEquals("/", resolveCwdAgainst("/", null), "root base, omitted cwd")
        assertEquals("/", resolveCwdAgainst("/", ""), "root base, empty cwd")
        assertEquals("/", resolveCwdAgainst("/", "."), "root base, '.'")
        assertEquals("/", resolveCwdAgainst("/", "./"), "root base, './' — the regression: this returned \"\"")
        assertEquals("/sub", resolveCwdAgainst("/", "sub"), "root base, a relative child — exactly one slash")
        assertEquals("/sub", resolveCwdAgainst("/", "./sub"), "root base, './sub'")
        assertEquals("/..", resolveCwdAgainst("/", ".."), "root base, '..' rides through for the kernel to clamp")
        assertEquals("/..", resolveCwdAgainst("/", "./.."), "root base, './..' — the '.' collapses, the '..' stays")
        assertEquals("/a/..", resolveCwdAgainst("/", "a/.."), "root base, an embedded '..' is preserved")
        // Every result is absolute, whatever the relative part ('..' may legitimately escape the base).
        for (rel in listOf(null, "", ".", "./", "././", "..", "./..", "sub", "./sub", "a/b/..")) {
            assertTrue(resolveCwdAgainst("/", rel).startsWith("/"), "resolveCwdAgainst(\"/\", $rel) must be absolute")
            assertTrue(resolveCwdAgainst("/base", rel).startsWith("/"), "resolveCwdAgainst(\"/base\", $rel) must be absolute")
        }
    }

    @Test
    fun resolveCwdAgainstCollapsesDotSegmentsButLeavesDotDotForTheFilesystem() {
        // `.`/duplicate/trailing slashes are pure SPELLING — collapsing them can never change which
        // directory is named. `..` is not: it crosses a directory boundary, and a lexical collapse
        // resolves it against the path's spelling while the kernel resolves it against the real
        // (symlink-traversed) tree — on macOS lexical `/tmp/../Users` says `/Users`, but /tmp is a
        // symlink into /private, so the filesystem's answer is `/private/Users`. So `..` passes through
        // untouched: `start` hands it to tmux (`new-session -c` resolves it in the kernel), and
        // `import`'s daemon canonicalizes with realpath(3) before probing/storing (SessionImportTest).
        val base = "/Users/me/project"
        assertEquals("$base/a/b", resolveCwdAgainst(base, "a/./b"), "'.' segments collapse")
        assertEquals("/abs/x", resolveCwdAgainst(base, "/abs/x/"), "a trailing slash is dropped")
        assertEquals("/abs/x", resolveCwdAgainst(base, "/abs//x"), "a doubled slash is collapsed")
        assertEquals("$base/..", resolveCwdAgainst(base, ".."), "'..' is preserved, not collapsed")
        assertEquals("$base/../other", resolveCwdAgainst(base, "../other"), "'..' rides through to the filesystem")
        assertEquals("$base/sub/..", resolveCwdAgainst(base, "sub/.."), "an embedded '..' is preserved too")
        assertEquals(
            "/tmp/../Users",
            resolveCwdAgainst(base, "/tmp/../Users"),
            "the symlink-hazard pin: lexically this is /Users, but the real path is /private/Users — " +
                "only code with the filesystem in hand may decide",
        )
    }

    @Test
    fun resolveCwdAgainstRefusesABaseThatIsNotItselfAbsolute() {
        // `currentWorkingDir()` can legitimately fail to name the cwd (getcwd errors, no usable $PWD) and
        // falls back to ".". Joining onto that produced "./sub" — still relative, so the daemon (cwd `/`
        // under launchd) would resolve it against root: the exact bug this function exists to prevent.
        // An empty base was worse: it was read as ROOT and silently launched the agent in `/`.
        for (base in listOf(".", "", "relative/base", "./rel", "..")) {
            assertFailsWith<UnresolvableCwdException>("base '$base' must not silently produce a relative path") {
                resolveCwdAgainst(base, "sub")
            }
            assertFailsWith<UnresolvableCwdException>("base '$base' with an omitted cwd must fail too") {
                resolveCwdAgainst(base, null)
            }
            assertFailsWith<UnresolvableCwdException>("base '$base' with '.' must fail too") {
                resolveCwdAgainst(base, ".")
            }
            // An ABSOLUTE cwd needs no base at all, so it still resolves even when the cwd is unknown.
            assertEquals("/abs/elsewhere", resolveCwdAgainst(base, "/abs/elsewhere"))
        }
    }

    @Test
    fun currentWorkingDirIsAbsoluteOrTheExplicitlyUnusableFallback() {
        // Either a real absolute cwd, or "." — a deliberate non-path that makes resolveCwdAgainst fail
        // loudly rather than quietly emitting another relative path.
        val cwd = currentWorkingDir()
        assertTrue(cwd.startsWith("/") || cwd == ".", "unexpected working directory: '$cwd'")
        if (cwd.startsWith("/")) {
            assertTrue(resolveCwdAgainst(cwd, "sub").startsWith("/"), "a real cwd resolves to an absolute path")
        }
    }

    @Test
    fun parsesDaemonPortAndUnknownCommand() {
        assertEquals(CliCommand.Daemon(DEFAULT_PORT), parseArgs(listOf("daemon")))
        assertEquals(CliCommand.Daemon(9999), parseArgs(listOf("daemon", "--port", "9999")))
        assertTrue(parseArgs(listOf("frobnicate")) is CliCommand.Invalid)
    }

    @Test
    fun parsesWebWithAndWithoutPrint() {
        assertEquals(CliCommand.Web(print = false), parseArgs(listOf("web")))
        assertEquals(CliCommand.Web(print = true), parseArgs(listOf("web", "--print")))
        assertTrue(parseArgs(listOf("web", "bogus")) is CliCommand.Invalid, "an unexpected arg is a usage error")
    }

    @Test
    fun parsesTokenRotateAndRejectsABareToken() {
        assertEquals(CliCommand.TokenRotate, parseArgs(listOf("token", "rotate")))
        // A bare `token` is deliberately NOT `cat ~/.kotgent/token` — it has no default action.
        assertTrue(parseArgs(listOf("token")) is CliCommand.Invalid, "bare `token` needs a subcommand")
        assertTrue(parseArgs(listOf("token", "wat")) is CliCommand.Invalid, "an unknown subcommand is invalid")
    }

    @Test
    fun parsesConfigGetAndSet() {
        assertEquals(CliCommand.ConfigGet, parseArgs(listOf("config", "get")))
        assertEquals(
            CliCommand.ConfigSet("public-url", "https://kotgent.heapyhop.com"),
            parseArgs(listOf("config", "set", "public-url", "https://kotgent.heapyhop.com")),
        )
        assertTrue(parseArgs(listOf("config", "set", "public-url")) is CliCommand.Invalid, "set without a value")
        assertTrue(parseArgs(listOf("config", "set")) is CliCommand.Invalid, "set without a key")
        assertTrue(parseArgs(listOf("config")) is CliCommand.Invalid, "config without a subcommand")
    }

    // ---- 2. ApiClient against an embedded stub daemon -------------------------------------------

    @Test
    fun listSessionsReturnsDaemonSessionsAndSendsBearer() = withStub { stub, api ->
        val list = api.listSessions()
        assertEquals(2, list.size)
        assertEquals(listOf("aaa11111", "bbb22222"), list.map { it.id })

        val req = stub.requests.receive()
        assertEquals("GET", req.method)
        assertEquals("/sessions", req.path)
        assertEquals("Bearer secret", req.auth, "the token is sent as a Bearer header")
    }

    @Test
    fun startSessionPostsAgentAndCwdAndSurfacesTheId() = withStub { stub, api ->
        val created = api.startSession("claude", "/tmp/project")
        assertEquals("newsess1", created.id, "the created session's id is surfaced")

        val req = stub.requests.receive()
        assertEquals("POST", req.method)
        assertEquals("/sessions", req.path)
        val decoded = TRANSPORT_JSON.decodeFromString(StartSessionRequest.serializer(), req.body)
        assertEquals("claude", decoded.agent, "agent is posted")
        assertEquals("/tmp/project", decoded.cwd, "cwd is posted")
    }

    @Test
    fun importSessionPostsTheImportBodyAndSurfacesTheCreatedSession() = withStub { stub, api ->
        val created = api.importSession("codex", PROVIDER_ID, cwd = "/tmp/p")
        assertEquals("newsess1", created.id, "the registered session's id is surfaced")

        val req = stub.requests.receive()
        assertEquals("POST", req.method)
        assertEquals("/sessions/import", req.path)
        assertEquals("Bearer secret", req.auth, "import is Bearer-authenticated like every control call")
        val decoded = TRANSPORT_JSON.decodeFromString(ImportSessionRequest.serializer(), req.body)
        assertEquals("codex", decoded.agent, "agent is posted")
        assertEquals(PROVIDER_ID, decoded.providerSessionId, "the provider session id is posted as a String")
        assertEquals("/tmp/p", decoded.cwd, "an explicit cwd rides along")
    }

    @Test
    fun importSessionOmittedCwdStaysAbsentForDaemonDiscovery() = withStub { stub, api ->
        api.importSession("claude", PROVIDER_ID)
        val req = stub.requests.receive()
        val decoded = TRANSPORT_JSON.decodeFromString(ImportSessionRequest.serializer(), req.body)
        assertNull(decoded.cwd, "no cwd in the body → the daemon's VendorSessionLocator discovers it")
    }

    @Test
    fun aControlVerbHitsTheRightPath() = withStub { stub, api ->
        api.stop("abc123")
        val req = stub.requests.receive()
        assertEquals("POST", req.method)
        assertEquals("/sessions/abc123/stop", req.path)
        assertEquals("Bearer secret", req.auth)
    }

    @Test
    fun issueTicketPostsToAuthTicketUnderBearerAndReturnsTheUrls() = withStub { stub, api ->
        val ticket = api.issueTicket()
        assertEquals("http://127.0.0.1:27508/auth#ticket=deadbeef", ticket.localUrl, "the local URL is surfaced")
        assertNull(ticket.publicUrl, "no public URL when the daemon is loopback-only")

        val req = stub.requests.receive()
        assertEquals("POST", req.method)
        assertEquals(AUTH_TICKET_PATH, req.path)
        assertEquals("Bearer secret", req.auth, "ticket issuance is Bearer-authenticated")
    }

    @Test
    fun rotateTokenPostsToAuthRotateAndReturnsTheNewToken() = withStub { stub, api ->
        val token = api.rotateToken()
        assertEquals("newmastertoken", token, "the freshly minted token is returned for the CLI to print")

        val req = stub.requests.receive()
        assertEquals("POST", req.method)
        assertEquals(AUTH_ROTATE_PATH, req.path)
        assertEquals("Bearer secret", req.auth)
    }

    @Test
    fun missingTokenFailsFastBeforeAnyNetworkCall() = withStub(token = null) { stub, api ->
        assertFailsWith<MissingTokenException> { api.listSessions() }
        assertNull(stub.requests.tryReceive().getOrNull(), "no request was ever sent")
    }

    // ---- 3. list rendering (pure) ---------------------------------------------------------------

    @Test
    fun renderSessionsShowsIdsAndFlagsAttention() {
        val out = renderSessions(
            listOf(
                sampleDto("aaa11111", "running", needsAttention = false),
                sampleDto("bbb22222", "needs_approval", needsAttention = true),
            ),
        )
        assertTrue("aaa11111" in out && "bbb22222" in out, "both ids render")
        assertTrue("needs_approval" in out, "state renders")
        assertTrue("*" in out, "a needs-attention session is flagged")
        assertEquals("no sessions\n", renderSessions(emptyList()))
    }

    @Test
    fun theWebOutputCarriesTheCodeAndAHintAboutTheInstalledApp() {
        // `kotgent web` printing only a URL is a dead end for the one client that needs it most: an installed
        // home-screen app opens at its own start_url with its own cookie jar and cannot be handed a fragment.
        // So the block must show the CODE, and say what it is for — otherwise nobody knows to type it.
        val code = "A1B2C3D4"
        val out = renderSignInCode(
            TicketResponse(
                ticket = code,
                localUrl = "http://127.0.0.1:27508$AUTH_PAGE_PATH#ticket=$code",
                publicUrl = null,
                expiresAt = 1_753_280_000_000L + TICKET_TTL_MILLIS,
            ),
        )
        assertTrue("A1B2 C3D4" in out, "the code is printed, grouped for reading: $out")
        assertTrue("home screen" in out, "with the reason an installed app needs it")
        assertTrue("${TICKET_TTL_MILLIS / 60_000} minutes" in out, "and its life, derived from the TTL")

        // The printed (grouped) form is what someone retypes, so it must survive the daemon's own
        // normalisation back to the exact value that was minted — the display cannot break the redemption.
        assertEquals(code, normalizeTicketCode("A1B2 C3D4"), "the grouped form redeems as the minted code")
    }

    @Test
    fun groupingRequiresTheTicketStoresFixedWidth() {
        assertEquals("A1B2 C3D4", groupLoginCode("A1B2C3D4"))
        assertFailsWith<IllegalArgumentException> { groupLoginCode("ABC") }
        assertFailsWith<IllegalArgumentException> { groupLoginCode("") }
    }

    @Test
    fun webPrintKeepsStdoutPipeableAndDoesNotOpenAnything() = runBlocking {
        val ticket = webTicket()
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val opened = mutableListOf<String>()

        val exit = runWebCommand(
            print = true,
            issueTicket = { ticket },
            open = { opened += it; 0 },
            stdout = stdout::add,
            stderr = stderr::add,
        )

        assertEquals(0, exit)
        assertEquals(listOf(ticket.localUrl), stdout, "stdout remains exactly the credentialed URL for piping")
        assertTrue(stderr.single().contains("A1B2 C3D4"), "the human-readable code stays on stderr")
        assertTrue(opened.isEmpty(), "--print never launches a browser")
    }

    @Test
    fun webOpenLeavesTheCodeUnspentForTheBrowserFormOrInstalledApp() = runBlocking {
        val ticket = webTicket()
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val opened = mutableListOf<String>()

        val exit = runWebCommand(
            print = false,
            issueTicket = { ticket },
            open = { opened += it; 0 },
            stdout = stdout::add,
            stderr = stderr::add,
        )

        assertEquals(0, exit)
        assertEquals(listOf("http://127.0.0.1:27508/auth"), opened, "normal mode opens no ticket fragment")
        assertTrue(stdout.any { "opening" in it })
        assertTrue(stdout.any { "A1B2 C3D4" in it }, "the still-valid code is printed")
        assertTrue(stderr.isEmpty())
    }

    @Test
    fun webOpenFailurePrintsTheCredentialFreeFallbackAndCode() = runBlocking {
        val ticket = webTicket()
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()

        val exit = runWebCommand(
            print = false,
            issueTicket = { ticket },
            open = { 7 },
            stdout = stdout::add,
            stderr = stderr::add,
        )

        assertEquals(0, exit)
        assertEquals("http://127.0.0.1:27508/auth", stdout.first(), "fallback cannot consume the code")
        assertTrue(stdout.last().contains("A1B2 C3D4"))
        assertTrue(stderr.single().contains("open exited 7"))
    }

    // ---- 3b. the import command (pure seams, no daemon) -----------------------------------------

    @Test
    fun importCommandRegistersThenResumesByDefault() = runBlocking {
        val stdout = mutableListOf<String>()
        val resumedIds = mutableListOf<String>()
        val exit = runImportCommand(
            noStart = false,
            importSession = { sampleDto("imp00001", "resumable", needsAttention = false) },
            resume = { id -> resumedIds += id; sampleDto(id, "running", needsAttention = false) },
            stdout = stdout::add,
            stderr = { error("no stderr expected: $it") },
        )
        assertEquals(0, exit)
        assertTrue(stdout.first().contains("imported imp00001"), "the created session id is printed: $stdout")
        assertEquals(listOf("imp00001"), resumedIds, "the freshly imported session is resumed by default")
        assertTrue(stdout.any { "running" in it }, "the post-resume state is reported: $stdout")
    }

    @Test
    fun importCommandNoStartOnlyRegisters() = runBlocking {
        val stdout = mutableListOf<String>()
        val exit = runImportCommand(
            noStart = true,
            importSession = { sampleDto("imp00001", "resumable", needsAttention = false) },
            resume = { error("resume must not be called under --no-start") },
            stdout = stdout::add,
            stderr = { error("no stderr expected: $it") },
        )
        assertEquals(0, exit)
        assertTrue(stdout.first().contains("imp00001"), "the registered session id is printed: $stdout")
        assertTrue(stdout.any { "kotgent resume imp00001" in it }, "how to start it later is printed: $stdout")
    }

    @Test
    fun importCommandPrintsTheExistingIdAndAResumeHintOnConflict() = runBlocking {
        val stderr = mutableListOf<String>()
        val exit = runImportCommand(
            noStart = false,
            // The stub body is BUILT from the real exception the route flattens (behind its
            // "cannot import session: " prefix), so a reworded DuplicateImportException fails this
            // test instead of silently degrading the hint — the CLI half of the
            // DUPLICATE_IMPORT_ID_IN_BODY contract (TransportTest pins the server half).
            importSession = {
                throw ApiException(
                    409,
                    "cannot import session: " +
                        DuplicateImportException(SessionId("abc12345"), archived = false).message,
                )
            },
            resume = { error("must not resume on a conflict") },
            stdout = { error("no stdout expected: $it") },
            stderr = stderr::add,
        )
        assertEquals(1, exit)
        assertTrue(stderr.any { "abc12345" in it }, "the existing session id is printed: $stderr")
        assertTrue(stderr.any { "kotgent resume abc12345" in it }, "the hint names the exact resume command: $stderr")
    }

    @Test
    fun importCommandPointsAnArchivedDuplicateAtRestore() = runBlocking {
        val stderr = mutableListOf<String>()
        val exit = runImportCommand(
            noStart = false,
            // Built from the real exception — see importCommandPrintsTheExistingIdAndAResumeHintOnConflict.
            importSession = {
                throw ApiException(
                    409,
                    "cannot import session: " +
                        DuplicateImportException(SessionId("abc12345"), archived = true).message,
                )
            },
            resume = { error("must not resume on a conflict") },
            stdout = { error("no stdout expected: $it") },
            stderr = stderr::add,
        )
        assertEquals(1, exit)
        assertTrue(stderr.any { "abc12345" in it }, "the existing session id is printed: $stderr")
        assertTrue(stderr.last().contains("Restore"), "the hint points at Restore: $stderr")
        assertFalse(stderr.any { "kotgent resume" in it }, "an archived duplicate must not be pointed at a bare resume")
    }

    @Test
    fun importCommandLetsAFailedFollowUpResumePropagateAfterReportingTheImport() = runBlocking {
        // The single most likely real-world failure: the import registered fine, but the daemon's
        // resume fails (e.g. the agent binary does not resolve on launchd's PATH). The registration
        // must already be reported — the operator has to know the row exists — and the ApiException
        // must propagate untouched to Commands' generic withApi handler, whose rendering carries the
        // daemon's `kotgent install` hint.
        val stdout = mutableListOf<String>()
        val ex = assertFailsWith<ApiException> {
            runImportCommand(
                noStart = false,
                importSession = { sampleDto("imp00001", "resumable", needsAttention = false) },
                resume = {
                    throw ApiException(
                        400,
                        "cannot resume session: agent binary 'claude' not found on the daemon's PATH — run `kotgent install`",
                    )
                },
                stdout = stdout::add,
                stderr = { error("the resume failure must propagate, not be swallowed here: $it") },
            )
        }
        assertTrue(ex.body.contains("kotgent install"), "the daemon's hint rides the propagated exception")
        assertTrue(
            stdout.any { "imported imp00001" in it },
            "the successful registration was reported before the resume failed: $stdout",
        )
    }

    @Test
    fun importCommandReportsAResumeThatReturnedNoState() = runBlocking {
        val stdout = mutableListOf<String>()
        val exit = runImportCommand(
            noStart = false,
            importSession = { sampleDto("imp00001", "resumable", needsAttention = false) },
            resume = { null },
            stdout = stdout::add,
            stderr = { error("no stderr expected: $it") },
        )
        assertEquals(0, exit)
        assertTrue(
            stdout.last().contains("resumed imp00001"),
            "a resume with no returned state still reports the id: $stdout",
        )
    }

    @Test
    fun importCommandPrintsTheServerMessageOnBadRequest() = runBlocking {
        val stderr = mutableListOf<String>()
        var resumeCalled = false
        val exit = runImportCommand(
            noStart = false,
            importSession = {
                throw ApiException(400, "cannot import session: unknown agent kind 'gemini' (supported: claude, codex)")
            },
            resume = { resumeCalled = true; null },
            stdout = { error("no stdout expected: $it") },
            stderr = stderr::add,
        )
        assertEquals(1, exit)
        assertTrue(stderr.single().contains("unknown agent kind 'gemini'"), "the server's message is surfaced: $stderr")
        assertFalse(resumeCalled, "a failed import must not resume anything")
    }

    // ---- 4. AttachClient smoke (no real tty, no socket) -----------------------------------------

    @Test
    fun terminalWsUrlIsBuiltFromTheHttpOrigin() {
        // No token in the URL any more (Task 9): AttachClient presents it as an Authorization header on
        // the handshake, so the WS URL is a plain same-origin path with the scheme upgraded to ws(s).
        assertEquals(
            "ws://127.0.0.1:27508/sessions/sess1/terminal",
            terminalWsUrl("http://127.0.0.1:27508", "sess1"),
        )
        assertEquals(
            "wss://host:8443/sessions/s/terminal",
            terminalWsUrl("https://host:8443/", "s"),
        )
        // A known geometry rides in the query so the daemon can open the upstream attach at OUR size
        // instead of the pty default; a bogus one is omitted rather than sent as `?cols=0`.
        assertEquals(
            "ws://127.0.0.1:27508/sessions/sess1/terminal?cols=143&rows=53",
            terminalWsUrl("http://127.0.0.1:27508", "sess1", WinSize(143, 53)),
        )
        assertEquals(
            "ws://h/sessions/s/terminal",
            terminalWsUrl("http://h", "s", WinSize(0, 24)),
        )
    }

    @Test
    fun resizeFrameEncodesTheServersResizeControlShape() {
        val frame = resizeFrame(120, 40)
        assertEquals("""{"type":"resize","cols":120,"rows":40}""", frame)
        // …and decodes to the expected fields (the server's terminalWs parses exactly this shape).
        val obj = TRANSPORT_JSON.parseToJsonElement(frame).jsonObject
        assertEquals("resize", obj.getValue("type").jsonPrimitive.content)
        assertEquals(120, obj.getValue("cols").jsonPrimitive.int)
        assertEquals(40, obj.getValue("rows").jsonPrimitive.int)
    }

    /**
     * `attach` must hand the terminal back the way it found it. `LocalTty.restore()` only covers
     * termios; the terminal modes (mouse reporting, bracketed paste, theme reporting, alternate screen,
     * cursor and application keypad) were turned on by bytes the *remote* side sent, and their disable
     * sequences are written into the upstream pty only after the last subscriber has gone — so they
     * never reach the operator. Hence an explicit reset written to stdout on exit; this pins its
     * contents AND order.
     *
     * Measured against a real pty attach (tmux 3.7b): the client unconditionally turns on `1049`,
     * `2004`, `2031`, `25`, and terminfo's `smkx` (`?1h` + `ESC =`); it turns on
     * `1006`+`1000`+`1002` because kotgent forces `mouse on`; and it additionally forwards `1003`
     * whenever the pane's own app asks for any-motion tracking
     * (crossterm/ratatui's `EnableMouseCapture`, i.e. the codex TUI). **Every tracker must go off
     * BEFORE the SGR encoding** — on terminals that model the three trackers as independent flags,
     * clearing `1006` first downgrades a surviving tracker to the legacy X10 encoding and sprays
     * bytes above 127 into the operator's shell, which is worse than not resetting at all.
     *
     * The name is scoped to what the constant really covers: DECCKM (`?1`, the cursor-key half of
     * `smkx`) and cursor blink (`?12`) are deliberately left alone, while application keypad
     * (`ESC =`, the other `smkx` half) is reset with `ESC >`; see [TERMINAL_MODE_RESET]'s KDoc.
     */
    @Test
    fun theTerminalModeResetDisablesMousePasteThemeAltScreenAndApplicationKeypadModes() {
        val esc = "\u001b"
        assertEquals(
            listOf(
                "$esc[?1003l", "$esc[?1002l", "$esc[?1000l", "$esc[?1006l",
                "$esc[?2004l", "$esc[?2031l", "$esc[?1049l", "$esc[?25h", "$esc>",
            ).joinToString(""),
            TERMINAL_MODE_RESET,
            "all three mouse trackers off, then the SGR encoding, bracketed paste, theme reporting, " +
                "the alternate screen and application keypad; cursor shown",
        )
        assertTrue(
            TERMINAL_MODE_RESET.indexOf("$esc[?1003l") < TERMINAL_MODE_RESET.indexOf("$esc[?1006l"),
            "the any-motion tracker must be disabled before the SGR encoding, or a survivor falls " +
                "back to the X10 encoding",
        )
    }

    @Test
    fun withRawModeSavesThenRestoresOnSuccess() {
        val tty = FakeTty()
        val result = tty.withRawMode { "ok" }
        assertEquals("ok", result)
        assertEquals(listOf("enter", "restore"), tty.events, "raw entered then restored, in order")
    }

    @Test
    fun withRawModeRestoresEvenWhenTheBodyThrows() {
        val tty = FakeTty()
        assertFailsWith<IllegalStateException> { tty.withRawMode { error("boom") } }
        assertEquals(listOf("enter", "restore"), tty.events, "the tty is restored on the failure path too")
    }

    // --- harness ---------------------------------------------------------------------------------

    /** One recorded request the stub daemon saw. */
    private data class Recorded(val method: String, val path: String, val auth: String?, val body: String)

    /** A minimal embedded stub of the daemon's control REST that records what the [ApiClient] sends. */
    private inner class Stub {
        val requests = Channel<Recorded>(Channel.UNLIMITED)
        private val cannedList = listOf(
            sampleDto("aaa11111", "running", needsAttention = false),
            sampleDto("bbb22222", "needs_approval", needsAttention = true),
        )
        private val cannedStart = sampleDto("newsess1", "running", needsAttention = false)
        private val cannedTicket = TicketResponse(
            ticket = "deadbeef",
            localUrl = "http://127.0.0.1:27508/auth#ticket=deadbeef",
            publicUrl = null,
            expiresAt = 42,
        )

        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/sessions") {
                    record("GET", "")
                    call.respondText(
                        TRANSPORT_JSON.encodeToString(ListSerializer(SessionDto.serializer()), cannedList),
                        ContentType.Application.Json,
                    )
                }
                post("/sessions") {
                    val body = call.receiveText()
                    record("POST", body)
                    call.respondText(
                        TRANSPORT_JSON.encodeToString(SessionDto.serializer(), cannedStart),
                        ContentType.Application.Json,
                        HttpStatusCode.Created,
                    )
                }
                post("/sessions/import") {
                    val body = call.receiveText()
                    record("POST", body)
                    call.respondText(
                        TRANSPORT_JSON.encodeToString(SessionDto.serializer(), cannedStart),
                        ContentType.Application.Json,
                        HttpStatusCode.Created,
                    )
                }
                post("/sessions/{id}/{action}") {
                    val body = call.receiveText()
                    record("POST", body)
                    call.respondText(
                        TRANSPORT_JSON.encodeToString(SessionDto.serializer(), cannedStart),
                        ContentType.Application.Json,
                    )
                }
                post(AUTH_TICKET_PATH) {
                    record("POST", "")
                    call.respondText(
                        TRANSPORT_JSON.encodeToString(TicketResponse.serializer(), cannedTicket),
                        ContentType.Application.Json,
                    )
                }
                post(AUTH_ROTATE_PATH) {
                    record("POST", "")
                    call.respondText(
                        TRANSPORT_JSON.encodeToString(RotateResponse.serializer(), RotateResponse("newmastertoken")),
                        ContentType.Application.Json,
                    )
                }
            }
        }

        private suspend fun io.ktor.server.routing.RoutingContext.record(method: String, body: String) {
            requests.trySend(Recorded(method, call.request.path(), call.request.headers[HttpHeaders.Authorization], body))
        }
    }

    private fun withStub(token: String? = "secret", block: suspend (Stub, ApiClient) -> Unit) = runBlocking {
        withTimeout(30_000) {
            val stub = Stub()
            stub.server.start(wait = false)
            val port = stub.server.engine.resolvedConnectors().first().port
            val api = ApiClient(baseUrl = "http://127.0.0.1:$port", token = token)
            try {
                block(stub, api)
            } finally {
                api.close()
                stub.server.stop(100, 500)
            }
        }
    }

    private fun sampleDto(id: String, state: String, needsAttention: Boolean) = SessionDto(
        id = id,
        name = "kt-$id",
        tags = emptyList(),
        agent = "claude",
        providerSessionId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        state = state,
        needsAttention = needsAttention,
        alive = true,
        cwd = "/tmp/work",
        tmuxSession = "kt-$id",
        paneId = "%1",
        lastSeq = 1,
        readCursor = 0,
        unread = 1,
        createdAt = 1,
        updatedAt = 1,
    )

    /** An [CliCommand.Import] with only the cwd varying — the resolution rule is what the tests probe. */
    private fun importCommand(cwd: String?) =
        CliCommand.Import("claude", PROVIDER_ID, cwd, name = null, tags = emptyList(), noStart = false)

    private fun webTicket() = TicketResponse(
        ticket = "A1B2C3D4",
        localUrl = "http://127.0.0.1:27508/auth#ticket=A1B2C3D4",
        publicUrl = "https://kotgent.example.com/auth#ticket=A1B2C3D4",
        expiresAt = 42,
    )

    /** A pure fake [LocalTty] recording enter/restore order — never touches a real terminal. */
    private class FakeTty(private val size: WinSize = WinSize(80, 24)) : LocalTty {
        val events = mutableListOf<String>()
        override fun enterRaw() { events.add("enter") }
        override fun restore() { events.add("restore") }
        override fun windowSize(): WinSize = size
    }

    private companion object {
        /** A well-formed provider session id for the import tests (parsing never validates it). */
        const val PROVIDER_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}
