package com.adong.adchat.ui.rp

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adong.adchat.data.ApiProfile
import com.adong.adchat.data.ApiRepository
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.ConfigStore
import com.adong.adchat.data.PromptInjectionStore
import com.adong.adchat.data.SkillRuntime
import com.adong.adchat.data.TavernPreset
import com.adong.adchat.data.TavernPresetStore
import com.adong.adchat.data.TavernPresetSummary
import com.adong.adchat.data.TavernRegexScript
import com.adong.adchat.data.contextLimits
import com.adong.adchat.data.usesAnthropicMessages
import com.adong.adchat.data.usesResponses
import com.adong.adchat.data.rp.RpAuthorsNote
import com.adong.adchat.data.rp.RpCardExtensions
import com.adong.adchat.data.rp.RpChatOps
import com.adong.adchat.data.rp.RpConfig
import com.adong.adchat.data.rp.RpGenerationType
import com.adong.adchat.data.rp.RpHelperActions
import com.adong.adchat.data.rp.RpHelperMessage
import com.adong.adchat.data.rp.RpHelperMessageUpdate
import com.adong.adchat.data.rp.RpHelperScript
import com.adong.adchat.data.rp.RpMemory
import com.adong.adchat.data.rp.RpMemoryStore
import com.adong.adchat.data.rp.RpMessage
import com.adong.adchat.data.rp.RpOpenings
import com.adong.adchat.data.rp.RpPendingReply
import com.adong.adchat.data.rp.RpPersona
import com.adong.adchat.data.rp.RpPersonaContext
import com.adong.adchat.data.rp.RpPersonaStore
import com.adong.adchat.data.rp.RpPromptRequest
import com.adong.adchat.data.rp.RpResolvedPersona
import com.adong.adchat.data.rp.RpSession
import com.adong.adchat.data.rp.RpSessionVariables
import com.adong.adchat.data.rp.RpStore
import com.adong.adchat.data.rp.RpSwipe
import com.adong.adchat.data.rp.RpSwipeResult
import com.adong.adchat.data.rp.RpVarScope
import com.adong.adchat.data.rp.helper.RpMvu
import com.adong.adchat.data.rp.helper.RpTavernHelper
import com.adong.adchat.data.rp.prompt.RpMacroContext
import com.adong.adchat.data.rp.prompt.RpMacros
import com.adong.adchat.data.rp.prompt.RpPromptEngine
import com.adong.adchat.data.rp.prompt.RpPromptOptions
import com.adong.adchat.data.rp.prompt.RpRegex
import com.adong.adchat.data.story.CharacterCardFile
import com.adong.adchat.data.summary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * RP session lifecycle + generation with SillyTavern semantics: swipes, continue, impersonate,
 * edit/hide/branch, personas, Author's Note, preset/world-info prompt assembly (RpPromptEngine),
 * ingest regex, native MVU before a reply is shown, and the tavern_helper host for card frontends.
 * Nothing runs at app startup beyond loading persisted sessions.
 */
class RpViewModel(application: Application) : AndroidViewModel(application) {
    private val store = RpStore(application)
    private val memoryStore = RpMemoryStore(application)
    private val configStore = ConfigStore(application)
    private val api = ApiRepository(skillLoader = SkillRuntime.persistent(application))
    private val tavernPresetStore = TavernPresetStore(application)
    private val injectionStore = PromptInjectionStore(application)
    val personaStore = RpPersonaStore(application)
    val lorebookStore = com.adong.adchat.data.rp.RpLorebookStore(application)
    var lorebooks by mutableStateOf<List<com.adong.adchat.data.rp.RpLorebook>>(emptyList())
        private set
    var globalLorebookIds by mutableStateOf<Set<String>>(emptySet())
        private set

    val sessions = mutableStateListOf<RpSession>()
    var activeSessionId by mutableStateOf<String?>(null)
        private set
    val tavernPresets = mutableStateListOf<TavernPresetSummary>()
    var tavernRegexEnabled by mutableStateOf(application.getSharedPreferences(PREFS, 0).getBoolean(KEY_REGEX, true))
        private set
    /** Built-in Prompt Template (EJS) processing for prompts and rendering. */
    var templatesEnabled by mutableStateOf(application.getSharedPreferences(PREFS, 0).getBoolean(KEY_TEMPLATES, true))
        private set
    var personas by mutableStateOf<List<RpPersona>>(emptyList())
        private set

    var generating by mutableStateOf(false)
        private set
    var generationType by mutableStateOf(RpGenerationType.Normal)
        private set
    var streamingContent by mutableStateOf("")
        private set
    var streamingReasoning by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Composer draft owned here so /setinput and impersonate can fill it. */
    var draft by mutableStateOf("")
    /** Bumped to move focus into the composer (edit & resend, impersonate). */
    var draftFocusRequest by mutableStateOf(0)
        private set

    fun loadDraftForEdit(text: String) {
        draft = text
        draftFocusRequest++
    }
    /** Activated world-info entry names of the last request (debug view). */
    var lastWorldEntries by mutableStateOf<List<String>>(emptyList())
        private set

    private var generationJob: Job? = null
    private val memoryJobs = mutableMapOf<String, Job>()
    private val helpers = mutableMapOf<String, RpTavernHelper>()
    /** Read from the main thread, Chromium's bridge thread and Default; one store per session+card. */
    private val variableStores = java.util.concurrent.ConcurrentHashMap<String, RpSessionVariables>()
    private var presetCache: Pair<String?, TavernPreset?>? = null
    var memoryBusy by mutableStateOf(false)
        private set
    var memoryStatus by mutableStateOf("")
        private set

    val activeSession: RpSession?
        get() = sessions.firstOrNull { it.id == activeSessionId }

    private suspend fun refreshPresets() {
        val list = withContext(Dispatchers.IO) { runCatching { tavernPresetStore.list() }.getOrDefault(emptyList()) }
        tavernPresets.clear()
        tavernPresets.addAll(list.map { it.summary() })
        presetCache = null
    }

    fun availableProfiles(): List<ApiProfile> = configStore.load().profiles

    /** Resolves the active chat route so a new session inherits the user's current service/model. */
    fun defaultRoute(): Pair<ApiProfile?, String> {
        val config = configStore.load()
        val profile = config.profiles.firstOrNull { it.id == config.activeChatProfileId } ?: config.profiles.firstOrNull()
        return profile to profile?.chatModel.orEmpty()
    }

    /** Set when a new session should open its setup sheet (drawer "新建剧情"). */
    var setupRequest by mutableStateOf(0)
        private set

    fun requestNewSession() {
        if (generating) return
        startNewSession()
        setupRequest++
    }

