from pathlib import Path

story = Path("app/src/main/java/com/adong/adchat/ui/screens/StoryScreen.kt")
text = story.read_text(encoding="utf-8")

anchor = '''    val scrollSessionKey = "${targetStory.id}:${targetStory.currentTimelineId}:${workspace.dbValue}"
    val listState = key(scrollSessionKey) {'''
replacement = '''    val scrollSessionKey = "${targetStory.id}:${targetStory.currentTimelineId}:${workspace.dbValue}"
    // Completed prose must keep its parsed presentation while LazyColumn recycles rows.
    // Re-entering the viewport with an empty async placeholder changes the row height by
    // hundreds/thousands of pixels (especially with thought/planning sections) and makes
    // LazyColumn compensate by jumping the reading position.
    val proseRenderSessionKey = remember(
        scrollSessionKey,
        storyVm.activeTavernPresetId,
        storyVm.activeTavernPresetConfiguration,
        storyVm.tavernRegexEnabled
    ) {
        listOf(
            scrollSessionKey,
            storyVm.activeTavernPresetId.orEmpty(),
            storyVm.activeTavernPresetConfiguration.hashCode().toString(),
            storyVm.tavernRegexEnabled.toString()
        ).joinToString(":")
    }
    val prosePresentationCache = remember(proseRenderSessionKey) {
        com.adong.adchat.data.story.StoryProsePresentationCache()
    }
    val listState = key(scrollSessionKey) {'''
if text.count(anchor) != 1:
    raise SystemExit(f"scroll session anchor count={text.count(anchor)}")
text = text.replace(anchor, replacement, 1)

old = '''                    val prose by produceState<com.adong.adchat.data.story.StoryProsePresentation?>(
                        null, row.revision.id, row.revision.content, row.revision.state,
                        storyVm.activeTavernPresetId, storyVm.activeTavernPresetConfiguration,
                        storyVm.tavernRegexEnabled, workspace, index, messages.size
                    ) {
                        value = if (nativeProse) storyVm.prosePresentation(row.revision.content,
                            row.message.role, messages.lastIndex - index, row.revision.state == StoryRevisionState.Streaming) else null
                    }
'''
new = '''                    val proseCacheKey = if (nativeProse && row.revision.state != StoryRevisionState.Streaming) {
                        com.adong.adchat.data.story.StoryProsePresentationCache.Key(
                            revisionId = row.revision.id,
                            content = row.revision.content,
                            depth = messages.lastIndex - index
                        )
                    } else null
                    // Completed/history rows are parsed synchronously on their first layout and
                    // retained for this story/timeline/workspace session. Therefore a recycled
                    // LazyColumn row never measures as empty and expands one frame later.
                    val completedProse = remember(proseCacheKey, prosePresentationCache) {
                        proseCacheKey?.let { key ->
                            prosePresentationCache.getOrPut(key) {
                                storyVm.prosePresentationNow(
                                    row.revision.content,
                                    row.message.role,
                                    messages.lastIndex - index,
                                    streaming = false
                                )
                            }
                        }
                    }
                    // Only the actively streaming reply keeps asynchronous parsing: its height is
                    // already changing with incoming tokens and it is governed by bottom-follow.
                    val streamingProse by produceState<com.adong.adchat.data.story.StoryProsePresentation?>(
                        null, row.revision.id, row.revision.content, row.revision.state,
                        storyVm.activeTavernPresetId, storyVm.activeTavernPresetConfiguration,
                        storyVm.tavernRegexEnabled, workspace, index, messages.size
                    ) {
                        value = if (nativeProse && row.revision.state == StoryRevisionState.Streaming) {
                            storyVm.prosePresentation(
                                row.revision.content,
                                row.message.role,
                                messages.lastIndex - index,
                                streaming = true
                            )
                        } else null
                    }
                    val prose = completedProse ?: streamingProse
'''
if text.count(old) != 1:
    raise SystemExit(f"prose async anchor count={text.count(old)}")
text = text.replace(old, new, 1)
story.write_text(text, encoding="utf-8")

vm = Path("app/src/main/java/com/adong/adchat/ui/story/StoryViewModel.kt")
text = vm.read_text(encoding="utf-8")
old = '''    suspend fun prosePresentation(content: String, role: String, depth: Int, streaming: Boolean):
        com.adong.adchat.data.story.StoryProsePresentation {
        val preset = activeTavernPreset
        val enabled = tavernRegexEnabled
        return withContext(Dispatchers.Default) {
            com.adong.adchat.data.story.StoryProsePresenter.present(content, preset, role, depth, enabled, streaming)
        }
    }
'''
new = '''    fun prosePresentationNow(content: String, role: String, depth: Int, streaming: Boolean):
        com.adong.adchat.data.story.StoryProsePresentation {
        val preset = activeTavernPreset
        val enabled = tavernRegexEnabled
        return com.adong.adchat.data.story.StoryProsePresenter.present(
            content, preset, role, depth, enabled, streaming
        )
    }

    suspend fun prosePresentation(content: String, role: String, depth: Int, streaming: Boolean):
        com.adong.adchat.data.story.StoryProsePresentation = withContext(Dispatchers.Default) {
        prosePresentationNow(content, role, depth, streaming)
    }
'''
if text.count(old) != 1:
    raise SystemExit(f"viewmodel prose anchor count={text.count(old)}")
vm.write_text(text.replace(old, new, 1), encoding="utf-8")

print("Applied stable completed-prose presentation cache patch")
