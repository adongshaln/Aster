package com.adong.adchat.data

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillAliasGuardTest {
    @Test
    fun duplicateReadAcrossAliasesReusesPreviousContentAndStopsTools() {
        val source = "https://github.com/example/demo"
        val resolved = "https://raw.githubusercontent.com/example/demo/main/SKILL.md"
        val sha = "a".repeat(64)
        val skill = LoadedSkill(
            name = "demo", sourceUrl = source, resolvedUrl = resolved, sha256 = sha,
            content = "instructions",
            files = mapOf("references/a.md" to Base64.getEncoder().encodeToString("资料".toByteArray()))
        )
        val delegate = SkillLoader { skill }
        val session = SkillSession(delegate, listOf(skill))
        val guard = SkillToolReuseGuard()
        val allowed = setOf(source, resolved, sha, skill.name)

        guard.beginRound()
        val loaded = guard.execute(
            PendingToolCall("load", "load", LOAD_SKILL_TOOL, JSONObject().put("url", source).toString()),
            session, allowed
        )
        assertTrue(JSONObject(loaded.output).getBoolean("ok"))
        assertFalse(guard.shouldForceNoToolsNextRound())

        guard.beginRound()
        val first = guard.execute(
            PendingToolCall("read-1", "read-1", READ_SKILL_FILE_TOOL,
                JSONObject().put("skill", sha).put("path", "references/a.md").put("offset", 0).toString()),
            session, allowed
        )
        assertTrue(JSONObject(first.output).getBoolean("ok"))
        assertFalse(guard.shouldForceNoToolsNextRound())

        guard.beginRound()
        val second = guard.execute(
            PendingToolCall("read-2", "read-2", READ_SKILL_FILE_TOOL,
                JSONObject().put("skill", skill.name).put("path", "references/a.md").put("offset", 0).toString()),
            session, allowed
        )
        val reused = JSONObject(second.output)
        assertTrue(reused.getBoolean("ok"))
        assertTrue(reused.getBoolean("reused"))
        assertTrue(guard.shouldForceNoToolsNextRound())
    }
}
