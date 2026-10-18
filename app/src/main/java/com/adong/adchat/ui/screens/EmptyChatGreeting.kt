package com.adong.adchat.ui.screens

/**
 * 聊天空状态的时段问候。
 *
 * [hourOfDay] 为本地时间 0–23；[userName] 自动 trim，空称呼时省略姓名。
 * 返回的问候语中，咖啡时段与深夜时段固定不携带称呼。
 *
 * 纯函数，便于单测；不要在 Composable 重组中随机选句。
 */
internal fun emptyChatGreeting(hourOfDay: Int, userName: String): String {
    val h = ((hourOfDay % 24) + 24) % 24
    val name = userName.trim()
    return when (h) {
        in 5..10 -> withName("早上好", name)
        in 11..13 -> "咖啡加 Aster 时间？"
        in 14..17 -> withName("下午好", name)
        in 18..22 -> withName("晚上好", name)
        else -> "还没睡？要做什么？"
    }
}

private fun withName(greeting: String, name: String): String =
    if (name.isNotEmpty()) "$greeting，$name" else "$greeting，想聊点什么？"
