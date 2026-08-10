package io.kotgent.transport

import io.kotgent.core.ProjectId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.TaskRef
import io.kotgent.daemon.TaskService
import io.kotgent.task.BacklogEntry
import io.kotgent.task.DependencyRefusedException
import io.kotgent.task.MalformedTaskRefException
import io.kotgent.task.MoveTarget
import io.kotgent.task.NoProjectException
import io.kotgent.task.NoSessionException
import io.kotgent.task.PROJECT_NAME_MAX_LENGTH
import io.kotgent.task.ProjectPathException
import io.kotgent.task.TaskState
import io.kotgent.task.UnknownProjectException
import io.kotgent.task.UnknownTaskException
import io.kotgent.task.mainCheckoutRoot
import io.kotgent.task.resolveProject
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException

/**
 * The task layer's mutating surface that is not a session link: `POST /tasks`, `PATCH /tasks/{ref}`,
 * `DELETE /tasks/{ref}`, `POST /tasks/{ref}/{move,deps,comment}`, and `POST /projects`.
 *
 * What the implementation owes (task-backlog plan, Task 14):
 *  - **`POST /tasks` resolves the project in exactly this order**: explicit `project` in the body → the
 *    calling session's `project_id` → `resolveProject(session cwd)` → create the file at
 *    `mainCheckoutRoot(session cwd)` through [io.kotgent.daemon.TaskService.projectFiles] → `400` naming
 *    `--project`, and **only** when there is no resolvable session at all (the board path with nothing
 *    selected). The two halves of that — "400 when there is no project" and "create the file when there
 *    is no project" — read as contradictory unless the order is written out, so it is.
 *    Whatever branch answers, the `projects` row is upserted: a project that never appears in
 *    `GET /projects` has a backlog the board's selector can never reach.
 *  - `PATCH` takes title / body / state, with an optional `message` on a state change so
 *    `kotgent task review -m "…"` is ONE operation — the transition and its activity row commit in one
 *    task-store transaction.
 *  - `DELETE` goes through [io.kotgent.daemon.TaskService.delete], which unlinks every holder first.
 *  - `/deps` answers `400` for each of the four refusals (self, unknown ref, cross-project, cycle) with a
 *    message that says which.
 *  - `POST /projects` writes `.kotgent.json` for a browser-supplied ABSOLUTE path that resolves to an
 *    existing DIRECTORY — the bounded departure from the upload rule recorded on [CreateProjectRequest],
 *    and all three of those words are checked here (a relative path before canonicalization, a
 *    non-existent one by it, a regular file after it). It ADOPTS whatever already owns the path before it
 *    considers writing (see below), and the path says WHICH checkout, not where the file lands: like every
 *    other creating path it anchors at [mainCheckoutRoot] (see below).
 *
 * ## Adopt before creating
 * `POST /projects` is documented as "create or ADOPT the project owning an absolute path", and the adopt
 * half is not a convenience — it is what keeps the endpoint from producing the split the anchor below
 * exists to prevent. Pointed at `/repo/packages/api`, which carries its own committed `.kotgent.json`,
 * a straight jump to [mainCheckoutRoot] writes a SECOND file at `/repo`: one repository with two projects,
 * and the uuid it answers with is one that no session under `packages/api` will ever resolve to, because
 * [resolveProject] is nearest-wins. So the canonical path is resolved FIRST and an existing owner — its
 * own file, or one committed above it, or the main checkout's when the path is a linked worktree — is
 * returned untouched. Only a path that belongs to no project reaches the writer.
 *
 * ## Creation anchors at the main checkout root, on BOTH creating paths
 * A project is a committed FILE, not a path, and the uuid inside it is what makes `/repo` and
 * `/repo-wt/feature` one backlog. Writing at the literal directory a caller named breaks exactly that: a
 * `kotgent project init` run from `/repo/src/cli` puts a second uuid there, `resolveProject` walks up and
 * NEAREST WINS, so every session under `src/cli` silently belongs to a different project than the rest of
 * the repository — and the same call inside a linked worktree commits the file into the worktree, where the
 * main checkout can never see it. So `POST /projects` anchors the same way `POST /tasks`' step 4 does
 * ([mainCheckoutRoot] of the canonicalized path, degrading to that path when it is in no repository), and
 * the default display name is taken from the ROOT rather than from the subdirectory that was named.
 * The cost is recorded, not overlooked: a deliberately NESTED project (a monorepo package with its own
 * backlog) can no longer be CREATED through this endpoint, only by committing a `.kotgent.json` by hand —
 * which the resolver still honours, because step 1 of resolution is still "nearest wins", and which the
 * adopt step above then answers with rather than shadowing.
 *
 * ## Where the "the `projects` row is upserted" obligation actually lands
 * On the branches that touch a `.kotgent.json`, and only those. `resolveProject` READ a file, so the
 * project it found is registered (that also refreshes `projects.path` to the checkout the daemon just
 * saw, which is exactly what that column means) — on both `POST /tasks`' step 3 and `POST /projects`'
 * adopt branch; a creating branch obviously registers what it wrote. The other two branches deliberately
 * do not write: an explicit `project` is required to be a project the daemon already knows — a uuid it
 * has never seen is a `404` rather than a row invented from a name nobody supplied — and a session's
 * stored `project_id` was registered when the session started. That keeps the rule the plan states
 * ("every path that reads or creates a project file upserts the row") literally true without inventing a
 * display name for a project this request never opened.
 *
 * ## …and the session row is bound along with it
 * A `POST /tasks` that had to READ or WRITE a project file also persists the answer onto the calling
 * session's `project_id` — see [bindSessionProject]. Without it the session that bootstraps a project
 * cannot use `task show` / `task next` / `task list` until the daemon restarts.
 *
 * ## Status conventions, shared with the other two task route files
 * `404` means "no such task `{ref}`" (or `{project}`), so a ref that cannot even be parsed is a `400`;
 * every typed failure in `TaskErrors.kt` maps as its header records. The exceptions are constructed for
 * their MESSAGE even where nothing throws, so the three route files word the same failure identically.
 */
