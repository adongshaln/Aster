package com.adong.adchat.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SkillPackagePersistenceTest {
    @Test fun deletingSelectedPackageFreesSlotsAcrossWorkspacesAndReinstallDoesNotReselect() {
        val context = RuntimeEnvironment.getApplication()
        val runtime = SkillRuntime(FileSkillLibrary(context))
        val skills = (1..5).map { index ->
            runtime.installZip(skillZip("SKILL.md" to "---\nname: editor-$index\n---\nCheck the text."))
        }
        runtime.select("discussion", skills.take(4).map { it.sourceUrl }.toSet())
        runtime.select("prose", setOf(skills.first().sourceUrl, skills.last().sourceUrl))
        runtime.remove(skills.first().sourceUrl)
        val restarted = SkillRuntime(FileSkillLibrary(context))
        assertEquals(3, restarted.selection("discussion").size)
        restarted.select("discussion", restarted.selection("discussion") + skills.last().sourceUrl)
        assertEquals(4, restarted.selected("discussion").size)
        assertEquals(setOf(skills.last().sourceUrl), restarted.selection("prose"))
        FileSkillLibrary(context).save(skills.first())
        assertFalse(restarted.selection("discussion").contains(skills.first().sourceUrl))
        assertFalse(restarted.selection("prose").contains(skills.first().sourceUrl))
    }

    @Test fun legacyDeletedChoicesDoNotOccupySelectionSlots() {
        val library = FileSkillLibrary(RuntimeEnvironment.getApplication())
        val runtime = SkillRuntime(library)
        val skill = runtime.installZip(skillZip("SKILL.md" to "---\nname: editor\n---\nCheck."))
        library.select("legacy", setOf("local:deleted", skill.sourceUrl))
        assertEquals(setOf(skill.sourceUrl), runtime.selection("legacy"))
    }

    @Test fun filesSelectionAndDisabledStateSurviveRestartAndUpdates() {
        val context = RuntimeEnvironment.getApplication()
        val store = FileSkillLibrary(context)
        val skill = SkillPackages.importZip(skillZip("SKILL.md" to "---\nname: editor\n---\nRead reference.", "references/a.md" to "中文资料"))
        store.save(skill); store.select("chat-one", setOf(skill.sourceUrl))
        val restarted = SkillRuntime(FileSkillLibrary(context))
        assertEquals(skill.files, restarted.load(skill.sha256).files)
        assertTrue(restarted.listInstalled().single().files.values.all { it.isEmpty() })
        assertEquals(skill.files.keys, restarted.listInstalled().single().files.keys)
        assertEquals(skill.sha256, restarted.selected("chat-one").single().sha256)
        assertTrue(restarted.selected("chat-two").isEmpty())
        restarted.enable(skill.sourceUrl, false)
        assertTrue(SkillRuntime(FileSkillLibrary(context)).selected("chat-one").isEmpty())
        restarted.remove(skill.sourceUrl)
        assertTrue(FileSkillLibrary(context).list().isEmpty())
    }
    @Test fun remoteManifestSurvivesRestartWithoutRepositoryArchivePayload() {
        val context = RuntimeEnvironment.getApplication()
        val source = "https://github.com/example/huge-skill"
        val skill = LoadedSkill(
            name = "huge-skill",
            sourceUrl = source,
            resolvedUrl = "https://raw.githubusercontent.com/example/huge-skill/0123456789012345678901234567890123456789/skills/huge/SKILL.md",
            sha256 = "a".repeat(64),
            content = "---\nname: huge-skill\n---\nRead references/a.md",
            remoteFiles = setOf("SKILL.md", "references/a.md", "project/assets/template.html")
        )
        FileSkillLibrary(context).save(skill)
        val restarted = FileSkillLibrary(context)
        assertEquals(skill.remoteFiles, restarted.list().single().remoteFiles)
        assertTrue(restarted.list().single().files.isEmpty())
        assertEquals(skill.remoteFiles, restarted.find(source)!!.remoteFiles)
    }

    @Test fun invalidUpdateKeepsExistingPackageAndLegacyTextRemainsReadable() {
        val context = RuntimeEnvironment.getApplication()
        val library = FileSkillLibrary(context)
        val source = "https://github.com/test/skill"
        val old = LoadedSkill("old", source, source, "old-sha", "Legacy instructions")
        library.save(old)
        val runtime = SkillRuntime(library, bundleLoader = SkillBundleLoader {
            SkillBundle("bad", source, source, "", "bad", 0, byteArrayOf(1, 2))
        })
        assertTrue(runCatching { runtime.install(source) }.isFailure)
        assertEquals("Legacy instructions", SkillRuntime(FileSkillLibrary(context)).load("old").content)
    }
}
