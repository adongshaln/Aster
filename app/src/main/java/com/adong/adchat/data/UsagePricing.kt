package com.adong.adchat.data

import java.time.LocalDate
import java.util.Locale

/** USD per million tokens. These rates estimate public list pricing, not a proxy invoice. */
data class PriceRange(val low: Double, val high: Double = low) {
    init { require(low >= 0.0 && high >= low) }
}

data class OfficialModelPrice(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val input: PriceRange,
    val cachedInput: PriceRange = input,
    /** Cache-write lifetime is not retained for every provider; this covers published short/long TTL rates. */
    val cacheWrite: PriceRange = input,
    val output: PriceRange,
    val sourceUrl: String,
    val validThrough: LocalDate? = null,
    val futureRate: DatedPrice? = null
)

data class DatedPrice(
    val effectiveFrom: LocalDate,
    val input: PriceRange,
    val cachedInput: PriceRange,
    val cacheWrite: PriceRange,
    val output: PriceRange
)

data class ModelUsageTotal(
    val model: String,
    val requests: Long = 0,
    val reportedRequests: Long = 0,
    val inputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val outputTokens: Long = 0
) {
    val unknownRequests: Long get() = (requests - reportedRequests).coerceAtLeast(0)
}

data class UsagePriceEstimate(
    val lowUsd: Double,
    val highUsd: Double,
    val price: OfficialModelPrice,
    val notice: String? = null
)

object OfficialModelPrices {
    const val CHECKED_ON = "2026-10-26"

    private val openAiPrices = "https://developers.openai.com/api/docs/pricing"
    private val geminiPrices = "https://ai.google.dev/gemini-api/docs/pricing"
    private val claudePrices = "https://platform.claude.com/docs/en/about-claude/pricing"
    private val deepSeekPrices = "https://api-docs.deepseek.com/quick_start/pricing/"

    val entries: List<OfficialModelPrice> = listOf(
        OfficialModelPrice(
            id = "gpt-6-astra", name = "GPT-6 Astra", input = PriceRange(10.0), cachedInput = PriceRange(1.0),
            cacheWrite = PriceRange(12.5, 25.0), output = PriceRange(50.0), sourceUrl = openAiPrices
        ),
        OfficialModelPrice(
            id = "gpt-6.1-sol", name = "GPT-6.1 Sol", input = PriceRange(2.0), cachedInput = PriceRange(0.10),
            cacheWrite = PriceRange(2.5, 5.0), output = PriceRange(10.0), sourceUrl = openAiPrices
        ),
        OfficialModelPrice(
            id = "gpt-6-luna", name = "GPT-6 Luna", input = PriceRange(0.10), cachedInput = PriceRange(0.01),
            cacheWrite = PriceRange(0.125, 0.25), output = PriceRange(0.50), sourceUrl = openAiPrices
        ),
        OfficialModelPrice(
            id = "gpt-5.6-sol", name = "GPT-5.6 Sol", input = PriceRange(4.0), cachedInput = PriceRange(0.40),
            cacheWrite = PriceRange(5.0, 10.0), output = PriceRange(20.0), sourceUrl = openAiPrices,
            validThrough = LocalDate.parse("2026-11-21")
        ),
        OfficialModelPrice(
            id = "gemini-4-argon", name = "Gemini 4 Argon", input = PriceRange(2.0), cachedInput = PriceRange(0.10),
            output = PriceRange(10.0), sourceUrl = geminiPrices,
            futureRate = DatedPrice(
                effectiveFrom = LocalDate.parse("2026-12-01"), input = PriceRange(4.0), cachedInput = PriceRange(0.20),
                cacheWrite = PriceRange(4.0), output = PriceRange(20.0)
            )
        ),
        OfficialModelPrice(
            id = "claude-opus-5-5", name = "Claude Opus 5.5", aliases = listOf("claude-opus-5.5"),
            input = PriceRange(4.0), cachedInput = PriceRange(0.20), cacheWrite = PriceRange(5.0, 8.0),
            output = PriceRange(20.0), sourceUrl = claudePrices
        ),
        OfficialModelPrice(
            id = "claude-sonnet-5-5", name = "Claude Sonnet 5.5", aliases = listOf("claude-sonnet-5.5"),
            input = PriceRange(2.0), cachedInput = PriceRange(0.10), cacheWrite = PriceRange(2.5, 4.0),
            output = PriceRange(10.0), sourceUrl = claudePrices
        ),
        OfficialModelPrice(
            id = "claude-haiku-5-5", name = "Claude Haiku 5.5", aliases = listOf("claude-haiku-5.5"),
            input = PriceRange(0.10, 0.50), cachedInput = PriceRange(0.01, 0.05),
            cacheWrite = PriceRange(0.125, 1.0), output = PriceRange(0.50, 2.50), sourceUrl = claudePrices
        ),
        OfficialModelPrice(
            id = "deepseek-v4-pro", name = "DeepSeek V4 Pro", input = PriceRange(0.66, 1.32),
            cachedInput = PriceRange(0.022, 0.044), cacheWrite = PriceRange(0.66, 1.32),
            output = PriceRange(1.98, 3.96), sourceUrl = deepSeekPrices
        ),
        OfficialModelPrice(
            id = "deepseek-flash", name = "DeepSeek Flash", input = PriceRange(0.15, 0.30),
            cachedInput = PriceRange(0.003, 0.006), cacheWrite = PriceRange(0.15, 0.30),
            output = PriceRange(0.60, 1.20), sourceUrl = deepSeekPrices
        )
    )

