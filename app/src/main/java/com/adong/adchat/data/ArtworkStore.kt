package com.adong.adchat.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ArtworkStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("adchat_artworks", Context.MODE_PRIVATE)
    private val imageDir = File(context.filesDir, "artworks").apply { mkdirs() }
    private val downloadClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun load(): List<GeneratedImage> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val source = item.optString("source")
                if (source.startsWith("file://") && !File(source.removePrefix("file://")).exists()) continue
                add(GeneratedImage(
                    id = item.optLong("id", System.nanoTime()),
                    prompt = item.optString("prompt"),
                    source = source,
                    size = item.optString("size", "1024x1024"),
                    style = item.optString("style", "原始"),
                    profileName = item.optString("profileName"),
                    model = item.optString("model"),
                    seriesId = item.optString("seriesId"),
                    seriesIndex = item.optInt("seriesIndex"),
                    seriesTotal = item.optInt("seriesTotal"),
                    seriesTitle = item.optString("seriesTitle")
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun save(images: List<GeneratedImage>) {
        val array = JSONArray()
        images.take(MAX_ITEMS).forEach { image ->
            array.put(JSONObject()
                .put("id", image.id)
                .put("prompt", image.prompt)
                .put("source", image.source)
                .put("size", image.size)
                .put("style", image.style)
                .put("profileName", image.profileName)
                .put("model", image.model)
                .put("seriesId", image.seriesId)
                .put("seriesIndex", image.seriesIndex)
                .put("seriesTotal", image.seriesTotal)
                .put("seriesTitle", image.seriesTitle))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        val retained = images.take(MAX_ITEMS).mapNotNull { it.source.takeIf { source -> source.startsWith("file://") }?.removePrefix("file://") }.toSet()
        imageDir.listFiles()?.filter { it.absolutePath !in retained }?.forEach { runCatching { it.delete() } }
    }

    fun cacheSource(source: String): String {
        val bytes = when {
            source.startsWith("data:") -> Base64.decode(source.substringAfter("base64,"), Base64.DEFAULT)
            source.startsWith("http://") || source.startsWith("https://") -> downloadBytes(source)
            else -> return source
        }
        require(bytes.size <= MAX_IMAGE_BYTES) { "生成图片超过 30 MB，无法缓存" }
        val extension = when {
            source.startsWith("data:image/jpeg") -> "jpg"
            source.startsWith("data:image/webp") -> "webp"
            else -> "png"
        }
        val file = File(imageDir, "artwork-${System.currentTimeMillis()}-${System.nanoTime()}.$extension")
        file.writeBytes(bytes)
        return "file://${file.absolutePath}"
    }

    fun delete(image: GeneratedImage) {
        if (image.source.startsWith("file://")) runCatching { File(image.source.removePrefix("file://")).delete() }
    }

    fun saveToGallery(image: GeneratedImage): String {
        val bytes = readBytes(image.source)
        val fileName = "ADChat-${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ADChat")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建相册文件")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入相册")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            val dir = File(context.getExternalFilesDir(null), "ADChat").apply { mkdirs() }
            File(dir, fileName).writeBytes(bytes)
        }
        return fileName
    }

    private fun readBytes(source: String): ByteArray = when {
        source.startsWith("file://") -> File(source.removePrefix("file://")).readBytes()
        source.startsWith("data:") -> Base64.decode(source.substringAfter("base64,"), Base64.DEFAULT)
        source.startsWith("http://") || source.startsWith("https://") -> downloadBytes(source)
        else -> error("无法读取图片数据")
    }

    private fun downloadBytes(source: String): ByteArray {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val request = Request.Builder().url(source).get().build()
                return downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("图片下载失败：HTTP ${response.code}")
                    response.body?.bytes() ?: throw IOException("图片下载返回空内容")
                }
            } catch (error: Throwable) {
                lastError = error
                if (attempt == 0) Thread.sleep(250)
            }
        }
        throw lastError ?: IOException("无法下载图片")
    }

    private companion object {
        const val KEY = "artworks_v1"
        const val MAX_ITEMS = 80
        const val MAX_IMAGE_BYTES = 30 * 1024 * 1024
    }
}
