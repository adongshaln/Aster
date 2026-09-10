package com.adong.adchat.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

internal fun skillZip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
    ZipOutputStream(output).use { zip -> files.forEach { (path, text) ->
        zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
    } }
}.toByteArray()

class SkillPackagesTest {
    private val manifest = "---\nname: story-editor\ndescription: >\n  检查人物动机\n  与剧情衔接\n---\nRead references/rules.md before writing."
    @Test fun importsWholeFolderWithStableHashAndMultilineDescription() {
        val first = SkillPackages.importZip(skillZip("editor/SKILL.md" to manifest, "editor/references/rules.md" to "角色不知道秘密"))
        val second = SkillPackages.importZip(skillZip("editor/references/rules.md" to "角色不知道秘密", "editor/SKILL.md" to manifest))
        assertEquals(first.sha256, second.sha256)
        assertEquals("检查人物动机 与剧情衔接", first.description)
        assertEquals(setOf("SKILL.md", "references/rules.md"), first.files.keys)
        assertFalse(skillCatalog(listOf(first)).contains("Read references"))
        assertFalse(skillCatalog(listOf(first)).contains("角色不知道秘密"))
    }
    @Test fun rejectsTraversalAmbiguousPackagesOversizedInstructionsAndZipBomb() {
        listOf("../bad", "/absolute", "a/../../bad", "a\\bad", "C:/bad").forEach { path ->
            assertTrue(path, runCatching { SkillPackages.importZip(skillZip("SKILL.md" to manifest, path to "bad")) }.isFailure)
        }
        assertTrue(runCatching { SkillPackages.importZip(skillZip("a/SKILL.md" to manifest, "b/SKILL.md" to manifest)) }.isFailure)
        assertTrue(runCatching { SkillPackages.importZip(skillZip("a/SKILL.md" to manifest, "other.txt" to "outside")) }.isFailure)
        assertTrue(runCatching { SkillPackages.importZip(skillZip("SKILL.md" to "x".repeat(32_001))) }.isFailure)
        assertTrue(runCatching { SkillPackages.importZip(skillZip("SKILL.md" to manifest, "bomb.txt" to "x".repeat(5 * 1024 * 1024 + 1))) }.isFailure)
    }
    @Test fun sessionRequiresLoadThenReadsExactVersionAsWholeFile() {
        val text = "人".repeat(12000) + "尾部标记"
        val imported = SkillPackages.importZip(skillZip("SKILL.md" to manifest, "references/rules.md" to text))
        val old = imported.copy(
            sourceUrl = "https://github.com/example/story-editor",
            resolvedUrl = "https://raw.githubusercontent.com/example/story-editor/main/SKILL.md"
        )
        val library = MemorySkillLibrary(); library.save(old)
        val runtime = SkillRuntime(library, SkillLoader { error("unexpected network") })
        val session = SkillSession(runtime, listOf(old))
        val aliases = listOf(old.sha256, old.sourceUrl, old.resolvedUrl, old.name)
        aliases.forEach { selector ->
            assertTrue(selector, runCatching { session.readFile(selector, "references/rules.md", 0) }.isFailure)
        }
        session.load(old.sha256)
        library.remove(old.sourceUrl)
        aliases.forEach { selector ->
            val whole = session.readFile(selector, "  references/rules.md  ", 0)
            assertEquals(text, whole.getString("content"))
            assertEquals("references/rules.md", whole.getString("path"))
            assertEquals(text.length, whole.getInt("total_characters"))
            assertTrue(whole.getBoolean("complete"))
            assertFalse(whole.has("next_offset"))
            assertFalse(whole.has("offset"))
        }
        assertTrue(runCatching { session.readFile(old.name, "references/rules.md", 1) }.isFailure)
        assertTrue(runCatching { session.readFile(old.sha256, "../secret", 0) }.isFailure)
        assertTrue(runCatching { session.readFile(old.sha256, "missing", 0) }.isFailure)
    }

    @Test fun wholeFileReadHonorsConfiguredModelInputBudget() {
        val text = "x".repeat(3000)
        val skill = SkillPackages.importZip(skillZip("SKILL.md" to manifest, "references/large.md" to text))
        val session = SkillSession(SkillLoader { skill }, listOf(skill), maxReadTokens = 100)
        session.load(skill.sha256)
        val failure = runCatching { session.readFile(skill.sha256, "references/large.md", 0) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("输入预算"))
    }

    @Test fun disabledAndDifferentWorkspaceSkillsStayOutOfCatalog() {
        val library = MemorySkillLibrary()
        val skill = SkillPackages.importZip(skillZip("SKILL.md" to manifest))
        library.save(skill); val runtime = SkillRuntime(library)
        runtime.select("discussion", setOf(skill.sourceUrl))
        assertEquals(1, runtime.selected("discussion").size)
        assertTrue(runtime.selected("prose").isEmpty())
        runtime.enable(skill.sourceUrl, false)
        assertTrue(runtime.selected("discussion").isEmpty())
        runtime.enable(skill.sourceUrl, true)
        assertEquals(skill.sha256, runtime.selected("discussion").single().sha256)
    }
}