fun Route.taskWriteRoutes(routing: TaskRouting) {

    /*
     * `POST /tasks` — the one endpoint that may run without any session at all, because creating cards is
     * the board's headline job and the board has neither a pane nor a session.
     */
    post("/tasks") {
        val req = decodeBody(CreateTaskRequest.serializer(), routing) ?: return@post
        val title = req.title.trim()
        if (title.isEmpty()) {
            call.respondText("a task needs a title", status = HttpStatusCode.BadRequest)
            return@post
        }
        // Resolved BEFORE the project, and that order is load-bearing: resolving the project can WRITE
        // (a `projects` row, a `.kotgent.json`, the session's `project_id`), and a caller who NAMED a
        // session that is not there gets a `400` — leaving a file on disk for a request that refused is
        // exactly what the `PATCH` handler avoids by resolving its own author before the tracker edit.
        val author = attributedAuthor(routing, req.sessionId) ?: return@post
        val project = resolveProjectForCreate(routing, req) ?: return@post
        val created = routing.tasks.create(project, title, req.body, author)
        val entry = routing.tasks.entry(created.ref)
        if (entry == null) {
            // The built-in tracker writes the `tasks` row, its `backlog_entries` row and the `created`
            // activity row in ONE transaction, so this cannot happen for it. Answering honestly rather
            // than synthesizing an entry keeps a future tracker that only creates its own half loud.
            call.respondText(
                "task '${created.ref.value}' was created but has no backlog entry",
                status = HttpStatusCode.InternalServerError,
            )
            return@post
        }
        respondEntry(routing, entry, HttpStatusCode.Created)
    }

    /*
     * `PATCH /tasks/{ref}` — tracker fields and/or the workflow state, in that order: the tracker edit is
     * `TaskTracker.update` (no activity row) and the state change is `TaskService.transition`, which
     * commits the state, its ONE activity row (carrying `message`) and the reverse-dependent re-stamp
     * together. That is what makes `kotgent task review -m "…"` a single operation.
     */
    patch("/tasks/{ref}") {
        val ref = taskRefParam() ?: return@patch
        val req = decodeBody(PatchTaskRequest.serializer(), routing) ?: return@patch
        val to = if (req.state != null) {
            taskStateOf(req.state) ?: run {
                call.respondText(
                    "unknown task state '${req.state}' — expected one of " +
                        TaskState.entries.joinToString(", ") { it.name },
                    status = HttpStatusCode.BadRequest,
                )
                return@patch
            }
        } else {
            null
        }
        if (req.title == null && req.body == null && to == null) {
            call.respondText(
                "nothing to change — a patch carries at least one of title, body or state",
                status = HttpStatusCode.BadRequest,
            )
            return@patch
        }
        if (req.message != null && to == null) {
            // The message is the transition's explanation and rides its activity row; without a state
            // change there is no row for it to ride, and silently dropping what somebody typed is worse
            // than saying so. A free-standing note is `POST /tasks/{ref}/comment`.
            call.respondText(
                "a message is only meaningful with a state change — use /tasks/${ref.value}/comment " +
                    "for a standalone note",
                status = HttpStatusCode.BadRequest,
            )
            return@patch
        }
        // Resolved BEFORE the tracker edit below, even though only the transition uses it: a refusal after
        // `update` would leave the title/body written and the state not, for a request that answered 400.
        //
        // The state and its author are carried as ONE nullable value rather than two, because two
        // nullables can spell a state with no author — a combination this route can never perform and
        // would silently SKIP, answering `200` for an un-transitioned entry. [StateChange] cannot spell it.
        val change = if (to == null) {
            null
        } else {
            StateChange(to, attributedAuthor(routing, req.sessionId) ?: return@patch)
        }
        if (req.title != null || req.body != null) {
            if (req.title != null && req.title.isBlank()) {
                call.respondText("a task needs a title", status = HttpStatusCode.BadRequest)
                return@patch
            }
            if (routing.tasks.update(ref, req.title?.trim(), req.body) == null) {
                fail(HttpStatusCode.NotFound, UnknownTaskException(ref))
                return@patch
            }
        }
        if (change != null) {
            if (routing.service.transition(ref, change.to, change.author, req.message) == null) {
                fail(HttpStatusCode.NotFound, UnknownTaskException(ref))
                return@patch
            }
        }
        // Re-read rather than answering with whichever half wrote last: a patch that carried both a
        // tracker edit and a transition has two writers, and the caller merges ONE observation newest-rev-wins.
        val entry = routing.tasks.entry(ref)
        if (entry == null) {
            fail(HttpStatusCode.NotFound, UnknownTaskException(ref))
            return@patch
        }
        respondEntry(routing, entry)
    }

    /*
     * `DELETE /tasks/{ref}` — through the service, which unlinks every holder BEFORE removing the task so
     * no session is left carrying a badge that points at nothing.
     */
    delete("/tasks/{ref}") {
        val ref = taskRefParam() ?: return@delete
        if (!routing.service.delete(ref)) {
            fail(HttpStatusCode.NotFound, UnknownTaskException(ref))
            return@delete
        }
        call.respondText("ok")
    }

    /*
     * `POST /tasks/{ref}/move` — a re-rank and nothing else. A board drop that changes both column and
     * rank is the `PATCH` first and then this, so neither endpoint has to define a half-applied combination.
     */
    post("/tasks/{ref}/move") {
        val ref = taskRefParam() ?: return@post
        val req = decodeBody(MoveTaskRequest.serializer(), routing) ?: return@post
        val target = moveTargetOf(req)
        if (target == null) {
            call.respondText(
                "move requires exactly one of before, after, top or bottom, and a named neighbour must " +
                    "be a well-formed task ref",
                status = HttpStatusCode.BadRequest,
            )
            return@post
        }
        val moved = routing.tasks.move(ref, target)
        if (moved == null) {
            // `move` answers null for an unknown ref AND for a named neighbour that is not there, and the
            // store cannot tell the caller which without a second read it does not owe.
            call.respondText(
                "no such task '${ref.value}', or the named neighbour is not in its project",
                status = HttpStatusCode.NotFound,
            )
            return@post
        }
        respondEntry(routing, moved)
    }

    /*
     * `POST /tasks/{ref}/deps` — add or remove one edge.
     *
     * The path ref is deliberately NOT pre-checked for existence here. All four refusals — self, unknown
     * ref, cross-project, cycle — are validated inside [io.kotgent.store.TaskStore.addDependency] and all
     * four answer `400`, so a pre-check would answer `404` for one of them and quietly make the
     * "unknown ref" refusal unreachable from the path side. A `remove` naming a task that is not there
     * still reaches the `404` below, through the read-back.
     */
    post("/tasks/{ref}/deps") {
        val ref = taskRefParam() ?: return@post
        val req = decodeBody(DepsRequest.serializer(), routing) ?: return@post
        val on = TaskRef.parseOrNull(req.on)
        if (on == null) {
            fail(HttpStatusCode.BadRequest, MalformedTaskRefException(req.on))
            return@post
        }
        try {
            when (req.action) {
                "add" -> routing.tasks.addDependency(ref, on)
                "remove" -> routing.tasks.removeDependency(ref, on)
                else -> {
                    call.respondText(
                        "unknown dependency action '${req.action}' — expected 'add' or 'remove'",
                        status = HttpStatusCode.BadRequest,
                    )
                    return@post
                }
            }
        } catch (e: DependencyRefusedException) {
            fail(HttpStatusCode.BadRequest, e)
            return@post
        }
        val entry = routing.tasks.entry(ref)
        if (entry == null) {
            fail(HttpStatusCode.NotFound, UnknownTaskException(ref))
            return@post
        }
        respondEntry(routing, entry)
    }

    /*
     * `POST /tasks/{ref}/comment` — requires session identity, because an activity row must be
     * attributable. The board's own notes arrive with a `sessionId`-less body only from a pane, so a
     * browser comment goes through the same header/`sessionId` resolution every other attributed write does.
     */
    post("/tasks/{ref}/comment") {
        val ref = taskRefParam() ?: return@post
        val req = decodeBody(CommentRequest.serializer(), routing) ?: return@post
        val text = req.text.trim()
        if (text.isEmpty()) {
            call.respondText("a comment needs text", status = HttpStatusCode.BadRequest)
            return@post
        }
        val author = requireCallerSession(routing, req.sessionId) ?: return@post
        val row = routing.tasks.comment(ref, author, text)
        if (row == null) {
            fail(HttpStatusCode.NotFound, UnknownTaskException(ref))
            return@post
        }
        call.respondText(
            routing.json.encodeToString(ActivityEntryDto.serializer(), row.toDto()),
            ContentType.Application.Json,
            HttpStatusCode.Created,
        )
    }

    /*
     * `POST /projects` — create or ADOPT the project OWNING an absolute path. Idempotent by construction
     * (the adopt branch below writes nothing, and an existing `.kotgent.json` always wins the `link(2)`
     * race and is read back), so the answer is `200` and not `201`: the caller cannot tell whether it
     * wrote the file, and does not need to.
     *
     * ADOPT comes first, and CREATE only when nothing owns the path — see the file header's "Adopt before
     * creating". The named directory then selects the checkout; the file itself lands at that checkout's
     * main root — see "Creation anchors at the main checkout root".
     */
    post("/projects") {
        val req = decodeBody(CreateProjectRequest.serializer(), routing) ?: return@post
        val requested = req.path.trim()
        if (!requested.startsWith('/')) {
            // Checked BEFORE canonicalization: `realpath` would resolve a relative path against the
            // DAEMON's cwd, silently accepting a path the caller never meant.
            fail(HttpStatusCode.BadRequest, ProjectPathException(req.path, "project path must be absolute: '${req.path}'"))
            return@post
        }
        val fs = routing.service.projectFs
        val canonical = fs.canonicalize(requested)
        if (canonical == null) {
            fail(
                HttpStatusCode.BadRequest,
                ProjectPathException(requested, "no such directory: '$requested'"),
            )
            return@post
        }
        // A path that resolved is not yet a path this endpoint may act on: `realpath(3)` canonicalizes a
        // regular FILE just as happily as a directory, and everything below treats what it is handed as a
        // directory. Pointed at `/repo/README.md`, `resolveProject` walks UP and adopts whatever owns
        // `/repo`, and with nothing committed there `mainCheckoutRoot` walks up to the checkout and the
        // writer creates the file at `/repo` — a `200` and a project uuid for a location that is not a
        // project location. The writer's own "not an existing directory" refusal cannot catch it: by then
        // the directory it is handed is the checkout root, which really is one. So the gate is here,
        // ABOVE the adopt branch (which writes nothing and would otherwise answer first) and above the
        // name check.
        if (!fs.isDirectory(canonical)) {
            fail(
                HttpStatusCode.BadRequest,
                ProjectPathException(requested, "not a directory: '$requested'"),
            )
            return@post
        }
        // Validated BEFORE the adopt lookup, even though only the creating branch can use it: a name a
        // `.kotgent.json` could never carry is a malformed REQUEST, and it must be refused for the same
        // input whether or not the directory happens to be adopted. Answering `200` with somebody else's
        // name would report success for a name that was rejected a moment ago at the same path.
        val requestedName = if (req.name == null) {
            null
        } else {
            validProjectName(req.name) ?: run {
                call.respondText(
                    "a project name must be 1..$PROJECT_NAME_MAX_LENGTH characters and carry no control " +
                        "characters — otherwise the .kotgent.json this writes could not be read back",
                    status = HttpStatusCode.BadRequest,
                )
                return@post
            }
        }
        // Adopt: `resolveProject` walks up from the canonical path (nearest wins) and reaches a linked
        // worktree's main root, so anything already owning this path answers here and nothing is written.
        // The row is upserted because a file was READ — the same obligation `POST /tasks`' step 3 has.
        val owner = resolveProject(fs, canonical)
        if (owner != null) {
            routing.tasks.upsertProject(owner.id, owner.name, owner.root)
            respondProject(routing, owner.id, owner.root)
            return@post
        }
        // `mainCheckoutRoot` requires a canonical absolute path (it answers null for anything else), which
        // is why it runs after `canonicalize` and not on `requested`.
        val dir = mainCheckoutRoot(fs, canonical) ?: canonical
        val name = requestedName ?: defaultProjectName(dir)
        val file = try {
            routing.service.projectFiles.ensureProjectFile(dir, name)
        } catch (e: ProjectPathException) {
            fail(HttpStatusCode.BadRequest, e)
            return@post
        }
        routing.tasks.upsertProject(file.id, file.name, dir)
        respondProject(routing, file.id, dir)
    }
}

