package com.adong.adchat.data

import org.json.JSONObject

/**
 * 模型家族。各厂商对"思考强度"的参数名和取值完全不同，按家族匹配而不是让用户猜。
 * 依据 2026-10 各官方文档：
 * - GPT：`reasoning.effort`（Responses）/ `reasoning_effort`（Chat Completions），
 *   取值 minimal / low / medium / high / xhigh / max。
 * - Grok 4.5+：`reasoning_effort`，取值 low / medium / high / xhigh，默认 high，推理不可关闭；
 *   且 reasoning 模型不接受 presence_penalty / frequency_penalty / stop。
 * - Claude：`output_config.effort`（low / medium / high）；兼容旧式 `thinking.type` +
 *   `thinking.budget_tokens`（≥1024 且必须小于 max_tokens）。
 * - GLM：`thinking.type`（enabled / disabled）+ `reasoning_effort`
 *   （max / xhigh / high / medium / low / minimal / none，GLM-5.3 仅 low / high / max）。
 * - Kimi：kimi-k3 用 `reasoning_effort`（low / high / max）；kimi-k2.x 只有 `thinking.type` 开关。
 * - DeepSeek：`thinking.type`（enabled / disabled）+ `reasoning_effort`
 *   （low / high / max；Responses 侧为 reasoning.effort，none 表示关闭思考）。
 */
internal enum class ReasoningFamily { Gpt, Grok, Claude, Glm, KimiEffort, KimiToggle, DeepSeek, Unsupported }

/** 一个可选项；[id] 直接存进 ApiProfile.reasoningEffort。 */
internal data class ReasoningChoice(val id: String, val label: String, val hint: String = "")

/** 家族面板上展示的参数说明，让用户知道客户端到底发了什么。 */
internal data class ReasoningContract(val field: String, val values: String)

internal object ReasoningPolicy {

    const val DEFAULT = "default"

    private val GPT_CHOICES = listOf(
        ReasoningChoice(DEFAULT, "模型默认", "不传 effort，由服务端决定"),
        ReasoningChoice("minimal", "极简", "几乎不思考，最快"),
        ReasoningChoice("low", "快速", "少量思考"),
        ReasoningChoice("medium", "均衡", "默认档位"),
        ReasoningChoice("high", "深入", "更多思考"),
        ReasoningChoice("xhigh", "深度", "接近极限"),
        ReasoningChoice("max", "极致", "最大思考深度")
    )

    private val GROK_CHOICES = listOf(
        ReasoningChoice(DEFAULT, "模型默认", "Grok 默认 high"),
        ReasoningChoice("low", "快速", "省思考 Token，适合简单工具调用"),
        ReasoningChoice("medium", "均衡", "复杂分析与长上下文"),
        ReasoningChoice("high", "深入", "多步逻辑与复杂计算"),
        ReasoningChoice("xhigh", "极致", "最大推理深度，延迟明显更高")
    )

    private val CLAUDE_CHOICES = listOf(
        ReasoningChoice(DEFAULT, "模型默认", "Opus 系默认 high，其余默认 medium"),
        ReasoningChoice("low", "快速", "短思考预算，响应更快"),
        ReasoningChoice("medium", "均衡", "中等思考预算"),
        ReasoningChoice("high", "深入", "长思考预算")
    )

    private val GLM_CHOICES = listOf(
        ReasoningChoice(DEFAULT, "模型默认", "GLM 默认 max"),
        ReasoningChoice("none", "关闭", "跳过思考"),
        ReasoningChoice("low", "快速", "低思考深度"),
        ReasoningChoice("medium", "均衡", "中等深度"),
        ReasoningChoice("high", "深入", "较高深度"),
        ReasoningChoice("xhigh", "深度", "接近极限"),
        ReasoningChoice("max", "极致", "默认档位")
    )

    private val KIMI_EFFORT_CHOICES = listOf(
        ReasoningChoice(DEFAULT, "模型默认", "Kimi K3 默认 max"),
        ReasoningChoice("low", "快速", "低推理投入"),
        ReasoningChoice("high", "深入", "高推理投入"),
        ReasoningChoice("max", "极致", "最大推理投入")
    )

