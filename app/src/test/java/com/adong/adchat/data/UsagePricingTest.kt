package com.adong.adchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class UsagePricingTest {
    @Test fun stripsRoutingPrefixAndReasoningSuffixOnlyAroundExactModelNames() {
        assertEquals("gemini-4-argon", OfficialModelPrices.match("Gemini-4-argon-high")?.id)
        assertEquals("claude-opus-5-5", OfficialModelPrices.match("ccmax-Claude-opus-5.5")?.id)
        assertNull(OfficialModelPrices.match("Gemini-4-argon-preview"))
        assertNull(OfficialModelPrices.match("ccmax-Claude-opus-5.5-2026"))
    }

    @Test fun estimatesUseEffectiveOfficialRatesAndShowPublishedRanges() {
        val gemini = ModelUsageTotal("gemini-4-argon", inputTokens = 1_000_000, outputTokens = 1_000_000)
        assertEquals(12.0, OfficialModelPrices.estimate(gemini, LocalDate.parse("2026-10-26"))!!.highUsd, 0.000001)
        assertEquals(24.0, OfficialModelPrices.estimate(gemini, LocalDate.parse("2026-12-01"))!!.highUsd, 0.000001)

        val claude = ModelUsageTotal(
            "claude-opus-5-5", inputTokens = 1_000_000, cachedTokens = 500_000,
            cacheWriteTokens = 250_000, outputTokens = 1_000_000
        )
        val range = OfficialModelPrices.estimate(claude)!!
        assertEquals(22.35, range.lowUsd, 0.000001)
        assertEquals(23.10, range.highUsd, 0.000001)
    }
}