// --- helpers ---------------------------------------------------------------------------------------
//
// Every one of these is `private`, which makes it FILE-scoped rather than package-scoped: `TaskReadRoutes`,
// `TaskLinkRoutes` and `EventsWs` are written by other agents in this same package at this same moment, so
// a non-private top-level declaration here is a redeclaration error nobody sees until the branches merge.
// The shared mappers live in `TaskDtos.kt` for exactly that reason.

/**
 * A `PATCH`'s workflow state together with the author it will be attributed to.
 *
 * The pair exists to make an unrepresentable combination unrepresentable: a `TaskState?` and a `String?`
 * beside each other can spell "a state change with no author", which [io.kotgent.daemon.TaskService.transition]
 * has no signature for, so the route would have to guard the pair with a conjunct that is dead today and
 * silently skips the transition the day it is not.
 */
private data class StateChange(val to: TaskState, val author: String)

/** Decode a request body, answering `400` (and returning `null`) when it is not the expected JSON. */
private suspend fun <T> RoutingContext.decodeBody(
    serializer: kotlinx.serialization.KSerializer<T>,
    routing: TaskRouting,
): T? = try {
    routing.json.decodeFromString(serializer, call.receiveText())
} catch (_: SerializationException) {
    call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
    null
}

/**
 * The `{ref}` path parameter as a [TaskRef], answering `400` (and returning `null`) when it is not one.
 *
 * A malformed ref is a bad REQUEST rather than a missing resource: by this package's convention `404`
 * means "no such task `{ref}`", which presupposes that `{ref}` names something.
 */
