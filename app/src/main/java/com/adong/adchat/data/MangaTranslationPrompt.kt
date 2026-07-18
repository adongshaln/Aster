package com.adong.adchat.data

enum class MangaTranslationTarget(
    val shortLabel: String,
    val promptLabel: String
) {
    SimplifiedChinese("简中", "简体中文"),
    TraditionalChinese("繁中", "繁体中文"),
    English("English", "自然流畅的英文"),
    Japanese("日本語", "自然流畅的日文"),
    Korean("한국어", "自然流畅的韩文")
}

object MangaTranslationPrompt {
    fun build(target: MangaTranslationTarget): String = """
        任务：把参考图中所有可读的漫画文字准确翻译为${target.promptLabel}，并只输出完成翻译后的单张漫画原页。

        翻译前的内部理解——先理解设定，再决定译法，但不要输出分析过程：
        1. 先综合当前页面中可见的标题、对白、旁白、人物外观、服饰、表情、动作、身份关系、分镜先后、场景、时代特征和世界观线索，内部判断作品题材、人物立场、上下文因果、说话对象、语气强弱、敬语层级与潜台词，再翻译每一处文字；不要把句子脱离画面逐字直译。
        2. 对人名、地名、组织、阵营、种族、职业、能力、招式、道具、称谓、敬称和拟声词建立本页内部术语表，同一概念必须使用一致译名；人物口吻应符合其年龄、身份、性格、关系和当时情绪。
        3. 如果能从明确证据可靠识别作品、角色或已有通行官方译名，优先使用目标语言中广泛认可的官方译名与设定用语；如果证据不足，不得猜测作品来源、虚构背景、擅自补全剧情或强行套用其他作品设定，应采用最保守、自然且不改变原意的译法。
        4. 遇到多义词、省略句、双关、代词指向或依赖语境的台词时，优先依据画面动作、表情、前后分镜和人物关系消除歧义；在无法确定时保留合理含义与语气，不添加原文没有的信息、解释或注释。

        最高优先级规则——严格保护原图与排版：
        1. 将参考图视为不可改动的底稿。画布比例、裁切范围、分镜边框、镜头构图、人物、姿势、表情、线稿、网点、色彩、背景、气泡、文字框和所有非文字内容必须与原图一致；禁止重绘、扩图、缩放、移动、增删或美化任何元素。
        2. 对每一段源文字分别定位其原始文字区域。只清除源文字，并在完全相同的位置填入对应译文。译文只能使用该段源文字原先占用的边界范围，不得越界，不得侵入原本未被该段文字占用的空白、插画或其他区域，不得扩大或移动气泡与文字框。
        3. 字体必须尽量匹配原字体的风格、粗细、字面比例、描边、阴影、颜色、书写方向、旋转角度、弧度、字距、行距、对齐方式与视觉重心。优先保持原有行数和换行；空间不足时，只能通过更精炼且语义完整的翻译、缩小字号、收紧字距或合理断行使译文留在原文字区域内，绝不能占用新位置。
        4. 翻译对白、旁白、标题、标牌和拟声词；保持人物语气、情绪、称谓、专有名词和剧情含义自然准确。不要添加注释、脚注、双语对照、说明文字、新气泡、水印或任何额外符号。
        5. 清除源字后，仅在源字覆盖处进行必要的局部底纹修复，周围像素保持不变。输出前逐区检查：每段译文都在对应原文字位置内，原本没有文字的位置绝不出现新文字，除文字替换所需的最小区域外不改变任何像素。

        输出要求：只返回一张构图与原图一致、文字已原位替换为${target.promptLabel}的完整漫画页，不要返回解释或额外内容。
    """.trimIndent()

    fun detectTarget(prompt: String): MangaTranslationTarget =
        MangaTranslationTarget.entries.firstOrNull {
            prompt.contains(it.promptLabel) || prompt.contains(it.shortLabel)
        }
            ?: MangaTranslationTarget.SimplifiedChinese
}

internal fun canvasSizeForReference(width: Int, height: Int): String = when {
    width <= 0 || height <= 0 -> "1024x1536"
    width.toFloat() / height.toFloat() > 1.12f -> "1536x1024"
    height.toFloat() / width.toFloat() > 1.12f -> "1024x1536"
    else -> "1024x1024"
}
