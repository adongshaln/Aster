package com.adong.adchat.data

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.adong.adchat.R
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Ported from Nwflower/dsh-claude-style (`packages/client/data/model-descriptions.json`,
 * `packages/client/src/features/model/model-copy.ts`, and `packages/client/src/features/peakrate/`):
 *
 * 1. Ordered, anchored brand rules resolved against a model's ID and display name (D79):
 *    recognizes all 26 model vendors/lines with their official vector mark & brand tint.
 * 2. Ordered, anchored family copy rules + tier fallback rules (D5):
 *    provides a concise, editorial one-line Chinese description for each model.
 * 3. Peak-rate pricing clock (`peakrate`):
 *    evaluates real-time UTC peak/off-peak status and countdowns for DeepSeek models.
 */
enum class ModelBrand(
    val id: String,
    val displayName: String,
    @DrawableRes val iconRes: Int,
    val lightTint: Color,
    val darkTint: Color
) {
    DeepSeek("deepseek", "DeepSeek", R.drawable.ic_brand_deepseek, Color(0xFF4D6BFE), Color(0xFF6B85FF)),
    Claude("claude", "Claude", R.drawable.ic_brand_claude, Color(0xFFD97757), Color(0xFFE28769)),
    OpenAI("openai", "OpenAI", R.drawable.ic_brand_openai, Color(0xFF10A37F), Color(0xFF2CC29D)),
    NanoBanana("nanobanana", "Nano Banana", R.drawable.ic_brand_nanobanana, Color(0xFFD99B00), Color(0xFFFABC12)),
    Gemma("gemma", "Gemma", R.drawable.ic_brand_gemma, Color(0xFF446EFF), Color(0xFF6B8DFF)),
    Gemini("gemini", "Gemini", R.drawable.ic_brand_gemini, Color(0xFF3186FF), Color(0xFF589DFF)),
    Zai("zai", "GLM", R.drawable.ic_brand_zai, Color(0xFF3B66FF), Color(0xFF6688FF)),
    Kimi("kimi", "Kimi", R.drawable.ic_brand_kimi, Color(0xFF1783FF), Color(0xFF459BFF)),
    Qwen("qwen", "Qwen", R.drawable.ic_brand_qwen, Color(0xFF6336E7), Color(0xFF8B66F5)),
    StepFun("stepfun", "StepFun", R.drawable.ic_brand_stepfun, Color(0xFF01A9FF), Color(0xFF3DBEFF)),
    MiniMax("minimax", "MiniMax", R.drawable.ic_brand_minimax, Color(0xFFE2167E), Color(0xFFF04E9E)),
    Meta("meta", "Meta", R.drawable.ic_brand_meta, Color(0xFF0867DF), Color(0xFF388BFF)),
    CelestoAi("celestoai", "Celesto", R.drawable.ic_brand_celestoai, Color(0xFF141413), Color(0xFFF0EFEA)),
    Inception("inception", "Inception", R.drawable.ic_brand_inception, Color(0xFF141413), Color(0xFFF0EFEA)),
    Grok("grok", "Grok", R.drawable.ic_brand_grok, Color(0xFF141413), Color(0xFFF0EFEA)),
    ByteDance("bytedance", "Doubao", R.drawable.ic_brand_bytedance, Color(0xFF0084FF), Color(0xFF3C9CFF)),
    Baidu("baidu", "ERNIE", R.drawable.ic_brand_baidu, Color(0xFF2932E1), Color(0xFF5C65FF)),
    Tencent("tencent", "Hunyuan", R.drawable.ic_brand_tencent, Color(0xFF0055E9), Color(0xFF3D82FF)),
    Mistral("mistral", "Mistral", R.drawable.ic_brand_mistral, Color(0xFFFA500F), Color(0xFFFF733D)),
    Cohere("cohere", "Cohere", R.drawable.ic_brand_cohere, Color(0xFF39594D), Color(0xFFD18EE2)),
    Microsoft("microsoft", "Phi", R.drawable.ic_brand_microsoft, Color(0xFF00A4EF), Color(0xFF38B8F5)),
    Nova("nova", "Amazon Nova", R.drawable.ic_brand_nova, Color(0xFFFF6200), Color(0xFFFF8533)),
    XiaomiMiMo("xiaomimimo", "MiMo", R.drawable.ic_brand_xiaomimimo, Color(0xFFFF6900), Color(0xFFFF8533)),
    LongCat("longcat", "LongCat", R.drawable.ic_brand_longcat, Color(0xFF141413), Color(0xFFF0EFEA)),
    Perplexity("perplexity", "Perplexity", R.drawable.ic_brand_perplexity, Color(0xFF22B8CD), Color(0xFF45CEE0)),
    Reflection("reflection", "Reflection", R.drawable.ic_brand_reflection, Color(0xFF2E5410), Color(0xFF7BA644));

    fun tint(dark: Boolean): Color = if (dark) darkTint else lightTint
}