private suspend fun RoutingContext.taskRefParam(): TaskRef? {
    val raw = call.parameters["ref"].orEmpty()
    val ref = TaskRef.parseOrNull(raw)
    if (ref == null) fail(HttpStatusCode.BadRequest, MalformedTaskRefException(raw))
    return ref
}

/** Answer with a typed failure's own message, so the three task route files word each failure alike. */
private suspend fun RoutingContext.fail(status: HttpStatusCode, e: RuntimeException) {
    call.respondText(e.message ?: status.description, status = status)
}

/**
 * The calling session's id as an activity `author`, or `null` after answering `400` naming `--session`.
 *
 * The row is looked up rather than trusted: [resolveCallerIdentity] deliberately does not check existence
 * (its KDoc says so), and an activity row attributed to a session that is not there is exactly the silent
 * no-op that check exists to prevent. It is a `400` and not a `404` because the id came from the body, not
 * from the path — `404` in this package means "no such task `{ref}`".
 *
 * Both non-resolving shapes are refused here, but with DIFFERENT words: `comment` needs somebody, and a
 * caller who sent nothing has to be told to send something, while a caller whose pane or `sessionId` was
 * rejected has to be told which one and why.
 */
private suspend fun RoutingContext.requireCallerSession(routing: TaskRouting, explicitSessionId: String?): String? =
    when (val caller = resolveCallerIdentity(routing, explicitSessionId)) {
        CallerIdentity.Absent -> {
            fail(
                HttpStatusCode.BadRequest,
                NoSessionException(
                    "no calling session: send the $TASK_PANE_HEADER header from inside a kotgent pane, " +
                        "or name one with --session",
                ),
            )
            null
        }

        is CallerIdentity.Rejected -> {
            fail(HttpStatusCode.BadRequest, NoSessionException(caller.reason))
            null
        }

        is CallerIdentity.Resolved -> existingCallerSession(routing, caller.id)
    }