    private val TOGGLE_CHOICES = listOf(
        ReasoningChoice(DEFAULT, "模型默认", "按服务端默认处理"),
        ReasoningChoice("off", "关闭", "本次不思考"),
        ReasoningChoice("on", "开启", "先思考再回答")
    )

    private val DEEPSEEK_CHOICES = listOf(
        ReasoningChoice(DEFAULT, "模型默认", "DeepSeek 默认 high"),
        ReasoningChoice("none", "关闭", "跳过思考"),
        ReasoningChoice("low", "快速", "低思考深度"),
        ReasoningChoice("high", "深入", "高思考深度"),
        ReasoningChoice("max", "极致", "最大思考深度")
    )

    fun family(model: String): ReasoningFamily {
        val value = model.trim().substringAfterLast('/').lowercase()
        return when {
            value.isBlank() -> ReasoningFamily.Unsupported
            value.isGptFamily() -> ReasoningFamily.Gpt
            value.isGrokFamily() -> ReasoningFamily.Grok
            value.isClaudeFamily() -> ReasoningFamily.Claude
            value.isGlmFamily() -> ReasoningFamily.Glm
            value.isKimiEffortFamily() -> ReasoningFamily.KimiEffort
            value.isKimiToggleFamily() -> ReasoningFamily.KimiToggle
            value.isDeepSeekFamily() -> ReasoningFamily.DeepSeek
            else -> ReasoningFamily.Unsupported
        }
    }

    fun isSupported(model: String): Boolean = family(model) != ReasoningFamily.Unsupported

    fun choices(model: String): List<ReasoningChoice> = when (family(model)) {
        ReasoningFamily.Gpt -> GPT_CHOICES
        ReasoningFamily.Grok -> GROK_CHOICES
        ReasoningFamily.Claude -> CLAUDE_CHOICES
        ReasoningFamily.Glm -> GLM_CHOICES
        ReasoningFamily.KimiEffort -> KIMI_EFFORT_CHOICES
        ReasoningFamily.KimiToggle -> TOGGLE_CHOICES
        ReasoningFamily.DeepSeek -> DEEPSEEK_CHOICES
        ReasoningFamily.Unsupported -> emptyList()
    }

    fun choiceOf(model: String, effort: String): ReasoningChoice? {
        val value = effort.trim().ifBlank { DEFAULT }
        return choices(model).firstOrNull { it.id == value }
            ?: choices(model).firstOrNull { it.id == DEFAULT }
            ?: choices(model).firstOrNull()
    }

    /**
     * 家族的最低思考档 id：输入建议这类小请求只要最快出结果，按模型家族取最省思考的档位。
     * 未知家族或档位表里没有对应 id（模型名变种防御）时返回 [DEFAULT]，即不写任何思考参数。
     */
    fun minimalEffort(model: String): String {
        val id = when (family(model)) {
            ReasoningFamily.Gpt -> "minimal"
            ReasoningFamily.Grok -> "low"
            ReasoningFamily.Claude -> "low"
            ReasoningFamily.Glm -> "none"
            ReasoningFamily.KimiEffort -> "low"
            ReasoningFamily.KimiToggle -> "off"
            ReasoningFamily.DeepSeek -> "none"
            ReasoningFamily.Unsupported -> return DEFAULT
        }
        return choices(model).firstOrNull { it.id == id }?.id ?: DEFAULT
    }

    fun contract(model: String, responsesApi: Boolean): ReasoningContract = when (family(model)) {
        ReasoningFamily.Gpt -> ReasoningContract(
            if (responsesApi) "reasoning.effort" else "reasoning_effort",
            "minimal / low / medium / high / xhigh / max"
        )
        ReasoningFamily.Grok -> ReasoningContract("reasoning_effort", "low / medium / high / xhigh")
        ReasoningFamily.Claude -> ReasoningContract(
            "thinking.type + thinking.budget_tokens",
            "budget_tokens ≥ 1024 且小于 max_tokens"
        )
        ReasoningFamily.Glm -> ReasoningContract("thinking.type + reasoning_effort", "enabled / disabled · none…max")
        ReasoningFamily.KimiEffort -> ReasoningContract("reasoning_effort", "low / high / max")
        ReasoningFamily.KimiToggle -> ReasoningContract("thinking.type", "enabled / disabled")
        ReasoningFamily.DeepSeek -> ReasoningContract("thinking.type + reasoning_effort", "enabled / disabled · low / high / max")
        ReasoningFamily.Unsupported -> ReasoningContract("", "")
    }