data class PeakRateBadgeState(
    val isPeak: Boolean,
    val badge: String,
    val periodName: String,
    val countdownLabel: String,
    val fullSummary: String
)

object ModelCatalogCopy {
    private data class BrandRule(val regex: Regex, val brand: ModelBrand)
    private data class CopyRule(val regex: Regex, val zh: String)

    // Ordered brand rules from dsh-claude-style `model-descriptions.json`
    private val brandRules: List<BrandRule> = listOf(
        BrandRule(Regex("deepseek", RegexOption.IGNORE_CASE), ModelBrand.DeepSeek),
        BrandRule(Regex("claude", RegexOption.IGNORE_CASE), ModelBrand.Claude),
        BrandRule(Regex("gpt|codex|(^|[^a-z0-9])o[1-9](-|$)", RegexOption.IGNORE_CASE), ModelBrand.OpenAI),
        BrandRule(Regex("nano-?banana", RegexOption.IGNORE_CASE), ModelBrand.NanoBanana),
        BrandRule(Regex("gemma", RegexOption.IGNORE_CASE), ModelBrand.Gemma),
        BrandRule(Regex("gemini", RegexOption.IGNORE_CASE), ModelBrand.Gemini),
        BrandRule(Regex("glm", RegexOption.IGNORE_CASE), ModelBrand.Zai),
        BrandRule(Regex("kimi|(^|[^a-z0-9])k3([._-]?\\d)?($|-)", RegexOption.IGNORE_CASE), ModelBrand.Kimi),
        BrandRule(Regex("qwen", RegexOption.IGNORE_CASE), ModelBrand.Qwen),
        BrandRule(Regex("step", RegexOption.IGNORE_CASE), ModelBrand.StepFun),
        BrandRule(Regex("minimax|(^|[^a-z0-9])m[23]($|-)", RegexOption.IGNORE_CASE), ModelBrand.MiniMax),
        BrandRule(Regex("muse-spark|llama", RegexOption.IGNORE_CASE), ModelBrand.Meta),
        BrandRule(Regex("celeris", RegexOption.IGNORE_CASE), ModelBrand.CelestoAi),
        BrandRule(Regex("mercury|inception", RegexOption.IGNORE_CASE), ModelBrand.Inception),
        BrandRule(Regex("grok", RegexOption.IGNORE_CASE), ModelBrand.Grok),
        BrandRule(Regex("doubao|seed|volc", RegexOption.IGNORE_CASE), ModelBrand.ByteDance),
        BrandRule(Regex("ernie", RegexOption.IGNORE_CASE), ModelBrand.Baidu),
        BrandRule(Regex("hunyuan|(^|[^a-z0-9])hy(\\d|-?mt)", RegexOption.IGNORE_CASE), ModelBrand.Tencent),
        BrandRule(Regex("mistral|magistral|codestral", RegexOption.IGNORE_CASE), ModelBrand.Mistral),
        BrandRule(Regex("north|command-?[ar]|cohere", RegexOption.IGNORE_CASE), ModelBrand.Cohere),
        BrandRule(Regex("phi-?\\d", RegexOption.IGNORE_CASE), ModelBrand.Microsoft),
        BrandRule(Regex("(^|[^a-z0-9])nova(-|$)", RegexOption.IGNORE_CASE), ModelBrand.Nova),
        BrandRule(Regex("mimo", RegexOption.IGNORE_CASE), ModelBrand.XiaomiMiMo),
        BrandRule(Regex("longcat", RegexOption.IGNORE_CASE), ModelBrand.LongCat),
        BrandRule(Regex("sonar|perplexity", RegexOption.IGNORE_CASE), ModelBrand.Perplexity),
        BrandRule(Regex("beam|reflection", RegexOption.IGNORE_CASE), ModelBrand.Reflection)
    )

