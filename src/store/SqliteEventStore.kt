package io.kotgent.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.Projection
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
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
import kotlinx.coroutines.flow.SharedFlow
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
) : EventStore {

    private val db: KotgentDatabase = KotgentDatabase(driver)
    private val events get() = db.eventsQueries
    private val sessions get() = db.sessionsQueries

    /** Serializes all writes (single writer) and guards the in-memory maps below. */
    private val mutex = Mutex()

    /** Per-session cached projection (reducer read-model); reconstructed lazily by [replay]. */
    private val projections = HashMap<SessionId, Projection>()

    /** Live relays per session; the writer fans committed events out to these after each append. */
    private val subscribers = HashMap<SessionId, MutableList<SendChannel<StoredEvent>>>()

    /**
     * Hot cross-session cache-change signal (Task 14 events-WS). Non-replaying so late subscribers do
     * not re-see history (the transport pairs it with a `listSessions` snapshot); buffered + DROP_OLDEST
     * so a burst of appends never suspends the single writer holding [mutex]. Emitted with [tryEmit]
     * (non-suspending) from [append] / [upsertSession] under the lock.
     *
     * A slow consumer that falls far behind can still miss an intermediate update; the events-WS guards
     * against that with a periodic full resync from the store (see [io.kotgent.transport.eventsWs]), so
     * a dropped notification can never leave the UI stuck on a stale "needs attention". The buffer is
     * generous (1024) to make even transient drops unlikely between resyncs.
     */
    private val _sessionUpdates = MutableSharedFlow<SessionUpdate>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val sessionUpdates: SharedFlow<SessionUpdate> get() = _sessionUpdates

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
        // Instead add the column here: on a fresh DB `create()` already added it, so this ALTER fails with
        // "duplicate column name" and is swallowed; on a pre-`archived` DB it adds it. `ALTER … ADD COLUMN`
        // returns no rows, so use execute(), not executeQuery().
        runCatching {
            driver.execute(null, "ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0", 0)
        }
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
        )
        _sessionUpdates.tryEmit(
            SessionUpdate(
                meta.id, meta.state, meta.lastSeq,
                unread(meta.lastSeq.value, meta.readCursor.value), meta.archived,
            ),
        )
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
        sessions.updateControlState(state.name, stateSource.name, paneId?.value, updatedAt, sessionId.value)
        val row = sessions.get(sessionId.value).executeAsOneOrNull() ?: return@withLock
        _sessionUpdates.tryEmit(
            SessionUpdate(sessionId, state, Seq(row.last_seq), unread(row.last_seq, row.read_cursor), row.archived != 0L),
        )
    }

    override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long): Unit = mutex.withLock {
        sessions.setArchived(if (archived) 1L else 0L, updatedAt, sessionId.value)
        val row = sessions.get(sessionId.value).executeAsOneOrNull() ?: return@withLock
        _sessionUpdates.tryEmit(
            SessionUpdate(
                sessionId, SessionState.valueOf(row.state), Seq(row.last_seq),
                unread(row.last_seq, row.read_cursor), row.archived != 0L,
            ),
        )
    }

    override suspend fun setModel(sessionId: SessionId, model: String, updatedAt: Long): Unit = mutex.withLock {
        sessions.setModel(model, updatedAt, sessionId.value)
        val row = sessions.get(sessionId.value).executeAsOneOrNull() ?: return@withLock
        // The model itself rides the periodic /events resync (SessionMeta.toUpdateDto); this signal just
        // keeps state/unread fresh (it carries archived, not model).
        _sessionUpdates.tryEmit(
            SessionUpdate(
                sessionId, SessionState.valueOf(row.state), Seq(row.last_seq),
                unread(row.last_seq, row.read_cursor), row.archived != 0L,
            ),
        )
    }

    override suspend fun getSession(sessionId: SessionId): SessionMeta? = mutex.withLock {
        sessions.get(sessionId.value).executeAsOneOrNull()?.toMeta()
    }

    override suspend fun listSessions(): List<SessionMeta> = mutex.withLock {
        sessions.list().executeAsList().map { it.toMeta() }
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
            db.transaction {
                events.insert(sessionId.value, seq, ts, type, source.name, payload)
                sessions.updateCache(
                    cacheState.name,
                    source.name,
                    next.lastSeq.value,
                    next.providerSessionId?.value,
                    ts,
                    sessionId.value,
                )
            }

            projections[sessionId] = next // the in-memory projection stays a PURE event-log replay
            val stored = StoredEvent(sessionId, Seq(seq), ts, source, event)
            // Fan out to live subscribers (registered under this same lock — see subscribe).
            subscribers[sessionId]?.forEach { it.trySend(stored) }
            // Signal the (control-authoritative) cache change for the events-WS.
            _sessionUpdates.tryEmit(
                SessionUpdate(
                    sessionId, cacheState, next.lastSeq,
                    unread(next.lastSeq.value, readCursor), (cachedRow?.archived ?: 0L) != 0L,
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
    )

    companion object {
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

/** Default wall-clock for event timestamps: epoch millis. Injectable so tests stay deterministic. */
@OptIn(ExperimentalTime::class)
private fun systemEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
