package io.kotgent.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.ProjectId
import io.kotgent.core.Projection
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.TaskRef
import io.kotgent.core.reduce
import io.kotgent.core.replay
import io.kotgent.core.unread
import io.kotgent.db.KotgentDatabase
import io.kotgent.db.Sessions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The SQLDelight-backed [EventStore] (Task 7) — the storage path proven by the Task 4 spike
 * (`.sq` codegen via the sqldelight-gen plugin + `native-driver` on macosArm64).
 *
 * Design:
 *  - **Single writer.** Every [append] runs under [mutex], so per-session seqs stay strictly
 *    monotonic and contiguous and the in-memory projection cache never races the log.
 *  - **Atomic append + cache.** Each append inserts the event AND advances the `sessions`
 *    read-model cache (state / state_source / last_seq / provider_session_id / updated_at) in ONE
 *    SQL transaction, computing the new projection by [reduce]-ing the prior projection with the
 *    event — no full-log replay per append.
 *  - **Restart-safe.** The prior projection is taken from the in-memory cache, or reconstructed
 *    once by [replay] over the session's stored events on a cold cache. Because the log is the
 *    source of truth, a fresh store over an existing DB rebuilds identical projections.
 *  - **Cursored subscribe.** [subscribe] snapshots stored events and registers a live relay under
 *    the same [mutex] the writer holds, so no committed append is missed or duplicated across the
 *    snapshot boundary; a cursor beyond the log fails with [StaleCursorException].
 *
 * The concrete driver is injected (see [inMemory] / [using]) so tests use in-memory SQLite while
 * the daemon can supply a file-backed driver.
 */