    // Ordered family rules from dsh-claude-style `model-descriptions.json`
    private val familyRules: List<CopyRule> = listOf(
        CopyRule(Regex("deepseek.*flash", RegexOption.IGNORE_CASE), "非对称模型结构，小成本大智能。"),
        CopyRule(Regex("deepseek.*pro", RegexOption.IGNORE_CASE), "性能比肩顶级闭源模型。"),
        CopyRule(Regex("deepseek", RegexOption.IGNORE_CASE), "更强、更快、更普惠。"),

        CopyRule(Regex("claude.*fable", RegexOption.IGNORE_CASE), "新一代智能，专为解决最艰深的知识工作和编程难题。"),
        CopyRule(Regex("claude.*mythos", RegexOption.IGNORE_CASE), "面向网络安全与生物学研究，仅向经过审核的机构开放。"),
        CopyRule(Regex("claude.*opus", RegexOption.IGNORE_CASE), "面向复杂编程与 AI 智能体的混合推理模型。"),
        CopyRule(Regex("claude.*sonnet", RegexOption.IGNORE_CASE), "面向实时智能体与高吞吐场景的混合推理模型。"),
        CopyRule(Regex("claude.*haiku", RegexOption.IGNORE_CASE), "面向速度敏感的高吞吐场景，响应快、成本低。"),
        CopyRule(Regex("claude", RegexOption.IGNORE_CASE), "擅长编程、写作与复杂分析。"),

        CopyRule(Regex("codex", RegexOption.IGNORE_CASE), "为 Codex 智能体编程优化，可并行处理多项软件工程任务。"),
        CopyRule(Regex("gpt.*astra", RegexOption.IGNORE_CASE), "面向计算机操作、编程、网络安全与科学研究中最艰深的问题。"),
        CopyRule(Regex("gpt.*sol", RegexOption.IGNORE_CASE), "面向日常工作，擅长写作、编程与分析。"),
        CopyRule(Regex("gpt.*luna", RegexOption.IGNORE_CASE), "响应快、成本低，适合高频与可重复的任务。"),
        CopyRule(Regex("gpt[._-]?5[._-]?4(?!\\d)", RegexOption.IGNORE_CASE), "面向编程与专业工作，原生支持计算机操作。"),
        CopyRule(Regex("gpt-?5", RegexOption.IGNORE_CASE), "内置思考、可配置推理强度，面向编程与智能体任务。"),
        CopyRule(Regex("(^|[/-])o[1-9](-|$)", RegexOption.IGNORE_CASE), "作答前先深入思考，擅长多步推理。"),
        CopyRule(Regex("gpt-oss|oss-\\d", RegexOption.IGNORE_CASE), "OpenAI 的开放权重推理模型，Apache 2.0 许可，可自托管。"),
        CopyRule(Regex("gpt", RegexOption.IGNORE_CASE), "支持文本与图像输入，覆盖多种语言。"),

        CopyRule(Regex("gemini.*argon", RegexOption.IGNORE_CASE), "面向真实软件工程、企业知识工作与网络安全防御的前沿模型，擅长长程复杂任务。"),
        CopyRule(Regex("gemini.*(pro|ultra)", RegexOption.IGNORE_CASE), "面向复杂任务、深度推理与多模态理解。"),
        CopyRule(Regex("gemini.*flash", RegexOption.IGNORE_CASE), "速度与成本优先，面向规模化智能体任务与高频低延迟场景。"),
        CopyRule(Regex("gemini", RegexOption.IGNORE_CASE), "原生理解文本、图像、视频与音频。"),
        CopyRule(Regex("gemma", RegexOption.IGNORE_CASE), "源自 Gemini 研究，从云端服务器到手机都能运行。"),
        CopyRule(Regex("nano-?banana", RegexOption.IGNORE_CASE), "Gemini 图像生成与编辑模型，支持自然语言对话式创建与修改。"),

        CopyRule(Regex("glm.*flashx", RegexOption.IGNORE_CASE), "Flash 档的高吞吐版本，适合高频任务。"),
        CopyRule(Regex("glm[._-]?5[._-]?3.*flash", RegexOption.IGNORE_CASE), "该系列首个原生多模态模型，320B 总参数，MIT 许可开放权重。"),
        CopyRule(Regex("glm[._-]?5[._-]?3(?!\\d)", RegexOption.IGNORE_CASE), "最强的开放权重编程模型之一，擅长长时间自主任务。"),
        CopyRule(Regex("glm.*flash", RegexOption.IGNORE_CASE), "更快更省的多模态推理，兼顾日常编程。"),
        CopyRule(Regex("glm", RegexOption.IGNORE_CASE), "覆盖对话、编程与智能体，支持思考模式与函数调用。"),

        CopyRule(Regex("kimi.*k3[._-]?1(?!\\d)|(^|[^a-z0-9])k3[._-]?1(?!\\d)", RegexOption.IGNORE_CASE), "开放权重模型，以代码、金融研究与长程 Agent 任务见长。"),
        CopyRule(Regex("kimi.*(k3|k-?3)|(^|[^a-z0-9])k-?3", RegexOption.IGNORE_CASE), "面向编程与知识工作的 2.8T 开放权重模型。"),
        CopyRule(Regex("kimi.*(cod(e|ing)|k2[._-]?7).*highspeed", RegexOption.IGNORE_CASE), "长程编程智能体模型的高速版本，开放权重。"),
        CopyRule(Regex("kimi.*(cod(e|ing)|k2[._-]?7)", RegexOption.IGNORE_CASE), "面向长程编程的开放权重智能体模型，1T 总参数。"),
        CopyRule(Regex("kimi", RegexOption.IGNORE_CASE), "原生支持文本、图像与视频输入，含思考模式与工具调用。"),

        CopyRule(Regex("qwen[._-]?3[._-]?8.*max", RegexOption.IGNORE_CASE), "面向编程与办公的 2.4 万亿参数 MoE，支持思考模式与内置工具。"),
        CopyRule(Regex("qwen.*(coder|code)", RegexOption.IGNORE_CASE), "开放权重 MoE，面向补全、生成与仓库级修改。"),
        CopyRule(Regex("qwen.*(flash|turbo)", RegexOption.IGNORE_CASE), "响应快、成本低，工具调用支持完整。"),
        CopyRule(Regex("qwen.*plus", RegexOption.IGNORE_CASE), "能力与成本均衡，完整工具调用支持。"),
        CopyRule(Regex("qwen.*a\\d+b", RegexOption.IGNORE_CASE), "MoE 架构，单位成本低于同规模稠密模型。"),
        CopyRule(Regex("qwen.*[-_]\\d+b($|[^a-z])", RegexOption.IGNORE_CASE), "可本地或低成本部署。"),
        CopyRule(Regex("qwen", RegexOption.IGNORE_CASE), "开放权重与云端 API 并行，覆盖文本、图像、音频与视频。"),

        CopyRule(Regex("step[._-]?5(?!\\d)", RegexOption.IGNORE_CASE), "面向编程与专业知识工作，原生支持文本、图像与视频输入。"),
        CopyRule(Regex("step[._-]?3[._-]?7.*flash", RegexOption.IGNORE_CASE), "面向智能体与代码任务的 198B 稀疏 MoE，支持图像与视频理解。"),
        CopyRule(Regex("step", RegexOption.IGNORE_CASE), "覆盖文本推理、多模态与语音，经官方开放平台提供。"),

        CopyRule(Regex("minimax.*m3|(^|[^a-z0-9])m3($|-)", RegexOption.IGNORE_CASE), "兼具前沿编程能力与原生多模态的开放权重模型。"),
        CopyRule(Regex("minimax|(^|[^a-z0-9])m2[-.]?\\d", RegexOption.IGNORE_CASE), "主打智能体推理、工具调用、编程与多模态输入。"),

        CopyRule(Regex("muse-spark", RegexOption.IGNORE_CASE), "小而快、原生多模态推理，驱动 Meta AI。"),
        CopyRule(Regex("celeris", RegexOption.IGNORE_CASE), "扩散架构大语言模型，主打超低延迟生成。"),
        CopyRule(Regex("mercury-?\\d|inception", RegexOption.IGNORE_CASE), "扩散架构，面向代码生成，以极快的生成速度见长。"),
        CopyRule(Regex("apodex", RegexOption.IGNORE_CASE), "面向复杂专业工作、金融与科研。"),
        CopyRule(Regex("motif", RegexOption.IGNORE_CASE), "MIT 许可的开放权重 MoE 模型。"),
        CopyRule(Regex("grok[._-]?4(?!\\d)", RegexOption.IGNORE_CASE), "全球最智能的模型之一，原生支持工具调用与实时搜索。"),
        CopyRule(Regex("grok", RegexOption.IGNORE_CASE), "覆盖编程、智能体与知识工作，API 支持多模态输入。"),
        CopyRule(Regex("doubao.*2[._-]?1(?!\\d)|seed[._-]?2[._-]?1(?!\\d)", RegexOption.IGNORE_CASE), "分 Pro 与 Turbo 两档的生产力智能体模型，擅长通用任务与代码。"),
        CopyRule(Regex("doubao|seed-?\\d|volc", RegexOption.IGNORE_CASE), "覆盖对话、编程与多模态，经大规模线上业务验证。"),
        CopyRule(Regex("ernie[._-]?5([._-]?0)?(-|$)", RegexOption.IGNORE_CASE), "2.4 万亿参数的原生全模态大模型。"),
        CopyRule(Regex("ernie", RegexOption.IGNORE_CASE), "产业级知识增强，支持中文理解与多模态生成。"),
        CopyRule(Regex("hunyuan|(^|[^a-z0-9])hy\\d", RegexOption.IGNORE_CASE), "中文理解与智能体场景表现均衡。"),
        CopyRule(Regex("codestral", RegexOption.IGNORE_CASE), "面向代码补全、FIM 与生成。"),
        CopyRule(Regex("mistral.*large[._-]?3(?!\\d)", RegexOption.IGNORE_CASE), "Apache 2.0 许可的开放权重多模态 MoE，675B 总参数。"),
        CopyRule(Regex("mistral|magistral", RegexOption.IGNORE_CASE), "从云端到边缘的前沿模型，开放权重与商用并行，可 API 调用或自托管。"),
        CopyRule(Regex("llama", RegexOption.IGNORE_CASE), "可自由微调、蒸馏并部署到任何环境。"),
        CopyRule(Regex("north", RegexOption.IGNORE_CASE), "把大模型、搜索与自动化整合进同一个企业级安全工作空间。"),
        CopyRule(Regex("command-?[ar]|cohere", RegexOption.IGNORE_CASE), "面向工具调用、RAG 与多语言任务。"),
        CopyRule(Regex("phi-?\\d", RegexOption.IGNORE_CASE), "面向边缘与本地部署的小模型，擅长推理、数学与代码。"),
        CopyRule(Regex("(^|[^a-z0-9])nova(-|$)", RegexOption.IGNORE_CASE), "亚马逊的多模态模型，支持文本、图像与视频输入。"),
        CopyRule(Regex("sonar|perplexity", RegexOption.IGNORE_CASE), "结合实时网络信息作答并附上引用来源，覆盖轻量问答到多步深度研究。")
    )

