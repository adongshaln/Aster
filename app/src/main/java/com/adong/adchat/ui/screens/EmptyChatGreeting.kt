package com.adong.adchat.ui.screens

/**
 * 聊天空状态的时段问候。
 *
 * [hourOfDay] 为本地时间 0–23；[userName] 自动 trim，空称呼时省略姓名。
 * 每个时段一组候选句（带 {name} 占位的问候句 + 固定句），按 [roll] 在组内随机轮换；
 * 由调用方在进入空态或跨时段时重新随机，同一时段内保持稳定不闪句。
 *
 * 纯函数，便于单测；不要在 Composable 重组中随机选句。
 */
internal fun emptyChatGreeting(hourOfDay: Int, userName: String, roll: Int = 0): String {
    val h = ((hourOfDay % 24) + 24) % 24
    val name = userName.trim()
    return when (h) {
        in 5..7 -> pick(roll, name,
            withName = listOf(
                "早上好，{name}",
                "早安，{name}，新的一天从一句话开始",
                "{name}，早。今天想先做哪件事？",
            ),
            fixed = listOf("晨光正好，从什么开始？"),
        )
        in 8..10 -> pick(roll, name,
            withName = listOf(
                "上午好，{name}",
                "{name}，上午的思路最清楚，试试难题？",
            ),
            fixed = listOf(
                "上午好，想聊点什么？",
                "上午了，有什么想理清的事？",
            ),
        )
        in 11..13 -> pick(roll, name, withName = emptyList(),
            fixed = listOf(
                "咖啡加 Aster 时间？",
                "午饭时间，聊点轻松的？",
                "中午了，忙里偷闲问点什么？",
            ),
        )
        in 14..16 -> pick(roll, name,
            withName = listOf(
                "下午好，{name}",
                "{name}，下午好。困意上来了？换个话题醒醒神",
            ),
            fixed = listOf("下午好，想聊点什么？"),
        )
        in 17..19 -> pick(roll, name,
            withName = listOf(
                "傍晚好，{name}，今天过得怎么样？",
                "{name}，快到晚上了，收尾还是开始？",
            ),
            fixed = listOf("傍晚了，散散步，聊聊天"),
        )
        in 20..22 -> pick(roll, name,
            withName = listOf(
                "晚上好，{name}",
                "{name}，晚上好。今晚想写点什么？",
            ),
            fixed = listOf(
                "晚上好，想聊点什么？",
                "夜色不错，来聊会儿天",
            ),
        )
        else -> pick(roll, name, withName = emptyList(),
            fixed = listOf(
                "还没睡？要做什么？",
                "夜深了，睡不着就聊聊",
                "这么晚还醒着，有事在心上？",
            ),
        )
    }
}

/** 时段编号：空态用它判断跨时段重新随机。 */
internal fun greetingBand(hourOfDay: Int): Int {
    val h = ((hourOfDay % 24) + 24) % 24
    return when (h) {
        in 5..7 -> 0
        in 8..10 -> 1
        in 11..13 -> 2
        in 14..16 -> 3
        in 17..19 -> 4
        in 20..22 -> 5
        else -> 6
    }
}

private fun <T> List<T>.at(roll: Int): T = this[((roll % size) + size) % size]

private fun pick(roll: Int, name: String, withName: List<String>, fixed: List<String>): String {
    // 有称呼时优先用带称呼的句子；空称呼则退到固定句，避免“朋友”代称满天飞。
    val pool = if (name.isNotEmpty() && withName.isNotEmpty()) withName else fixed
    return pool.at(roll).replace("{name}", name)
}
