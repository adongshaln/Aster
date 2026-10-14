package com.adong.adchat.data

/**
 * 输入建议的纯逻辑：从对话历史裁出预测上下文、清洗模型返回的预测文本。
 * 网络请求在 ApiRepository.suggestNextMessage，这里不发起任何 IO；
 * 预测模型由设置指定（ApiProfile.suggestionModel），不在此硬编码。
 */
internal object InputSuggester {
    const val SUGGESTION_INSTRUCTION = "你是一个输入预测器。分析上面的对话：助手的回复刚结束，用户正在考虑下一步。预测用户此刻最可能输入的下一条消息。要求：1)必须站在用户视角，说用户想解决的问题，绝不要延续助手的话题惯性 2)符合用户此前消息的口吻（口语/书面、长短） 3)一条消息，不超过30字 4)只输出消息文本本身，无引号无解释。如果对话已自然收尾、用户大概率不再继续，只输出：结束"
    const val SUGGESTION_PLACEHOLDER = "[在此预测用户下一条消息]"
    private const val END_MARK = "结束"
    private const val MAX_SUGGESTION_LENGTH = 60
    private const val USER_CLIP = 100
    private const val ASSISTANT_CLIP = 500

    /**
     * 只取最近 2 轮问答（最多 4 条消息）。错误气泡、流式占位、应用代发的「继续生成」
     * 指令和纯图片消息都不是"用户问过 / 助手答过"的真实文本，剔除后再按角色截断。
     */
    fun buildSuggestHistory(messages: List<ChatMessage>): List<ChatMessage> {
        val turns = messages.filter { message ->
            (message.role == "user" || message.role == "assistant") &&
                !message.isError && !message.isStreaming && !message.isContinuation &&
                message.content.isNotBlank()
        }
        return turns.takeLast(4).map { message ->
            val clip = if (message.role == "user") USER_CLIP else ASSISTANT_CLIP
            message.copy(content = message.content.trim().take(clip))
        }
    }

    /** 「结束」= 对话已收尾，不显示建议；其余去掉包裹引号后截到 60 字符。 */
    fun cleanSuggestion(raw: String?): String? {
        val text = raw.orEmpty().trim().trim('"', '“', '”')
        if (text.isBlank() || text == END_MARK) return null
        return text.take(MAX_SUGGESTION_LENGTH)
    }
}
