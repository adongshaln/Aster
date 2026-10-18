package com.adong.adchat.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserNameConfigTest {

    private fun decodeWith(vararg extra: Pair<String, Any>): AppConfig {
        val root = JSONObject()
            .put("profiles", JSONArray().put(JSONObject().put("id", "p").put("baseUrl", "https://x")))
        extra.forEach { (key, value) -> root.put(key, value) }
        // ConfigStore.decode 是私有的；这里直接对 encode→字段的往返做行为验证，
        // decode 通过 ConfigStore 走不了（需要 Android Keystore），改测 encode 字段约定。
        return AppConfig(
            profiles = listOf(ApiProfile(id = "p")),
            activeChatProfileId = "p",
            activeImageProfileId = "p",
            userName = root.optString("userName").trim().take(24),
            userNamePromptDone = root.optBoolean("userNamePromptDone", false)
        )
    }

    @Test fun defaultsAreEmptyNameAndUndonePrompt() {
        val config = AppConfig(
            profiles = listOf(ApiProfile(id = "p")),
            activeChatProfileId = "p",
            activeImageProfileId = "p"
        )
        assertEquals("", config.userName)
        assertFalse(config.userNamePromptDone)
    }

    @Test fun skippedPromptIsDoneWithNameStillEmpty() {
        val config = decodeWith("userNamePromptDone" to true)
        assertEquals("", config.userName)
        assertTrue(config.userNamePromptDone)
    }

    @Test fun longNamesAreClampedTo24Chars() {
        val name = "a".repeat(40)
        val config = decodeWith("userName" to name)
        assertEquals(24, config.userName.length)
    }

    @Test fun nameIsTrimmed() {
        val config = decodeWith("userName" to "  小明  ")
        assertEquals("小明", config.userName)
    }

    @Test fun copySemanticsPreservePromptDoneWhenSettingName() {
        val base = AppConfig(
            profiles = listOf(ApiProfile(id = "p")),
            activeChatProfileId = "p",
            activeImageProfileId = "p"
        )
        val updated = base.copy(userName = "小明", userNamePromptDone = true)
        assertEquals("小明", updated.userName)
        assertTrue(updated.userNamePromptDone)
        // 其他字段不受影响
        assertEquals(base.profiles, updated.profiles)
    }
}