    // Tier fallback rules from dsh-claude-style `model-descriptions.json`
    private val tierRules: List<CopyRule> = listOf(
        CopyRule(Regex("flash|mini|lite|nano|turbo|air|small|swift|highspeed|fast|instant", RegexOption.IGNORE_CASE), "响应快、成本低，适合简单或高频任务。"),
        CopyRule(Regex("pro|max|ultra|opus|thinking|reasoner|plus|large|advanced|heavy|frontier", RegexOption.IGNORE_CASE), "适合复杂或质量优先的任务，成本更高。"),
        CopyRule(Regex("a\\d+b", RegexOption.IGNORE_CASE), "MoE 架构，单位成本低于同规模稠密模型。"),
        CopyRule(Regex("(^|[-_])\\d+b($|[^a-z])", RegexOption.IGNORE_CASE), "可本地或低成本部署。")
    )

    private fun candidates(modelId: String, modelName: String = "", provider: String = ""): List<String> {
        val id = modelId.trim().lowercase(Locale.ROOT)
        val name = modelName.trim().lowercase(Locale.ROOT)
        val prov = provider.trim().lowercase(Locale.ROOT)
        return buildList {
            if (id.isNotEmpty()) {
                add(id)
                if (prov.isNotEmpty()) add("$prov/$id")
            }
            if (name.isNotEmpty() && name != id) add(name)
        }
    }

