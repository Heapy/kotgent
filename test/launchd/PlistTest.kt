package io.kotgent.launchd

import io.kotgent.sys.DEFAULT_UTF8_LOCALE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlistTest {

    private val binary = "/Users/tester/dev/kotgent/build/kotgent"
    private val logDir = "/Users/tester/Library/Logs/kotgent"

    private fun xml(): String = launchAgentPlist(binaryPath = binary, logDir = logDir)

    @Test
    fun isWellFormedPlistDocument() {
        val x = xml()
        assertTrue(x.startsWith("<?xml version=\"1.0\""), "carries an XML declaration")
        assertTrue("<!DOCTYPE plist PUBLIC" in x, "carries the plist DOCTYPE")
        assertTrue("<plist version=\"1.0\">" in x && "</plist>" in x, "wrapped in a <plist> element")
        assertTrue("<dict>" in x && "</dict>" in x, "top-level dict present")
    }

    @Test
    fun labelDefaultsToTheDaemonReverseDnsId() {
        val x = xml()
        assertEquals("io.kotgent.daemon", DAEMON_LABEL, "the label constant is the reverse-DNS id")
        assertTrue(labelValue(x) == "io.kotgent.daemon", "Label value is io.kotgent.daemon")
    }

    @Test
    fun programArgumentsAreExactlyTheBinaryThenDaemon() {
        val args = programArguments(xml())
        assertEquals(listOf(binary, "daemon"), args, "ProgramArguments = [<binary path>, \"daemon\"]")
    }

    @Test
    fun runAtLoadAndKeepAliveAreTrue() {
        val x = xml()
        assertTrue(boolAfterKey(x, "RunAtLoad"), "RunAtLoad is <true/>")
        assertTrue(boolAfterKey(x, "KeepAlive"), "KeepAlive is <true/>")
    }

    @Test
    fun throttleIntervalIsAnIntegerAndDefaultsToTen() {
        assertEquals(10, DAEMON_THROTTLE_INTERVAL)
        assertEquals(10, integerAfterKey(xml(), "ThrottleInterval"), "ThrottleInterval defaults to 10")
    }

    @Test
    fun environmentPathIncludesHomebrewAndSystemBins() {
        val path = environmentPath(xml())
        assertTrue("/opt/homebrew/bin" in path, "PATH carries /opt/homebrew/bin (Apple-silicon brew)")
        for (dir in listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin")) {
            assertTrue(dir in path, "PATH carries $dir")
        }
    }

    @Test
    fun environmentCarriesAUtf8LangAlongsidePath() {
        val lang = environmentValue(xml(), "LANG")
        assertEquals(DEFAULT_UTF8_LOCALE, lang, "LANG defaults to the UTF-8 default")
        assertTrue(lang.endsWith("UTF-8"), "the emitted locale is a UTF-8 one")
    }

    @Test
    fun standardOutAndErrorPathsLiveUnderTheLogDir() {
        val x = xml()
        val out = stringAfterKey(x, "StandardOutPath")
        val err = stringAfterKey(x, "StandardErrorPath")
        assertTrue(out.startsWith("$logDir/"), "StandardOutPath is under the log dir: $out")
        assertTrue(err.startsWith("$logDir/"), "StandardErrorPath is under the log dir: $err")
        assertTrue(out != err, "stdout and stderr go to distinct files")
    }

    @Test
    fun labelAndThrottleAndPathAndLangAreParameterizable() {
        val x = launchAgentPlist(
            binaryPath = binary,
            logDir = logDir,
            label = "io.example.custom",
            path = "/custom/bin",
            lang = "ru_RU.UTF-8",
            throttleInterval = 42,
        )
        assertEquals("io.example.custom", labelValue(x))
        assertEquals(42, integerAfterKey(x, "ThrottleInterval"))
        assertEquals("/custom/bin", environmentPath(x))
        assertEquals("ru_RU.UTF-8", environmentValue(x, "LANG"))
    }

    @Test
    fun specialCharactersInPathsAreXmlEscaped() {
        val x = launchAgentPlist(binaryPath = "/opt/a & b/kotgent", logDir = "/logs/<x>")
        assertTrue("&amp;" in x, "an ampersand in the binary path is escaped")
        assertTrue("&lt;x&gt;" in x, "angle brackets in the log dir are escaped")
        assertTrue("/opt/a & b/kotgent" !in x, "the raw ampersand is not left unescaped")
    }


    @Test
    fun mergedDaemonPathNullCapturedReturnsTheDefaultExactly() {
        assertEquals(DAEMON_DEFAULT_PATH, mergedDaemonPath(null), "null captured → the default, verbatim")
    }

    @Test
    fun mergedDaemonPathBlankOnlyCapturedReturnsTheDefault() {
        assertEquals(DAEMON_DEFAULT_PATH, mergedDaemonPath(""), "empty string → default")
        assertEquals(DAEMON_DEFAULT_PATH, mergedDaemonPath(":::"), "only empty segments → default")
        assertEquals(DAEMON_DEFAULT_PATH, mergedDaemonPath("   "), "only a blank segment → default")
    }

    @Test
    fun mergedDaemonPathPutsCapturedDirsFirstThenAppendsDefaults() {
        val nvm = "/Users/x/.nvm/versions/node/v20.0.0/bin"
        val merged = mergedDaemonPath("/Users/x/.local/bin:$nvm")
        val entries = merged.split(':')
        val defaults = DAEMON_DEFAULT_PATH.split(':')
        assertEquals(listOf("/Users/x/.local/bin", nvm) + defaults, entries, "captured first, defaults appended")
        assertTrue("/Users/x/.local/bin" in entries && nvm in entries, "the new dirs are present")
        for (dir in defaults) assertTrue(dir in entries, "default $dir retained")
    }

    @Test
    fun mergedDaemonPathDedupsDefaultsAlreadyPresentInCaptured() {
        val merged = mergedDaemonPath("/opt/homebrew/bin:/Users/x/.local/bin:/usr/bin")
        val entries = merged.split(':')
        assertEquals(entries.distinct(), entries, "no duplicate PATH entries")
        assertEquals(listOf("/opt/homebrew/bin", "/Users/x/.local/bin", "/usr/bin"), entries.take(3))
        for (dir in listOf("/bin", "/usr/sbin", "/sbin")) assertTrue(dir in entries, "$dir still retained")
    }

    @Test
    fun mergedDaemonPathDropsEmptySegmentsInCaptured() {
        val merged = mergedDaemonPath(":/Users/x/.local/bin::/some/dir:")
        val entries = merged.split(':')
        assertTrue("" !in entries, "no empty segment survives the merge")
        assertEquals(listOf("/Users/x/.local/bin", "/some/dir"), entries.take(2), "captured dirs, empties dropped")
    }

    @Test
    fun mergedDaemonPathDedupsRepeatsWithinTheCapturedPath() {
        val merged = mergedDaemonPath("/Users/x/.local/bin:/some/dir:/Users/x/.local/bin")
        val entries = merged.split(':')
        assertEquals(entries.distinct(), entries, "no duplicate PATH entries")
        assertEquals(1, entries.count { it == "/Users/x/.local/bin" }, "the repeated dir appears exactly once")
        assertEquals(listOf("/Users/x/.local/bin", "/some/dir"), entries.take(2), "first-seen order preserved")
    }

    @Test
    fun mergedDaemonPathDropsNonAbsoluteSegmentsInCaptured() {
        val merged = mergedDaemonPath(".:/Users/x/.local/bin:relative/dir")
        val entries = merged.split(':')
        assertTrue("." !in entries, "a bare `.` is dropped")
        assertTrue("relative/dir" !in entries, "a relative dir is dropped")
        assertTrue(entries.all { it.startsWith("/") }, "every surviving entry is absolute")
        assertEquals("/Users/x/.local/bin", entries.first(), "the absolute captured dir survives, first")
    }


    private fun labelValue(x: String) = stringAfterKey(x, "Label")

    private fun stringAfterKey(x: String, key: String): String =
        Regex("<key>$key</key>\\s*<string>([^<]*)</string>").find(x)?.groupValues?.get(1)
            ?: error("no <string> value after <key>$key</key>")

    private fun integerAfterKey(x: String, key: String): Int =
        Regex("<key>$key</key>\\s*<integer>(-?\\d+)</integer>").find(x)?.groupValues?.get(1)?.toInt()
            ?: error("no <integer> value after <key>$key</key>")

    private fun boolAfterKey(x: String, key: String): Boolean =
        Regex("<key>$key</key>\\s*<(true|false)/>").find(x)?.groupValues?.get(1) == "true"

    private fun programArguments(x: String): List<String> {
        val array = Regex("<key>ProgramArguments</key>\\s*<array>(.*?)</array>", RegexOption.DOT_MATCHES_ALL)
            .find(x)?.groupValues?.get(1) ?: error("no ProgramArguments array")
        return Regex("<string>([^<]*)</string>").findAll(array).map { it.groupValues[1] }.toList()
    }

    private fun environmentPath(x: String): String = environmentValue(x, "PATH")

    private fun environmentValue(x: String, key: String): String {
        val envDict = Regex("<key>EnvironmentVariables</key>\\s*<dict>(.*?)</dict>", RegexOption.DOT_MATCHES_ALL)
            .find(x)?.groupValues?.get(1) ?: error("no EnvironmentVariables dict")
        return stringAfterKey(envDict, key)
    }
}