/**
 * The `author` for a write that MAY come from the board: the caller's session id,
 * [TaskService.BOARD_AUTHOR] when NOTHING identified a caller — and `null` after answering `400` when an
 * identity was supplied and names nobody.
 *
 * The board branch is what separates this from [requireCallerSession]: a `PATCH` (dragging a card) and a
 * `POST /tasks` (filing one) really can arrive with nobody behind them. Everything else must fail loudly,
 * and the reason is one line of the design. The activity feed is the only place the no-exclusivity model
 * tells operators to look to see who is doing what, so a write that lands under `board` is a claim that a
 * human did it. `kotgent task review --session <typo>`, and equally an agent whose pane the registry has
 * lost, must not be able to make that claim on the operator's behalf — which is precisely what mapping
 * every unresolvable identity to "no caller" did. `comment` refuses the same inputs for the same reason.
 *
 * Both callers pass what they get straight through — `PATCH` to
 * [io.kotgent.daemon.TaskService.transition]'s activity row, `POST /tasks` to the `created` row
 * [io.kotgent.task.TaskTracker.create] writes — and the default on that create signature exists for the
 * board alone, which is exactly the case this function answers [TaskService.BOARD_AUTHOR] for.
 */
private suspend fun RoutingContext.attributedAuthor(routing: TaskRouting, explicitSessionId: String?): String? =
    when (val caller = resolveCallerIdentity(routing, explicitSessionId)) {
        CallerIdentity.Absent -> TaskService.BOARD_AUTHOR
        is CallerIdentity.Rejected -> {
            fail(HttpStatusCode.BadRequest, NoSessionException(caller.reason))
            null
        }

        is CallerIdentity.Resolved -> existingCallerSession(routing, caller.id)
    }

