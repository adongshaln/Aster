package com.adong.adchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class ModelCatalogAndBillingTest {
    @Test
    fun brandRulesResolveVendorFromIdAndDisplayNameWithoutFalsePositives() {
        assertEquals(ModelBrand.DeepSeek, ModelCatalogCopy.brandFor("deepseek-v4.1-flash"))
        assertEquals(ModelBrand.Claude, ModelCatalogCopy.brandFor("anthropic/claude-sonnet-4-6"))
        assertEquals(ModelBrand.OpenAI, ModelCatalogCopy.brandFor("gpt-6-astra"))
        assertEquals(ModelBrand.OpenAI, ModelCatalogCopy.brandFor("o3-mini"))
        assertEquals(ModelBrand.NanoBanana, ModelCatalogCopy.brandFor("nano-banana-pro"))
        assertEquals(ModelBrand.Gemma, ModelCatalogCopy.brandFor("gemma-3-27b"))
        assertEquals(ModelBrand.Gemini, ModelCatalogCopy.brandFor("gemini-4-argon-high"))
        assertEquals(ModelBrand.Zai, ModelCatalogCopy.brandFor("zai-glm-5.3-flash"))
        assertEquals(ModelBrand.Kimi, ModelCatalogCopy.brandFor("cn:k3.1-pro"))
        assertEquals(ModelBrand.Qwen, ModelCatalogCopy.brandFor("qwen3.8-max"))
        assertEquals(ModelBrand.StepFun, ModelCatalogCopy.brandFor("step-3.7-flash"))
        assertEquals(ModelBrand.MiniMax, ModelCatalogCopy.brandFor("cn:m3-1m"))
        assertEquals(ModelBrand.Meta, ModelCatalogCopy.brandFor("meta-llama-4-maverick"))
        assertEquals(ModelBrand.Grok, ModelCatalogCopy.brandFor("xai/grok-4.6"))
        assertEquals(ModelBrand.Nova, ModelCatalogCopy.brandFor("us.amazon.nova-pro-v1:0"))
        // sensenova must NOT be claimed by Amazon Nova
        assertNull(ModelCatalogCopy.brandFor("sensenova-6.8-flash-lite"))
    }

    @Test
    fun combineLabelPartsReplacesVendorWordOrLeadsWhenAbsent() {
        val claudeParts = ModelCatalogCopy.combineLabelParts("claude-sonnet-4-6")
        assertNotNull(claudeParts)
        assertEquals(ModelBrand.Claude, claudeParts!!.brand)
        assertEquals("", claudeParts.head)
        assertEquals(" sonnet-4-6", claudeParts.tail)

        val prefixedParts = ModelCatalogCopy.combineLabelParts("anthropic/claude-opus-4.5")
        assertNotNull(prefixedParts)
        assertEquals("anthropic/", prefixedParts!!.head)
        assertEquals(" opus-4.5", prefixedParts.tail)

        val kimiK3Parts = ModelCatalogCopy.combineLabelParts("k3.1-pro")
        assertNotNull(kimiK3Parts)
        assertEquals(ModelBrand.Kimi, kimiK3Parts!!.brand)
        assertEquals("", kimiK3Parts.head)
        assertEquals(" k3.1-pro", kimiK3Parts.tail)
    }

    @Test
    fun familyAndTierCopyRulesMatchDshClaudeStyleCatalog() {
        assertEquals(
            "非对称模型结构，小成本大智能。",
            ModelCatalogCopy.descriptionFor("deepseek-v4.1-flash")
        )
        assertEquals(
            "性能比肩顶级闭源模型。",
            ModelCatalogCopy.descriptionFor("deepseek-v4-pro")
        )
        assertEquals(
            "面向复杂编程与 AI 智能体的混合推理模型。",
            ModelCatalogCopy.descriptionFor("claude-opus-5-5")
        )
        assertEquals(
            "面向真实软件工程、企业知识工作与网络安全防御的前沿模型，擅长长程复杂任务。",
            ModelCatalogCopy.descriptionFor("gemini-4-argon-high")
        )
        assertEquals(
            "该系列首个原生多模态模型，320B 总参数，MIT 许可开放权重。",
            ModelCatalogCopy.descriptionFor("glm-5.3-flash")
        )
        // Tier fallback for unknown vendor with -flash suffix
        assertEquals(
            "响应快、成本低，适合简单或高频任务。",
            ModelCatalogCopy.descriptionFor("custom-unknown-flash")
        )
    }

    @Test
    fun deepSeekPeakRateClockRespectsUtcWindowsWeekendsAndChineseHolidays() {
        // Wednesday 2026-10-28 02:30 UTC -> inside 01:00..04:00 UTC peak window
        val wedPeakMs = ZonedDateTime.parse("2026-10-28T02:30:00Z").toInstant().toEpochMilli()
        assertTrue(ModelCatalogCopy.isDeepSeekPeakAt(wedPeakMs))
        val badgePeak = ModelCatalogCopy.peakRateBadgeFor("deepseek-v4-flash", wedPeakMs)
        assertNotNull(badgePeak)
        assertEquals("2×", badgePeak!!.badge)
        assertEquals("1h 30m", badgePeak.countdownLabel)

        // Saturday 2026-10-31 02:30 UTC -> weekend, off-peak all day
        val satOffPeakMs = ZonedDateTime.parse("2026-10-31T02:30:00Z").toInstant().toEpochMilli()
        assertFalse(ModelCatalogCopy.isDeepSeekPeakAt(satOffPeakMs))
        val badgeOffPeak = ModelCatalogCopy.peakRateBadgeFor("deepseek-v4-pro", satOffPeakMs)
        assertNotNull(badgeOffPeak)
        assertEquals("1×", badgeOffPeak!!.badge)

        // Thursday 2026-10-01 02:30 UTC -> National Day holiday in Asia/Shanghai, off-peak
        val holidayMs = ZonedDateTime.parse("2026-10-01T02:30:00Z").toInstant().toEpochMilli()
        assertFalse(ModelCatalogCopy.isDeepSeekPeakAt(holidayMs))
    }

    @Test
    fun customBillingTableOverlaysOfficialPricesAndSupportsCnyAndUsd() {
        val rows = CustomBillingTable.parseTable(
            listOf(
                "my-private-model|10|1|30",
                "deepseek-flash||0.5|12"
            )
        )
        assertEquals(2, rows.size)

        val customUsage = ModelUsageTotal(
            model = "provider/my-private-model",
            requests = 1,
            reportedRequests = 1,
            inputTokens = 1_000_000,
            cachedTokens = 500_000,
            outputTokens = 1_000_000
        )
        val estimateCny = OfficialModelPrices.estimate(
            customUsage,
            customRows = rows,
            customCurrency = BILLING_CURRENCY_CNY
        )
        assertNotNull(estimateCny)
        assertTrue(estimateCny!!.customOverride)
        // 0.5M regular * 10 + 0.5M cached * 1 + 1M output * 30 = 5 + 0.5 + 30 = 35.5 CNY
        assertEquals(35.5, estimateCny.lowIn(BILLING_CURRENCY_CNY), 0.001)
    }
}
