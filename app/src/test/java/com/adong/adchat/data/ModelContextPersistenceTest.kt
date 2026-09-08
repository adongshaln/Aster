package com.adong.adchat.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ModelContextPersistenceTest {
    @Test fun survivesStoreReloadAndResetDoesNotAffectAnotherModel() {
        val context=RuntimeEnvironment.getApplication()
        val store=ConfigStore(context)
        val values=mapOf("gemini-custom" to ModelContextLimits(1048576,8192),"other" to ModelContextLimits(32768,4096))
        val profile=ApiProfile(id="ctx-test",baseUrl="https://example.com",modelContexts=values)
        val config=AppConfig(listOf(profile),profile.id,profile.id)
        store.save(config)
        assertEquals(values,ConfigStore(context).load().chatProfile().modelContexts)
        store.save(config.copy(profiles=listOf(profile.copy(modelContexts=values-"gemini-custom"))))
        val restored=ConfigStore(context).load().chatProfile()
        assertNull(restored.contextLimits("gemini-custom"))
        assertEquals(values["other"],restored.contextLimits("other"))
    }
}
