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
    @Test fun filesSelectionAndDisabledStateSurviveRestartAndUpdates() {
        val context = RuntimeEnvironment.getApplication()
        val store = FileSkillLibrary(context)
        val skill = SkillPackages.importZip(skillZip("SKILL.md" to "---\nname: editor\n---\nRead reference.", "references/a.md" to "中文资料"))
        store.save(skill); store.select("chat-one", setOf(skill.sourceUrl))
        val restarted = SkillRuntime(FileSkillLibrary(context))
        assertEquals(skill.files, restarted.load(skill.sha256).files)
        assertEquals(skill.sha256, restarted.selected("chat-one").single().sha256)
        assertTrue(restarted.selected("chat-two").isEmpty())
        restarted.enable(skill.sourceUrl, false)
        assertTrue(SkillRuntime(FileSkillLibrary(context)).selected("chat-one").isEmpty())
        restarted.remove(skill.sourceUrl)
        assertTrue(FileSkillLibrary(context).list().isEmpty())
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
