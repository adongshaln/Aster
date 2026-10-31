package com.adong.adchat.data

import java.time.LocalDate
import java.util.Locale

/**
 * Ported & extended from Nwflower/dsh-claude-style (`packages/contracts/src/prices.ts`,
 * `packages/contracts/src/billing-table.ts`, and `packages/host/data/prices.json`):
 *
 * - Supports both CNY (`¥`) and USD (`$`) official price tables (`USD_TO_CNY = 7.2` for USD-only catalog entries,
 *   while DeepSeek and Kimi use their native published CNY rates).
 * - Supports user-defined custom price table rows (`model|input|cacheRead|output` per 1M tokens)
 *   that overlay or add to the official price catalog.
 * - Retains full backwards compatibility with `OfficialModelPrices.match`, `priceFor`, and `estimate`.
 */
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
    val futureRate: DatedPrice? = null,
    /** Published official CNY per 1M tokens when the vendor publishes an independent CNY price table. */
    val inputCny: PriceRange? = null,
    val cachedInputCny: PriceRange? = null,
    val cacheWriteCny: PriceRange? = null,
    val outputCny: PriceRange? = null
) {
    fun inputFor(currency: String): PriceRange =
        if (currency == BILLING_CURRENCY_CNY) inputCny ?: PriceRange(input.low * OfficialModelPrices.USD_TO_CNY, input.high * OfficialModelPrices.USD_TO_CNY)
        else input

    fun cachedInputFor(currency: String): PriceRange =
        if (currency == BILLING_CURRENCY_CNY) cachedInputCny ?: PriceRange(cachedInput.low * OfficialModelPrices.USD_TO_CNY, cachedInput.high * OfficialModelPrices.USD_TO_CNY)
        else cachedInput

    fun cacheWriteFor(currency: String): PriceRange =
        if (currency == BILLING_CURRENCY_CNY) cacheWriteCny ?: PriceRange(cacheWrite.low * OfficialModelPrices.USD_TO_CNY, cacheWrite.high * OfficialModelPrices.USD_TO_CNY)
        else cacheWrite

    fun outputFor(currency: String): PriceRange =
        if (currency == BILLING_CURRENCY_CNY) outputCny ?: PriceRange(output.low * OfficialModelPrices.USD_TO_CNY, output.high * OfficialModelPrices.USD_TO_CNY)
        else output
}

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
    val lowCny: Double = lowUsd * OfficialModelPrices.USD_TO_CNY,
    val highCny: Double = highUsd * OfficialModelPrices.USD_TO_CNY,
    val price: OfficialModelPrice,
    val notice: String? = null,
    val customOverride: Boolean = false
) {
    fun lowIn(currency: String): Double = if (currency == BILLING_CURRENCY_CNY) lowCny else lowUsd
    fun highIn(currency: String): Double = if (currency == BILLING_CURRENCY_CNY) highCny else highUsd
}

/**
 * Custom billing table row (`model|input|cacheRead|output` per million tokens),
 * matching `packages/contracts/src/billing-table.ts` in `dsh-claude-style`.
 */
data class CustomBillingRow(
    val model: String,
    val input: Double?,
    val cacheRead: Double?,
    val output: Double?
)

object CustomBillingTable {
    fun splitRow(entry: String): List<String> {
        val parts = entry.split('|')
        return List(4) { index -> parts.getOrNull(index)?.trim().orEmpty() }
    }

    fun formatEntry(model: String, input: String, cacheRead: String, output: String): String =
        listOf(model.trim(), input.trim(), cacheRead.trim(), output.trim()).joinToString("|")

    private fun parseRate(text: String): Double? {
        if (text.isBlank()) return null
        val v = text.toDoubleOrNull() ?: return null
        return if (v.isFinite() && v >= 0.0) v else null
    }

    fun parseRow(entry: String): CustomBillingRow? {
        val parts = splitRow(entry)
        val model = parts[0]
        if (model.isBlank()) return null
        return CustomBillingRow(
            model = model,
            input = parseRate(parts[1]),
            cacheRead = parseRate(parts[2]),
            output = parseRate(parts[3])
        )
    }

    fun parseTable(entries: List<String>): List<CustomBillingRow> =
        entries.mapNotNull(::parseRow)

    fun modelKey(modelId: String): String? {
        val raw = modelId.trim().lowercase(Locale.ROOT)
        if (raw.isEmpty()) return null
        return if ('/' in raw) raw.substringAfterLast('/') else raw
    }

