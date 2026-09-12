from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"expected one anchor in {path}, found {text.count(old)}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Shared policy: Chat custom tools are mutually exclusive with provider-native search only
# when neither Responses nor Aster delegated search is available.
path = "app/src/main/java/com/adong/adchat/data/DelegatedSearch.kt"
replace_once(
    path,
    'const val MAX_DELEGATED_SEARCH_CALLS = 4\n',
    'const val MAX_DELEGATED_SEARCH_CALLS = 4\n\ninternal fun requiresLegacyChatToolExclusion(responsesApi: Boolean, delegatedSearchConfigured: Boolean): Boolean =\n    !responsesApi && !delegatedSearchConfigured\n'
)

path = "app/src/main/java/com/adong/adchat/ui/MainViewModel.kt"
replace_once(
    path,
'''    fun setChatWebSearchEnabled(enabled: Boolean) {
        val profile = chatProfile
        updateProfile(profile.id) {
            it.copy(
                webSearchEnabled = enabled,
                fileCreationEnabled = if (enabled && !profile.usesResponses()) false else it.fileCreationEnabled
            )
        }
        persist()
    }

    fun setChatFileCreationEnabled(enabled: Boolean) {
        val profile = chatProfile
        updateProfile(profile.id) {
            it.copy(
                fileCreationEnabled = enabled,
                webSearchEnabled = if (enabled && !profile.usesResponses()) false else it.webSearchEnabled
            )
        }
        persist()
    }''',
'''    fun setChatWebSearchEnabled(enabled: Boolean) {
        val profile = chatProfile
        val legacyToolExclusion = requiresLegacyChatToolExclusion(
            responsesApi = profile.usesResponses(),
            delegatedSearchConfigured = searchBackendConfig() != null
        )
        updateProfile(profile.id) {
            it.copy(
                webSearchEnabled = enabled,
                fileCreationEnabled = if (enabled && legacyToolExclusion) false else it.fileCreationEnabled
            )
        }
        persist()
    }

    fun setChatFileCreationEnabled(enabled: Boolean) {
        val profile = chatProfile
        val legacyToolExclusion = requiresLegacyChatToolExclusion(
            responsesApi = profile.usesResponses(),
            delegatedSearchConfigured = searchBackendConfig() != null
        )
        updateProfile(profile.id) {
            it.copy(
                fileCreationEnabled = enabled,
                webSearchEnabled = if (enabled && legacyToolExclusion) false else it.webSearchEnabled
            )
        }
        persist()
    }'''
)

path = "app/src/main/java/com/adong/adchat/ui/screens/SettingsScreen.kt"
replace_once(
    path,
'''                                draft = draft.copy(chatApiMode = "chat",
                                    fileCreationEnabled = if (draft.webSearchEnabled) false else draft.fileCreationEnabled)''',
'''                                draft = draft.copy(
                                    chatApiMode = "chat",
                                    fileCreationEnabled = if (
                                        draft.webSearchEnabled && requiresLegacyChatToolExclusion(
                                            responsesApi = false,
                                            delegatedSearchConfigured = searchBackendConfigured
                                        )
                                    ) false else draft.fileCreationEnabled
                                )'''
)

path = "app/src/test/java/com/adong/adchat/data/DelegatedSearchTest.kt"
replace_once(
    path,
'''    private fun JSONArray.toStringList(): List<String> = (0 until length()).map(::getString)
}''',
'''    @Test
    fun legacyChatToolExclusionIsDisabledByDelegatedSearch() {
        assertTrue(requiresLegacyChatToolExclusion(responsesApi = false, delegatedSearchConfigured = false))
        assertFalse(requiresLegacyChatToolExclusion(responsesApi = false, delegatedSearchConfigured = true))
        assertFalse(requiresLegacyChatToolExclusion(responsesApi = true, delegatedSearchConfigured = false))
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map(::getString)
}'''
)

print("Applied delegated search coexistence fix")
