package com.adong.adchat.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRemoteManifestTest {
    @Test
    fun loadSkillExposesLocalAndLazyRemoteFilesToTheModel() {
        val source = "https://github.com/example/large-skill"
        val skill = LoadedSkill(
            name = "large-skill",
            sourceUrl = source,
            resolvedUrl = "https://raw.githubusercontent.com/example/large-skill/0123456789abcdef0123456789abcdef01234567/SKILL.md",
            sha256 = "skill-version",
            content = "---\nname: large-skill\n---\nRead references/themes.md when needed.",
            files = mapOf("local-note.md" to "bG9jYWw="),
            remoteFiles = setOf("references/themes.md", "project/assets/template.html")
        )

        val result = executeAppTool(
            PendingToolCall(
                itemId = "item-load",
                callId = "call-load",
                name = LOAD_SKILL_TOOL,
                arguments = JSONObject().put("url", source).toString()
            ),
            SkillLoader { skill },
            setOf(source)
        )

        val output = JSONObject(result.output)
        assertTrue(output.getBoolean("ok"))
        val files = output.getJSONArray("files")
        val actual = (0 until files.length()).map { files.getString(it) }.toSet()
        assertEquals(
            setOf("local-note.md", "references/themes.md", "project/assets/template.html"),
            actual
        )
    }
}
