from pathlib import Path

p = Path('app/src/main/java/com/adong/adchat/data/ApiRepository.kt')
s = p.read_text()

def rep(old: str, new: str, count: int = 1):
    global s
    found = s.count(old)
    if found != count:
        raise SystemExit(f'Expected {count}, found {found}: {old[:180]!r}')
    s = s.replace(old, new, count)

rep(
    'class ApiRepository internal constructor(private val skillLoader: SkillLoader = GitHubSkillRuntime) {',
    '''class ApiRepository internal constructor(
    private val skillLoader: SkillLoader = GitHubSkillRuntime,
    private val skillBundleLoader: SkillBundleLoader = GitHubSkillBundleRuntime,
    private val nativeSkillUploader: NativeSkillUploader = NativeSkillsApi
) {'''
)

rep(
    '''        val skillLoadingEnabled = shouldOfferSkillLoader(history)
        val toolsActive = profile.webSearchEnabled || profile.fileCreationEnabled || skillLoadingEnabled
''',
    '''        val requestedSkillUrl = requestedGitHubSkillUrl(history)
        var skillLoadingEnabled = requestedSkillUrl != null
        var nativeSkillReference: NativeSkillReference? = null
        if (requestedSkillUrl != null && profile.usesResponses(model)) {
            onToolActivity(ChatToolActivity("native_skill", LOAD_SKILL_TOOL, "正在从 GitHub 准备原生 Skill", TOOL_STATUS_RUNNING))
            try {
                val bundle = skillBundleLoader.loadBundle(requestedSkillUrl)
                nativeSkillReference = nativeSkillUploader.upload(profile, bundle)
                skillLoadingEnabled = false
                onToolActivity(ChatToolActivity(
                    "native_skill",
                    LOAD_SKILL_TOOL,
                    "已通过原生 Skills API 加载：${nativeSkillReference?.name ?: bundle.name}",
                    TOOL_STATUS_COMPLETED
                ))
            } catch (unsupported: NativeSkillsUnsupportedException) {
                skillLoadingEnabled = true
                onToolActivity(ChatToolActivity(
                    "native_skill",
                    LOAD_SKILL_TOOL,
                    "当前服务不支持原生 Skills，改用 GitHub 兼容加载",
                    TOOL_STATUS_COMPLETED
                ))
            } catch (error: Throwable) {
                onToolActivity(ChatToolActivity(
                    "native_skill",
                    LOAD_SKILL_TOOL,
                    error.message ?: "原生 Skill 加载失败",
                    TOOL_STATUS_FAILED
                ))
                throw error
            }
        }
        val toolsActive = profile.webSearchEnabled || profile.fileCreationEnabled || requestedSkillUrl != null
'''
)

rep(
    '                streamResponses(profile, model, systemPrompt, prepared.history, cacheKey, skillLoadingEnabled, onToolActivity, deltaSink)\n',
    '                streamResponses(profile, model, systemPrompt, prepared.history, cacheKey, skillLoadingEnabled, nativeSkillReference, onToolActivity, deltaSink)\n'
)

old_try = '''        try {
            val result = executeWithPreDeltaRetry(initialContext.history, initialSink)
            return@withContext result.copy(text = combined.toString().ifBlank { result.text })
        } catch (initialError: Throwable) {
            if (initialError is CancellationException) throw initialError
            currentCoroutineContext().ensureActive()
            if (combined.isEmpty() || toolsActive || !profile.autoResumeStream || !initialError.isRetryableStreamFailure()) throw initialError
        }
'''
new_try = '''        try {
            val result = executeWithPreDeltaRetry(initialContext.history, initialSink)
            return@withContext result.copy(text = combined.toString().ifBlank { result.text })
        } catch (initialError: Throwable) {
            if (initialError is CancellationException) throw initialError
            currentCoroutineContext().ensureActive()
            if (combined.isEmpty() && nativeSkillReference != null && initialError.isNativeSkillCompatibilityFailure()) {
                onToolActivity(ChatToolActivity(
                    "native_skill",
                    LOAD_SKILL_TOOL,
                    "当前模型不支持原生 Skill 环境，改用 GitHub 兼容加载",
                    TOOL_STATUS_COMPLETED
                ))
                nativeSkillReference = null
                skillLoadingEnabled = true
                val fallback = executeWithPreDeltaRetry(initialContext.history, initialSink)
                return@withContext fallback.copy(text = combined.toString().ifBlank { fallback.text })
            }
            if (combined.isEmpty() || toolsActive || !profile.autoResumeStream || !initialError.isRetryableStreamFailure()) throw initialError
        }
'''
rep(old_try, new_try)

rep(
    '''        skillLoadingEnabled: Boolean,
        onToolActivity: suspend (ChatToolActivity) -> Unit,
''',
    '''        skillLoadingEnabled: Boolean,
        nativeSkillReference: NativeSkillReference?,
        onToolActivity: suspend (ChatToolActivity) -> Unit,
'''
)

rep(
    '        val tools = buildResponsesTools(profile.fileCreationEnabled, profile.webSearchEnabled, skillLoadingEnabled)\n',
    '''        val tools = buildResponsesTools(profile.fileCreationEnabled, profile.webSearchEnabled, skillLoadingEnabled).apply {
            nativeSkillReference?.let { put(nativeSkillShellTool(it)) }
        }
'''
)

rep(
    '''                body.put("tool_choice", if (forceSkill)
                    JSONObject().put("type", "function").put("name", LOAD_SKILL_TOOL)
                else "auto")
''',
    '''                body.put("tool_choice", when {
                    nativeSkillReference != null && previousResponseId == null -> JSONObject().put("type", "shell")
                    forceSkill -> JSONObject().put("type", "function").put("name", LOAD_SKILL_TOOL)
                    else -> "auto"
                })
'''
)

rep(
    '            if (profile.fileCreationEnabled || skillLoadingEnabled) body.put("store", true)\n',
    '            if (profile.fileCreationEnabled || skillLoadingEnabled || nativeSkillReference != null) body.put("store", true)\n'
)

marker = '''    private fun Throwable.isCacheCompatibilityError(): Boolean {
'''
helper = '''    private fun Throwable.isNativeSkillCompatibilityFailure(): Boolean {
        val value = generateSequence(this) { it.cause }.joinToString(" ") { it.message.orEmpty() }.lowercase()
        if (!(value.contains("skill") || value.contains("shell") || value.contains("container"))) return false
        return listOf(
            "unsupported", "not supported", "does not support", "unknown tool",
            "invalid tool", "not available", "not allowed", "unrecognized",
            "unsupported parameter", "invalid_request_error"
        ).any(value::contains)
    }

'''
if s.count(marker) != 1:
    raise SystemExit('cache compatibility marker missing')
s = s.replace(marker, helper + marker, 1)

p.write_text(s)