    fun startNewSession(config: RpConfig = RpConfig()) {
        val (profile, model) = defaultRoute()
        createSession(config, profile?.id.orEmpty(), model)
    }

    fun selectSession(id: String) {
        if (generating) return
        if (activeSessionId != id) activeSessionId?.let { memoryJobs.remove(it)?.cancel() }
        activeSessionId = id
        error = null
        memoryStatus = ""
        viewModelScope.launch { fallbackUserName = withContext(Dispatchers.IO) { configStore.load().userName } }
    }

    fun createSession(config: RpConfig, profileId: String, model: String) {
        val now = System.currentTimeMillis()
        val session = RpSession(
            title = config.card.name.ifBlank { "新剧情" },
            createdAt = now,
            updatedAt = now,
            config = config,
            messages = listOfNotNull(RpOpenings.greetingMessage(config, model, now)),
            profileId = profileId,
            model = model
        )
        sessions.add(0, session)
        activeSessionId = session.id
        store.save(session)
        saveCardBook(session)
        seedInitialVariables(session)
    }

    fun deleteSession(id: String) {
        if (generating && activeSessionId == id) return
        memoryJobs.remove(id)?.cancel()
        memoryStore.clear(id)
        helpers.remove(id)
        variableStores.keys.filter { it.startsWith(id + "\u0000") }.forEach { variableStores.remove(it)?.deleteSession() }
        sessions.removeAll { it.id == id }
        store.delete(id)
        if (activeSessionId == id) activeSessionId = sessions.firstOrNull()?.id
    }

    fun updateActiveConfig(draft: RpConfig) {
        val session = activeSession ?: return
        // The setup sheet edits the card only; the preset is chosen in its own picker and may have
        // changed while the sheet was open, so the live value always wins.
        val config = draft.copy(presetId = session.config.presetId)
        val reconciled = RpOpenings.reconcile(session, config)
        val cardChanged = session.cardKey != reconciled.cardKey
        updateSession(
            reconciled.copy(
                title = config.card.name.ifBlank { session.title },
                // A different card must be re-approved before its scripts/regex run.
                cardRegexAllowed = if (cardChanged) null else reconciled.cardRegexAllowed,
                cardScriptsAllowed = if (cardChanged) null else reconciled.cardScriptsAllowed,
                updatedAt = System.currentTimeMillis()
            )
        )
        if (cardChanged) activeSession?.let { helpers[it.id]?.setScripts(helperScripts(it)) }
        activeSession?.let(::saveCardBook)
        if (reconciled.pristine) seedInitialVariables(activeSession ?: return)
    }

    /* ---------------- world books ---------------- */

    private fun refreshLorebooks() {
        lorebooks = lorebookStore.list()
        globalLorebookIds = lorebookStore.globalIds()
    }

    /** The card's embedded book is kept in the library as an optional book (ST "import card lorebook"). */
    private fun saveCardBook(session: RpSession) {
        val card = session.config.card
        if (card.worldBook.entries.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            lorebookStore.saveCardBook(session.cardKey, card.name, card.worldBook)
            withContext(Dispatchers.Main) { refreshLorebooks() }
        }
    }

    /** Imports a SillyTavern world book (native lorebook JSON, character_book, or a whole card). */
    suspend fun importLorebook(bytes: ByteArray, fileName: String, bindToChat: Boolean): String = withContext(Dispatchers.IO) {
        val parsed = com.adong.adchat.data.rp.RpLorebookStore.parseFile(bytes.toString(Charsets.UTF_8), fileName)
        val saved = lorebookStore.save(parsed)
        withContext(Dispatchers.Main) {
            refreshLorebooks()
            if (bindToChat) setChatLorebook(saved.id, true)
        }
        saved.name
    }

    fun setGlobalLorebook(id: String, enabled: Boolean) {
        lorebookStore.setGlobal(id, enabled)
        refreshLorebooks()
    }

    fun setChatLorebook(id: String, bound: Boolean) {
        val session = activeSession ?: return
        val ids = session.chatLorebookIds.toMutableList().apply { remove(id); if (bound) add(id) }
        updateSession(session.copy(chatLorebookIds = ids))
    }

    fun setCardBookEnabled(enabled: Boolean) {
        val session = activeSession ?: return
        updateSession(session.copy(cardBookEnabled = enabled))
    }

    fun deleteLorebook(id: String) {
        lorebookStore.delete(id)
        sessions.filter { id in it.chatLorebookIds }.forEach { updateSession(it.copy(chatLorebookIds = it.chatLorebookIds - id)) }
        refreshLorebooks()
    }

    fun renameLorebook(id: String, name: String) {
        val book = lorebookStore.get(id) ?: return
        lorebookStore.save(book.copy(name = name.trim().ifBlank { book.name }))
        refreshLorebooks()
    }

    /** Toggles one entry of a library book (ST entry "disable"). */
    fun setLorebookEntryEnabled(id: String, index: Int, enabled: Boolean) {
        val book = lorebookStore.get(id) ?: return
        val entries = book.book.entries.toMutableList()
        if (index !in entries.indices) return
        entries[index] = entries[index].copy(enabled = enabled)
        lorebookStore.save(book.copy(book = book.book.copy(entries = entries)))
        refreshLorebooks()
    }

    private fun activeWorldBook(session: RpSession): com.adong.adchat.data.story.StoryWorldBook {
        val persona = resolvedPersona(session)?.persona?.lorebook
        return com.adong.adchat.data.rp.RpLorebookStore.merge(lorebookStore.activeBooks(session, persona))
    }

    /* ---------------- presets ---------------- */

    /* ---- preset editing (same store as Story mode; edits apply per preset, never to the file) ---- */

    var presetConfiguration by mutableStateOf<com.adong.adchat.data.TavernPresetConfiguration?>(null)
        private set
    var presetBusy by mutableStateOf(false)
        private set
    var presetError by mutableStateOf<String?>(null)
        private set

    /** Loads the editable configuration of the active session's preset (null when none selected). */
    fun openPresetEditor(): Boolean {
        val id = activeSession?.config?.presetId ?: return false
        presetError = null
        presetConfiguration = runCatching { tavernPresetStore.configuration(id) }.getOrNull()
        return presetConfiguration != null
    }

    fun closePresetEditor() { presetConfiguration = null }

