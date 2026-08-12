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

class SqliteEventStore private constructor(
    driver: SqlDriver,
    private val json: Json,
    private val now: () -> Long,
) : EventStore, PreferencesStore {

    private val db: KotgentDatabase = KotgentDatabase(driver)
    private val events get() = db.eventsQueries
    private val sessions get() = db.sessionsQueries
    private val preferenceQueries get() = db.uiPreferencesQueries

    private val mutex = Mutex()

    private var revCounter: Long = 0

    private val _preferences: MutableStateFlow<UiPreferences>
    override val preferences: StateFlow<UiPreferences> get() = _preferences

    private val projections = HashMap<SessionId, Projection>()

    private val subscribers = HashMap<SessionId, MutableList<SendChannel<StoredEvent>>>()

    private val _sessionUpdates = MutableSharedFlow<SessionUpdate>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val sessionUpdates: SharedFlow<SessionUpdate> get() = _sessionUpdates

    private val _reliableSessionUpdates = MutableSharedFlow<SessionUpdate>()
    override val reliableSessionUpdates: SharedFlow<SessionUpdate> get() = _reliableSessionUpdates

    init {
        // journal_mode returns a row, so SQLiter requires executeQuery rather than execute.
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA journal_mode=WAL",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Unit
            },
            parameters = 0,
        )
        // SQLDelight migrations are disabled; the guard avoids SQLiter logging duplicate-column errors.
        if (!driver.hasColumn("sessions", "archived")) {
            driver.execute(null, "ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0", 0)
        }
        if (!driver.hasColumn("sessions", "rev")) {
            driver.execute(null, "ALTER TABLE sessions ADD COLUMN rev INTEGER NOT NULL DEFAULT 0", 0)
        }
        if (!driver.hasColumn("sessions", "task_ref")) {
            driver.execute(null, "ALTER TABLE sessions ADD COLUMN task_ref TEXT", 0)
        }
        if (!driver.hasColumn("sessions", "project_id")) {
            driver.execute(null, "ALTER TABLE sessions ADD COLUMN project_id TEXT", 0)
        }
        revCounter = sessions.maxRev().executeAsOne()

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
            ++revCounter,
            meta.taskRef?.value,
            meta.projectId?.value,
        )
        emitFromRow(meta.id)
    }

    override suspend fun updateSessionState(
        sessionId: SessionId,
        state: SessionState,
        stateSource: EventSource,
        paneId: PaneId?,
        updatedAt: Long,
    ): Unit = mutex.withLock {
        sessions.updateControlState(state.name, stateSource.name, paneId?.value, updatedAt, ++revCounter, sessionId.value)
        emitFromRow(sessionId)
    }

    override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long): Unit = mutex.withLock {
        sessions.setArchived(if (archived) 1L else 0L, updatedAt, ++revCounter, sessionId.value)
        emitFromRow(sessionId)
    }

    override suspend fun setModel(sessionId: SessionId, model: String?, updatedAt: Long): Unit = mutex.withLock {
        sessions.setModel(model, updatedAt, ++revCounter, sessionId.value)
        emitFromRow(sessionId)
    }

    override suspend fun setModelForProvider(
        sessionId: SessionId,
        providerSessionId: ProviderSessionId,
        model: String,
        updatedAt: Long,
    ): Boolean = mutex.withLock {
        sessions.setModelForProvider(model, updatedAt, ++revCounter, sessionId.value, providerSessionId.value)
        val applied = sessions.get(sessionId.value).executeAsOneOrNull()
            ?.provider_session_id == providerSessionId.value
        if (applied) emitFromRow(sessionId)
        applied
    }

    override suspend fun setTaskRef(sessionId: SessionId, taskRef: TaskRef?, updatedAt: Long): Unit =
        mutex.withLock {
            sessions.setTaskRef(taskRef?.value, updatedAt, ++revCounter, sessionId.value)
            emitFromRow(sessionId)
        }

    override suspend fun clearTaskRefIf(
        sessionId: SessionId,
        expectedRef: TaskRef,
        updatedAt: Long,
    ): Boolean = mutex.withLock {
        val cleared = sessions.clearTaskRefIf(updatedAt, ++revCounter, sessionId.value, expectedRef.value)
            .value > 0L
        if (cleared) emitFromRow(sessionId)
        cleared
    }

    override suspend fun setProjectId(sessionId: SessionId, projectId: ProjectId?, updatedAt: Long): Unit =
        mutex.withLock {
            sessions.setProjectId(projectId?.value, updatedAt, ++revCounter, sessionId.value)
            emitFromRow(sessionId)
        }

    override suspend fun sessionsHoldingTask(taskRef: TaskRef): List<SessionMeta> = mutex.withLock {
        sessions.sessionsHoldingTask(taskRef.value).executeAsList().map { it.toMeta() }
    }

    override suspend fun markRead(sessionId: SessionId, seq: Seq): Unit = mutex.withLock {
        sessions.setReadCursor(seq.value, ++revCounter, sessionId.value)
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
            preferenceQueries.save(basePath, groupingLevel.toLong())
            readPreferences().also { _preferences.value = it }
        }

    override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq =
        mutex.withLock {
            val prior = projectionLocked(sessionId)
            val seq = events.nextSeq(sessionId.value).executeAsOne()
            val next = reduce(prior, event)
            check(next.lastSeq.value == seq) {
                "seq divergence for '${sessionId.value}': reducer=${next.lastSeq.value} db=$seq"
            }
            val ts = now()
            val (type, payload) = serialize(event)

            val cachedRow = sessions.get(sessionId.value).executeAsOneOrNull()
            val cachedState = cachedRow?.state?.let { SessionState.valueOf(it) }
            // A late hook may advance the log but must not resurrect a cache-authoritative dead session.
            val cacheState = when {
                cachedState == null -> next.state
                cachedState.isDead -> cachedState
                else -> reduce(prior.copy(state = cachedState), event).state
            }
            val readCursor = cachedRow?.read_cursor ?: 0L

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

            projections[sessionId] = next
            val stored = StoredEvent(sessionId, Seq(seq), ts, source, event)
            subscribers[sessionId]?.forEach { it.trySend(stored) }
            emitSessionUpdate(
                SessionUpdate(
                    sessionId, cacheState, next.lastSeq,
                    unread(next.lastSeq.value, readCursor), (cachedRow?.archived ?: 0L) != 0L,
                    model = cachedRow?.model,
                    rev = if (cachedRow != null) rev else 0,
                    taskRef = cachedRow?.task_ref?.let(TaskRef::parseOrNull),
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
        // Register the relay under the same lock as the snapshot so no committed event falls between them.
        val snapshot = mutex.withLock {
            val last = projectionLocked(sessionId).lastSeq
            if (fromSeq.value > last.value + 1) throw StaleCursorException(sessionId, fromSeq, last)
            readLocked(sessionId, fromSeq).also {
                subscribers.getOrPut(sessionId) { mutableListOf() }.add(relay)
            }
        }
        try {
            var next = maxOf(fromSeq.value, 1L)
            for (e in snapshot) {
                check(e.seq.value == next) {
                    "gapped stored stream for '${sessionId.value}': expected $next, got ${e.seq.value}"
                }
                send(e)
                next++
            }
            for (e in relay) {
                if (e.seq.value < next) continue
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

    suspend fun activeSubscribers(sessionId: SessionId): Int =
        mutex.withLock { subscribers[sessionId]?.size ?: 0 }


    private suspend fun emitFromRow(sessionId: SessionId) {
        val row = sessions.get(sessionId.value).executeAsOneOrNull() ?: return
        emitSessionUpdate(
            SessionUpdate(
                sessionId, SessionState.valueOf(row.state), Seq(row.last_seq),
                unread(row.last_seq, row.read_cursor), row.archived != 0L,
                model = row.model,
                rev = row.rev,
                taskRef = row.task_ref?.let(TaskRef::parseOrNull),
                projectId = row.project_id?.let(ProjectId::parseOrNull),
            ),
        )
    }

    private suspend fun emitSessionUpdate(update: SessionUpdate) {
        _sessionUpdates.tryEmit(update)
        // Notification edge tracking cannot tolerate DROP_OLDEST; cancellation must not split committed order.
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

    private fun projectionLocked(sessionId: SessionId): Projection =
        projections.getOrPut(sessionId) {
            replay(readLocked(sessionId, Seq(0)).map { it.event })
        }

    private fun serialize(event: AgentEvent): Pair<String, String> {
        val payload = json.encodeToString(AgentEvent.serializer(), event)
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
        taskRef = task_ref?.let(TaskRef::parseOrNull),
        projectId = project_id?.let(ProjectId::parseOrNull),
    )

    companion object {
        const val CREATE_PREFERENCES_TABLE_IF_NOT_EXISTS: String =
            "CREATE TABLE IF NOT EXISTS ui_preferences (" +
                "singleton INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1), " +
                "base_path TEXT NOT NULL, " +
                "grouping_level INTEGER NOT NULL, " +
                "revision INTEGER NOT NULL)"

        val DEFAULT_JSON: Json = Json {
            classDiscriminator = "type"
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun inMemory(
            now: () -> Long = ::systemEpochMillis,
            json: Json = DEFAULT_JSON,
        ): SqliteEventStore = SqliteEventStore(inMemoryDriver(KotgentDatabase.Schema), json, now)

        fun using(
            driver: SqlDriver,
            now: () -> Long = ::systemEpochMillis,
            json: Json = DEFAULT_JSON,
        ): SqliteEventStore = SqliteEventStore(driver, json, now)
    }
}

@OptIn(ExperimentalTime::class)
private fun systemEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
