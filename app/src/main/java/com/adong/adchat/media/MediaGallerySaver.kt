package com.adong.adchat.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

sealed interface MediaGallerySaveResult {
    data class Saved(val uri: String) : MediaGallerySaveResult
    data object PermissionRequired : MediaGallerySaveResult
    data class Failed(val reason: String) : MediaGallerySaveResult
}

class MediaGallerySaver(context: Context) {
    private val appContext = context.applicationContext

    suspend fun save(record: MediaDownloadRecord): MediaGallerySaveResult = withContext(Dispatchers.IO) {
        val source = File(record.filePath)
        if (!source.isFile || source.length() <= 0L) {
            return@withContext MediaGallerySaveResult.Failed("下载文件不存在")
        }
        if (record.galleryUri.isNotBlank() && galleryEntryExists(record.galleryUri)) {
            return@withContext MediaGallerySaveResult.Saved(record.galleryUri)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(source, record)
            else saveLegacy(source, record)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            MediaGallerySaveResult.Failed(error.message ?: "无法写入系统相册")
        }
    }

    private suspend fun saveScoped(source: File, record: MediaDownloadRecord): MediaGallerySaveResult {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName(record))
            put(MediaStore.Video.Media.MIME_TYPE, record.mimeType.ifBlank { "video/mp4" })
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ADChat")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1_000L)
            put(MediaStore.Video.Media.DATE_TAKEN, record.createdAt)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: return MediaGallerySaveResult.Failed("系统相册拒绝创建文件")
        return try {
            resolver.openOutputStream(uri, "w")?.use { output -> copyFile(source, output) }
                ?: error("系统相册无法打开文件")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            MediaGallerySaveResult.Saved(uri.toString())
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun saveLegacy(source: File, record: MediaDownloadRecord): MediaGallerySaveResult {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return MediaGallerySaveResult.PermissionRequired
        }
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "ADChat"
        ).apply { if (!exists() && !mkdirs()) error("无法创建相册目录") }
        val target = nextAvailableFile(directory, displayName(record))
        val temporary = File(directory, ".${target.name}.adchat")
        try {
            FileOutputStream(temporary).use { output -> copyFile(source, output) }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            val scannedUri = scanFile(target, record.mimeType)
            return MediaGallerySaveResult.Saved(scannedUri.ifBlank { Uri.fromFile(target).toString() })
        } catch (error: Exception) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    private suspend fun copyFile(source: File, output: java.io.OutputStream) {
        FileInputStream(source).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.flush()
        }
    }

    private suspend fun scanFile(file: File, mimeType: String): String = suspendCancellableCoroutine { continuation ->
        MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), arrayOf(mimeType)) { _, uri ->
            if (continuation.isActive) continuation.resume(uri?.toString().orEmpty())
        }
    }

    private fun galleryEntryExists(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        if (uri.scheme == "file") File(uri.path.orEmpty()).exists()
        else appContext.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)

    private fun displayName(record: MediaDownloadRecord): String {
        val extension = record.fileExtension.lowercase().filter(Char::isLetterOrDigit).take(5).ifBlank { "mp4" }
        val base = record.title
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .take(72)
            .ifBlank { "${record.platform.displayName}视频-${record.videoId.takeLast(12)}" }
        return "$base.$extension"
    }

    private fun nextAvailableFile(directory: File, displayName: String): File {
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
        val base = displayName.substringBeforeLast('.', missingDelimiterValue = displayName)
        var candidate = File(directory, displayName)
        var suffix = 2
        while (candidate.exists()) {
            val name = if (extension.isBlank()) "$base ($suffix)" else "$base ($suffix).$extension"
            candidate = File(directory, name)
            suffix += 1
        }
        return candidate
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 512 * 1024
    }
}