/** [id]'s value once its row is confirmed to exist, or `null` after answering `400` naming `--session`. */
private suspend fun RoutingContext.existingCallerSession(routing: TaskRouting, id: SessionId): String? {
    if (routing.sessions.getSession(id) == null) {
        fail(HttpStatusCode.BadRequest, NoSessionException("no such session '${id.value}' — name a live one with --session"))
        return null
    }
    return id.value
}

/**
 * `POST /tasks`'s project, in the plan's order, or `null` after answering the failure itself.
 *
 * The order is the whole contract, so it is written as four consecutive branches rather than folded:
 *  1. an explicit `project` — the board's path. It must already be a project the daemon knows; a uuid it
 *     has never seen is a `404`, because there is no name to register it under and a row invented here
 *     would show up in the selector as a project nobody named.
 *  2. the calling session's stored `project_id` — registered when the session started, so nothing to
 *     re-register here.
 *  3. `resolveProject(session cwd)` — this READS a `.kotgent.json`, so it upserts.
 *  4. create the file at `mainCheckoutRoot(session cwd)` — this WRITES one, so it upserts too.
 *
 * `400` naming `--project` only when step 2 finds no session at all. That is the board with nothing
 * selected, or a CLI call from outside any pane: there is no cwd to resolve and nowhere to create.
 */
