package io.kotgent.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Host-free domain tests (Task 5, TDD): @Serializable round-trip for every v1 [AgentEvent]
 * subtype, value-class id invariants (enforced on construction AND on decode), and the
 * [SessionState] groupings / [SessionState.needsAttention] predicate.
 */
class DomainTest {

    private val json = Json

    // A valid preallocated UUID reused across cases.
    private val uuid = "11111111-2222-3333-4444-555555555555"

    /**
     * One instance of EACH v1 subtype. The size guard makes forgetting a subtype (or slipping a
     * backlog `Question*` in) a test failure, and the distinct-class check keeps the list honest.
     */
    private val allEvents: List<AgentEvent> = listOf(
        AgentEvent.TurnStarted,
        AgentEvent.TurnCompleted,
        AgentEvent.ApprovalRequested(approvalId = "appr-1"),
        AgentEvent.ApprovalResolved(approvalId = "appr-1", approved = true),
        AgentEvent.ToolCall(name = "Bash"),
        AgentEvent.Exited(code = 0),
        AgentEvent.SessionBound(ProviderSessionId(uuid)),
    )

    // ---- AgentEvent @Serializable round-trip (every v1 subtype) ----

    @Test
    fun everyAgentEventSubtypeRoundTripsThroughJson() {
        assertEquals(7, allEvents.size, "expected exactly the 7 v1 AgentEvent subtypes")
        assertEquals(
            allEvents.size,
            allEvents.map { it::class }.toSet().size,
            "each sample must be a distinct subtype",
        )
        for (event in allEvents) {
            val encoded = json.encodeToString<AgentEvent>(event)
            val decoded = json.decodeFromString<AgentEvent>(encoded)
            assertEquals(event, decoded, "round-trip must preserve $event (json=$encoded)")
            assertEquals(event::class, decoded::class, "round-trip must preserve the subtype of $event")
        }
    }

    @Test
    fun agentEventDiscriminatorsAndValueIdsUseStableWireShape() {
        // The polymorphic `type` discriminator is the `events.type` column value.
        assertTrue(
            json.encodeToString<AgentEvent>(AgentEvent.TurnStarted).contains("\"turn_started\""),
            "TurnStarted must carry the snake_case discriminator",
        )
        assertTrue(
            json.encodeToString<AgentEvent>(AgentEvent.SessionBound(ProviderSessionId(uuid)))
                .contains("\"session_bound\""),
            "SessionBound must carry the snake_case discriminator",
        )
        // A value-class id serializes as its bare underlying primitive (no wrapper object).
        val bound = json.encodeToString<AgentEvent>(AgentEvent.SessionBound(ProviderSessionId(uuid)))
        assertTrue(bound.contains("\"providerSessionId\":\"$uuid\""), "provider id must be a bare string, was $bound")
        val exited = json.encodeToString<AgentEvent>(AgentEvent.Exited(3))
        assertTrue(exited.contains("\"code\":3"), "Exited.code must encode as a bare int, was $exited")
    }

    @Test
    fun sessionBoundRoundTripPreservesProviderId() {
        val id = ProviderSessionId("abcdef01-2345-6789-abcd-ef0123456789")
        val decoded = json.decodeFromString<AgentEvent>(json.encodeToString<AgentEvent>(AgentEvent.SessionBound(id)))
        assertIs<AgentEvent.SessionBound>(decoded)
        assertEquals(id, decoded.providerSessionId)
    }

    @Test
    fun eventSourceRoundTripsByLowerCaseName() {
        for (source in EventSource.entries) {
            assertEquals(source, json.decodeFromString<EventSource>(json.encodeToString<EventSource>(source)))
        }
        assertEquals("\"hook\"", json.encodeToString<EventSource>(EventSource.hook))
        assertEquals("\"appserver\"", json.encodeToString<EventSource>(EventSource.appserver))
    }