    private fun mutatePreset(errorMessage: String, mutation: (String) -> Unit) {
        val id = presetConfiguration?.id ?: return
        if (presetBusy) return
        presetBusy = true
        presetError = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { mutation(id) }
                val updated = withContext(Dispatchers.IO) { tavernPresetStore.configuration(id) }
                presetConfiguration = updated
                refreshPresets()
            }.onFailure { presetError = it.message ?: errorMessage }
            presetBusy = false
        }
    }

    fun setPresetPromptEnabled(identifier: String, enabled: Boolean) =
        mutatePreset("更新提示词模块失败") { tavernPresetStore.setPromptEnabled(it, identifier, enabled) }

    fun setPresetPromptContent(identifier: String, content: String) =
        mutatePreset("保存提示词内容失败") { tavernPresetStore.setPromptContent(it, identifier, content) }

    fun setPresetRegexScriptEnabled(index: Int, enabled: Boolean) =
        mutatePreset("更新正则脚本失败") { tavernPresetStore.setRegexScriptEnabled(it, index, enabled) }

    fun resetPresetConfiguration() = mutatePreset("恢复预设默认失败") { tavernPresetStore.resetConfiguration(it) }

    fun setPreset(id: String?) {
        val session = activeSession ?: return
        updateSession(session.copy(config = session.config.copy(presetId = id)))
    }

    fun setSessionPinned(id: String, value: Boolean) { sessionById(id)?.let { updateSession(it.copy(pinned = value)) } }
    fun setSessionFavorite(id: String, value: Boolean) { sessionById(id)?.let { updateSession(it.copy(favorite = value)) } }
    fun setSessionArchived(id: String, value: Boolean) { sessionById(id)?.let { updateSession(it.copy(archived = value)) } }
    fun setSessionTags(id: String, tags: List<String>) { sessionById(id)?.let { updateSession(it.copy(tags = tags)) } }

    /** Drawer rows: RP save files presented in the chat conversation shape. */
    fun drawerConversations(): List<com.adong.adchat.data.Conversation> = sessions.map { s ->
        com.adong.adchat.data.Conversation(
            id = s.id, title = s.title.ifBlank { s.config.card.name.ifBlank { "未命名剧情" } },
            messages = s.messages.takeLast(1).map { it.toChatMessage() },
            createdAt = s.createdAt, updatedAt = s.updatedAt, profileId = s.profileId, model = s.model,
            pinned = s.pinned, archived = s.archived, favorite = s.favorite, tags = s.tags
        )
    }

    fun renameSession(id: String, title: String) {
        val session = sessionById(id) ?: return
        updateSession(session.copy(title = title.trim().ifBlank { session.title }))
    }

    /** Imports a SillyTavern chat-completion preset and selects it for the active chat. */
    suspend fun importPreset(bytes: ByteArray, fileName: String): TavernPreset {
        val preset = withContext(Dispatchers.IO) {
            tavernPresetStore.importPreset(bytes.inputStream(), fileName)
        }
        refreshPresets()
        val imported = tavernPresetStore.list().firstOrNull { it.id == preset.id } ?: preset
        activeSession?.let { updateSession(it.copy(config = it.config.copy(presetId = imported.id))) }
        return imported
    }

    /** Resolves the session's selected Tavern preset (with user configuration applied). Cached. */
    fun presetFor(config: RpConfig): TavernPreset? {
        val id = config.presetId ?: return null
        presetCache?.let { (cachedId, preset) -> if (cachedId == id) return preset }
        val preset = runCatching { tavernPresetStore.list().firstOrNull { it.id == id } }.getOrNull()
        presetCache = id to preset
        return preset
    }

    fun setActiveProfileModel(profileId: String, model: String) {
        val session = activeSession ?: return
        updateSession(session.copy(profileId = profileId, model = model, updatedAt = System.currentTimeMillis()))
    }

    fun setInjectionEnabled(enabled: Boolean) {
        val session = activeSession ?: return
        updateSession(session.copy(injectionEnabled = enabled))
    }

    fun setMemoryEnabled(enabled: Boolean) {
        val session = activeSession ?: return
        updateSession(session.copy(memoryEnabled = enabled))
        if (enabled) maybeScheduleMemory(session.id) else {
            memoryJobs.remove(session.id)?.cancel()
            memoryBusy = false
        }
    }

    fun setAuthorsNote(note: RpAuthorsNote) {
        val session = activeSession ?: return
        updateSession(session.copy(authorsNote = note, updatedAt = System.currentTimeMillis()))
    }

    /** Records consent for the card's regex scripts and tavern_helper scripts. */
    fun setCardPermissions(regex: Boolean, scripts: Boolean) {
        val session = activeSession ?: return
        updateSession(session.copy(cardRegexAllowed = regex, cardScriptsAllowed = scripts))
        helpers[session.id]?.setScripts(helperScripts(activeSession ?: return))
    }

    fun memorySummaryFor(sessionId: String): String? = memoryStore.summary(sessionId)

    fun clearMemory(sessionId: String) {
        memoryJobs.remove(sessionId)?.cancel()
        memoryStore.clear(sessionId)
        memoryBusy = false
        memoryStatus = "剧情摘要已清空"
    }

    fun updateTemplatesEnabled(enabled: Boolean) {
        templatesEnabled = enabled
        getApplication<Application>().getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_TEMPLATES, enabled).apply()
    }

    fun setRegexEnabled(enabled: Boolean) {
        tavernRegexEnabled = enabled
        getApplication<Application>().getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_REGEX, enabled).apply()
    }

    /** Parses a Tavern character card (JSON/PNG/charx) into a config with the raw card, keeping the preset. */
    fun parseCard(bytes: ByteArray): RpConfig {
        val imported = CharacterCardFile.parse(bytes)
        return RpConfig.fromImported(imported.copy(card = imported.rawCard), presetId = activeSession?.config?.presetId)
    }

    suspend fun parseCardAsync(bytes: ByteArray): RpConfig = withContext(Dispatchers.Default) { parseCard(bytes) }

    fun clearError() {
        error = null
    }

    fun reportError(message: String) {
        error = message
    }

    /* ---------------- personas ---------------- */

    /** Settings user name; read once (and on session switch) instead of decoding the config per call. */
    private var fallbackUserName by mutableStateOf("")

    fun resolvedPersona(session: RpSession? = activeSession): RpResolvedPersona? =
        session?.let { personaStore.resolve(it, fallbackUserName) }

    fun userName(): String = resolvedPersona()?.persona?.name ?: fallbackUserName.ifBlank { RpPersona.DEFAULT_NAME }

    /** Composition-safe {{user}} name: recomputed only when the session, persona or library changes. */
    val cachedUserName: String by androidx.compose.runtime.derivedStateOf {
        personas
        val session = activeSession
        session?.let { personaStore.resolve(it, fallbackUserName).persona.name } ?: fallbackUserName.ifBlank { RpPersona.DEFAULT_NAME }
    }

    fun savePersona(persona: RpPersona, makeDefault: Boolean = false) {
        personaStore.upsert(persona)
        if (makeDefault || personaStore.library().defaultId == null) personaStore.setDefault(persona.id)
        personas = personaStore.list()
    }

    fun deletePersona(id: String) {
        personaStore.delete(id)
        personas = personaStore.list()
        sessions.filter { it.personaId == id }.forEach { updateSession(it.copy(personaId = null)) }
    }

    fun setDefaultPersona(id: String) {
        personaStore.setDefault(id)
        personas = personaStore.list()
    }

    /** Locks a persona to the active chat (null = follow card connection / default). */
    fun lockPersona(id: String?) {
        val session = activeSession ?: return
        updateSession(session.copy(personaId = id))
    }

    fun connectPersonaToCard(id: String, connected: Boolean) {
        val session = activeSession ?: return
        personaStore.connect(id, session.cardKey, connected)
        personas = personaStore.list()
    }

    /* ---------------- chat actions ---------------- */

    /** Returns false when nothing was sent (keep the draft). */
    fun sendMessage(text: String): Boolean {
        val trimmed = text.trim()
        if (generating) return false
        val session = activeSession ?: return false
        val route = route(session) ?: return false
        if (trimmed.isEmpty()) {
            // ST: an empty send continues the story from the current chat.
            if (session.messages.isNotEmpty()) launchGeneration(withRoute(session, route), RpGenerationType.Normal)
            return true
        }
        val stored = RpRegex.ingest(trimmed, "user", regexScripts(session), macroContext(session))
        val base = RpChatOps.removeTrailingError(session) ?: session
        val withUser = withRoute(RpChatOps.append(base, "user", stored, name = userName()), route).copy(tainted = true)
        updateSession(withUser)
        helpers[withUser.id]?.emitEvent("message_sent", withUser.messages.lastIndex)
        launchGeneration(withUser, RpGenerationType.Normal)
        return true
    }

    /** Resumes after a failure or a stopped generation that left the chat ending on the user. */
    fun resumeGeneration() {
        if (generating) return
        val session = activeSession ?: return
        val base = RpChatOps.removeTrailingError(session) ?: session
        val route = route(base) ?: return
        updateSession(base)
        launchGeneration(withRoute(base, route), RpGenerationType.Normal)
    }

    /** ST swipe: left cycles, right past the end generates a new alternative. */
    fun swipe(direction: Int) {
        if (generating) return
        val session = activeSession ?: return
        when (val result = RpChatOps.swipe(session, direction)) {
            is RpSwipeResult.Moved -> {
                val greeting = result.session.messages.size == 1
                updateSession(if (greeting) result.session.copy(tainted = session.tainted) else result.session)
                val id = result.session.messages.lastIndex
                helpers[session.id]?.let {
                    it.emitEvent("message_swiped", id)
                    if (greeting) it.emitEvent("character_first_message_selected", result.session.messages.first().currentIndex)
                }
                if (greeting) seedInitialVariables(activeSession ?: return)
            }
            RpSwipeResult.NeedsGeneration -> {
                val route = route(session) ?: return
                launchGeneration(withRoute(session, route), RpGenerationType.Swipe)
            }
            RpSwipeResult.None -> Unit
        }
    }

    /** Regenerate keeps the old reply as a swipe (ST "regenerate" on the last message = new swipe). */
    fun regenerateLast() {
        if (generating) return
        val session = activeSession ?: return
        val last = session.messages.lastOrNull() ?: return
        if (last.isError || last.isUser) return resumeGeneration()
        val route = route(session) ?: return
        launchGeneration(withRoute(session, route), RpGenerationType.Swipe)
    }

    fun retryLast() = resumeGeneration()

    fun continueLast() {
        if (generating) return
        val session = activeSession ?: return
        val last = session.messages.lastOrNull() ?: return
        if (last.isUser || last.isError) return resumeGeneration()
        val route = route(session) ?: return
        launchGeneration(withRoute(session, route), RpGenerationType.Continue)
    }

    fun impersonate() {
        if (generating) return
        val session = activeSession ?: return
        val route = route(session) ?: return
        launchGeneration(withRoute(session, route), RpGenerationType.Impersonate)
    }

    fun deleteSwipe() {
        if (generating) return
        val session = activeSession ?: return
        val last = session.messages.lastOrNull() ?: return
        val deleted = last.currentIndex
        val updated = RpChatOps.deleteSwipe(session, last.id) ?: return
        variables(session).onSwipeDeleted(last.id, deleted)
        updateSession(updated)
        helpers[session.id]?.emitEvent("message_swipe_deleted", session.messages.lastIndex)
    }

    fun editMessage(id: Long, text: String) {
        if (generating) return
        val session = activeSession ?: return
        val message = session.messages.firstOrNull { it.id == id } ?: return
        val stored = RpRegex.ingest(text, message.role, regexScripts(session), macroContext(session), isEdit = true)
        val updated = RpChatOps.edit(session, id, stored) ?: return
        updateSession(updated)
        helpers[session.id]?.let { it.emitEvent("message_edited", session.indexOf(id)); it.touch(id) }
    }

    fun setHidden(id: Long, hidden: Boolean) {
        val session = activeSession ?: return
        RpChatOps.setHidden(session, listOf(id), hidden)?.let(::updateSession)
    }

    fun deleteMessage(id: Long) {
        if (generating) return
        val session = activeSession ?: return
        val index = session.indexOf(id)
        val (updated, removed) = RpChatOps.delete(session, id) ?: return
        variables(session).dropFloors(removed)
        updateSession(updated)
        helpers[session.id]?.emitEvent("message_deleted", index)
    }

    /** Removes the given message and everything after it (edit & resend). */
    fun truncateFromMessage(id: Long) {
        if (generating) return
        val session = activeSession ?: return
        val index = session.indexOf(id)
        val (updated, removed) = RpChatOps.truncateFrom(session, id) ?: return
        variables(session).dropFloors(removed)
        updateSession(updated)
        helpers[session.id]?.emitEvent("message_deleted", index)
    }

    /** Copies the chat up to [id] into a new session and switches to it. */
    fun branchFrom(id: Long) {
        if (generating) return
        val session = activeSession ?: return
        val branch = RpChatOps.branch(session, id) ?: return
        variables(session).copyTo(variables(branch), branch.messages.map { it.id })
        sessions.add(0, branch)
        store.save(branch)
        activeSessionId = branch.id
    }

    fun stopGeneration() {
        generationJob?.cancel()
    }

    /* ---------------- helper host ---------------- */

    /** Card frontends may run scripts only after the user allowed them for this session. */
    fun scriptsAllowed(session: RpSession): Boolean = session.cardScriptsAllowed == true

    fun helperScripts(session: RpSession): List<RpHelperScript> =
        if (!scriptsAllowed(session)) emptyList()
        else RpCardExtensions.helperScripts(session.config.card, null)

    /** Needs consent: the card ships regex scripts or tavern_helper scripts and the user was not asked. */
    fun needsCardConsent(session: RpSession): Boolean {
        val card = session.config.card
        val hasRegex = RpCardExtensions.scripts(card).isNotEmpty()
        val hasScripts = RpCardExtensions.helperScripts(card, null).any { it.content.isNotBlank() }
        return (hasRegex && session.cardRegexAllowed == null) || (hasScripts && session.cardScriptsAllowed == null)
    }

    fun helperFor(session: RpSession): RpTavernHelper = helpers.getOrPut(session.id) {
        RpTavernHelper(
            sessionId = session.id,
            variables = object : com.adong.adchat.data.rp.RpVariableStore {
                override fun table(scope: RpVarScope, messageId: Long?, swipeId: Int, scriptId: String?) =
                    variables(session).table(scope, messageId, swipeId, scriptId)
                override fun replace(scope: RpVarScope, table: JSONObject, messageId: Long?, swipeId: Int, scriptId: String?) =
                    variables(session).replace(scope, table, messageId, swipeId, scriptId)
            },
            actions = HelperActions(session.id),
            scope = viewModelScope,
            macros = { text -> sessionById(session.id)?.let { RpMacros.expand(text, macroContext(it)) } ?: text },
            card = { JSONObject(sessionById(session.id)?.config?.card?.toJson() ?: "{}") },
            worldEntries = {
                JSONArray(sessionById(session.id)?.let(::activeWorldBook)?.entries.orEmpty().map { it.toJson() })
            },
            storage = object : RpTavernHelper.Storage {
                override fun read() = variables(session).storage()
                override fun write(values: JSONObject) = variables(session).replaceStorage(values)
            }
        ).also { it.setScripts(helperScripts(session)) }
    }

    /** Display-side regex scripts (preset + consented card scripts). */
    fun regexScripts(session: RpSession): List<TavernRegexScript> =
        if (!tavernRegexEnabled) emptyList()
        else RpRegex.scripts(presetFor(session.config), session.config.card, session.cardRegexAllowed == true)

    fun macroContext(session: RpSession, persona: RpPersonaContext? = null): RpMacroContext {
        val card = session.config.card
        val p = persona ?: resolvedPersona(session)?.toContext() ?: RpPersonaContext(name = RpPersona.DEFAULT_NAME)
        val store = variables(session)
        return RpMacroContext(
            user = p.name, char = card.macroName, persona = p.description,
            description = card.description, personality = card.personality, scenario = card.scenario,
            mesExamples = card.mesExample, charPrompt = card.systemPrompt, charJailbreak = card.postHistory,
            charDepthPrompt = card.depthPrompt?.prompt.orEmpty(), creatorNotes = card.creatorNotes,
            charVersion = card.characterVersion, messages = session.promptMessages(), model = session.model,
            variables = store, chatId = session.id,
            messageVariables = { latestFloorVariables(session) }
        )
    }

    private fun latestFloorVariables(session: RpSession): JSONObject? {
        val store = variables(session)
        val (id, swipe) = store.latestFloor(sessionById(session.id)?.messages ?: session.messages) { it.length() > 0 } ?: return null
        return store.table(RpVarScope.Message, id, swipe)
    }

    private fun variables(session: RpSession): RpSessionVariables {
        val current = sessionById(session.id) ?: session
        val key = current.id + "\u0000" + current.cardKey
        return variableStores.computeIfAbsent(key) {
            // A card change re-keys the Character scope; drop the previous store of this session.
            variableStores.keys.removeIf { it.startsWith(current.id + "\u0000") && it != key }
            RpSessionVariables(getApplication(), current.id, current.cardKey).also {
                it.migrateLegacy(RpSessionVariables.legacyFile(getApplication(), current.id), current.messages)
            }
        }
    }

    private fun sessionById(id: String): RpSession? = sessions.firstOrNull { it.id == id }

    private inner class HelperActions(private val sessionId: String) : RpHelperActions {
        private fun session() = sessionById(sessionId)

        override fun messages(): List<RpHelperMessage> = session()?.messages.orEmpty().mapIndexed { index, m ->
            RpHelperMessage(index, m.id, m.role, m.name, m.content, m.safeSwipes.map { it.content }, m.currentIndex, m.hidden)
        }

        override fun setMessages(updates: List<RpHelperMessageUpdate>) {
            if (generating) return
            var current = session() ?: return
            updates.forEach { update ->
                val message = current.messages.getOrNull(update.index) ?: return@forEach
                update.swipeId?.let { swipe ->
                    if (message.swipeCount > swipe) RpChatOps.selectSwipe(current, message.id, swipe)?.let { current = it }
                }
                update.content?.let { text -> RpChatOps.edit(current, message.id, text)?.let { current = it } }
                update.hidden?.let { hidden -> RpChatOps.setHidden(current, listOf(message.id), hidden)?.let { current = it } }
            }
            updateSession(current)
            updates.forEach { helpers[sessionId]?.emitEvent("message_updated", it.index) }
        }

        override fun createMessages(items: List<Pair<String, String>>, insertBefore: Int?) {
            if (generating) return
            val current = session() ?: return
            val created = items.map { (role, text) ->
                RpMessage(role = role.ifBlank { "assistant" }, name = if (role == "user") userName() else current.config.card.macroName,
                    swipes = listOf(RpSwipe(text)))
            }
            updateSession(RpChatOps.insert(current, created, insertBefore).copy(tainted = true))
        }

        override fun deleteMessages(indices: List<Int>) {
            if (generating) return
            val current = session() ?: return
            val ids = indices.mapNotNull { current.messages.getOrNull(it)?.id }
            val (updated, removed) = RpChatOps.deleteMany(current, ids) ?: return
            variables(current).dropFloors(removed)
            updateSession(updated)
        }

        override fun sendUser(text: String) {
            if (generating) return
            val current = session() ?: return
            if (text.isBlank()) return
            updateSession(RpChatOps.append(current, "user", text, name = userName()).copy(tainted = true))
            helpers[sessionId]?.emitEvent("message_sent", (session()?.messages?.lastIndex ?: 0))
        }

        override fun sendAs(role: String, name: String, text: String) {
            if (generating) return
            val current = session() ?: return
            if (text.isBlank()) return
            val r = when (role) { "comment" -> "system"; "user", "assistant", "system" -> role; else -> "assistant" }
            updateSession(RpChatOps.append(current, r, text, name = name.ifBlank { current.config.card.macroName }).copy(tainted = true))
        }

        override fun trigger() {
            if (generating) return
            val current = session() ?: return
            val route = route(current) ?: return
            launchGeneration(withRoute(current, route), RpGenerationType.Normal)
        }

        override fun setInput(text: String) {
            if (sessionId == activeSessionId) draft = text
        }

        override fun toast(text: String) {
            if (text.isNotBlank()) Toast.makeText(getApplication(), text.take(200), Toast.LENGTH_SHORT).show()
        }

        override fun context(): JSONObject {
            val current = session()
            return JSONObject()
                .put("name1", userName())
                .put("name2", current?.config?.card?.macroName.orEmpty())
                .put("characterId", current?.cardKey.orEmpty())
                .put("chatId", sessionId)
                .put("groupId", JSONObject.NULL)
                .put("onlineStatus", current?.model.orEmpty())
                .put("mainApi", "openai")
        }

        override suspend fun generate(prompt: String, options: JSONObject): String {
            val current = session() ?: return ""
            val route = route(current) ?: return ""
            return runCatching { quietGenerate(current, route, prompt, raw = options.optBoolean("raw")) }.getOrDefault("")
        }
    }

    /* ---------------- generation ---------------- */

    private data class Route(val profile: ApiProfile, val model: String)

    fun setReasoningEffort(effort: String) {
        val session = activeSession ?: return
        updateSession(session.copy(reasoningEffort = effort))
    }

    /** The session's effort, falling back to the service profile's own setting. */
    fun reasoningEffortFor(session: RpSession): String =
        session.reasoningEffort.ifBlank { availableProfiles().firstOrNull { it.id == session.profileId }?.reasoningEffort.orEmpty() }

    /** Folds the preset's in-chat system messages into a shape the selected protocol accepts. */
    private fun sendable(route: Route, prepared: com.adong.adchat.data.rp.RpPromptResult, type: RpGenerationType): com.adong.adchat.data.rp.prompt.RpPromptPostProcess.Result {
        val strict = route.profile.usesAnthropicMessages(route.model) || route.profile.usesResponses(route.model) ||
            route.model.contains("gemini", ignoreCase = true) || route.model.contains("claude", ignoreCase = true)
        val mode = if (strict) com.adong.adchat.data.rp.prompt.RpPromptPostProcess.Mode.Strict else com.adong.adchat.data.rp.prompt.RpPromptPostProcess.Mode.Lenient
        val prefill = type == RpGenerationType.Continue || prepared.messages.lastOrNull()?.role == "assistant"
        return com.adong.adchat.data.rp.prompt.RpPromptPostProcess.apply(prepared.systemPrompt, prepared.messages, mode, prefill)
    }

    private fun route(session: RpSession): Route? {
        val config = configStore.load()
        val profile = config.profiles.firstOrNull { it.id == session.profileId } ?: runCatching { config.chatProfile() }.getOrNull()
        val model = session.model.ifBlank { profile?.chatModel.orEmpty() }
        if (profile == null || model.isBlank()) {
            error = "请先在配置面板选择模型"
            return null
        }
        val effort = session.reasoningEffort
        return Route(if (effort.isNotBlank()) profile.copy(reasoningEffort = effort) else profile, model)
    }

    private fun withRoute(session: RpSession, route: Route) =
        session.copy(profileId = route.profile.id, model = route.model, updatedAt = System.currentTimeMillis())

    private suspend fun buildPrompt(session: RpSession, type: RpGenerationType, route: Route, quietPrompt: String = ""): com.adong.adchat.data.rp.RpPromptResult =
        withContext(Dispatchers.Default) {
            val preset = presetFor(session.config)
            val persona = resolvedPersona(session)?.toContext() ?: RpPersonaContext(name = RpPersona.DEFAULT_NAME)
            val memory = if (session.memoryEnabled) memoryStore.validState(session.id, session.messages) else null
            val history = when (type) {
                RpGenerationType.Swipe -> RpChatOps.swipeBase(session)
                else -> session.promptMessages()
            }
            val request = RpPromptRequest(
                card = session.config.card,
                messages = history,
                persona = persona,
                authorsNote = session.authorsNote,
                preset = preset,
                regexEnabled = tavernRegexEnabled,
                type = type,
                variables = variables(session),
                timedState = session.timedWorldInfo,
                worldBook = activeWorldBook(session).let { if (templatesEnabled) com.adong.adchat.data.rp.template.RpPromptTemplate.scanBook(it) else it },
                memorySummary = memory?.summary.orEmpty(),
                coveredUntilId = memory?.coveredUntilId,
                inputBudgetTokens = route.profile.contextLimits(route.model)?.inputTokens,
                modelName = route.model,
                quietPrompt = quietPrompt
            )
            val result = RpPromptEngine.build(
                request,
                RpPromptOptions(chatId = session.id, regexScripts = regexScripts(session), messageVariables = { latestFloorVariables(session) })
            )
            if (!templatesEnabled) return@withContext result
            val book = activeWorldBook(session)
            if (!com.adong.adchat.data.rp.template.RpPromptTemplate.active(book, result.messages, result.systemPrompt)) return@withContext result
            val (system, messages) = com.adong.adchat.data.rp.template.RpPromptTemplate.generate(
                result.systemPrompt, result.messages, templateContext(session, com.adong.adchat.data.rp.template.RpTplStage.Generate, type.wire, route.model)
            )
            result.copy(systemPrompt = system, messages = messages)
        }

    /** Context for Prompt Template evaluation; message floor = [messageIndex]'s floor, else the latest. */
    fun templateContext(
        session: RpSession,
        stage: com.adong.adchat.data.rp.template.RpTplStage,
        generationType: String = "",
        model: String = session.model,
        messageIndex: Int? = null
    ): com.adong.adchat.data.rp.template.RpTplContext {
        val current = sessionById(session.id) ?: session
        val macroCtx = macroContext(current)
        val target = messageIndex?.let { current.messages.getOrNull(it) } ?: current.messages.lastOrNull()
        return com.adong.adchat.data.rp.template.RpTplContext(
            stage = stage,
            variables = variables(current),
            messages = current.promptMessages(),
            worldBook = activeWorldBook(current),
            userName = macroCtx.user,
            charName = macroCtx.char,
            chatId = current.id,
            model = model,
            generationType = generationType,
            messageIndex = messageIndex,
            messageFloor = target?.let { it.id to it.currentIndex },
            isUser = target?.isUser == true,
            isSystem = target?.role == "system",
            macros = { RpMacros.expand(it, macroCtx) },
            characterJson = { JSONObject(current.config.card.toJson()) }
        )
    }

    /**
     * Built presentations of displayed messages. Items leaving and re-entering the list while scrolling
     * reuse these instead of re-running regex, macros, templates and HTML parsing on the main thread.
     */
    private val presentationCache = object : LinkedHashMap<String, com.adong.adchat.data.story.StoryProsePresentation>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, com.adong.adchat.data.story.StoryProsePresentation>?) = size > 160
    }

    fun presentation(
        session: RpSession,
        index: Int,
        content: String,
        role: String,
        depth: Int,
        regexScripts: List<TavernRegexScript>,
        macros: RpMacroContext,
        revision: Int
    ): com.adong.adchat.data.story.StoryProsePresentation {
        val key = listOf(session.id, index, role, depth, revision, templatesEnabled, System.identityHashCode(regexScripts),
            System.identityHashCode(macros), content.hashCode(), content.length).joinToString("|")
        presentationCache[key]?.let { return it }
        val built = com.adong.adchat.data.rp.RpPresentation.build(renderTemplate(session, index, content), role, depth, regexScripts, macros)
        presentationCache[key] = built
        return built
    }

    /** Render-stage template pass for one displayed message (display only). */
    fun renderTemplate(session: RpSession, index: Int, content: String): String {
        if (!templatesEnabled) return content
        val book = activeWorldBook(session)
        if (!RpTemplateQuick.needs(content, book)) return content
        return com.adong.adchat.data.rp.template.RpPromptTemplate.render(
            content, templateContext(session, com.adong.adchat.data.rp.template.RpTplStage.Render, messageIndex = index)
        )
    }

    private suspend fun quietGenerate(session: RpSession, route: Route, prompt: String, raw: Boolean): String {
        val prepared = if (raw) null else buildPrompt(session, RpGenerationType.Quiet, route, quietPrompt = RpMacros.expand(prompt, macroContext(session)))
        val wire = prepared?.let { sendable(route, it, RpGenerationType.Quiet) }
        val result = api.streamChat(
            profile = route.profile,
            model = route.model,
            systemPrompt = wire?.systemPrompt.orEmpty(),
            history = wire?.messages ?: listOf(ChatMessage(role = "user", content = prompt)),
            cacheKey = "rp-quiet-${session.id}",
            trimHistory = false,
            skillsAllowed = false,
            generationOptions = prepared?.generationOptions ?: com.adong.adchat.data.ChatGenerationOptions(),
            onDelta = {}
        )
        return result.text
    }

    private fun launchGeneration(base: RpSession, type: RpGenerationType) {
        val route = route(base) ?: return
        error = null
        streamingContent = ""
        streamingReasoning = ""
        generating = true
        generationType = type
        updateSession(base)
        val helper = helpers[base.id]
        helper?.emitEvent("generation_started", type.wire, JSONObject(), false)
        generationJob = viewModelScope.launch {
            var content = ""
            var reasoning = ""
            val startedAt = System.currentTimeMillis()
            val separator = presetFor(base.config)?.continuePostfix ?: ""
            fun pending() = RpPendingReply(type, content, reasoning, route.model, startedAt, separator)
            try {
                val prepared = buildPrompt(base, type, route)
                lastWorldEntries = prepared.activatedWorldEntries
                sessionById(base.id)?.let { updateSession(it.copy(timedWorldInfo = prepared.timedState)) }
                val injection = if (base.injectionEnabled) injectionStore.loadPreset().takeIf { it.enabledCount > 0 } else null
                val wire = sendable(route, prepared, type)
                val result = api.streamChat(
                    profile = route.profile,
                    model = route.model,
                    systemPrompt = wire.systemPrompt,
                    history = wire.messages,
                    cacheKey = "rp-${base.id}",
                    trimHistory = false,
                    skillsAllowed = false,
                    generationOptions = prepared.generationOptions,
                    injectionPreset = injection,
                    onReasoning = { delta -> reasoning += delta; streamingReasoning = reasoning },
                    onDelta = { delta ->
                        content += delta
                        streamingContent = content
                        helper?.emitEvent("stream_token_received", content)
                        if (type != RpGenerationType.Impersonate) store.checkpoint(sessionById(base.id) ?: base, pending())
                    }
                )
                val swipe = RpSwipe(
                    content = result.text.ifBlank { content },
                    reasoning = result.reasoning.ifBlank { reasoning },
                    model = route.model,
                    usage = result.usage,
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    interrupted = !result.outputComplete
                )
                commitReply(base.id, type, swipe, separator)
                maybeScheduleMemory(base.id)
            } catch (cancelled: CancellationException) {
                if (content.isNotBlank()) {
                    commitReply(base.id, type, RpSwipe(content, reasoning, route.model, startedAt = startedAt,
                        finishedAt = System.currentTimeMillis(), interrupted = true), separator)
                }
                helper?.emitEvent("generation_stopped")
            } catch (failure: Exception) {
                val message = failure.message ?: "生成失败"
                error = message
                // ST shows swipe/continue failures as a toast and keeps the reply; only a fresh turn leaves an error floor.
                if (type == RpGenerationType.Normal) sessionById(base.id)?.let { updateSession(RpChatOps.appendError(it, message)) }
            } finally {
                generating = false
                streamingContent = ""
                streamingReasoning = ""
                generationJob = null
                helper?.emitEvent("generation_ended", sessionById(base.id)?.messages?.lastIndex ?: 0)
            }
        }
    }

    /**
     * Stores a finished reply. The order matches SillyTavern: ingest regex, then MVU commands update
     * the new floor's variables BEFORE the message becomes visible, so a card's status bar renders
     * the new values on its first frame; finally message_received fans out to every frame.
     */
    private fun commitReply(sessionId: String, type: RpGenerationType, swipe: RpSwipe, separator: String) {
        val current = sessionById(sessionId) ?: return
        if (type == RpGenerationType.Impersonate) {
            loadDraftForEdit(swipe.content.trim())
            helpers[sessionId]?.emitEvent("impersonate_ready", draft)
            return
        }
        val scripts = regexScripts(current)
        val ctx = macroContext(current)
        val ingested = swipe.copy(content = RpRegex.ingest(swipe.content, "assistant", scripts, ctx))
        val committed = when (type) {
            RpGenerationType.Swipe -> RpChatOps.addSwipe(current, ingested)
            RpGenerationType.Continue -> RpChatOps.continueWith(stripPlaceholder(current), ingested.content, separator, ingested)
                ?: RpChatOps.appendReply(current, ingested, name = current.config.card.macroName)
            else -> RpChatOps.appendReply(RpChatOps.removeTrailingError(current) ?: current, ingested, name = current.config.card.macroName)
        }.copy(tainted = true)
        val last = committed.messages.lastOrNull()
        val final = if (last != null && !last.isUser) applyMvu(committed, last) else committed
        updateSession(final)
        val index = final.messages.lastIndex
        helpers[sessionId]?.let { helper ->
            last?.let { helper.touch(it.id) }
            helper.emitEvent(if (type == RpGenerationType.Swipe) "message_swiped" else "message_received", index)
            helper.emitEvent("character_message_rendered", index)
        }
    }

    private fun stripPlaceholder(session: RpSession): RpSession {
        val last = session.messages.lastOrNull() ?: return session
        if (!last.content.contains(RpMvu.PLACEHOLDER)) return session
        val cleaned = last.withContent(last.content.replace(RpMvu.PLACEHOLDER, "").trimEnd())
        return session.copy(messages = session.messages.dropLast(1) + cleaned)
    }

    /** Separator the Continue commit uses, so the streaming preview matches the stored text. */
    fun continueSeparator(session: RpSession): String = presetFor(session.config)?.continuePostfix ?: ""

    /** Native MagVarUpdate: baseline = nearest earlier floor with MVU data; writes the new floor. */
    private fun applyMvu(session: RpSession, message: RpMessage): RpSession {
        val store = variables(session)
        val index = session.indexOf(message.id)
        val earlier = session.messages.take(index)
        val baseline = store.latestFloor(earlier) { RpMvu.isMvuData(it) }?.let { (id, swipe) -> store.table(RpVarScope.Message, id, swipe) }
            ?: store.table(RpVarScope.Message, message.id, message.currentIndex).takeIf { RpMvu.isMvuData(it) }
        val content = RpMacros.expand(message.content, macroContext(session))
        val result = RpMvu.process(content, baseline)
        result.variables?.let { store.replace(RpVarScope.Message, it, message.id, message.currentIndex) }
            ?: baseline?.let { if (!store.hasFloor(message.id, message.currentIndex)) store.replace(RpVarScope.Message, it, message.id, message.currentIndex) }
        if (result.variables != null) {
            helpers[session.id]?.let { helper ->
                helper.emitEvent("mag_variable_update_started", result.variables, false)
                helper.emitEvent("mag_variable_update_ended", result.variables, baseline ?: JSONObject())
            }
        }
        if (result.displayContent == message.content || baseline == null && result.variables == null) return session
        val edited = message.withContent(result.displayContent)
        return session.copy(messages = session.messages.map { if (it.id == message.id) edited else it })
    }

    /** initvar seeding: world-book [initvar] entries + greeting blocks become floor 0's MVU data. */
    private fun seedInitialVariables(session: RpSession) {
        val first = session.messages.firstOrNull()?.takeIf { it.role == "assistant" } ?: return
        val entries = activeWorldBook(session).entries.filter { it.comment.contains("[initvar]", ignoreCase = true) }.map { it.content }
        viewModelScope.launch(Dispatchers.Default) {
            val ctx = macroContext(session)
            val initial = RpMvu.initial(entries, first.content) { RpMacros.expand(it, ctx) }
            // Prompt Template [InitialVariables]: a variable tree merged at the top of floor 0.
            if (templatesEnabled) com.adong.adchat.data.rp.template.RpPromptTemplate.initialVariables(activeWorldBook(session)) { RpMacros.expand(it, ctx) }
                ?.let { tree -> tree.keys().forEach { k -> if (!initial.has(k)) initial.put(k, tree.opt(k)) } }
            if (initial.length() == 0) return@launch
            val store = variables(session)
            store.replace(RpVarScope.Message, initial, first.id, first.currentIndex)
            withContext(Dispatchers.Main) {
                helpers[session.id]?.let { it.touch(first.id); it.emitEvent("mag_variable_initialized", initial, 0) }
            }
        }
    }

    /** Schedules a layered-summary batch only for the active, memory-enabled session; never at startup. */
    private fun maybeScheduleMemory(sessionId: String) {
        val session = sessionById(sessionId) ?: return
        if (!session.memoryEnabled || sessionId != activeSessionId) return
        if (memoryJobs[sessionId]?.isActive == true) return
        val route = route(session) ?: return
        val plan = RpMemory.plan(session.messages, memoryStore.validState(sessionId, session.messages)) ?: return
        memoryBusy = true
        memoryStatus = "正在整理剧情摘要…"
        memoryJobs[sessionId] = viewModelScope.launch {
            try {
                val summary = RpMemory.summarize(api, route.profile, route.model, memoryStore, sessionId, plan) {
                    sessionById(sessionId)?.messages
                }
                memoryStatus = "剧情摘要已更新 · ${summary.length} 字"
            } catch (cancelled: CancellationException) {
                memoryStatus = ""
            } catch (failure: Exception) {
                memoryStatus = "摘要整理失败：${failure.message ?: "未知错误"}"
            } finally {
                memoryBusy = false
                memoryJobs.remove(sessionId)
            }
        }
    }

    private fun updateSession(updated: RpSession) {
        val index = sessions.indexOfFirst { it.id == updated.id }
        if (index >= 0) sessions[index] = updated else sessions.add(updated)
        store.save(updated)
    }

    // Keep this block below every property. withContext(IO) returns without suspending when the IO
    // work finishes first, so the coroutine can run on inside the constructor; a state declared
    // further down (fallbackUserName was) is still null at that point and the app dies on launch.
    init {
        viewModelScope.launch {
            val fallback = withContext(Dispatchers.IO) { configStore.load().userName }
            fallbackUserName = fallback
            val loaded = store.load().map { session ->
                withContext(Dispatchers.IO) { personaStore.migrateLegacy(session, fallback) }.also { migrated ->
                    if (migrated !== session) store.save(migrated)
                }
            }
            sessions.addAll(0, loaded.filter { stored -> sessions.none { it.id == stored.id } })
            if (activeSessionId == null) activeSessionId = sessions.firstOrNull()?.id
            refreshPresets()
            personas = withContext(Dispatchers.IO) { personaStore.list() }
            refreshLorebooks()
        }
    }

    override fun onCleared() {
        runCatching { store.flush() }
        super.onCleared()
    }

    private companion object {
        const val PREFS = "aster_rp_settings"
        const val KEY_REGEX = "regex_enabled"
        const val KEY_TEMPLATES = "templates_enabled"
    }
}

/** Cheap gate so plain messages skip the render pass. */
private object RpTemplateQuick {
    fun needs(content: String, book: com.adong.adchat.data.story.StoryWorldBook): Boolean =
        content.contains("<%") || content.contains("&lt;%") || book.entries.any { e ->
            e.enabled && com.adong.adchat.data.rp.template.RpPromptTemplate.classify(e)?.kind.let {
                it == com.adong.adchat.data.rp.template.RpPromptTemplate.Kind.RenderBefore ||
                    it == com.adong.adchat.data.rp.template.RpPromptTemplate.Kind.RenderAfter
            }
        }
}
