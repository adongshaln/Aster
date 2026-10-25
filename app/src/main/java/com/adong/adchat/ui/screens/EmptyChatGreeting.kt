package com.adong.adchat.ui.screens

/**
 * 聊天空态的一句开场。时段只影响话题倾向，不猜测用户的作息或状态。
 *
 * 每个宽时段保留三个用途不同的入口，不把姓名和寒暄当作句式模板。
 * 调用方在进入空态或跨时段时生成 [roll]；本函数不随机，重组时文案保持稳定。
 */
internal fun emptyChatGreeting(hourOfDay: Int, roll: Int = 0): String {
    val candidates = openings[greetingBand(hourOfDay)]
    return candidates[((roll % candidates.size) + candidates.size) % candidates.size]
}

/** 宽时段减少刷新频率，也避免为相邻时段堆叠意思相同的寒暄。 */
internal fun greetingBand(hourOfDay: Int): Int {
    val hour = ((hourOfDay % 24) + 24) % 24
    return when (hour) {
        in 5..10 -> 0
        in 11..16 -> 1
        in 17..22 -> 2
        else -> 3
    }
}

private val openings = listOf(
    listOf(
        "今天想从哪件事开始？",
        "有什么想弄明白的？",
        "有个新想法？说来听听。",
    ),
    listOf(
        "有件事拿不定主意？",
        "想写点什么？",
        "最近有什么新鲜事？",
    ),
    listOf(
        "今天有什么值得记下来？",
        "故事的下一幕，会发生什么？",
        "换个角度看看？",
    ),
    listOf(
        "随便聊聊，也很好。",
        "没成形的想法，也可以聊。",
        "从你想写的那句话开始。",
    ),
)
