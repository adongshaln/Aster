package com.adong.adchat.island

/** Content stays local until the user chooses an action. */
data class StarIslandContent(
    val text: String,
    val url: String? = null,
    val mediaLink: IslandLink? = null,
) {
    val kind: StarIslandKind get() = when {
        mediaLink != null -> StarIslandKind.Media
        url != null -> StarIslandKind.Web
        else -> StarIslandKind.Text
    }
    val key: String get() = url ?: text
    val textInsight: StarIslandTextInsight by lazy { StarIslandContentAnalyzer.analyze(this) }
}

enum class StarIslandKind { Text, Web, Media }

enum class StarIslandTextScene(
    val title: String,
    val badge: String
) {
    PickupOrCode("取件 / 验证码", "智能识别"),
    Schedule("日程与时间", "智能识别"),
    CodeOrError("代码 / 报错日志", "智能识别"),
    ForeignText("外文内容", "智能识别"),
    LongArticle("长文笔记", "智能识别"),
    ChatMessage("消息对话", "智能识别"),
    General("剪贴板文字", "")
}

data class StarIslandTextInsight(
    val scene: StarIslandTextScene,
    val extractedCode: String? = null,
    val extractedLabel: String = "",
    val suggestedScheduleTitle: String = "",
    val primaryActions: List<StarIslandAction> = emptyList()
)

enum class StarIslandAction(val label: String) {
    Translate("翻译"),
    Polish("润色"),
    Explain("解释"),
    Summarize("总结要点"),
    SmartReply("高情商回复"),
    ExtractInfo("提取信息"),
    FactCheck("联网查证"),
    ReadWeb("阅读网页"),
    Download("解析下载"),
    OpenLink("打开原页"),
    CopyLink("复制链接"),
    CopyExtracted("复制号码"),
    AddCalendar("建日程"),
    ContinueInChat("转入对话"),
    AskScreen("屏幕问答"),
}

enum class StarIslandPhase { Working, Complete, Stopped, Error }

data class StarIslandTaskState(
    val action: StarIslandAction,
    val phase: StarIslandPhase,
    val output: String = "",
    val detail: String = "",
    val needsConfiguration: Boolean = false,
    val progress: Float = -1f,
    val badge: String = "",
)

/**
 * Local zero-latency clipboard scene analyzer:
 * Detects pickup codes, OTP verification codes, meeting/schedule dates, code/stacktraces,
 * foreign-language passages, long articles, and chat messages so Star Island immediately
 * surfaces the most useful one-tap actions.
 */
internal object StarIslandContentAnalyzer {
    private val PICKUP_KEYWORDS = Regex("(取件码|提货码|取餐码|取票码|丰巢|菜鸟驿站|驿站|快递柜|取货码|凭码)")
    private val PICKUP_CODE_PATTERN = Regex("(?<![0-9A-Za-z])([A-Za-z0-9]{1,4}[-—][0-9]{1,4}(?:[-—][0-9]{1,6})?|[0-9]{6,8})(?![0-9A-Za-z])")
    private val OTP_KEYWORDS = Regex("(验证码|校验码|动态码|确认码|verification\\s*code|security\\s*code|passcode|OTP)", RegexOption.IGNORE_CASE)
    private val OTP_CODE_PATTERN = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")
    private val SCHEDULE_PATTERN = Regex(
        "(今天|明天|后天|大后天|本周[一二三四五六日天]|下周[一二三四五六日天]|\\d{1,2}月\\d{1,2}[日号]|\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2})" +
            "[\\s\\S]{0,18}?(上午|下午|晚上|凌晨|中午|\\d{1,2}[:：点时]\\d{0,2})"
    )
    private val MEETING_KEYWORDS = Regex("(会议|开会|面谈|面试|航班|高铁|列车|预约|截止|提醒|日程|聚餐|活动|讲座|演出)")
    private val CODE_OR_ERROR_PATTERN = Regex(
        "(Exception|Error:|Traceback|at\\s+[a-zA-Z0-9_.$]+\\(|fun\\s+\\w+|class\\s+\\w+|import\\s+[a-zA-Z0-9_.]+|const\\s+\\w+|def\\s+\\w+|SELECT\\s+.+FROM|\\{[\\s\\S]*\"\\w+\"\\s*:)"
    )

