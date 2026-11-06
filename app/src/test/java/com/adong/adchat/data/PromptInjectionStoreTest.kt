package com.adong.adchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PromptInjectionStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun preset(entries: List<PromptInjectionEntry>) = PromptInjectionPreset(
        id = "custom-style",
        name = "Custom style",
        description = "User preferences",
        builtIn = false,
        pureMode = false,
        zeroWidthObfuscation = false,
        entries = entries
    )

    @Test fun explicitlyEmptyPresetSurvivesReloadWithItsOptions() {
        val saved = preset(emptyList())
        PromptInjectionStore(context).savePreset(saved)

        assertEquals(saved, PromptInjectionStore(context).loadPreset())
    }

    @Test fun deletingLastEntryDoesNotRestoreDefaultsAfterReload() {
        val store = PromptInjectionStore(context)
        val entry = PromptInjectionEntry(id = "style", name = "Style", content = "Use concise wording.")
        store.savePreset(preset(listOf(entry)))
        store.setEnabled(true)

        assertTrue(store.deleteEntry(entry.id).entries.isEmpty())
        val reloaded = PromptInjectionStore(context)
        assertTrue(reloaded.loadPreset().entries.isEmpty())
        assertEquals(0, reloaded.loadPreset().enabledCount)
        assertTrue(reloaded.isEnabled())
    }

    @Test fun disabledEntriesAndMasterSwitchRemainIndependent() {
        val saved = preset(listOf(PromptInjectionEntry(
            id = "style", name = "Style", content = "Use concise wording.", enabled = false
        )))
        val store = PromptInjectionStore(context)
        store.savePreset(saved)
        store.setEnabled(false)

        val reloaded = PromptInjectionStore(context)
        assertEquals(saved, reloaded.loadPreset())
        assertFalse(reloaded.isEnabled())
    }

    @Test fun missingEntriesFieldStillFallsBackToDefaults() {
        context.getSharedPreferences("aster_prompt_injection", 0).edit()
            .putString("injection_preset_v1", "{\"id\":\"incomplete\"}").commit()

        assertEquals(PromptInjectionDefaults.builtInNyGeminiPreset, PromptInjectionStore(context).loadPreset())
    }

    @Test fun corruptJsonStillFallsBackToDefaults() {
        context.getSharedPreferences("aster_prompt_injection", 0).edit()
            .putString("injection_preset_v1", "{invalid").commit()

        assertEquals(PromptInjectionDefaults.builtInNyGeminiPreset, PromptInjectionStore(context).loadPreset())
    }
}