    /** Invalid ids must be rejected on the wire too: the value-class init runs on decode. */
    @Test
    fun decodingAMalformedProviderIdIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<AgentEvent>("""{"type":"session_bound","providerSessionId":"has spaces"}""")
        }
        // A non-UUID id is NOT malformed — junie's ids look like this, and the wire must carry them.
        val junie = json.decodeFromString<AgentEvent>(
            """{"type":"session_bound","providerSessionId":"session-260730-015553-1j1h"}""",
        )
        assertIs<AgentEvent.SessionBound>(junie)
        assertEquals("session-260730-015553-1j1h", junie.providerSessionId.value)
    }

    // ---- value-class id invariants ----

    @Test
    fun sessionIdRejectsBlank() {
        assertFailsWith<IllegalArgumentException> { SessionId("") }
        assertFailsWith<IllegalArgumentException> { SessionId("   ") }
        assertEquals("kt-abc123", SessionId("kt-abc123").value)
    }

    @Test
    fun seqRejectsNegativeAndIsOrdered() {
        assertFailsWith<IllegalArgumentException> { Seq(-1) }
        assertEquals(0L, Seq(0).value)
        assertTrue(Seq(1) < Seq(2))
        assertTrue(Seq(5) >= Seq(5))
        assertEquals(Seq(6), Seq(5).next())
    }

    @Test
    fun providerSessionIdAcceptsEveryProvidersIdShape() {
        // claude/codex mint UUIDs; junie mints `session-<ts>-<suffix>`. Both must construct.
        assertEquals(uuid, ProviderSessionId(uuid).value)
        assertEquals("session-260730-015553-1j1h", ProviderSessionId("session-260730-015553-1j1h").value)
        assertEquals("a.b_c-1", ProviderSessionId("a.b_c-1").value)
    }

    @Test
    fun providerSessionIdRejectsWhatIsUnsafeInAPathArgvOrUrl() {
        assertFailsWith<IllegalArgumentException> { ProviderSessionId("") }
        assertFailsWith<IllegalArgumentException> { ProviderSessionId("   ") }
        assertFailsWith<IllegalArgumentException> { ProviderSessionId("has spaces") }
        assertFailsWith<IllegalArgumentException> { ProviderSessionId("slash/es") }
        assertFailsWith<IllegalArgumentException> { ProviderSessionId("quote'y") }
        assertFailsWith<IllegalArgumentException> { ProviderSessionId("pipe|d") }
        // `..` is a path component that escapes its parent, and a leading `-` reads as a CLI flag.
        assertFailsWith<IllegalArgumentException> { ProviderSessionId("..") }
        assertFailsWith<IllegalArgumentException> { ProviderSessionId(".hidden") }
        assertFailsWith<IllegalArgumentException> { ProviderSessionId("--resume") }
        assertFailsWith<IllegalArgumentException> { ProviderSessionId("x".repeat(ProviderSessionId.MAX_LENGTH + 1)) }
        assertEquals("x".repeat(128), ProviderSessionId("x".repeat(ProviderSessionId.MAX_LENGTH)).value)
    }

    @Test
    fun isCanonicalUuidIsTheBoundaryCheckForUuidProviders() {
        assertTrue(isCanonicalUuid(uuid))
        assertTrue(isCanonicalUuid(uuid.uppercase()), "hex case is insignificant in a UUID")
        assertFalse(isCanonicalUuid("session-260730-015553-1j1h"))
        assertFalse(isCanonicalUuid("not-a-uuid"))
        // wrong length in the final group
        assertFalse(isCanonicalUuid("11111111-2222-3333-4444-55555555555"))
        // non-hex character
        assertFalse(isCanonicalUuid("g1111111-2222-3333-4444-555555555555"))
        assertFalse(isCanonicalUuid(""))
    }

    @Test
    fun paneIdRequiresTmuxFormat() {
        assertFailsWith<IllegalArgumentException> { PaneId("3") }
        assertFailsWith<IllegalArgumentException> { PaneId("%") }
        assertFailsWith<IllegalArgumentException> { PaneId("%x") }
        assertFailsWith<IllegalArgumentException> { PaneId("") }
        assertEquals("%3", PaneId("%3").value)
        assertEquals("%42", PaneId("%42").value)
    }

    // ---- SessionState groupings / needsAttention ----

    @Test
    fun sessionStatesPartitionIntoAliveAndDead() {
        assertEquals(7, SessionState.entries.size, "there must be exactly 7 states")
        assertEquals(
            setOf(SessionState.running, SessionState.needs_approval, SessionState.needs_answer, SessionState.ready),
            SessionState.ALIVE,
        )
        assertEquals(
            setOf(SessionState.stopped, SessionState.crashed, SessionState.resumable),
            SessionState.DEAD,
        )
        // exhaustive and disjoint
        assertEquals(SessionState.entries.toSet(), SessionState.ALIVE + SessionState.DEAD)
        assertTrue((SessionState.ALIVE intersect SessionState.DEAD).isEmpty(), "alive/dead must be disjoint")
        for (s in SessionState.entries) {
            assertEquals(s in SessionState.ALIVE, s.isAlive, "$s.isAlive")
            assertEquals(s in SessionState.DEAD, s.isDead, "$s.isDead")
            assertTrue(s.isAlive != s.isDead, "$s must be exactly one of alive/dead")
        }
    }

    @Test
    fun needsAttentionIsExactlyApprovalAndAnswer() {
        assertEquals(setOf(SessionState.needs_approval, SessionState.needs_answer), SessionState.NEEDS_ATTENTION)
        for (s in SessionState.entries) {
            val expected = s == SessionState.needs_approval || s == SessionState.needs_answer
            assertEquals(expected, s.needsAttention, "$s.needsAttention")
        }
        assertTrue(SessionState.NEEDS_ATTENTION.all { it.isAlive }, "needs-attention states are all alive")
    }

    // ---- SessionMeta shape ----

    @Test
    fun sessionMetaDefaultsUnknownFieldsAndCopiesCleanly() {
        val meta = SessionMeta(
            id = SessionId("kt-abc123"),
            name = "demo",
            agent = "claude",
            cwd = "/work/repo",
            tmuxSession = "kt-abc123",
            state = SessionState.running,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )
        assertEquals(null, meta.providerSessionId, "provider id unknown until SessionBound")
        assertEquals(null, meta.paneId, "pane id unknown until new-session")
        assertEquals(emptyList(), meta.tags)
        assertEquals(Seq(0), meta.lastSeq)
        assertEquals(Seq(0), meta.readCursor)
        assertEquals(null, meta.stateSource)

        val bound = meta.copy(
            providerSessionId = ProviderSessionId(uuid),
            paneId = PaneId("%1"),
            state = SessionState.needs_approval,
            stateSource = EventSource.hook,
            lastSeq = Seq(4),
        )
        assertEquals(ProviderSessionId(uuid), bound.providerSessionId)
        assertEquals(PaneId("%1"), bound.paneId)
        assertTrue(bound.state.needsAttention)
        assertEquals(Seq(4), bound.lastSeq)
        // copy must not mutate the original
        assertEquals(null, meta.providerSessionId)
    }
}
