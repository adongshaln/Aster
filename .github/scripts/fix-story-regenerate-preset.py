from pathlib import Path

vm = Path('app/src/main/java/com/adong/adchat/ui/story/StoryViewModel.kt')
text = vm.read_text(encoding='utf-8')

old = '''        if ((!retrying && input.isBlank()) || revisionBusy || attachmentBusy) return
        val key = jobKey(story.id, workspace)
'''
new = '''        if ((!retrying && input.isBlank()) || revisionBusy || attachmentBusy) return
        // Do not let a generation race an in-flight preset selection/configuration write.
        // The request must observe one coherent preset snapshot from start to finish.
        if (tavernPresetBusy) {
            errors[workspace] = "预设配置正在更新，请稍后再生成。"
            return
        }
        val key = jobKey(story.id, workspace)
'''
if text.count(old) != 1:
    raise SystemExit(f'guard anchor count={text.count(old)}')
text = text.replace(old, new, 1)

old = '''            var lastPersistAt = 0L
            try {
                val userMessage = if (!retrying) store.appendMessage(
'''
new = '''            var lastPersistAt = 0L
            try {
                // Request-side source of truth: freeze the persisted Tavern selection and regex
                // setting before mutating the assistant revision. Do not rely on the ViewModel's
                // asynchronously refreshed display cache here; regenerate can otherwise observe
                // a stale/null preset and silently fall back to the base story prompt.
                val presetSnapshot = if (workspace == StoryWorkspace.Prose) tavernPresetStore.active() else null
                val presetRegexEnabledSnapshot = tavernPresetStore.regexEnabled()

                val userMessage = if (!retrying) store.appendMessage(
'''
if text.count(old) != 1:
    raise SystemExit(f'job anchor count={text.count(old)}')
text = text.replace(old, new, 1)

old = '''                val preset = activeTavernPreset.takeIf { workspace == StoryWorkspace.Prose }
                val prepared = preset?.let {
                    TavernPresetRuntime.prepare(
                        preset = it,
                        baseSystemPrompt = context.systemPrompt,
                        history = context.history,
                        regexEnabled = tavernRegexEnabled
                    )
                }

                val result = trackedChat(
                    storyId = story.id, timelineId = story.currentTimelineId, category = workspace.dbValue, sourceId = assistant?.revision?.id,
                    profile = profile,
                    model = routeModel,
                    systemPrompt = prepared?.systemPrompt ?: context.systemPrompt,
                    history = prepared?.history ?: context.history,
                    cacheKey = "aster-story-${story.id}-${workspace.dbValue}",
                    generationOptions = prepared?.generationOptions ?: com.adong.adchat.data.ChatGenerationOptions()
                ) { delta ->
'''
new = '''                val preparedRequest = com.adong.adchat.data.story.StoryGenerationPreset.prepare(
                    workspace = workspace,
                    context = context,
                    preset = presetSnapshot,
                    regexEnabled = presetRegexEnabledSnapshot
                )

                val result = trackedChat(
                    storyId = story.id, timelineId = story.currentTimelineId, category = workspace.dbValue, sourceId = assistant?.revision?.id,
                    profile = profile,
                    model = routeModel,
                    systemPrompt = preparedRequest.systemPrompt,
                    history = preparedRequest.history,
                    cacheKey = "aster-story-${story.id}-${workspace.dbValue}",
                    generationOptions = preparedRequest.generationOptions
                ) { delta ->
'''
if text.count(old) != 1:
    raise SystemExit(f'prepare anchor count={text.count(old)}')
text = text.replace(old, new, 1)
vm.write_text(text, encoding='utf-8')

# Keep this bugfix installable over 2.5.6 while giving it an unambiguous version.
build = Path('app/build.gradle.kts')
bt = build.read_text(encoding='utf-8')
if 'versionCode = 66' not in bt or 'versionName = "2.5.6"' not in bt:
    raise SystemExit('unexpected app version; refusing blind bump')
bt = bt.replace('versionCode = 66', 'versionCode = 67', 1).replace('versionName = "2.5.6"', 'versionName = "2.5.7"', 1)
build.write_text(bt, encoding='utf-8')

changelog = Path('CHANGELOG.md')
if changelog.exists():
    ct = changelog.read_text(encoding='utf-8')
    marker = '## 未发布\n'
    if marker in ct and '## 2.5.7' not in ct:
        ct = ct.replace(marker, marker + '\n## 2.5.7\n\n- 修复故事正文“重新生成”可能读取到异步 UI 预设缓存而未携带当前酒馆预设的问题；生成开始时从预设存储冻结完整请求快照，并阻止与预设配置保存并发。\n\n', 1)
        changelog.write_text(ct, encoding='utf-8')

print('Applied story regenerate preset snapshot fix and bumped 2.5.7')
