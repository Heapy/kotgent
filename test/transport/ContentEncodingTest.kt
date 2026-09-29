package io.kotgent.transport

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentEncodingTest {
    private val available = setOf("br", "gzip")

    @Test
    fun negotiatesAcceptedCodingsByQualityWithBrotliWinningTies() {
        val cases = mapOf(
            "br, gzip" to "br",
            "gzip, br" to "br",
            "gzip" to "gzip",
            "br;q=0, gzip" to "gzip",
            "gzip;q=0.5, br;q=1" to "br",
            "br;q=0.5, gzip;q=1" to "gzip",
            "BR, GZIP" to "br",
            " \t GZip ; Q = 0.5 , BR ; q = 1 \t " to "br",
            "*" to "br",
            "*;q=0.5, br;q=0" to "gzip",
            "*;q=0, gzip;q=0.5" to "gzip",
            "*;q=1, br;q=0.5" to "gzip",
            "unknown, gzip" to "gzip",
            "br;q=0.000, gzip;q=1.0" to "gzip",
            "br;q=1.0, gzip;q=0.001" to "br",
            "identity;q=0, gzip;q=0.001" to "gzip",
            "identity;q=0.5, br;q=1" to "br",
        )
        for ([header, expected] in cases) {
            assertEquals(ContentEncodingSelection.Encoded(expected), negotiateContentEncoding(header, available), header)
        }
    }

    @Test
    fun fallsBackToIdentityWhenNoCodingIsAccepted() {
        for (header in listOf("identity", "unknown", "br;q=0, gzip;q=0", "*;q=0, identity;q=0.5")) {
            assertEquals(ContentEncodingSelection.Identity, negotiateContentEncoding(header, available), header)
        }
    }

    @Test
    fun absentHeaderAllowsTheServersIdentityPreferenceWhileAnEmptyHeaderRequiresIdentity() {
        for (header in listOf(null, "", " \t ")) {
            assertEquals(ContentEncodingSelection.Identity, negotiateContentEncoding(header, available), header)
        }
    }

    @Test
    fun explicitIdentityPreferenceCanOutweighCompression() {
        assertEquals(
            ContentEncodingSelection.Identity,
            negotiateContentEncoding("identity;q=1, br;q=0.5, gzip;q=0.5", available),
        )
    }

    @Test
    fun rejectsWhenIdentityAndEveryAvailableCodingAreExcluded() {
        for (header in listOf("*;q=0", "*;q=0.000", "identity;q=0", "identity;q=0, br;q=0, gzip;q=0")) {
            assertEquals(ContentEncodingSelection.NotAcceptable, negotiateContentEncoding(header, available), header)
        }
        assertEquals(
            ContentEncodingSelection.NotAcceptable,
            negotiateContentEncoding("identity;q=0, br", setOf("gzip")),
        )
        assertEquals(
            ContentEncodingSelection.NotAcceptable,
            negotiateContentEncoding("identity;q=0, br, gzip", emptySet()),
        )
    }

    @Test
    fun gzipAliasIsNormalizedBeforeWeightsAndWildcardFallback() {
        assertEquals(ContentEncodingSelection.Encoded("gzip"), negotiateContentEncoding("X-GZIP", available))
        for (header in listOf("x-gzip;q=0, *", "gzip, x-gzip;q=0, *", "x-gzip;q=0, gzip, *")) {
            assertEquals(ContentEncodingSelection.Identity, negotiateContentEncoding(header, setOf("gzip")), header)
            assertEquals(
                ContentEncodingSelection.NotAcceptable,
                negotiateContentEncoding("$header, identity;q=0", setOf("gzip")),
                header,
            )
        }
        assertEquals(
            ContentEncodingSelection.Encoded("br"),
            negotiateContentEncoding("x-gzip;q=0.2, gzip;q=1, br;q=0.5", available),
        )
    }

    @Test
    fun duplicateCodingsTakeTheirLowestWeightRegardlessOfOrder() {
        for (header in listOf(
            "br;q=0, br, gzip", "br, br;q=0, gzip",
            "br;q=0.2, br;q=1, gzip;q=0.5", "br;q=1, br;q=0.2, gzip;q=0.5",
        )) {
            assertEquals(ContentEncodingSelection.Encoded("gzip"), negotiateContentEncoding(header, available), header)
        }
        for (header in listOf(
            "identity;q=0, identity", "identity, identity;q=0", "*;q=0, *", "*, *;q=0",
        )) {
            assertEquals(ContentEncodingSelection.NotAcceptable, negotiateContentEncoding(header, available), header)
        }
    }

    @Test
    fun onlySelectsAvailableCodings() {
        assertEquals(ContentEncodingSelection.Encoded("gzip"), negotiateContentEncoding("br, gzip", setOf("gzip")))
        assertEquals(ContentEncodingSelection.Encoded("gzip"), negotiateContentEncoding("*", setOf("gzip")))
        assertEquals(ContentEncodingSelection.Identity, negotiateContentEncoding("br", setOf("gzip")))
        assertEquals(ContentEncodingSelection.Identity, negotiateContentEncoding("br, gzip", emptySet()))
    }

    @Test
    fun outOfGrammarWeightsIncludingMoreThanThreeDecimalPlacesAreTreatedAsZero() {
        for (quality in listOf("", "invalid", "-1", "2", "NaN", "0.0001", "1.0000", "0.5e0", ".5")) {
            assertEquals(
                ContentEncodingSelection.NotAcceptable,
                negotiateContentEncoding("br;q=$quality, identity;q=0", available),
                quality,
            )
        }
    }
}