    /**
     * 把思考强度写进请求体。[effort] 为用户选择；[outputTokenLimit] 是本次请求的 max_tokens，
     * Claude 的思考预算必须严格小于它。档位不属于当前家族时按默认处理——切换模型后旧的取值
     * 可能是对方不认识的词，硬发只会换来 400。
     */
    fun write(
        body: JSONObject,
        model: String,
        effort: String,
        responsesApi: Boolean,
        outputTokenLimit: Int
    ) {
        val available = choices(model)
        val value = available.firstOrNull { it.id == effort.trim() }?.id ?: DEFAULT
        when (family(model)) {
            ReasoningFamily.Gpt -> {
                if (value == DEFAULT) return
                if (responsesApi) body.put("reasoning", JSONObject().put("effort", value))
                else body.put("reasoning_effort", value)
            }

            // Grok 文档只定义顶层 reasoning_effort；Responses 兼容端点同样接受顶层字段。
            ReasoningFamily.Grok -> {
                if (value == DEFAULT) return
                body.put("reasoning_effort", value)
            }

            ReasoningFamily.Claude -> {
                if (value == DEFAULT) return
                val budget = claudeBudget(value, outputTokenLimit)
                when {
                    budget != null -> body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", budget))
                    // 确实放不下思考预算才显式关掉；还不知道 max_tokens 时不表态，交给服务端。
                    outputTokenLimit > 0 -> body.put("thinking", JSONObject().put("type", "disabled"))
                    else -> Unit
                }
            }

            ReasoningFamily.Glm -> {
                if (value == DEFAULT) return
                body.put("thinking", JSONObject().put("type", if (value == "none") "disabled" else "enabled"))
                if (value != "none") body.put("reasoning_effort", value)
            }

            ReasoningFamily.KimiEffort -> {
                if (value == DEFAULT) return
                body.put("reasoning_effort", value)
            }

            ReasoningFamily.KimiToggle -> {
                if (value == DEFAULT) return
                body.put("thinking", JSONObject().put("type", if (value == "off") "disabled" else "enabled"))
            }

            ReasoningFamily.DeepSeek -> {
                if (value == DEFAULT) return
                body.put("thinking", JSONObject().put("type", if (value == "none") "disabled" else "enabled"))
                if (value != "none") body.put("reasoning_effort", value)
            }

            ReasoningFamily.Unsupported -> Unit
        }
    }

    /** xAI 明确拒绝推理模型携带这三个采样参数。 */
    fun forbidsSamplingControls(model: String): Boolean = family(model) == ReasoningFamily.Grok

    private fun claudeBudget(effort: String, outputTokenLimit: Int): Int? {
        val requested = when (effort) {
            "low" -> 2_048
            "medium" -> 8_192
            "high" -> 24_576
            else -> null
        } ?: return null
        val ceiling = if (outputTokenLimit > 2_048) outputTokenLimit - 1_024 else 0
        return requested.coerceAtMost(ceiling).takeIf { it >= 1_024 }
    }

    private fun String.isGptFamily(): Boolean = startsWith("gpt-", ignoreCase = true) ||
        startsWith("gpt_", ignoreCase = true) ||
        (length > 3 && startsWith("gpt", ignoreCase = true) && this[3].isDigit())

    private fun String.isGrokFamily(): Boolean = startsWith("grok", ignoreCase = true)

    private fun String.isClaudeFamily(): Boolean =
        startsWith("claude", ignoreCase = true) || contains("claude-", ignoreCase = true)

    private fun String.isGlmFamily(): Boolean = startsWith("glm", ignoreCase = true)

    private fun String.isKimiEffortFamily(): Boolean =
        contains("kimi", ignoreCase = true) && contains("k3", ignoreCase = true)

    private fun String.isKimiToggleFamily(): Boolean = contains("kimi", ignoreCase = true)

    private fun String.isDeepSeekFamily(): Boolean =
        startsWith("deepseek", ignoreCase = true) || contains("deepseek-", ignoreCase = true)
}