    fun analyze(content: StarIslandContent): StarIslandTextInsight {
        if (content.kind != StarIslandKind.Text) {
            return StarIslandTextInsight(StarIslandTextScene.General)
        }
        val raw = content.text.trim()
        if (raw.isEmpty()) return StarIslandTextInsight(StarIslandTextScene.General)

        // 1. Express pickup code / verification code
        if (PICKUP_KEYWORDS.containsMatchIn(raw)) {
            val code = PICKUP_CODE_PATTERN.find(raw)?.groupValues?.get(1)
                ?: OTP_CODE_PATTERN.find(raw)?.groupValues?.get(1)
            if (code != null) {
                return StarIslandTextInsight(
                    scene = StarIslandTextScene.PickupOrCode,
                    extractedCode = code,
                    extractedLabel = "复制 $code",
                    primaryActions = listOf(
                        StarIslandAction.CopyExtracted,
                        StarIslandAction.ExtractInfo,
                        StarIslandAction.AddCalendar
                    )
                )
            }
        }
        if (OTP_KEYWORDS.containsMatchIn(raw)) {
            val code = OTP_CODE_PATTERN.find(raw)?.groupValues?.get(1)
            if (code != null) {
                return StarIslandTextInsight(
                    scene = StarIslandTextScene.PickupOrCode,
                    extractedCode = code,
                    extractedLabel = "复制 $code",
                    primaryActions = listOf(
                        StarIslandAction.CopyExtracted,
                        StarIslandAction.ExtractInfo,
                        StarIslandAction.Explain
                    )
                )
            }
        }

        // 2. Schedule / meeting / event time
        if (SCHEDULE_PATTERN.containsMatchIn(raw) && (MEETING_KEYWORDS.containsMatchIn(raw) || raw.length <= 240)) {
            val scheduleTitle = raw.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
                .take(36)
            return StarIslandTextInsight(
                scene = StarIslandTextScene.Schedule,
                suggestedScheduleTitle = scheduleTitle,
                primaryActions = listOf(
                    StarIslandAction.AddCalendar,
                    StarIslandAction.ExtractInfo,
                    StarIslandAction.SmartReply
                )
            )
        }

        // 3. Code snippet / crash log / JSON
        if (CODE_OR_ERROR_PATTERN.containsMatchIn(raw)) {
            return StarIslandTextInsight(
                scene = StarIslandTextScene.CodeOrError,
                primaryActions = listOf(
                    StarIslandAction.Explain,
                    StarIslandAction.FactCheck,
                    StarIslandAction.ContinueInChat
                )
            )
        }

        // 4. Foreign language dominance -> Translate first
        val cjkCount = raw.count { it in '\u4e00'..'\u9fff' }
        val latinCount = raw.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (latinCount >= 18 && cjkCount * 3 < latinCount) {
            return StarIslandTextInsight(
                scene = StarIslandTextScene.ForeignText,
                primaryActions = listOf(
                    StarIslandAction.Translate,
                    StarIslandAction.Explain,
                    StarIslandAction.SmartReply
                )
            )
        }

        // 5. Long article / document notes (> 220 chars) -> Summarize & FactCheck first
        if (raw.length >= 220) {
            return StarIslandTextInsight(
                scene = StarIslandTextScene.LongArticle,
                primaryActions = listOf(
                    StarIslandAction.Summarize,
                    StarIslandAction.FactCheck,
                    StarIslandAction.Translate
                )
            )
        }

        // 6. Short conversational / message snippet -> SmartReply, Polish, FactCheck, Translate
        if (raw.length in 6..120 && (raw.contains('？') || raw.contains('?') || raw.contains('吗') || raw.contains('请') || raw.contains('怎'))) {
            return StarIslandTextInsight(
                scene = StarIslandTextScene.ChatMessage,
                primaryActions = listOf(
                    StarIslandAction.SmartReply,
                    StarIslandAction.FactCheck,
                    StarIslandAction.Translate
                )
            )
        }

        // Default text actions
        return StarIslandTextInsight(
            scene = StarIslandTextScene.General,
            primaryActions = listOf(
                StarIslandAction.Translate,
                StarIslandAction.Polish,
                StarIslandAction.Explain
            )
        )
    }
}
