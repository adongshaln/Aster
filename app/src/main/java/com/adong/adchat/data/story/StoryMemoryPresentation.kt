package com.adong.adchat.data.story

/** Shared by creative requests, organizer input and archive UI: never erase epistemic ownership. */
fun storyMemoryNatureLabel(record: StoryMemoryRecord): String = when (record.nature) {
    StoryMemoryNature.UserConfirmed -> "用户确认"
    StoryMemoryNature.ProseOccurred -> "正文记录"
    StoryMemoryNature.CharacterBelief -> "角色主观看法 · 不等于事实"
    StoryMemoryNature.Inference -> "未确认推断"
}

fun renderStoryMemory(record: StoryMemoryRecord): String {
    val kind = when (record.kind) {
        StoryMemoryKind.WorldFact -> "世界设定"
        StoryMemoryKind.CharacterProfile -> "人物"
        StoryMemoryKind.CurrentState -> "当前状态"
        StoryMemoryKind.DirectedRelationship -> "本轮有向关系观察"
        StoryMemoryKind.CharacterKnowledge -> "角色认知"
        StoryMemoryKind.PlotEvent -> "已发生剧情"
        StoryMemoryKind.OpenThread -> "未完线索"
        StoryMemoryKind.AuthorPlan -> "作者计划 · 尚未发生，不得提前兑现或当作角色已知"
        StoryMemoryKind.Summary -> "剧情摘要"
    }
    val owner = record.subjectEntityNames.firstOrNull() ?: "归属未明确，不得推定角色知情"
    val ownership = when {
        record.kind == StoryMemoryKind.CharacterKnowledge || record.nature == StoryMemoryNature.CharacterBelief ->
            "；认知主体：$owner；不得扩散为其他角色已知"
        record.kind == StoryMemoryKind.DirectedRelationship ->
            "；方向：$owner → ${record.objectEntityNames.firstOrNull() ?: "对象未明确"}；不代表反向或永久关系"
        record.subjectEntityNames.isNotEmpty() -> "；主体：$owner"
        else -> ""
    }
    return "• [$kind / ${storyMemoryNatureLabel(record)}$ownership；来源轮次 ${record.effectiveSequence}] ${record.content.trim()}"
}
