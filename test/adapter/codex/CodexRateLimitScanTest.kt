package io.kotgent.adapter.codex

import io.kotgent.core.UsageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexRateLimitScanTest {
    @Test
    fun primaryCanBeTheWeeklyWindowAndTimestampsConvertAtIngress() {
        val observation = extractCodexRateLimits(record(""""primary":${window()}""")).single()
        assertEquals("codex", observation.provider)
        assertEquals("primary", observation.windowKey)
        assertEquals(58.0, observation.usedPercent)
        assertEquals(604_800L, observation.windowSeconds)
        assertEquals(1_789_435_330_000L, observation.resetsAt)
        assertEquals(0L, observation.observedAt)
        assertNull(observation.source)
    }

    @Test
    fun mapsBothWindowsAndIgnoresNullableSecondary() {
        val both = extractCodexRateLimits(record(
            """"primary":${window(minutes = "300")},"secondary":${window(percent = "12.5")}""",
        ))
        assertEquals(listOf("primary", "secondary"), both.map { it.windowKey })
        assertEquals(listOf(18_000L, 604_800L), both.map { it.windowSeconds })
        assertEquals(listOf(58.0, 12.5), both.map { it.usedPercent })
        assertEquals(1, extractCodexRateLimits(record(""""primary":${window()},"secondary":null""")).size)
    }

    @Test
    fun creditsRequireAnActualPercentageRatherThanABalance() {
        val without = record(""""credits":{"has_credits":false,"unlimited":false,"balance":"0"}""")
        assertTrue(extractCodexRateLimits(without).isEmpty())
        val credits = extractCodexRateLimits(record(""""credits":{"used_percent":25}""")).single()
        assertEquals("credits", credits.windowKey)
        assertEquals(25.0, credits.usedPercent)
        assertNull(credits.windowSeconds)
        assertNull(credits.resetsAt)
    }

    @Test
    fun theLastQuotaObjectWinsWithoutResurrectingEarlierWindows() {
        val old = record(""""primary":${window()},"secondary":${window(percent = "90")}""")
        val latest = record(""""primary":${window(percent = "8")}""")
        val result = extractCodexRateLimits("$old\n$latest\n")
        assertEquals(listOf("primary"), result.map { it.windowKey })
        assertEquals(8.0, result.single().usedPercent)
        assertTrue(extractCodexRateLimits("$old\n${record("\"credits\":{\"balance\":\"0\"}")}").isEmpty())
        assertTrue(extractCodexRateLimits("$old\n${record("")}").isEmpty())
        assertTrue(extractCodexRateLimits("$old\n${record("\"primary\":{\"used_percent\":101}")}").isEmpty())
    }

    @Test
    fun ignoresMissingLimitsAndRecordsThatAreNotTokenCountEvents() {
        for (text in listOf(
            "",
            "{}",
            "[]",
            """{"type":"event_msg","payload":{"type":"token_count"}}""",
            """{"type":"event_msg","payload":{"type":"token_count","rate_limits":null}}""",
            """{"type":"turn_context","payload":{"type":"token_count","rate_limits":{"primary":${window()}}}}""",
            """{"type":"event_msg","payload":{"type":"message","rate_limits":{"primary":${window()}}}}""",
        )) assertTrue(extractCodexRateLimits(text).isEmpty(), text)
    }

    @Test
    fun otherLimitBucketsDoNotReplaceTheDefaultCodexMeter() {
        val default = record(""""primary":${window()}""")
        val other = record(""""limit_id":"other-meter","primary":${window(percent = "1")}""")
        assertTrue(extractCodexRateLimits(other).isEmpty())
        assertEquals(58.0, extractCodexRateLimits("$default\n$other").single().usedPercent)
        assertEquals(58.0, extractCodexRateLimits(record(""""limit_id":"codex","primary":${window()}""")).single().usedPercent)
        assertEquals(58.0, extractCodexRateLimits(record(""""limit_id":null,"primary":${window()}""")).single().usedPercent)
    }

    @Test
    fun partialTailRecordsAndMalformedLinesDoNotHideTheLastCompleteObject() {
        val complete = record(""""primary":${window(percent = "17")}""")
        val tail = "cut off first record}\nnot-json\n$complete\n" +
            """{"type":"event_msg","payload":{"type":"token_count","rate_limits":{"primary": """
        assertEquals(17.0, extractCodexRateLimits(tail).single().usedPercent)
        assertEquals(17.0, extractCodexRateLimits("$complete\r\n").single().usedPercent)
        assertEquals(17.0, extractCodexRateLimits(complete).single().usedPercent)
    }

    @Test
    fun rejectsNonNumericNonFiniteAndOutOfRangePercentages() {
        for (percent in listOf("-0.01", "100.01", "1e999", "true", "null", "\"42\"", "\"NaN\"", "{}", "[]")) {
            assertTrue(extractCodexRateLimits(record(""""primary":${window(percent = percent)}""")).isEmpty(), percent)
        }
        for (percent in listOf("0", "100")) {
            assertEquals(percent.toDouble(), extractCodexRateLimits(record(""""primary":${window(percent = percent)}""")).single().usedPercent)
        }
        assertTrue(extractCodexRateLimits(record(""""primary":{"resets_at":1789435330}""")).isEmpty())
    }

    @Test
    fun invalidOptionalNumbersBecomeUnknownWithoutDiscardingThePercentage() {
        for (invalid in listOf("null", "-1", "1.5", "\"20\"", "true", "{}", "9223372036854775807")) {
            val parsed = extractCodexRateLimits(record(
                """"primary":${window(reset = invalid, minutes = invalid)}""",
            )).single()
            assertNull(parsed.resetsAt, invalid)
            assertNull(parsed.windowSeconds, invalid)
            assertEquals(58.0, parsed.usedPercent)
        }
        val zero = extractCodexRateLimits(record(""""primary":${window(reset = "0", minutes = "0")}""")).single()
        assertEquals(0L, zero.resetsAt)
        assertNull(zero.windowSeconds)
    }

    @Test
    fun sourceIdentityAndRecordTimestampRemainStableOnRepeatedScans() {
        val text = record(""""primary":${window()}""", timestamp = "1970-01-01T00:01:00.123Z")
        val first = extractCodexRateLimits(text, "source-session").single()
        assertEquals(UsageSource("source-session", 0, 60_123), first.source)
        assertEquals(first, extractCodexRateLimits(text, "source-session").single())
        val next = record(""""primary":${window()}""", timestamp = "1970-01-01T00:01:01.123Z")
        val later = extractCodexRateLimits(
            "$text\n$next",
            "source-session",
        ).single()
        assertEquals(UsageSource("source-session", text.encodeToByteArray().size + 1L, 61_123), later.source)
        assertEquals(0L, later.observedAt)
    }

    @Test
    fun distinctRecordsWithTheSameTimestampUseTheirAbsoluteByteOffsets() {
        val prefix = """{"type":"message","text":"é🙂"}""" + "\n"
        val first = record(""""primary":${window(percent = "40")}""", "1970-01-01T00:01:00.123Z")
        val second = record(""""primary":${window(percent = "41")}""", "1970-01-01T00:01:00.123Z")
        val complete = extractCodexRateLimits("$prefix$first\n$second", "source", baseOffset = 100).single()
        val start = 100L + prefix.encodeToByteArray().size + first.encodeToByteArray().size + 1
        assertEquals(UsageSource("source", start, 60_123), complete.source)
        assertEquals(41.0, complete.usedPercent)
        assertEquals(complete, extractCodexRateLimits(second, "source", baseOffset = start).single())

        val bytes = "$prefix$first\n$second".encodeToByteArray()
        val cutInsideUnicode = prefix.substringBefore('é').encodeToByteArray().size + 1
        val partial = bytes.copyOfRange(cutInsideUnicode, bytes.size)
        assertEquals(complete, extractCodexRateLimits(partial, "source", baseOffset = 100L + cutInsideUnicode).single())
    }

    @Test
    fun missingInvalidAndNegativeRecordTimestampsCannotCreateOrderingEvidence() {
        for (timestamp in listOf(null, "not-a-time", "1969-12-31T23:59:59.999Z")) {
            val parsed = extractCodexRateLimits(record(""""primary":${window()}""", timestamp), "source-session").single()
            assertNull(parsed.source)
        }
    }

    private fun window(
        percent: String = "58.0",
        reset: String = "1789435330",
        minutes: String = "10080",
    ): String = """{"used_percent":$percent,"resets_at":$reset,"window_minutes":$minutes}"""

    private fun record(limits: String, timestamp: String? = null): String {
        val timestampField = timestamp?.let { "\"timestamp\":\"$it\"," } ?: ""
        return """{$timestampField"type":"event_msg","payload":{"type":"token_count","rate_limits":{$limits}}}"""
    }
}