    /** Resolve the vendor brand for a model (D79: model ID first, then display name; never provider route). */
    fun brandFor(modelId: String, modelName: String = ""): ModelBrand? {
        val list = candidates(modelId, modelName)
        for (candidate in list) {
            for (rule in brandRules) {
                if (rule.regex.containsMatchIn(candidate)) return rule.brand
            }
        }
        return null
    }

    /** Resolve the one-line editorial description for a model (D5: family rules -> tier rules -> null). */
    fun descriptionFor(modelId: String, modelName: String = "", provider: String = ""): String? {
        val familyCandidates = candidates(modelId, modelName, provider)
        for (candidate in familyCandidates) {
            for (rule in familyRules) {
                if (rule.regex.containsMatchIn(candidate)) return rule.zh
            }
        }
        val tierCandidates = candidates(modelId, modelName)
        for (candidate in tierCandidates) {
            for (rule in tierRules) {
                if (rule.regex.containsMatchIn(candidate)) return rule.zh
            }
        }
        return null
    }

    // ── DeepSeek Peak/Off-Peak Rate Clock (`peakrate` feature from dsh-claude-style) ──
    // UTC peak windows: Mon-Fri 01:00-04:00 UTC and 06:00-10:00 UTC (09:00-12:00 & 14:00-18:00 Beijing Time),
    // except Chinese public holidays (Asia/Shanghai calendar).
    private val chineseHolidays2026 = setOf(
        "2026-01-01", "2026-01-02", "2026-01-03",
        "2026-02-15", "2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19",
        "2026-02-20", "2026-02-21", "2026-02-22", "2026-02-23",
        "2026-04-04", "2026-04-05", "2026-04-06",
        "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05",
        "2026-06-19", "2026-06-20", "2026-06-21",
        "2026-09-25", "2026-09-26", "2026-09-27",
        "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04",
        "2026-10-05", "2026-10-06", "2026-10-07"
    )
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    fun isDeepSeekClockModel(modelId: String): Boolean {
        val lower = modelId.trim().lowercase(Locale.ROOT)
        return lower.contains("deepseek")
    }

