package com.adong.adchat.data.rp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One row of the session index (listing without decoding every chat). */
data class RpSessionSummary(val id: String, val title: String, val updatedAt: Long, val messageCount: Int)

/**
 * File-per-session persistence: `<root>/sessions/<id>.json` plus `<root>/sessions/index.json`, written
 * atomically (temp + rename) on a single background thread. [save] is debounced per session; [flush]
 * blocks until everything queued is on disk. Streaming generations call [checkpoint] so a killed
 * process keeps the partial reply as an interrupted swipe. The legacy SharedPreferences blob
 * (`aster_rp_sessions`) is migrated once on first [loadAll] and then removed.
 */
class RpStore internal constructor(
    private val root: File,
    private val legacy: android.content.SharedPreferences?,
    private val debounceMs: Long = 400,
    private val checkpointIntervalMs: Long = 1_500
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "rp"),
        context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
    )

    private val dir = File(root, "sessions")
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "rp-store").apply { isDaemon = true }
    }
    private val lock = Any()
    private val pending = LinkedHashMap<String, RpSession>()
    private val deleted = LinkedHashSet<String>()
    private val index = LinkedHashMap<String, RpSessionSummary>()
    private var scheduled: ScheduledFuture<*>? = null
    private val lastCheckpoint = HashMap<String, Long>()

    /** Loads every session (newest first), migrating legacy storage once. Blocking: call off the main thread. */
    fun loadAll(): List<RpSession> {
        migrateLegacy()
        val sessions = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") && file.name != INDEX }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { RpSession.fromJson(JSONObject(file.readText())) }.getOrNull()?.takeIf { it.id.isNotBlank() }
            }
            .sortedByDescending { it.updatedAt }
        synchronized(lock) {
            index.clear()
            sessions.forEach { index[it.id] = it.summary() }
        }
        return sessions
    }

    suspend fun load(): List<RpSession> = withContext(Dispatchers.IO) { loadAll() }

    /** Index rows as last written; cheap listing without decoding chats. */
    fun listIndex(): List<RpSessionSummary> = runCatching {
        val array = JSONArray(File(dir, INDEX).readText())
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let {
                RpSessionSummary(it.optString("id"), it.optString("title"), it.optLong("updated_at"), it.optInt("count"))
            }
        }
    }.getOrDefault(emptyList())

    fun loadSession(id: String): RpSession? = runCatching {
        RpSession.fromJson(JSONObject(sessionFile(id).readText()))
    }.getOrNull()

    /** Queues a debounced write of [session]. Safe to call on every state change from the main thread. */
    fun save(session: RpSession) {
        synchronized(lock) {
            deleted.remove(session.id)
            pending[session.id] = session
            index[session.id] = session.summary()
            schedule(debounceMs)
        }
    }

    /** Legacy bulk API: saves each session and deletes files of sessions no longer present. */
    fun save(sessions: List<RpSession>) {
        synchronized(lock) {
            val keep = sessions.map { it.id }.toSet()
            index.keys.filterNot { it in keep }.forEach { delete(it) }
        }
        sessions.forEach(::save)
    }

    fun delete(id: String) {
        synchronized(lock) {
            pending.remove(id)
            index.remove(id)
            lastCheckpoint.remove(id)
            deleted += id
            schedule(0)
        }
    }

    /**
     * Persists the in-flight reply on top of [base] as an interrupted swipe, throttled to one write per
     * interval. The in-memory session is not changed; the final [save] overwrites the checkpoint.
     */
    fun checkpoint(base: RpSession, reply: RpPendingReply, force: Boolean = false) {
        if (reply.content.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val last = lastCheckpoint[base.id] ?: 0L
            if (!force && now - last < checkpointIntervalMs) return
            lastCheckpoint[base.id] = now
        }
        save(RpChatOps.checkpoint(base, reply, now))
    }

    /** Blocks until every queued write and delete has hit the disk. */
    fun flush() {
        synchronized(lock) { scheduled?.cancel(false); scheduled = null }
        executor.submit { drain() }.get()
    }

    suspend fun flushAsync() = withContext(Dispatchers.IO) { flush() }

    private fun schedule(delayMs: Long) {
        val current = scheduled
        if (current != null && !current.isDone && delayMs > 0) return
        current?.cancel(false)
        scheduled = executor.schedule({ drain() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun drain() {
        val writes: List<RpSession>
        val removals: List<String>
        val rows: List<RpSessionSummary>
        synchronized(lock) {
            writes = pending.values.toList()
            removals = deleted.toList()
            pending.clear()
            deleted.clear()
            rows = index.values.sortedByDescending { it.updatedAt }
            scheduled = null
        }
        if (writes.isEmpty() && removals.isEmpty()) return
        dir.mkdirs()
        writes.forEach { session -> runCatching { atomicWrite(sessionFile(session.id), session.toJson().toString()) } }
        removals.forEach { id -> sessionFile(id).delete() }
        runCatching {
            atomicWrite(File(dir, INDEX), JSONArray(rows.map {
                JSONObject().put("id", it.id).put("title", it.title).put("updated_at", it.updatedAt).put("count", it.messageCount)
            }).toString())
        }
    }

    private fun migrateLegacy() {
        val prefs = legacy ?: return
        val raw = prefs.getString(LEGACY_KEY, null) ?: return
        val array = runCatching { JSONArray(raw) }.getOrNull()
        dir.mkdirs()
        var ok = true
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val session = runCatching { RpSession.fromJson(item) }.getOrNull()?.takeIf { it.id.isNotBlank() } ?: continue
                val file = sessionFile(session.id)
                if (file.exists()) continue
                ok = ok && runCatching { atomicWrite(file, session.toJson().toString()) }.isSuccess
            }
        }
        if (ok) prefs.edit().remove(LEGACY_KEY).commit()
    }

    private fun sessionFile(id: String) = File(dir, safeName(id) + ".json")

    private fun RpSession.summary() = RpSessionSummary(id, title, updatedAt, messages.size)

    companion object {
        private const val LEGACY_PREFS = "aster_rp_sessions"
        private const val LEGACY_KEY = "sessions"
        private const val INDEX = "index.json"

        internal fun safeName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "_" }

        /** Writes via a sibling temp file and rename so readers never see a torn file. */
        internal fun atomicWrite(file: File, text: String) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!temp.renameTo(file)) {
                file.delete()
                check(temp.renameTo(file)) { "无法写入 ${file.name}" }
            }
        }
    }
}