    private val aliasIndex: List<Pair<OfficialModelPrice, List<String>>> = entries.flatMap { price ->
        (listOf(price.id) + price.aliases).map { price to modelTokens(it) }
    }
    private val effortSuffixes = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

    /** Arbitrary routing prefixes are ignored, but the priced model itself must be an exact token sequence. */
    fun match(model: String): OfficialModelPrice? {
        val tokens = modelTokens(model)
        if (tokens.isEmpty()) return null
        val matches = aliasIndex.mapNotNull { (price, alias) ->
            if (alias.isEmpty() || alias.size > tokens.size) return@mapNotNull null
            val valid = (0..(tokens.size - alias.size)).any { start ->
                val end = start + alias.size
                tokens.subList(start, end) == alias && tokens.drop(end).all { it in effortSuffixes }
            }
            if (valid) price to alias.size else null
        }
        val longest = matches.maxOfOrNull { it.second } ?: return null
        return matches.filter { it.second == longest }.map { it.first }.distinctBy { it.id }.singleOrNull()
    }

    fun priceFor(model: String, date: LocalDate = LocalDate.now()): OfficialModelPrice? {
        val match = match(model) ?: return null
        if (match.validThrough != null && date > match.validThrough) return null
        return match.futureRate?.takeIf { date >= it.effectiveFrom }?.let { future ->
            match.copy(input = future.input, cachedInput = future.cachedInput, cacheWrite = future.cacheWrite, output = future.output)
        } ?: match
    }