class SqliteEventStore private constructor(
    driver: SqlDriver,
    private val json: Json,
    private val now: () -> Long,
) : EventStore, PreferencesStore {

    private val db: KotgentDatabase = KotgentDatabase(driver)
    private val events get() = db.eventsQueries
    private val sessions get() = db.sessionsQueries
    private val preferenceQueries get() = db.uiPreferencesQueries

    /** Serializes all writes (single writer) and guards the in-memory maps below. */
    private val mutex = Mutex()

    /**
     * The global session-row revision counter (see `Sessions.sq`'s `rev` column). Seeded from
     * `maxRev` in [init] (single-threaded construction), incremented only under [mutex] — every
     * mutator stamps `++revCounter` into its statement. A value consumed by a write that touched
     * zero rows (a rejected [setModelForProvider], a mutator on a missing row) is never persisted or
     * emitted, so its post-restart reuse is unobservable.
     */
    private var revCounter: Long = 0

    /** Initialized from the seeded singleton row after the legacy-database DDL runs in [init]. */
    private val _preferences: MutableStateFlow<UiPreferences>
    override val preferences: StateFlow<UiPreferences> get() = _preferences

    /** Per-session cached projection (reducer read-model); reconstructed lazily by [replay]. */
    private val projections = HashMap<SessionId, Projection>()

    /** Live relays per session; the writer fans committed events out to these after each append. */
    private val subscribers = HashMap<SessionId, MutableList<SendChannel<StoredEvent>>>()

    /**
     * Hot cross-session cache-change signal (Task 14 events-WS). Non-replaying so late subscribers do
     * not re-see history (the transport pairs it with a `listSessions` snapshot); buffered + DROP_OLDEST
     * so a burst of appends never suspends the single writer holding [mutex]. Emitted non-suspendingly
     * (`tryEmit`) under the lock: by [append] directly, and by every other mutator via [emitFromRow].
     *
     * A slow consumer that falls far behind can still miss an intermediate update; the events-WS guards
     * against that per-socket with a conflating sender that never blocks its collector (see
     * [io.kotgent.transport.eventsWs]), so a slow client conflates instead of backing this buffer up. The
     * buffer is generous (1024) to make even transient drops unlikely under bursts.
     */
    private val _sessionUpdates = MutableSharedFlow<SessionUpdate>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val sessionUpdates: SharedFlow<SessionUpdate> get() = _sessionUpdates

    /**
     * Correctness-oriented companion to [_sessionUpdates]. It is deliberately unbuffered: once a
     * subscriber exists, a committed writer waits only until that subscriber receives the update, bounding
     * memory without discarding an intermediate transition. With no subscriber, `MutableSharedFlow` drops
     * the value without suspending; [PushNotifier][io.kotgent.push.PushNotifier] establishes its baseline
     * from [listSessions] before subscribing.
     *
     * Every publish happens under [mutex], immediately after the matching database mutation, so subscribers
     * observe the same total order as committed writes. The notifier's collector does only constant-time
     * edge tracking before handing delivery to its separate, conflated worker.
     */
    private val _reliableSessionUpdates = MutableSharedFlow<SessionUpdate>()
    override val reliableSessionUpdates: SharedFlow<SessionUpdate> get() = _reliableSessionUpdates

    init {
        // WAL at DB init (Technical Details): lets readers not block the single writer on a
        // file-backed DB. `PRAGMA journal_mode` returns a row (the resulting mode), so it must go
        // through executeQuery — sqliter's execute() rejects result-returning statements. A no-op
        // for :memory: (SQLite keeps a MEMORY journal there, returned as one harmless row).
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA journal_mode=WAL",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Unit
            },
            parameters = 0,
        )
        // Additive, idempotent migration for the `archived` column. The vendored sqldelight-gen plugin
        // drops `.sqm` files (deriveSchemaFromMigrations/verifyMigrations are off) and leaves the
        // generated `Schema.migrate()` empty, so a schema-version bump would NOT alter an existing table.
        // Instead add the column here — but ONLY when it is actually missing. On a fresh DB `create()`
        // already added it, and letting the ALTER run-and-fail there is not free: sqliter logs the
        // SQLITE_ERROR ("duplicate column name: archived") with a full stack trace before the throw ever
        // reaches us, so every single daemon start printed a scary-looking failure for a no-op. Asking
        // `PRAGMA table_info` first is exact and cheap. A genuine ALTER failure now propagates: the column
        // really is missing, and every session write after it would fail with "no such column" anyway.
        // `ALTER … ADD COLUMN` returns no rows, so use execute(), not executeQuery().
        if (!driver.hasColumn("sessions", "archived")) {
            driver.execute(null, "ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0", 0)
        }
        // Same additive-migration idiom for the `rev` column (see Sessions.sq for its semantics).
        if (!driver.hasColumn("sessions", "rev")) {
            driver.execute(null, "ALTER TABLE sessions ADD COLUMN rev INTEGER NOT NULL DEFAULT 0", 0)
        }
        // ... and for the two task-layer columns. Both are nullable with no default, so an existing row
        // reads as "no task, no project" — which is exactly true for every session created before the
        // backlog existed. Same guard, same reason: a duplicate-column ALTER makes sqliter log a
        // SQLITE_ERROR with a full stack trace before throwing, on every daemon start, for a no-op.
        if (!driver.hasColumn("sessions", "task_ref")) {
            driver.execute(null, "ALTER TABLE sessions ADD COLUMN task_ref TEXT", 0)
        }
        if (!driver.hasColumn("sessions", "project_id")) {
            driver.execute(null, "ALTER TABLE sessions ADD COLUMN project_id TEXT", 0)
        }
        // Seed the revision counter from the committed rows. Runs after the guard above, so the
        // generated query always finds the column; construction is single-threaded, so no lock yet.
        revCounter = sessions.maxRev().executeAsOne()

        // A whole new table follows the same runtime-migration rule as push_subscriptions: SQLDelight's
        // generated create() covers fresh databases, while this idempotent DDL covers databases created by
        // an older binary (generated Schema.migrate() is intentionally empty in this project). Keep this
        // string in exact step with UiPreferences.sq. Seeding is idempotent too and gives both fresh and
        // legacy databases the same revision-0 default.
        driver.execute(null, CREATE_PREFERENCES_TABLE_IF_NOT_EXISTS, 0)
        preferenceQueries.seedDefaults()
        _preferences = MutableStateFlow(readPreferences())
    }

    override suspend fun upsertSession(meta: SessionMeta): Unit = mutex.withLock {
        sessions.upsert(
            meta.id.value,
            meta.name,
            encodeTags(meta.tags),
            meta.agent,
            meta.providerSessionId?.value,
            meta.model,
            meta.cliVersion,
            meta.cliPath,
            meta.cwd,
            meta.repository,
            meta.worktree,
            meta.branch,
            meta.tmuxSession,
            meta.paneId?.value,
            meta.state.name,
            meta.stateSource?.name,
            meta.lastSeq.value,
            meta.readCursor.value,
            meta.createdAt,
            meta.updatedAt,
            if (meta.archived) 1L else 0L,
            ++revCounter, // the store stamps the revision; whatever `meta.rev` carries is ignored
            // COALESCEd in the statement: a caller writing a snapshot it read BEFORE a link landed must
            // not clear it. Only setTaskRef / setProjectId can ever null these columns.
            meta.taskRef?.value,
            meta.projectId?.value,
        )
        // Emit from the COMMITTED row, not from `meta`: the upsert max-merges read_cursor, so a `meta`
        // carrying a cursor the row has already moved past would broadcast an `unread` the DB disagrees
        // with. Defence in depth — no caller can produce that today (see the note on Sessions.sq's upsert).
        emitFromRow(meta.id)
    }

    override suspend fun updateSessionState(
        sessionId: SessionId,
        state: SessionState,
        stateSource: EventSource,
        paneId: PaneId?,
        updatedAt: Long,
    ): Unit = mutex.withLock {
        // Update only the daemon-owned control fields — never last_seq / provider_session_id, which a
        // concurrent hook append advances under this same lock (a stale full-row upsert would clobber
        // them). The in-memory `projections` cache is the pure event-log replay and is untouched here.
        sessions.updateControlState(state.name, stateSource.name, paneId?.value, updatedAt, ++revCounter, sessionId.value)
        emitFromRow(sessionId)
    }

    override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long): Unit = mutex.withLock {
        sessions.setArchived(if (archived) 1L else 0L, updatedAt, ++revCounter, sessionId.value)
        emitFromRow(sessionId)
    }

    override suspend fun setModel(sessionId: SessionId, model: String?, updatedAt: Long): Unit = mutex.withLock {
        sessions.setModel(model, updatedAt, ++revCounter, sessionId.value)
        // The signal carries the committed row's model verbatim (null included), so a capture — or the
        // rebind correction's clear — reaches connected clients on this very emission.
        emitFromRow(sessionId)
    }

    override suspend fun setModelForProvider(
        sessionId: SessionId,
        providerSessionId: ProviderSessionId,
        model: String,
        updatedAt: Long,
    ): Boolean = mutex.withLock {
        // The WHERE carries the provider-id check, so check-and-write is one atomic statement; the
        // read-back below only decides the return value / whether to emit, and cannot go stale because
        // every writer holds this same mutex.
        sessions.setModelForProvider(model, updatedAt, ++revCounter, sessionId.value, providerSessionId.value)
        val applied = sessions.get(sessionId.value).executeAsOneOrNull()
            ?.provider_session_id == providerSessionId.value
        if (applied) emitFromRow(sessionId)
        applied
    }

    override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?, updatedAt: Long) {
        TODO("Task 10: targeted task_ref write + emit")
    }

    override suspend fun setProjectId(sessionId: SessionId, projectId: ProjectId?, updatedAt: Long) {
        TODO("Task 10: targeted project_id write + emit")
    }

    override suspend fun sessionsHoldingTask(taskRef: TaskRef): List<SessionMeta> =
        TODO("Task 10: every session linked to this task")

    override suspend fun markRead(sessionId: SessionId, seq: Seq): Unit = mutex.withLock {
        // Monotonicity (MAX) and the clamp to last_seq (MIN) live in the statement itself, so nothing is
        // computed here; the in-memory `projections` map is a pure event-log replay and is untouched.
        sessions.setReadCursor(seq.value, ++revCounter, sessionId.value)
        // Emitted unconditionally — even when the MAX/MIN made the UPDATE a no-op — because this signal is
        // how a client whose earlier POST was lost gets re-synchronized.
        emitFromRow(sessionId)
    }

    override suspend fun getSession(sessionId: SessionId): SessionMeta? = mutex.withLock {
        sessions.get(sessionId.value).executeAsOneOrNull()?.toMeta()
    }

    override suspend fun listSessions(): List<SessionMeta> = mutex.withLock {
        sessions.list().executeAsList().map { it.toMeta() }
    }

    override suspend fun savePreferences(basePath: String, groupingLevel: Int): UiPreferences =
        mutex.withLock {
            // Increment in SQLite rather than deriving from StateFlow, so the persisted row remains the
            // revision authority across restarts. The singleton was seeded during init, so this always
            // updates exactly one row.
            preferenceQueries.save(basePath, groupingLevel.toLong())
            readPreferences().also { _preferences.value = it }
        }

    override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq =
        mutex.withLock {
            val prior = projectionLocked(sessionId)
            // The DB is the authority for the next per-session seq; the reducer must agree (the
            // in-memory projection is kept in lockstep with the log), which this check guards.
            val seq = events.nextSeq(sessionId.value).executeAsOne()
            val next = reduce(prior, event) // PURE event-log projection: authoritative for seq/provider id
            check(next.lastSeq.value == seq) {
                "seq divergence for '${sessionId.value}': reducer=${next.lastSeq.value} db=$seq"
            }
            val ts = now()
            val (type, payload) = serialize(event)

            // Cache-state authority. The sessions-cache `state` is the CONTROL-authoritative lifecycle:
            // control ops (interrupt/resume/terminate) and the reconciler set it WITHOUT an event, so the
            // pure event-log `next.state` must NOT clobber it — otherwise a state-neutral append (a late
            // SessionBound, a stray hook after a kill) would resurrect a stopped/interrupted session. So
            // seed the cache-state reduce from the CURRENT cached state: a dead/terminal cached session is
            // never revived by an append (its lifecycle is owned by control/reconciliation — and once its
            // pane is gone, no genuine hook can arrive); an alive one applies the event over the cached
            // (control-aware) state. The provider id / last_seq still come from the pure `next`.
            val cachedRow = sessions.get(sessionId.value).executeAsOneOrNull()
            val cachedState = cachedRow?.state?.let { SessionState.valueOf(it) }
            val cacheState = when {
                cachedState == null -> next.state // no cache row yet → the log state is all we have
                cachedState.isDead -> cachedState // never resurrect a dead session with a stray append
                else -> reduce(prior.copy(state = cachedState), event).state
            }
            val readCursor = cachedRow?.read_cursor ?: 0L

            // Atomic: the event row AND the session read-model cache advance together, or neither.
            val rev = ++revCounter
            db.transaction {
                events.insert(sessionId.value, seq, ts, type, source.name, payload)
                sessions.updateCache(
                    cacheState.name,
                    source.name,
                    next.lastSeq.value,
                    next.providerSessionId?.value,
                    ts,
                    rev,
                    sessionId.value,
                )
            }

            projections[sessionId] = next // the in-memory projection stays a PURE event-log replay
            val stored = StoredEvent(sessionId, Seq(seq), ts, source, event)
            // Fan out to live subscribers (registered under this same lock — see subscribe).
            subscribers[sessionId]?.forEach { it.trySend(stored) }
            // Signal the (control-authoritative) cache change for the events-WS. Hand-built rather than
            // emitFromRow — see that helper's KDoc for why, and keep the two in step. With no `sessions`
            // row, updateCache touched nothing, so the update carries rev 0 (nothing persisted holds
            // `rev`) — the transport does not forward row-less updates anyway.
            emitSessionUpdate(
                SessionUpdate(
                    sessionId, cacheState, next.lastSeq,
                    unread(next.lastSeq.value, readCursor), (cachedRow?.archived ?: 0L) != 0L,
                    model = cachedRow?.model, // updateCache never touches model, so the pre-transaction row is current
                    rev = if (cachedRow != null) rev else 0,
                    // updateCache touches neither column either, so the pre-transaction row is current.
                    taskRef = cachedRow?.task_ref?.let(::TaskRef),
                    projectId = cachedRow?.project_id?.let(ProjectId::parseOrNull),
                ),
            )
            Seq(seq)
        }

    override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> =
        mutex.withLock { readLocked(sessionId, fromSeq) }

    override suspend fun projectionOf(sessionId: SessionId): Projection =
        mutex.withLock { projectionLocked(sessionId) }

    override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = channelFlow {
        val relay = Channel<StoredEvent>(Channel.UNLIMITED)
        // Snapshot + register atomically against the writer: capture stored events and add the live
        // relay under the same lock append holds, so an append either lands in the snapshot or is
        // delivered live — never both, never lost.
        val snapshot = mutex.withLock {
            val last = projectionLocked(sessionId).lastSeq
            if (fromSeq.value > last.value + 1) throw StaleCursorException(sessionId, fromSeq, last)
            readLocked(sessionId, fromSeq).also {
                subscribers.getOrPut(sessionId) { mutableListOf() }.add(relay)
            }
        }
        try {
            // Emit the stored snapshot, then live appends, verifying contiguity throughout.
            var next = maxOf(fromSeq.value, 1L)
            for (e in snapshot) {
                check(e.seq.value == next) {
                    "gapped stored stream for '${sessionId.value}': expected $next, got ${e.seq.value}"
                }
                send(e)
                next++
            }
            for (e in relay) {
                if (e.seq.value < next) continue // defensive dedup across the snapshot boundary
                check(e.seq.value == next) {
                    "gapped live stream for '${sessionId.value}': expected $next, got ${e.seq.value}"
                }
                send(e)
                next++
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { subscribers[sessionId]?.remove(relay) }
            }
            relay.close()
        }
    }

    /** Number of live subscribers for a session — observability, and lets tests await registration. */
    suspend fun activeSubscribers(sessionId: SessionId): Int =
        mutex.withLock { subscribers[sessionId]?.size ?: 0 }

    // --- internals (callers hold [mutex]) ---------------------------------------------------------

    /**
     * Broadcast a [SessionUpdate] rebuilt from the session's COMMITTED row — the tail of every mutator
     * except [append], which builds the same fields by hand a few lines above. Reading back is what
     * keeps the wire and the DB in agreement when a statement rewrote a value the caller did not supply
     * (`upsert`'s max-merged `read_cursor`) or did not touch at all.
     *
     * [append]'s exemption is **not** "it would re-read what it just wrote" — so would the others. It is
     * that (a) it already holds every value: the control-authoritative `cacheState` it computed and the row
     * it read pre-transaction (whose `read_cursor` / `archived` `updateCache` never touches), and (b) it must
     * emit even when there is NO `sessions` row — the event was stored and got a real seq regardless — while
     * this helper is deliberately a silent no-op there. Edit the two together: they emit the same shape.
     *
     * `archived` comes from the row, never from a default: an archived ("done") session can still be the
     * selected one, and a live update claiming `archived=false` would un-hide it in every client. A
     * vanished row is a silent no-op, matching every mutator's "no-op if the row does not exist".
     *
     * Uses `sessions.get` directly, NOT [getSession] — [mutex] is not reentrant and the caller holds it.
     */
    private suspend fun emitFromRow(sessionId: SessionId) {
        val row = sessions.get(sessionId.value).executeAsOneOrNull() ?: return
        emitSessionUpdate(
            SessionUpdate(
                sessionId, SessionState.valueOf(row.state), Seq(row.last_seq),
                unread(row.last_seq, row.read_cursor), row.archived != 0L,
                model = row.model,
                rev = row.rev,
                taskRef = row.task_ref?.let(::TaskRef),
                projectId = row.project_id?.let(ProjectId::parseOrNull),
            ),
        )
    }

    /**
     * Publish one committed cache change to both audiences.
     *
     * The browser signal remains best-effort and non-blocking (the events-WS conflates per socket and a
     * reconnect re-baselines from a snapshot).
     * The notifier signal is lossless while subscribed, so its unbuffered [MutableSharedFlow.emit] applies
     * bounded backpressure until the constant-time edge collector receives the update. Publishing is
     * non-cancellable because the database change has already committed; cancellation must not make the
     * caller observe a failed write while silently omitting its corresponding notification transition.
     */
    private suspend fun emitSessionUpdate(update: SessionUpdate) {
        _sessionUpdates.tryEmit(update)
        withContext(NonCancellable) {
            _reliableSessionUpdates.emit(update)
        }
    }

    private fun readLocked(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> =
        events.selectFromSeq(sessionId.value, fromSeq.value) { session_id, seq, ts, _, source, payload ->
            StoredEvent(
                sessionId = SessionId(session_id),
                seq = Seq(seq),
                ts = ts,
                source = EventSource.valueOf(source),
                event = deserialize(payload),
            )
        }.executeAsList()

    /** Cached projection, or reconstruct once by replaying the session's stored events. */
    private fun projectionLocked(sessionId: SessionId): Projection =
        projections.getOrPut(sessionId) {
            replay(readLocked(sessionId, Seq(0)).map { it.event })
        }

    private fun serialize(event: AgentEvent): Pair<String, String> {
        val payload = json.encodeToString(AgentEvent.serializer(), event)
        // `type` column = the kotlinx-serialization class discriminator (== the @SerialName), pulled
        // back out of the encoded JSON so it stays queryable without a hand-maintained mapping.
        val type = json.parseToJsonElement(payload).jsonObject.getValue("type").jsonPrimitive.content
        return type to payload
    }

    private fun deserialize(payload: String): AgentEvent =
        json.decodeFromString(AgentEvent.serializer(), payload)

    private fun encodeTags(tags: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), tags)

    private fun decodeTags(text: String): List<String> =
        json.decodeFromString(ListSerializer(String.serializer()), text)

    private fun readPreferences(): UiPreferences =
        preferenceQueries.selectCurrent { basePath, groupingLevel, revision ->
            UiPreferences(basePath, groupingLevel.toInt(), revision)
        }.executeAsOne()

    private fun Sessions.toMeta(): SessionMeta = SessionMeta(
        id = SessionId(id),
        name = name,
        tags = decodeTags(tags),
        agent = agent,
        providerSessionId = provider_session_id?.let(::ProviderSessionId),
        model = model,
        cliVersion = cli_version,
        cliPath = cli_path,
        cwd = cwd,
        repository = repository,
        worktree = worktree,
        branch = branch,
        tmuxSession = tmux_session,
        paneId = pane_id?.let(::PaneId),
        state = SessionState.valueOf(state),
        stateSource = state_source?.let(EventSource::valueOf),
        lastSeq = Seq(last_seq),
        readCursor = Seq(read_cursor),
        createdAt = created_at,
        updatedAt = updated_at,
        archived = archived != 0L,
        rev = rev,
        taskRef = task_ref?.let(::TaskRef),
        // `parseOrNull`, not the constructor: [ProjectId] has a private constructor precisely so every
        // value is case-normalized on the way in, and a READ must not throw on a column somebody edited
        // by hand — an unparseable project reads as "no project", which is the honest degradation.
        projectId = project_id?.let(ProjectId::parseOrNull),
    )

    companion object {
        /** Mirror of the `UiPreferences.sq` DDL, for databases created before that table existed. */
        const val CREATE_PREFERENCES_TABLE_IF_NOT_EXISTS: String =
            "CREATE TABLE IF NOT EXISTS ui_preferences (" +
                "singleton INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1), " +
                "base_path TEXT NOT NULL, " +
                "grouping_level INTEGER NOT NULL, " +
                "revision INTEGER NOT NULL)"

        /** JSON used for the `payload` column: `type` discriminator matches [AgentEvent]'s @SerialName. */
        val DEFAULT_JSON: Json = Json {
            classDiscriminator = "type"
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /** In-memory SQLite store (tests / ephemeral). The schema is created by the driver. */
        fun inMemory(
            now: () -> Long = ::systemEpochMillis,
            json: Json = DEFAULT_JSON,
        ): SqliteEventStore = SqliteEventStore(inMemoryDriver(KotgentDatabase.Schema), json, now)

        /**
         * Store over a caller-provided driver (the daemon's file-backed driver, or a shared
         * in-memory driver to simulate a restart in tests). The caller owns schema creation.
         */
        fun using(
            driver: SqlDriver,
            now: () -> Long = ::systemEpochMillis,
            json: Json = DEFAULT_JSON,
        ): SqliteEventStore = SqliteEventStore(driver, json, now)
    }
}

/**
 * True when [table] already has a column named [column], per `PRAGMA table_info` (row layout:
 * `cid, name, type, notnull, dflt_value, pk` — hence index 1). An unknown table yields no rows, i.e.
 * false. The names are interpolated because a PRAGMA takes no bind parameters; both call sites pass
 * literals from this file, never user input.
 */
private fun SqlDriver.hasColumn(table: String, column: String): Boolean =
    executeQuery(
        identifier = null,
        sql = "PRAGMA table_info($table)",
        mapper = { cursor ->
            var found = false
            while (!found && cursor.next().value) found = cursor.getString(1) == column
            QueryResult.Value(found)
        },
        parameters = 0,
    ).value

/** Default wall-clock for event timestamps: epoch millis. Injectable so tests stay deterministic. */
@OptIn(ExperimentalTime::class)
private fun systemEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