private suspend fun RoutingContext.resolveProjectForCreate(
    routing: TaskRouting,
    req: CreateTaskRequest,
): ProjectId? {
    val explicit = req.project?.takeIf { it.isNotBlank() }
    if (explicit != null) {
        val id = ProjectId.parseOrNull(explicit)
        if (id == null) {
            call.respondText(
                "malformed project id '$explicit' — expected a canonical uuid",
                status = HttpStatusCode.BadRequest,
            )
            return null
        }
        if (routing.tasks.project(id) == null) {
            fail(HttpStatusCode.NotFound, UnknownProjectException(id))
            return null
        }
        return id
    }

    // [resolveCallerSession] joins "absent" and "rejected" back into one null, which is right HERE and
    // only because of the order in the handler: `attributedAuthor` ran first and already answered `400`
    // for a rejected identity, so the only null this can see is a caller who supplied nothing — the board
    // with no project selected, which is exactly the `400` below.
    val sessionId = resolveCallerSession(routing, req.sessionId)
    val session = sessionId?.let { routing.sessions.getSession(it) }
    if (session == null) {
        fail(
            HttpStatusCode.BadRequest,
            NoProjectException(
                "no project: name one with --project, or run this from inside a kotgent session " +
                    "(the $TASK_PANE_HEADER header, or --session)",
            ),
        )
        return null
    }

    session.projectId?.let { return it }

    val fs = routing.service.projectFs
    resolveProject(fs, session.cwd)?.let { resolved ->
        routing.tasks.upsertProject(resolved.id, resolved.name, resolved.root)
        bindSessionProject(routing, session, resolved.id)
        return resolved.id
    }

    // Nothing committed anywhere above the session's cwd: create the file. `mainCheckoutRoot` puts it at
    // the checkout root so every worktree of that repository shares the uuid; with no repository at all
    // the session's own directory IS the root, which is the same degradation the resolution rules make
    // for every unsupported git layout.
    val canonical = fs.canonicalize(session.cwd) ?: session.cwd
    val root = mainCheckoutRoot(fs, canonical) ?: canonical
    val file = try {
        routing.service.projectFiles.ensureProjectFile(root, defaultProjectName(root))
    } catch (e: ProjectPathException) {
        fail(HttpStatusCode.BadRequest, e)
        return null
    }
    routing.tasks.upsertProject(file.id, file.name, root)
    bindSessionProject(routing, session, file.id)
    return file.id
}

/**
 * Persist [project] onto [session]'s row, for the two `POST /tasks` branches that had to consult the
 * filesystem to find it.
 *
 * ## Why the create path owes this write at all
 * `sessions.project_id` is what `GET /tasks` and `POST /tasks/next` resolve a ref-less request through
 * (`TaskReadRoutes.resolveProjectParameter`, `TaskLinkRoutes`' `post("/tasks/next")`), and until this was
 * written back the ONLY production caller of [io.kotgent.store.EventStore.setProjectId] was the
 * `Reconciler`'s startup backfill. So the session that BOOTSTRAPPED a project — the one that filed the
 * first card in a repository that had no `.kotgent.json` — could not then run `task next` or `task list`
 * without a `--project` uuid until the daemon was restarted, which is the whole ref-less agent loop.
 * Steps 1 and 2 owe nothing: an explicit `project` is the board naming someone else's backlog and must not
 * re-point the session that relayed it, and step 2 read the column it would be writing.
 *
 * ## Order and stamp
 * The `projects` row is upserted by the caller FIRST and the session bound second — the same
 * write-both-or-neither order `SessionManager` and `Reconciler.backfillProjectId` use, so a failure
 * between them leaves `project_id` null and the next create (or the next daemon start) simply tries again;
 * the reverse order would pin a session to a project the board cannot list, with nothing left to repair it.
 *
 * The stamp is the row's CURRENT `updated_at`, never a fresh clock, for the reason
 * `Reconciler.sortKeyOf` writes down: `updated_at` is ACTIVITY and is what `kotgent list` sorts by, while
 * this is a derived backfill, which must neither advance nor rewind the sort key. It is RE-READ for the
 * same reason that function re-reads: [session] was snapshotted before a filesystem walk and possibly a
 * file write, and a hook that advanced this row in that window (a real state change, which IS activity)
 * must not be rolled back by the stale value. A row that vanished meanwhile falls back to the snapshot,
 * and its write is a no-op anyway.
 */
