package com.adong.adchat.data.story

/** An immutable quotation is discussion input, never a memory operation or prose revision. */
object StoryDiscussionQuote {
    fun append(draft: String, source: StoryMessageWithRevision, start: Int, end: Int): String {
        require(source.message.role == "assistant" && source.message.workspace == StoryWorkspace.Prose &&
            source.revision.workspace == StoryWorkspace.Prose && source.revision.state == StoryRevisionState.Complete) {
            "只能引用完整正文；停止或未完整结束的内容请先手动处理。"
        }
        val text = source.revision.content
        val from = minOf(start,end); val to = maxOf(start,end)
        require(from >= 0 && to <= text.length && from < to) { "请先选择要讨论的文字。" }
        fun boundary(index: Int) = index == 0 || index == text.length ||
            !(text[index-1].isHighSurrogate() && text[index].isLowSurrogate())
        require(boundary(from) && boundary(to)) { "选区截断了一个字符，请重新选择。" }
        val excerpt = text.substring(from,to)
        require(excerpt.isNotBlank() && excerpt.length <= 16_000) { "一次最多引用 16,000 字符，请缩小选区。" }
        val quote = "[正文引用 · 第 ${source.message.sequence} 条 · 仅供讨论]\n" +
            "以下是引用时的正文片段，不代表新增或已确认的设定；讨论意见不会自动改写正文。\n" +
            excerpt.lineSequence().joinToString("\n") { "> $it" } + "\n[引用结束]\n\n我的修改想法："
        val result = if(draft.isEmpty()) quote else draft + "\n\n" + quote
        require(result.length <= 40_000) { "讨论草稿过长，请先处理现有草稿或缩小选区。" }
        return result
    }
}