    fun isDeepSeekPeakAt(epochMillis: Long = System.currentTimeMillis()): Boolean {
        val instant = Instant.ofEpochMilli(epochMillis)
        val shanghaiDate = ZonedDateTime.ofInstant(instant, ZoneId.of("Asia/Shanghai")).format(dateFormatter)
        if (shanghaiDate in chineseHolidays2026) return false
        val utc = ZonedDateTime.ofInstant(instant, ZoneId.of("UTC"))
        val dow = utc.dayOfWeek.value // 1 = Mon .. 7 = Sun
        if (dow !in 1..5) return false
        val minutes = utc.hour * 60 + utc.minute
        return (minutes in 60 until 240) || (minutes in 360 until 600)
    }

    /**
     * Evaluate the live Peak/Off-Peak badge state for a model at [epochMillis],
     * matching dsh-claude-style's `peakrate/badge.ts` (`峰价 2× · 1h 24m` / `谷价 1× · 3h 10m`).
     */
    fun peakRateBadgeFor(modelId: String, epochMillis: Long = System.currentTimeMillis()): PeakRateBadgeState? {
        if (!isDeepSeekClockModel(modelId)) return null
        val currentPeak = isDeepSeekPeakAt(epochMillis)
        // Search forward in 1-minute increments up to 9 days for the next transition
        val baseMinuteMs = (epochMillis / 60_000L) * 60_000L
        var minutesUntilSwitch = 0
        for (step in 1..(9 * 1440)) {
            val probeMs = baseMinuteMs + step * 60_000L
            if (isDeepSeekPeakAt(probeMs) != currentPeak) {
                minutesUntilSwitch = step
                break
            }
        }
        val countdown = formatCountdown(minutesUntilSwitch)
        val badge = if (currentPeak) "2×" else "1×"
        val periodName = if (currentPeak) "峰价" else "谷价"
        val nextName = if (currentPeak) "谷价 1×" else "峰价 2×"
        val fullSummary = if (countdown.isNotEmpty()) {
            "此刻 $periodName $badge · $countdown 后转为 $nextName"
        } else {
            "此刻 $periodName $badge"
        }
        return PeakRateBadgeState(
            isPeak = currentPeak,
            badge = badge,
            periodName = periodName,
            countdownLabel = countdown,
            fullSummary = fullSummary
        )
    }

    private fun formatCountdown(minutes: Int): String {
        if (minutes <= 0) return ""
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val mins = minutes % 60
        return when {
            days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
            hours > 0 -> if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
            else -> "${mins}m"
        }
    }
}
