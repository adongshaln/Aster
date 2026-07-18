package com.adong.adchat.media

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class MediaDownloadStore(context: Context) {
    private val historyFile = File(context.filesDir, "media_download_history.json")
    private val lock = Any()

    fun load(): List<MediaDownloadRecord> = synchronized(lock) {
        if (!historyFile.exists()) return@synchronized emptyList()
        runCatching {
            val array = JSONArray(historyFile.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    decode(item)?.let(::add)
                }
            }.sortedByDescending(MediaDownloadRecord::createdAt)
        }.getOrDefault(emptyList())
    }

    fun add(record: MediaDownloadRecord): List<MediaDownloadRecord> = synchronized(lock) {
        val next = buildList {
            add(record)
            addAll(load().filterNot { it.id == record.id || it.filePath == record.filePath })
        }.take(MAX_HISTORY)
        saveLocked(next)
        next
    }

    fun remove(id: String, deleteFile: Boolean): List<MediaDownloadRecord> = synchronized(lock) {
        val current = load()
        current.firstOrNull { it.id == id }?.let { record ->
            if (deleteFile) runCatching { File(record.filePath).delete() }
        }
        val next = current.filterNot { it.id == id }
        saveLocked(next)
        next
    }

    private fun saveLocked(records: List<MediaDownloadRecord>) {
        val array = JSONArray().apply { records.forEach { put(encode(it)) } }
        val temporary = File(historyFile.parentFile, "${historyFile.name}.tmp")
        temporary.writeText(array.toString(), Charsets.UTF_8)
        if (!temporary.renameTo(historyFile)) {
            historyFile.writeText(array.toString(), Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun encode(record: MediaDownloadRecord): JSONObject = JSONObject()
        .put("id", record.id)
        .put("videoId", record.videoId)
        .put("title", record.title)
        .put("author", record.author)
        .put("thumbnailUrl", record.thumbnailUrl)
        .put("thumbnailFallbackUrl", record.thumbnailFallbackUrl)
        .put("sourceUrl", record.sourceUrl)
        .put("filePath", record.filePath)
        .put("galleryUri", record.galleryUri)
        .put("fileSize", record.fileSize)
        .put("watermarkFree", record.watermarkFree)
        .put("platform", record.platform.key)
        .put("qualityLabel", record.qualityLabel)
        .put("mimeType", record.mimeType)
        .put("fileExtension", record.fileExtension)
        .put("createdAt", record.createdAt)

    private fun decode(item: JSONObject): MediaDownloadRecord? {
        val filePath = item.optString("filePath")
        if (filePath.isBlank()) return null
        return MediaDownloadRecord(
            id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            videoId = item.optString("videoId"),
            title = item.optString("title").ifBlank { "抖音视频" },
            author = item.optString("author"),
            thumbnailUrl = item.optString("thumbnailUrl"),
            thumbnailFallbackUrl = item.optString("thumbnailFallbackUrl"),
            sourceUrl = item.optString("sourceUrl"),
            filePath = filePath,
            galleryUri = item.optString("galleryUri"),
            fileSize = item.optLong("fileSize", File(filePath).length()),
            watermarkFree = item.optBoolean("watermarkFree", false),
            platform = MediaPlatform.fromKey(item.optString("platform", "douyin")),
            qualityLabel = item.optString("qualityLabel"),
            mimeType = item.optString("mimeType", "video/mp4"),
            fileExtension = item.optString("fileExtension", "mp4"),
            createdAt = item.optLong("createdAt", System.currentTimeMillis())
        )
    }

    private companion object {
        const val MAX_HISTORY = 100
    }
}