    fun estimate(usage: ModelUsageTotal, date: LocalDate = LocalDate.now()): UsagePriceEstimate? {
        val price = priceFor(usage.model, date) ?: return null
        val input = usage.inputTokens.coerceAtLeast(0)
        val cached = usage.cachedTokens.coerceIn(0, input)
        val cacheWrite = usage.cacheWriteTokens.coerceIn(0, (input - cached).coerceAtLeast(0))
        val regular = (input - cached - cacheWrite).coerceAtLeast(0)
        val million = 1_000_000.0
        val low = (
            regular * price.input.low + cached * price.cachedInput.low + cacheWrite * price.cacheWrite.low +
                usage.outputTokens.coerceAtLeast(0) * price.output.low
            ) / million
        val high = (
            regular * price.input.high + cached * price.cachedInput.high + cacheWrite * price.cacheWrite.high +
                usage.outputTokens.coerceAtLeast(0) * price.output.high
            ) / million
        val notice = when (price.id) {
            "deepseek-flash", "deepseek-v4-pro" -> "官方价格随 UTC 时段变化，金额为低峰至高峰区间"
            "gpt-5.6-sol" -> "促销价格目前仅确认至 2026-11-21"
            "gemini-4-argon" -> if (date < LocalDate.parse("2026-12-01")) "2026-12-01 起官方标准价调整为当前的两倍" else null
            "claude-haiku-5-5" -> "单次提示词超过 100K token 时执行较高价格，区间覆盖短/长上下文费率"
            else -> if ((usage.cachedTokens > 0 || usage.cacheWriteTokens > 0) && price.id.startsWith("gpt-")) {
                "缓存写入按官方短时至长时价格范围估算；加速、批处理和地域附加费未计"
            } else null
        }
        return UsagePriceEstimate(lowUsd = low, highUsd = high, price = price, notice = notice)
    }

    private fun modelTokens(value: String): List<String> = Regex("[a-z]+|[0-9]+(?:\\.[0-9]+)*")
        .findAll(value.lowercase(Locale.ROOT))
        .map { it.value }
        .toList()
}

fun aggregateConversationUsage(conversations: List<Conversation>): List<ModelUsageTotal> {
    val rows = linkedMapOf<String, ModelUsageTotal>()
    conversations.forEach { conversation ->
        conversation.messages.filter { it.role.equals("assistant", ignoreCase = true) }.forEach { message ->
            val rawModel = message.model.ifBlank { conversation.model }.trim().ifBlank { "模型未记录" }
            val price = OfficialModelPrices.match(rawModel)
            val key = price?.id ?: rawModel.lowercase(Locale.ROOT)
            val current = rows[key] ?: ModelUsageTotal(model = price?.id ?: rawModel)
            val usage = message.usage?.takeIf { it.providerUsageReported }
            rows[key] = current.copy(
                requests = current.requests + 1,
                reportedRequests = current.reportedRequests + if (usage != null) 1 else 0,
                inputTokens = current.inputTokens + (usage?.inputTokens?.coerceAtLeast(0) ?: 0),
                cachedTokens = current.cachedTokens + (usage?.cachedTokens?.coerceAtLeast(0) ?: 0),
                cacheWriteTokens = current.cacheWriteTokens + (usage?.cacheWriteTokens?.coerceAtLeast(0) ?: 0),
                outputTokens = current.outputTokens + (usage?.outputTokens?.coerceAtLeast(0) ?: 0)
            )
        }
    }
    return rows.values.sortedWith(compareByDescending<ModelUsageTotal> { OfficialModelPrices.estimate(it)?.highUsd }.thenBy { it.model })
}

fun mergeUsageTotals(first: List<ModelUsageTotal>, second: List<ModelUsageTotal>): List<ModelUsageTotal> {
    val rows = linkedMapOf<String, ModelUsageTotal>()
    (first + second).forEach { row ->
        val price = OfficialModelPrices.match(row.model)
        val key = price?.id ?: row.model.lowercase(Locale.ROOT)
        val current = rows[key]
        rows[key] = if (current == null) row.copy(model = price?.id ?: row.model) else current.copy(
            requests = current.requests + row.requests,
            reportedRequests = current.reportedRequests + row.reportedRequests,
            inputTokens = current.inputTokens + row.inputTokens,
            cachedTokens = current.cachedTokens + row.cachedTokens,
            cacheWriteTokens = current.cacheWriteTokens + row.cacheWriteTokens,
            outputTokens = current.outputTokens + row.outputTokens
        )
    }
    return rows.values.sortedWith(compareByDescending<ModelUsageTotal> { OfficialModelPrices.estimate(it)?.highUsd }.thenBy { it.model })
}
