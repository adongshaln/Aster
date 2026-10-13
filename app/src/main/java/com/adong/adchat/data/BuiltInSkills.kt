package com.adong.adchat.data

internal const val ASK_USER_TOOL = "ask_user"
internal const val ASK_USER_SOURCE = "aster:skills/ask-user"
internal const val ASK_USER_CUSTOM_OPTION = "描述您的其他想法"

internal val ASK_USER_INSTRUCTION = """
[ASTER_ASK_USER]
当用户的需求缺少会显著改变结果、且不能合理假定的条件时，调用 ask_user 提一个问题。条件已经足够就直接完成，不要为了确认而打断。
一次只问最关键的一个问题。options 给 2 到 3 个可以直接执行的不同选项，更稳妥的放在第一位。不要自己加入“描述您的其他想法”，应用会固定把它放在最后并允许用户输入。不要在正文里重复这个问题，等工具返回后再继续。
""".trim()

private val ASK_USER_MARKDOWN = """
---
name: 模型主动提问
description: 需求里还有会改变结果、又不能合理假定的条件时，先用 ask_user 问一个带选项的问题。条件足够就直接做。
---

# 模型主动提问

用户提出了需求，但目标、范围、对象、风格、长度或约束还不明确，而且猜错会让结果没用时，调用 ask_user。不要把问题写进正文。

- 一次只问当前最关键的一个问题。
- options 提供 2 到 3 个彼此不同、选定后就能继续的选项，把更稳妥的放在第一位。
- 不要自己添加“其他”“描述您的其他想法”或自由输入项。应用会把“描述您的其他想法”固定为最后一项，并让用户在那里输入文字。
- 需求已经明确，或缺口可以用一个无害的默认处理时，不要调用这个工具。
""".trim()

data class AskUserPrompt(val question: String, val options: List<String>)

internal fun normalizeAskUserPrompt(question: String, options: List<String>): AskUserPrompt {
    val cleaned = options.map { it.trim() }
        .filter { it.isNotBlank() && it != ASK_USER_CUSTOM_OPTION }
        .distinct()
        .take(3)
    require(question.trim().isNotBlank()) { "提问缺少问题" }
    require(cleaned.size >= 2) { "至少需要两个可选项" }
    return AskUserPrompt(question.trim(), cleaned + ASK_USER_CUSTOM_OPTION)
}

internal object BuiltInSkills {
    fun isEnabled(loader: SkillLoader): Boolean =
        loader.listInstalled().any { it.sourceUrl == ASK_USER_SOURCE && it.enabled }

    fun ensureInstalled(library: SkillLibrary) {
        val skill = definition()
        val existing = library.list().firstOrNull { it.sourceUrl == ASK_USER_SOURCE }
        if (existing == null) {
            library.save(skill)
            return
        }
        if (existing.sha256 != skill.sha256 || existing.content != skill.content) {
            library.save(skill.copy(enabled = existing.enabled, installedAt = existing.installedAt))
        }
    }

    private fun definition(): LoadedSkill {
        val hash = skillDigest(ASK_USER_MARKDOWN.toByteArray(Charsets.UTF_8))
        return LoadedSkill(
            name = "模型主动提问",
            sourceUrl = ASK_USER_SOURCE,
            resolvedUrl = ASK_USER_SOURCE,
            sha256 = hash,
            content = ASK_USER_MARKDOWN,
            description = SkillPackages.metadata(ASK_USER_MARKDOWN, "description").take(600)
        )
    }
}