private suspend fun bindSessionProject(routing: TaskRouting, session: SessionMeta, project: ProjectId) {
    val sortKey = routing.sessions.getSession(session.id)?.updatedAt ?: session.updatedAt
    routing.sessions.setProjectId(session.id, project, sortKey)
}

/**
 * Answer `POST /projects` with the registered row for [project], or `500` when it is not registered.
 *
 * Shared by the adopt and the create branch so both report a project the board can actually reach: the
 * row is what `GET /projects` lists, and answering a uuid whose row is missing would hand the caller a
 * project its own selector cannot show. [dir] is named in the failure only — it is the directory the
 * answer came from, which is the one thing an operator needs to look at.
 */
private suspend fun RoutingContext.respondProject(routing: TaskRouting, project: ProjectId, dir: String) {
    val record = routing.tasks.project(project)
    if (record == null) {
        call.respondText(
            "project '${project.value}' at $dir is not registered",
            status = HttpStatusCode.InternalServerError,
        )
        return
    }
    call.respondText(
        routing.json.encodeToString(ProjectDto.serializer(), record.toDto()),
        ContentType.Application.Json,
    )
}

/** Serialize one backlog entry with its tracker fields and its dependency slice. */
private suspend fun RoutingContext.respondEntry(
    routing: TaskRouting,
    entry: BacklogEntry,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    val dto = entry.toDto(routing.tasks.get(entry.ref), routing.tasks.dependenciesOf(entry.ref))
    call.respondText(
        routing.json.encodeToString(BacklogEntryDto.serializer(), dto),
        ContentType.Application.Json,
        status,
    )
}

/** A state name as a [TaskState], or `null`. Matched exactly — the enum name IS the wire value. */
private fun taskStateOf(raw: String): TaskState? = TaskState.entries.firstOrNull { it.name == raw }

/**
 * Exactly one of the four move fields, or `null` when the request named none, named several, or named a
 * neighbour that is not a well-formed ref. One `null` for all three because they are one client mistake —
 * "this is not a move" — and the route's message says all of it.
 */
private fun moveTargetOf(req: MoveTaskRequest): MoveTarget? {
    val targets = mutableListOf<MoveTarget>()
    val before = req.before
    if (before != null) targets += MoveTarget.Before(TaskRef.parseOrNull(before) ?: return null)
    val after = req.after
    if (after != null) targets += MoveTarget.After(TaskRef.parseOrNull(after) ?: return null)
    if (req.top) targets += MoveTarget.Top
    if (req.bottom) targets += MoveTarget.Bottom
    return targets.singleOrNull()
}

/**
 * A display name for a project file this daemon is about to write, derived from its directory.
 *
 * It is sanitized to what [io.kotgent.task.parseProjectFile] will accept — trimmed, control characters
 * dropped, capped at [PROJECT_NAME_MAX_LENGTH] — because a name outside those bounds would produce a file
 * the resolver then refuses to read, i.e. a project that vanishes the moment the daemon restarts.
 */
private fun defaultProjectName(dir: String): String {
    val base = dir.trimEnd('/').substringAfterLast('/')
        .filter { it.code >= 0x20 && it.code != 0x7f }
        .trim()
        .take(PROJECT_NAME_MAX_LENGTH)
    return base.ifEmpty { "project" }
}

/** A caller-supplied project name, or `null` when it is not one [io.kotgent.task.parseProjectFile] accepts. */
private fun validProjectName(raw: String): String? {
    val name = raw.trim()
    if (name.isEmpty() || name.length > PROJECT_NAME_MAX_LENGTH) return null
    if (name.any { it.code < 0x20 || it.code == 0x7f }) return null
    return name
}
