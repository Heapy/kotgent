package io.kotgent.daemon

import io.kotgent.core.EventSource
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.store.SqliteEventStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import platform.posix.usleep
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
// Filesystem fixtures use unique TMPDIR trees and never read the developer's ~/.junie.
class JunieSessionScanTest {

    private val files = mutableListOf<String>()
    private val dirs = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        for (f in files) unlink(f)
        for (d in dirs.asReversed()) rmdir(d)
    }

    private fun id(suffix: String) = ProviderSessionId("session-260730-0155$suffix")


    @Test
    fun anIndexLineYieldsItsIdProjectDirAndCreatedAt() {
        val line = """{"sessionId":"session-260730-015553-1j1h","createdAt":1785365753000,""" +
            """"updatedAt":1785407489000,"projectDir":"/Users/yoda/dev/pet/kotgent","taskName":"Add Junie"}"""
        val record = junieIndexRecord(line)!!
        assertEquals("session-260730-015553-1j1h", record.sessionId)
        assertEquals("/Users/yoda/dev/pet/kotgent", record.projectDir)
        assertEquals(1785365753000L, record.createdAtMillis)
    }

    @Test
    fun anUnusableIndexLineIsSkippedRatherThanFatal() {
        assertNull(junieIndexRecord("""sionId":"session-260730-015553-1j1h","createdAt":178536575300"""))
        assertNull(junieIndexRecord(""), "the blank line every JSONL file ends with")
        assertNull(junieIndexRecord("""{"createdAt":1785365753000}"""), "no sessionId -> no record")
        assertNull(
            junieIndexRecord("""{"sessionId":"../../etc/passwd","projectDir":"/x"}"""),
            "an id kotgent could not put in a path is refused at the parse boundary",
        )
    }

    @Test
    fun anIndexLineWithoutAProjectDirIsStillARecord() {
        val record = junieIndexRecord("""{"sessionId":"session-260730-015553-1j1h","createdAt":1}""")!!
        assertNull(record.projectDir)
        assertEquals(1L, record.createdAtMillis)
    }

    @Test
    fun aProjectDirWithEscapesRoundTrips() {
        val record = junieIndexRecord(
            """{"sessionId":"session-260730-015553-1j1h","projectDir":"/work/we\"ird\\path"}""",
        )!!
        assertEquals("""/work/we"ird\path""", record.projectDir)
    }


    @Test
    fun aSurvivingSessionDirectoryIsResumable() {
        val junieDir = makeJunieDir()
        val mine = id("53-1j1h")
        placeSession(junieDir, mine)
        assertTrue(JunieSessionScan(junieDir).hasSession(mine))
    }

    @Test
    fun aStaleIndexRowForAPrunedSessionIsNotResumable() {
        val junieDir = makeJunieDir()
        val pruned = id("53-1j1h")
        placeIndex(junieDir, indexLine(pruned, "/work/repo"))
        assertFalse(JunieSessionScan(junieDir).hasSession(pruned))
    }

    @Test
    fun aSessionDirectoryWithoutItsEventStreamIsNotASession() {
        val junieDir = makeJunieDir()
        val empty = id("53-1j1h")
        makeDir("$junieDir/sessions")
        makeDir("$junieDir/sessions/${empty.value}")
        assertFalse(JunieSessionScan(junieDir).hasSession(empty))
    }

    @Test
    fun anAbsentJunieHomeDegradesToNotResumable() {
        val scan = JunieSessionScan("/nonexistent/kotgent-junie-home")
        assertFalse(scan.hasSession(id("53-1j1h")))
        assertNull(scan.cwdOf(id("53-1j1h")))
        assertNull(scan.modelOf(id("53-1j1h")))
        assertNull(scan.discoverSessionId("/work/repo", 0L))
    }


    @Test
    fun discoveryFindsASessionWithNoIndexRowYet() {
        val junieDir = makeJunieDir()
        val mine = id("53-1j1h")
        placeSession(junieDir, mine)
        assertEquals(mine, JunieSessionScan(junieDir).discoverSessionId("/work/repo", pastThreshold()))
    }

    @Test
    fun discoveryIgnoresSessionsCreatedBeforeTheLaunch() {
        val junieDir = makeJunieDir()
        placeSession(junieDir, id("53-1j1h"))
        assertNull(
            JunieSessionScan(junieDir).discoverSessionId("/work/repo", futureThreshold()),
            "nothing was created after the threshold",
        )
    }

    @Test
    fun discoveryExcludesASessionAttributableToADifferentProjectDir() {
        val junieDir = makeJunieDir()
        val neighbour = id("53-1j1h")
        placeSession(junieDir, neighbour)
        placeIndex(junieDir, indexLine(neighbour, "/work/OTHER"))
        assertNull(
            JunieSessionScan(junieDir).discoverSessionId("/work/repo", pastThreshold()),
            "an index row naming a different projectDir positively excludes the candidate",
        )
    }

    @Test
    fun discoveryAcceptsASessionWhoseIndexRowNamesThisCwd() {
        val junieDir = makeJunieDir()
        val mine = id("53-1j1h")
        placeSession(junieDir, mine)
        placeIndex(junieDir, indexLine(mine, "/work/repo"))
        assertEquals(mine, JunieSessionScan(junieDir).discoverSessionId("/work/repo", pastThreshold()))
    }

    @Test
    fun discoveryPrefersTheNewestCandidate() {
        val junieDir = makeJunieDir()
        val older = id("30-1uhf")
        val newer = id("53-1j1h")
        placeSession(junieDir, older)
        usleep(50_000u)
        placeSession(junieDir, newer)
        assertEquals(newer, JunieSessionScan(junieDir).discoverSessionId("/work/repo", pastThreshold()))
    }

    @Test
    fun discoverySkipsANeighbourAndStillFindsMine() {
        val junieDir = makeJunieDir()
        val mine = id("30-1uhf")
        val neighbour = id("53-1j1h")
        placeSession(junieDir, mine)
        usleep(50_000u)
        placeSession(junieDir, neighbour)
        placeIndex(junieDir, indexLine(neighbour, "/work/OTHER"))
        assertEquals(mine, JunieSessionScan(junieDir).discoverSessionId("/work/repo", pastThreshold()))
    }

    @Test
    fun discoveryIgnoresANonSessionDirectory() {
        val junieDir = makeJunieDir()
        makeDir("$junieDir/sessions")
        makeDir("$junieDir/sessions/logs")
        writeFile("$junieDir/sessions/logs/events.jsonl", "{}\n")
        assertNull(JunieSessionScan(junieDir).discoverSessionId("/work/repo", pastThreshold()))
    }


    @Test
    fun cwdOfReadsTheRecordedProjectDir() {
        val junieDir = makeJunieDir()
        val mine = id("53-1j1h")
        placeSession(junieDir, mine)
        placeIndex(junieDir, indexLine(mine, "/work/repo"))
        assertEquals("/work/repo", JunieSessionScan(junieDir).cwdOf(mine))
    }

    @Test
    fun cwdOfIsNullWithoutAnIndexRowOrWithoutTheSessionOnDisk() {
        val junieDir = makeJunieDir()
        val noRow = id("30-1uhf")
        val pruned = id("53-1j1h")
        placeSession(junieDir, noRow)
        placeIndex(junieDir, indexLine(pruned, "/work/gone"))
        val scan = JunieSessionScan(junieDir)
        assertNull(scan.cwdOf(noRow), "only the index records a project dir")
        assertNull(scan.cwdOf(pruned), "a pruned session must not be importable")
    }

    @Test
    fun theNewestIndexRowForAnIdWins() {
        val junieDir = makeJunieDir()
        val mine = id("53-1j1h")
        placeSession(junieDir, mine)
        placeIndex(junieDir, indexLine(mine, "/work/OLD") + indexLine(mine, "/work/NEW"))
        assertEquals("/work/NEW", JunieSessionScan(junieDir).cwdOf(mine))
    }


    @Test
    fun modelOfPicksTheDominantModelOverTheHelperModels() {
        val junieDir = makeJunieDir()
        val mine = id("53-1j1h")
        placeSession(
            junieDir,
            mine,
            events = modelUsageLine("claude-haiku-4-5-20251001") +
                modelUsageLine("gpt-4.1-mini-2025-04-14") +
                modelUsageLine("claude-fable-5") +
                modelUsageLine("claude-fable-5") +
                modelUsageLine("gpt-5.4-nano") +
                modelUsageLine("claude-fable-5"),
        )
        assertEquals("claude-fable-5", JunieSessionScan(junieDir).modelOf(mine))
    }

    @Test
    fun modelOfIsNullBeforeTheSessionTakesATurn() {
        val junieDir = makeJunieDir()
        val mine = id("53-1j1h")
        placeSession(junieDir, mine)
        assertNull(JunieSessionScan(junieDir).modelOf(mine))
    }

    @Test
    fun modelOfIsKeyedByIdSoANeighbourCannotAnswer() {
        val junieDir = makeJunieDir()
        val mine = id("30-1uhf")
        val neighbour = id("53-1j1h")
        placeSession(junieDir, mine)
        placeSession(junieDir, neighbour, events = modelUsageLine("gpt-6").repeat(5))
        assertNull(
            JunieSessionScan(junieDir).modelOf(mine),
            "a busier neighbour session must never answer for this one",
        )
    }


    @Test
    fun captureJunieModelOncePersistsTheIdKeyedModel() = runBlocking {
        withTimeout(20_000) {
            val junieDir = makeJunieDir()
            val mine = id("53-1j1h")
            placeSession(junieDir, mine, events = modelUsageLine("claude-fable-5").repeat(3))
            val store = SqliteEventStore.inMemory(now = { 42L })
            val meta = launchMeta(providerSessionId = mine)
            store.upsertSession(meta)

            assertTrue(captureJunieModelOnce(store, JunieSessionScan(junieDir), meta, now = { 43L }))
            assertEquals("claude-fable-5", store.getSession(meta.id)!!.model)
        }
    }

    @Test
    fun captureJunieModelOncePersistsNothingWhileTheIdIsUnknown() = runBlocking {
        withTimeout(20_000) {
            val junieDir = makeJunieDir()
            placeSession(junieDir, id("53-1j1h"), events = modelUsageLine("gpt-6").repeat(3))
            val store = SqliteEventStore.inMemory(now = { 42L })
            val meta = launchMeta(providerSessionId = null)
            store.upsertSession(meta)
            val scan = JunieSessionScan(junieDir)

            assertFalse(captureJunieModelOnce(store, scan, meta, now = { 43L }))
            assertNull(store.getSession(meta.id)!!.model, "an id-less attempt persists nothing")

            store.upsertSession(meta.copy(providerSessionId = id("53-1j1h")))
            assertTrue(captureJunieModelOnce(store, scan, meta, now = { 44L }))
            assertEquals("gpt-6", store.getSession(meta.id)!!.model)
        }
    }

    @Test
    fun captureJunieModelOnceCannotRacePastARebindClear() = runBlocking {
        withTimeout(20_000) {
            val junieDir = makeJunieDir()
            val displaced = id("30-1uhf")
            val authoritative = id("53-1j1h")
            placeSession(junieDir, displaced, events = modelUsageLine("gpt-6").repeat(3))
            val store = SqliteEventStore.inMemory(now = { 42L })
            val meta = launchMeta(providerSessionId = displaced)
            store.upsertSession(meta)
            store.upsertSession(meta.copy(providerSessionId = authoritative))

            assertFalse(
                store.setModelForProvider(meta.id, displaced, "gpt-6", 43L),
                "a write keyed by the displaced id hits zero rows",
            )
            assertNull(store.getSession(meta.id)!!.model)
        }
    }


    @Test
    fun theDefaultHomeIsTheJunieHomeConvention() {
        val dir = defaultJunieDir()
        assertTrue(dir.isNotEmpty())
        assertFalse(dir.endsWith("/"), "trailing slashes are trimmed so path joins stay single-slashed")
        if (getenv("JUNIE_HOME") == null) {
            assertTrue(dir.endsWith(".junie"), "without \$JUNIE_HOME the default is ~/.junie: $dir")
        }
    }


    private val mode0700: Int get() = S_IRUSR or S_IWUSR or S_IXUSR

    private fun makeJunieDir(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val base = makeDir("$tmp/kotgent-junie-scan-${getpid()}-${counter++}")
        return makeDir("$base/.junie")
    }

    private fun makeDir(path: String): String {
        mkdir(path, mode0700.convert())
        if (!dirs.contains(path)) dirs += path
        return path
    }

    private fun placeSession(
        junieDir: String,
        id: ProviderSessionId,
        events: String = """{"kind":"SystemMessageEvent","text":"hi","timestampMs":1785000000000}""" + "\n",
    ) {
        makeDir("$junieDir/sessions")
        val session = makeDir("$junieDir/sessions/${id.value}")
        writeFile("$session/events.jsonl", events)
    }

    private fun placeIndex(junieDir: String, content: String) {
        makeDir("$junieDir/sessions")
        writeFile("$junieDir/sessions/index.jsonl", content)
    }

    private fun indexLine(id: ProviderSessionId, projectDir: String): String =
        """{"sessionId":"${id.value}","createdAt":1785000000000,"updatedAt":1785000009999,""" +
            """"projectDir":"$projectDir","taskName":"Do the thing"}""" + "\n"

    private fun modelUsageLine(model: String): String =
        """{"kind":"SessionA2uxEvent","event":{"agentEvent":{"kind":"TaskUsageEvent",""" +
            """"modelUsage":[{"model":"$model","inputTokens":10,"outputTokens":20}]}}}""" + "\n"

    private fun pastThreshold(): Long = 0L

    private fun futureThreshold(): Long =
        kotlin.time.Clock.System.now().toEpochMilliseconds() + 60_000

    private fun launchMeta(providerSessionId: ProviderSessionId?) = SessionMeta(
        id = SessionId("jcap0001"),
        name = "kt-jcap0001", tags = emptyList(), agent = JUNIE_AGENT_KIND,
        providerSessionId = providerSessionId,
        cwd = "/work/repo", tmuxSession = "kt-jcap0001", paneId = null,
        state = SessionState.running, stateSource = EventSource.system,
        createdAt = 0L, updatedAt = 0L,
    )

    private fun writeFile(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        val fp = fopen(path, "wb") ?: error("cannot write $path")
        try {
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp) }
        } finally {
            fclose(fp)
        }
        if (!files.contains(path)) files += path
    }

    private companion object {
        var counter = 0
    }
}
