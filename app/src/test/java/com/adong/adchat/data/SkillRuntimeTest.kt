package com.adong.adchat.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRuntimeTest {
    @Test
    fun repositoryRootUsesTheActualResolvedDefaultBranch() {
        var resolved = false
        val target = resolveGitHubSkillTarget("https://github.com/example/presentation-skills") { owner, repo ->
            assertEquals("example", owner)
            assertEquals("presentation-skills", repo)
            resolved = true
            "trunk"
        }

        assertTrue(resolved)
        assertEquals("trunk", target.ref)
        assertEquals("SKILL.md", target.path)
        assertEquals("https://raw.githubusercontent.com/example/presentation-skills/trunk/SKILL.md", target.rawUrl)
    }

    @Test
    fun treeBlobAndRawLinksResolveToRealSkillMarkdown() {
        val noDefault: (String, String) -> String = { _, _ -> error("default branch must not be queried") }
        val tree = resolveGitHubSkillTarget(
            "https://github.com/example/repo/tree/main/skills/presentation",
            noDefault
        )
        assertEquals("skills/presentation/SKILL.md", tree.path)
        assertEquals("https://raw.githubusercontent.com/example/repo/main/skills/presentation/SKILL.md", tree.rawUrl)

        val blob = resolveGitHubSkillTarget(
            "https://github.com/example/repo/blob/abc123/skills/presentation/SKILL.md",
            noDefault
        )
        assertEquals("abc123", blob.ref)
        assertEquals("skills/presentation/SKILL.md", blob.path)

        val raw = resolveGitHubSkillTarget(
            "https://raw.githubusercontent.com/example/repo/main/SKILL.md",
            noDefault
        )
        assertEquals("https://raw.githubusercontent.com/example/repo/main/SKILL.md", raw.rawUrl)
    }

    @Test
    fun nonGitHubAndNonSkillTargetsAreRejected() {
        assertTrue(runCatching {
            resolveGitHubSkillTarget("https://example.com/SKILL.md") { _, _ -> "main" }
        }.isFailure)
        assertTrue(runCatching {
            resolveGitHubSkillTarget("https://github.com/example/repo/blob/main/README.md") { _, _ -> "main" }
        }.isFailure)
    }

    @Test
    fun skillToolIsOnlyOfferedForAnExplicitGitHubSkillTurn() {
        assertFalse(shouldOfferSkillLoader(listOf(ChatMessage(role = "user", content = "看看 https://github.com/example/repo"))))
        assertTrue(shouldOfferSkillLoader(listOf(ChatMessage(role = "user", content = "请加载这个 skill：https://github.com/example/repo"))))
        assertTrue(shouldOfferSkillLoader(listOf(ChatMessage(role = "user", content = "https://github.com/example/repo/blob/main/SKILL.md"))))
        assertTrue(shouldOfferSkillLoader(listOf(ChatMessage(role = "user", content = "https://github.com/example/repo/tree/main/skills/ppt"))))
    }

    @Test
    fun loadSkillToolReturnsExactLoaderContentAndProofMetadata() {
        val source = "https://github.com/example/repo/blob/main/SKILL.md"
        var calledWith = ""
        val loader = SkillLoader { url ->
            calledWith = url
            LoadedSkill(
                name = "presentation-design",
                sourceUrl = url,
                resolvedUrl = "https://raw.githubusercontent.com/example/repo/main/SKILL.md",
                sha256 = "0123456789abcdef",
                content = "---\nname: presentation-design\n---\n# Exact skill body\nDo the real work."
            )
        }
        val result = executeAppTool(
            PendingToolCall(
                itemId = "item-skill",
                callId = "call-skill",
                name = LOAD_SKILL_TOOL,
                arguments = JSONObject().put("url", source).toString()
            ),
            loader,
            setOf(source)
        )

        assertEquals(source, calledWith)
        assertEquals(TOOL_STATUS_COMPLETED, result.activity.status)
        val output = JSONObject(result.output)
        assertTrue(output.getBoolean("ok"))
        assertEquals("untrusted_external_instructions", output.getString("trust"))
        assertEquals("presentation-design", output.getString("name"))
        assertEquals("0123456789abcdef", output.getString("sha256"))
        assertEquals("---\nname: presentation-design\n---\n# Exact skill body\nDo the real work.", output.getString("content"))
    }

    @Test
    fun modelCannotInventAnUnapprovedSkillUrl() {
        val approved = "https://github.com/example/repo/blob/main/SKILL.md"
        val invented = "https://github.com/attacker/other/blob/main/SKILL.md"
        var loads = 0
        val result = executeAppTool(
            PendingToolCall("item", "call", LOAD_SKILL_TOOL, JSONObject().put("url", invented).toString()),
            SkillLoader { loads++; error("must not reach loader") },
            setOf(approved)
        )
        assertEquals(0, loads)
        assertEquals(TOOL_STATUS_FAILED, result.activity.status)
        assertFalse(JSONObject(result.output).getBoolean("ok"))
        assertTrue(JSONObject(result.output).getString("error").contains("未授权"))
    }

    @Test
    fun bothProtocolsExposeTheSameLoadSkillFunction() {
        val source = "https://github.com/example/repo/blob/main/SKILL.md"
        val chatTools = buildChatTools(fileCreationEnabled = false, skillLoadingEnabled = true, skillSelectors = listOf(source))
        assertEquals(2, chatTools.length())
        val chatFunction = chatTools.getJSONObject(0).getJSONObject("function")
        assertEquals(LOAD_SKILL_TOOL, chatFunction.getString("name"))
        val chatUrl = chatFunction.getJSONObject("parameters").getJSONObject("properties").getJSONObject("url")
        assertEquals(source, chatUrl.getJSONArray("enum").getString(0))

        val responsesTools = buildResponsesTools(fileCreationEnabled = false, webSearchEnabled = false, skillLoadingEnabled = true, skillSelectors = listOf(source))
        assertEquals(2, responsesTools.length())
        assertEquals(LOAD_SKILL_TOOL, responsesTools.getJSONObject(0).getString("name"))
        assertTrue(responsesTools.getJSONObject(0).getBoolean("strict"))
    }
}