    fun rowFor(rows: List<CustomBillingRow>, modelId: String): CustomBillingRow? {
        val key = modelKey(modelId) ?: return null
        return rows.firstOrNull { modelKey(it.model) == key }
    }
}

object OfficialModelPrices {
    const val CHECKED_ON = "2026-10-30"
    const val USD_TO_CNY = 7.2

    private const val openAiPrices = "https://developers.openai.com/api/docs/pricing"
    private const val geminiPrices = "https://ai.google.dev/gemini-api/docs/pricing"
    private const val claudePrices = "https://platform.claude.com/docs/en/about-claude/pricing"
    private const val deepSeekPrices = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"
    private const val kimiPrices = "https://platform.moonshot.cn/docs/pricing/chat"
    private const val zaiPrices = "https://open.bigmodel.cn/pricing"
    private const val qwenPrices = "https://help.aliyun.com/zh/model-studio/getting-started/models"
    private const val xaiPrices = "https://docs.x.ai/docs/models"
    private const val mistralPrices = "https://mistral.ai/technology/#pricing"

    val entries: List<OfficialModelPrice> = listOf(
        // OpenAI
        OfficialModelPrice(
            id = "gpt-6-astra", name = "GPT-6 Astra", input = PriceRange(10.0), cachedInput = PriceRange(1.0),
            cacheWrite = PriceRange(12.5, 25.0), output = PriceRange(50.0), sourceUrl = openAiPrices
        ),
        OfficialModelPrice(
            id = "gpt-6.1-sol", name = "GPT-6.1 Sol", aliases = listOf("gpt-6-sol"),
            input = PriceRange(2.0), cachedInput = PriceRange(0.10),
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
            id = "gpt-5.4", name = "GPT-5.4", aliases = listOf("gpt-5", "gpt-5.4-pro"),
            input = PriceRange(2.5), cachedInput = PriceRange(0.25),
            cacheWrite = PriceRange(2.5), output = PriceRange(10.0), sourceUrl = openAiPrices
        ),
        OfficialModelPrice(
            id = "o3", name = "OpenAI o3", aliases = listOf("o3-mini", "o4-mini"),
            input = PriceRange(2.0), cachedInput = PriceRange(0.50),
            cacheWrite = PriceRange(2.0), output = PriceRange(8.0), sourceUrl = openAiPrices
        ),

        // Google Gemini
        OfficialModelPrice(
            id = "gemini-4-argon", name = "Gemini 4 Argon", input = PriceRange(2.0), cachedInput = PriceRange(0.10),
            output = PriceRange(10.0), sourceUrl = geminiPrices,
            futureRate = DatedPrice(
                effectiveFrom = LocalDate.parse("2026-12-01"), input = PriceRange(4.0), cachedInput = PriceRange(0.20),
                cacheWrite = PriceRange(4.0), output = PriceRange(20.0)
            )
        ),
        OfficialModelPrice(
            id = "gemini-2.5-pro", name = "Gemini 2.5 Pro", aliases = listOf("gemini-3-pro", "gemini-2.5-pro-preview"),
            input = PriceRange(1.25, 2.50), cachedInput = PriceRange(0.31, 0.625),
            output = PriceRange(10.0, 15.0), sourceUrl = geminiPrices
        ),
        OfficialModelPrice(
            id = "gemini-2.5-flash", name = "Gemini 2.5 Flash", aliases = listOf("gemini-3-flash", "gemini-2.0-flash"),
            input = PriceRange(0.15), cachedInput = PriceRange(0.0375),
            output = PriceRange(0.60), sourceUrl = geminiPrices
        ),

        // Anthropic Claude
        OfficialModelPrice(
            id = "claude-fable-5", name = "Claude Fable 5", aliases = listOf("claude-fable-5.1", "claude-5-fable"),
            input = PriceRange(10.0), cachedInput = PriceRange(0.25, 1.0), cacheWrite = PriceRange(12.5),
            output = PriceRange(50.0), sourceUrl = claudePrices
        ),
        OfficialModelPrice(
            id = "claude-opus-5-5", name = "Claude Opus 5.5",
            aliases = listOf("claude-opus-5.5", "claude-opus-4-6", "claude-opus-4.6", "claude-opus-4-5", "claude-opus-4.5"),
            input = PriceRange(4.0), cachedInput = PriceRange(0.20), cacheWrite = PriceRange(5.0, 8.0),
            output = PriceRange(20.0), sourceUrl = claudePrices
        ),
        OfficialModelPrice(
            id = "claude-sonnet-5-5", name = "Claude Sonnet 5.5",
            aliases = listOf("claude-sonnet-5.5", "claude-sonnet-5", "claude-sonnet-4-6", "claude-sonnet-4.6", "claude-sonnet-4-5", "claude-sonnet-4.5", "claude-sonnet-4"),
            input = PriceRange(2.0), cachedInput = PriceRange(0.10), cacheWrite = PriceRange(2.5, 4.0),
            output = PriceRange(10.0), sourceUrl = claudePrices
        ),
        OfficialModelPrice(
            id = "claude-haiku-5-5", name = "Claude Haiku 5.5",
            aliases = listOf("claude-haiku-5.5", "claude-haiku-4-5", "claude-haiku-4.5"),
            input = PriceRange(0.10, 0.50), cachedInput = PriceRange(0.01, 0.05),
            cacheWrite = PriceRange(0.125, 1.0), output = PriceRange(0.50, 2.50), sourceUrl = claudePrices
        ),

        // DeepSeek (with official CNY & USD off-peak/peak tables from dsh-claude-style prices.json)
        OfficialModelPrice(
            id = "deepseek-v4-pro", name = "DeepSeek V4 Pro",
            aliases = listOf("deepseek-pro"),
            input = PriceRange(0.66, 1.32),
            cachedInput = PriceRange(0.022, 0.044),
            cacheWrite = PriceRange(0.66, 1.32),
            output = PriceRange(1.98, 3.96),
            inputCny = PriceRange(4.5, 9.0),
            cachedInputCny = PriceRange(0.15, 0.30),
            cacheWriteCny = PriceRange(4.5, 9.0),
            outputCny = PriceRange(13.5, 27.0),
            sourceUrl = deepSeekPrices
        ),
        OfficialModelPrice(
            id = "deepseek-flash", name = "DeepSeek Flash",
            aliases = listOf("deepseek-v4-flash", "deepseek-v4.1-flash", "deepseek-chat", "deepseek-reasoner", "deepseek-v3", "deepseek-r1"),
            input = PriceRange(0.15, 0.30),
            cachedInput = PriceRange(0.003, 0.006),
            cacheWrite = PriceRange(0.15, 0.30),
            output = PriceRange(0.60, 1.20),
            inputCny = PriceRange(1.0, 2.0),
            cachedInputCny = PriceRange(0.02, 0.04),
            cacheWriteCny = PriceRange(1.0, 2.0),
            outputCny = PriceRange(4.0, 8.0),
            sourceUrl = deepSeekPrices
        ),

        // Moonshot Kimi (from dsh-claude-style prices.json catalogCny)
        OfficialModelPrice(
            id = "kimi-k3.1-pro", name = "Kimi K3.1 Pro",
            aliases = listOf("k3.1-pro", "kimi-k3-1-pro"),
            input = PriceRange(1.80), cachedInput = PriceRange(0.18), output = PriceRange(9.44),
            inputCny = PriceRange(13.0), cachedInputCny = PriceRange(1.3), cacheWriteCny = PriceRange(13.0), outputCny = PriceRange(68.0),
            sourceUrl = kimiPrices
        ),
        OfficialModelPrice(
            id = "kimi-k3", name = "Kimi K3",
            aliases = listOf("k3", "k3-256k", "kimi-k2", "kimi-k2.6", "kimi-k2.7"),
            input = PriceRange(2.78), cachedInput = PriceRange(0.28), output = PriceRange(13.89),
            inputCny = PriceRange(20.0), cachedInputCny = PriceRange(2.0), cacheWriteCny = PriceRange(20.0), outputCny = PriceRange(100.0),
            sourceUrl = kimiPrices
        ),

        // Z.ai GLM (from dsh-claude-style prices.json)
        OfficialModelPrice(
            id = "glm-5.3", name = "GLM-5.3",
            aliases = listOf("glm-5-3", "zai-glm-5-3", "glm-5", "glm-5.1", "glm-5.2"),
            input = PriceRange(1.40), cachedInput = PriceRange(0.14), output = PriceRange(4.40),
            sourceUrl = zaiPrices
        ),
        OfficialModelPrice(
            id = "glm-5.3-flash", name = "GLM-5.3 Flash",
            aliases = listOf("glm-5-3-flash", "zai-glm-5-3-flash", "glm-4.7-flash", "glm-4-flash"),
            input = PriceRange(0.15), cachedInput = PriceRange(0.03), output = PriceRange(0.50),
            sourceUrl = zaiPrices
        ),

        // Alibaba Qwen
        OfficialModelPrice(
            id = "qwen3.8-max", name = "Qwen 3.8 Max",
            aliases = listOf("qwen-max", "qwen3-max", "qwen3.7-max", "qwen-plus"),
            input = PriceRange(1.20), cachedInput = PriceRange(0.24), output = PriceRange(4.80),
            sourceUrl = qwenPrices
        ),
        OfficialModelPrice(
            id = "qwen3.8-flash", name = "Qwen 3.8 Flash",
            aliases = listOf("qwen-flash", "qwen-turbo", "qwen-coder"),
            input = PriceRange(0.15), cachedInput = PriceRange(0.03), output = PriceRange(0.60),
            sourceUrl = qwenPrices
        ),

        // xAI Grok
        OfficialModelPrice(
            id = "grok-4.6", name = "Grok 4.6",
            aliases = listOf("grok-4", "grok-4.7", "grok-3"),
            input = PriceRange(2.10), cachedInput = PriceRange(0.525), output = PriceRange(6.30),
            sourceUrl = xaiPrices
        ),

        // Xiaomi MiMo
        OfficialModelPrice(
            id = "xiaomi-mimo-v2.5", name = "Xiaomi MiMo v2.5",
            aliases = listOf("mimo-v2.5", "xiaomi-mimo-v2-5"),
            input = PriceRange(0.40), cachedInput = PriceRange(0.08), output = PriceRange(2.00),
            sourceUrl = "https://api.xiaomimimo.com"
        ),

        // Mistral
        OfficialModelPrice(
            id = "mistral-large-3", name = "Mistral Large 3",
            aliases = listOf("mistral-large", "codestral"),
            input = PriceRange(2.00), cachedInput = PriceRange(0.40), output = PriceRange(6.00),
            sourceUrl = mistralPrices
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

    fun estimate(
        usage: ModelUsageTotal,
        date: LocalDate = LocalDate.now(),
        customRows: List<CustomBillingRow> = emptyList(),
        customCurrency: String = BILLING_CURRENCY_CNY
    ): UsagePriceEstimate? {
        val custom = CustomBillingTable.rowFor(customRows, usage.model)
        val basePrice = priceFor(usage.model, date)
        if (basePrice == null && custom == null) return null

        val input = usage.inputTokens.coerceAtLeast(0)
        val cached = usage.cachedTokens.coerceIn(0, input)
        val cacheWrite = usage.cacheWriteTokens.coerceIn(0, (input - cached).coerceAtLeast(0))
        val regular = (input - cached - cacheWrite).coerceAtLeast(0)
        val output = usage.outputTokens.coerceAtLeast(0)
        val million = 1_000_000.0

        if (custom != null) {
            val factorToUsd = if (customCurrency == BILLING_CURRENCY_CNY) 1.0 / USD_TO_CNY else 1.0
            val factorToCny = if (customCurrency == BILLING_CURRENCY_CNY) 1.0 else USD_TO_CNY
            val baseIn = basePrice?.inputFor(customCurrency) ?: PriceRange(0.0)
            val baseCache = basePrice?.cachedInputFor(customCurrency) ?: baseIn
            val baseWrite = basePrice?.cacheWriteFor(customCurrency) ?: baseIn
            val baseOut = basePrice?.outputFor(customCurrency) ?: PriceRange(0.0)

            val effIn = custom.input?.let { PriceRange(it) } ?: baseIn
            val effCache = custom.cacheRead?.let { PriceRange(it) } ?: baseCache
            val effWrite = custom.input?.let { PriceRange(it) } ?: baseWrite
            val effOut = custom.output?.let { PriceRange(it) } ?: baseOut

            if (effIn.high == 0.0 && effOut.high == 0.0) return null

            val lowInCustom = (regular * effIn.low + cached * effCache.low + cacheWrite * effWrite.low + output * effOut.low) / million
            val highInCustom = (regular * effIn.high + cached * effCache.high + cacheWrite * effWrite.high + output * effOut.high) / million

            val resolvedPrice = basePrice ?: OfficialModelPrice(
                id = custom.model,
                name = custom.model,
                input = PriceRange(effIn.low * factorToUsd, effIn.high * factorToUsd),
                cachedInput = PriceRange(effCache.low * factorToUsd, effCache.high * factorToUsd),
                cacheWrite = PriceRange(effWrite.low * factorToUsd, effWrite.high * factorToUsd),
                output = PriceRange(effOut.low * factorToUsd, effOut.high * factorToUsd),
                inputCny = PriceRange(effIn.low * factorToCny, effIn.high * factorToCny),
                cachedInputCny = PriceRange(effCache.low * factorToCny, effCache.high * factorToCny),
                cacheWriteCny = PriceRange(effWrite.low * factorToCny, effWrite.high * factorToCny),
                outputCny = PriceRange(effOut.low * factorToCny, effOut.high * factorToCny),
                sourceUrl = ""
            )
            return UsagePriceEstimate(
                lowUsd = lowInCustom * factorToUsd,
                highUsd = highInCustom * factorToUsd,
                lowCny = lowInCustom * factorToCny,
                highCny = highInCustom * factorToCny,
                price = resolvedPrice,
                notice = "使用自定义计费表费率",
                customOverride = true
            )
        }

        val price = basePrice!!
        val lowUsd = (
            regular * price.input.low + cached * price.cachedInput.low + cacheWrite * price.cacheWrite.low +
                output * price.output.low
            ) / million
        val highUsd = (
            regular * price.input.high + cached * price.cachedInput.high + cacheWrite * price.cacheWrite.high +
                output * price.output.high
            ) / million

        val inCny = price.inputFor(BILLING_CURRENCY_CNY)
        val cacheCny = price.cachedInputFor(BILLING_CURRENCY_CNY)
        val writeCny = price.cacheWriteFor(BILLING_CURRENCY_CNY)
        val outCny = price.outputFor(BILLING_CURRENCY_CNY)
        val lowCny = (regular * inCny.low + cached * cacheCny.low + cacheWrite * writeCny.low + output * outCny.low) / million
        val highCny = (regular * inCny.high + cached * cacheCny.high + cacheWrite * writeCny.high + output * outCny.high) / million

        val notice = when (price.id) {
            "deepseek-flash", "deepseek-v4-pro" -> "官方价格随 UTC 时段变化（谷价 1× / 峰价 2×），金额为低峰至高峰区间"
            "gpt-5.6-sol" -> "促销价格目前仅确认至 2026-11-21"
            "gemini-4-argon" -> if (date < LocalDate.parse("2026-12-01")) "2026-12-01 起官方标准价调整为当前的两倍" else null
            "claude-haiku-5-5" -> "单次提示词超过 100K token 时执行较高价格，区间覆盖短/长上下文费率"
            else -> if ((usage.cachedTokens > 0 || usage.cacheWriteTokens > 0) && price.id.startsWith("gpt-")) {
                "缓存写入按官方短时至长时价格范围估算；加速、批处理和地域附加费未计"
            } else null
        }
        return UsagePriceEstimate(
            lowUsd = lowUsd,
            highUsd = highUsd,
            lowCny = lowCny,
            highCny = highCny,
            price = price,
            notice = notice
        )
    }

    /** Estimate a single message's cost in the chosen currency (`CNY` or `USD`). */
    fun estimateSingleMessage(
        model: String,
        usage: TokenUsage,
        currency: String,
        customRows: List<CustomBillingRow> = emptyList()
    ): UsagePriceEstimate? {
        if (currency == BILLING_CURRENCY_OFF) return null
        val total = ModelUsageTotal(
            model = model,
            requests = 1,
            reportedRequests = 1,
            inputTokens = usage.inputTokens.toLong(),
            cachedTokens = usage.cachedTokens.toLong(),
            cacheWriteTokens = usage.cacheWriteTokens.toLong(),
            outputTokens = usage.outputTokens.toLong()
        )
        return estimate(total, customRows = customRows, customCurrency = currency)
    }

    fun formatCost(low: Double, high: Double, currency: String): String {
        val symbol = if (currency == BILLING_CURRENCY_CNY) "¥" else "$"
        fun value(amount: Double): String = when {
            amount <= 0.0 -> "${symbol}0.00"
            amount < 0.0001 -> "< ${symbol}0.0001"
            amount < 0.01 -> "$symbol%.4f".format(Locale.US, amount)
            else -> "$symbol%.3f".format(Locale.US, amount)
        }
        return if (kotlin.math.abs(high - low) < 0.00005) value(low) else "${value(low)}–${value(high)}"
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
