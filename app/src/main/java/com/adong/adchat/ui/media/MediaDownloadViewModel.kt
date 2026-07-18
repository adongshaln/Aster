package com.adong.adchat.ui.media

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adong.adchat.media.DouyinResolveRequest
import com.adong.adchat.media.DouyinUrlParser
import com.adong.adchat.media.ExpiredMediaUrlException
import com.adong.adchat.media.MediaDownloadProgress
import com.adong.adchat.media.MediaDownloadRecord
import com.adong.adchat.media.MediaDownloadStore
import com.adong.adchat.media.MediaFileDownloader
import com.adong.adchat.media.MediaGallerySaveResult
import com.adong.adchat.media.MediaGallerySaver
import com.adong.adchat.media.MediaInputParser
import com.adong.adchat.media.MediaPlatform
import com.adong.adchat.media.RemoteMediaResolver
import com.adong.adchat.media.ResolvedMedia
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MediaWorkspacePhase {
    Idle,
    Resolving,
    Verification,
    Ready,
    Downloading,
    Error
}

data class MediaDownloadUiState(
    val input: String = "",
    val detectedPlatform: MediaPlatform? = null,
    val phase: MediaWorkspacePhase = MediaWorkspacePhase.Idle,
    val resolveRequest: DouyinResolveRequest? = null,
    val resolverMessage: String = "",
    val resolvedMedia: ResolvedMedia? = null,
    val downloadProgress: MediaDownloadProgress = MediaDownloadProgress(),
    val downloads: List<MediaDownloadRecord> = emptyList(),
    val errorMessage: String = "",
    val savingToGallery: Boolean = false,
    val galleryPermissionRecordId: String? = null,
    val notice: String? = null
)

class MediaDownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val store = MediaDownloadStore(application)
    private val downloader = MediaFileDownloader(application)
    private val gallerySaver = MediaGallerySaver(application)
    private val remoteResolver = RemoteMediaResolver()
    private var requestSequence = 0L
    private var resolveJob: Job? = null
    private var downloadJob: Job? = null
    private var pendingAutoDownload = false
    private var automaticRefreshAttempts = 0

    var state = mutableStateOf(MediaDownloadUiState())
        private set

    init {
        viewModelScope.launch {
            val records = withContext(Dispatchers.IO) {
                store.load().filter { File(it.filePath).exists() }
            }
            update { copy(downloads = records) }
        }
    }

    fun updateInput(value: String) {
        val detected = MediaInputParser.parse(value)?.platform
        update {
            copy(
                input = value,
                detectedPlatform = detected,
                errorMessage = if (phase == MediaWorkspacePhase.Error) "" else errorMessage,
                phase = if (phase == MediaWorkspacePhase.Error) MediaWorkspacePhase.Idle else phase
            )
        }
    }

    fun acceptSharedText(value: String) {
        if (value.isBlank()) return
        updateInput(value.trim())
        resolve()
    }

    fun resolve() {
        if (state.value.phase == MediaWorkspacePhase.Downloading) return
        if (!pendingAutoDownload) automaticRefreshAttempts = 0
        val input = MediaInputParser.parse(state.value.input)
        if (input == null) {
            update {
                copy(
                    phase = MediaWorkspacePhase.Error,
                    errorMessage = "无法识别链接，请使用抖音、X、B站或视频直链",
                    detectedPlatform = null,
                    resolveRequest = null,
                    resolvedMedia = null
                )
            }
            return
        }

        resolveJob?.cancel()
        requestSequence += 1L
        if (input.platform == MediaPlatform.Douyin) {
            val link = DouyinUrlParser.parse(input.sourceUrl)
            if (link == null) {
                update { copy(phase = MediaWorkspacePhase.Error, errorMessage = "抖音链接已失效或格式不正确") }
                return
            }
            update {
                copy(
                    phase = MediaWorkspacePhase.Resolving,
                    detectedPlatform = input.platform,
                    resolveRequest = DouyinResolveRequest(
                        token = requestSequence,
                        link = link,
                        message = if (pendingAutoDownload) "地址已过期，正在刷新" else "正在安全解析"
                    ),
                    resolverMessage = "正在读取播放器",
                    resolvedMedia = null,
                    downloadProgress = MediaDownloadProgress(),
                    errorMessage = ""
                )
            }
            return
        }

        val activeToken = requestSequence
        update {
            copy(
                phase = MediaWorkspacePhase.Resolving,
                detectedPlatform = input.platform,
                resolveRequest = null,
                resolverMessage = if (pendingAutoDownload) "地址已过期，正在刷新" else "正在解析 ${input.platform.displayName}",
                resolvedMedia = null,
                downloadProgress = MediaDownloadProgress(),
                errorMessage = ""
            )
        }
        resolveJob = viewModelScope.launch {
            try {
                val maxAttempts = if (input.platform == MediaPlatform.Bilibili) BILIBILI_RESOLVE_ATTEMPTS else 1
                var lastError: Exception? = null
                var media: ResolvedMedia? = null
                repeat(maxAttempts) { attempt ->
                    if (media != null) return@repeat
                    try {
                        media = remoteResolver.resolve(input) { message ->
                            viewModelScope.launch(Dispatchers.Main.immediate) {
                                if (requestSequence == activeToken) onResolverProgress(message)
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        lastError = error
                        if (attempt < maxAttempts - 1) {
                            onResolverProgress("B 站接口波动，正在自动重试 ${attempt + 2}/$maxAttempts")
                            delay(BILIBILI_RETRY_DELAY_MS * (attempt + 1L))
                        }
                    }
                }
                val resolved = media ?: throw lastError ?: IllegalStateException("解析失败")
                if (requestSequence == activeToken) onResolved(resolved)
            } catch (_: CancellationException) {
                if (requestSequence == activeToken) update { copy(phase = MediaWorkspacePhase.Idle, resolverMessage = "") }
            } catch (error: Exception) {
                if (requestSequence == activeToken) onResolveFailed(error.message ?: "解析失败，请稍后重试")
            }
        }
    }

    fun onResolverProgress(message: String) {
        update { copy(resolverMessage = message) }
    }

    fun onVerificationRequired(message: String) {
        update {
            if (phase != MediaWorkspacePhase.Resolving && phase != MediaWorkspacePhase.Verification) this
            else copy(phase = MediaWorkspacePhase.Verification, resolverMessage = message)
        }
    }

    fun onResolved(media: ResolvedMedia) {
        val shouldAutoDownload = pendingAutoDownload
        pendingAutoDownload = false
        update {
            copy(
                phase = MediaWorkspacePhase.Ready,
                detectedPlatform = media.platform,
                resolveRequest = null,
                resolverMessage = "解析完成",
                resolvedMedia = media,
                errorMessage = ""
            )
        }
        if (shouldAutoDownload) startDownload()
    }

    fun onResolveFailed(message: String) {
        pendingAutoDownload = false
        update {
            copy(
                phase = MediaWorkspacePhase.Error,
                resolveRequest = null,
                resolvedMedia = null,
                errorMessage = message
            )
        }
    }

    fun cancelResolve() {
        requestSequence += 1L
        resolveJob?.cancel(CancellationException("User cancelled media resolution"))
        pendingAutoDownload = false
        update {
            copy(
                phase = MediaWorkspacePhase.Idle,
                resolveRequest = null,
                resolverMessage = "",
                errorMessage = ""
            )
        }
    }

    fun selectVariant(id: String) {
        if (state.value.phase == MediaWorkspacePhase.Downloading) return
        update { copy(resolvedMedia = resolvedMedia?.selectVariant(id)) }
    }

    fun startDownload() {
        val media = state.value.resolvedMedia ?: return
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            update {
                copy(
                    phase = MediaWorkspacePhase.Downloading,
                    downloadProgress = MediaDownloadProgress(),
                    savingToGallery = false,
                    errorMessage = ""
                )
            }
            try {
                val record = downloader.download(media) { progress ->
                    withContext(Dispatchers.Main.immediate) { update { copy(downloadProgress = progress) } }
                }
                update {
                    copy(
                        savingToGallery = true,
                        downloadProgress = MediaDownloadProgress(record.fileSize, record.fileSize, 0L)
                    )
                }
                val galleryResult = gallerySaver.save(record)
                val storedRecord = when (galleryResult) {
                    is MediaGallerySaveResult.Saved -> record.copy(galleryUri = galleryResult.uri)
                    else -> record
                }
                val records = withContext(Dispatchers.IO) { store.add(storedRecord) }
                automaticRefreshAttempts = 0
                update {
                    copy(
                        phase = MediaWorkspacePhase.Ready,
                        downloadProgress = MediaDownloadProgress(record.fileSize, record.fileSize, 0L),
                        savingToGallery = false,
                        downloads = records,
                        galleryPermissionRecordId = if (galleryResult is MediaGallerySaveResult.PermissionRequired) record.id else null,
                        notice = when (galleryResult) {
                            is MediaGallerySaveResult.Saved -> if (record.watermarkFree) "无水印视频已保存到相册" else "视频已保存到相册"
                            is MediaGallerySaveResult.PermissionRequired -> "视频已下载，请允许写入相册"
                            is MediaGallerySaveResult.Failed -> "视频已下载，相册保存失败"
                        }
                    )
                }
            } catch (_: CancellationException) {
                update {
                    copy(
                        phase = if (resolvedMedia != null) MediaWorkspacePhase.Ready else MediaWorkspacePhase.Idle,
                        downloadProgress = MediaDownloadProgress(),
                        savingToGallery = false,
                        notice = "下载已取消"
                    )
                }
            } catch (_: ExpiredMediaUrlException) {
                if (automaticRefreshAttempts < MAX_AUTOMATIC_REFRESHES) {
                    automaticRefreshAttempts += 1
                    pendingAutoDownload = true
                    update { copy(phase = MediaWorkspacePhase.Ready, savingToGallery = false) }
                    resolve()
                } else {
                    pendingAutoDownload = false
                    update { copy(phase = MediaWorkspacePhase.Error, savingToGallery = false, errorMessage = "视频地址反复失效，请重新解析") }
                }
            } catch (error: Exception) {
                update { copy(phase = MediaWorkspacePhase.Error, savingToGallery = false, errorMessage = error.message ?: "下载失败，请检查网络") }
            }
        }
    }

    fun onGalleryPermissionResult(granted: Boolean) {
        val recordId = state.value.galleryPermissionRecordId ?: return
        val record = state.value.downloads.firstOrNull { it.id == recordId }
        update { copy(galleryPermissionRecordId = null) }
        if (!granted || record == null) {
            update { copy(notice = if (granted) "找不到待保存的视频" else "视频已下载，未获得相册权限") }
            return
        }
        viewModelScope.launch {
            val result = gallerySaver.save(record)
            if (result is MediaGallerySaveResult.Saved) {
                val updatedRecord = record.copy(galleryUri = result.uri)
                val records = withContext(Dispatchers.IO) { store.add(updatedRecord) }
                update { copy(downloads = records, notice = "视频已保存到相册") }
            } else {
                update { copy(notice = "视频已下载，相册保存失败") }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel(CancellationException("User cancelled media download"))
        downloader.cancel()
    }

    fun removeDownload(id: String, deleteFile: Boolean = true) {
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { store.remove(id, deleteFile) }
            update { copy(downloads = next, notice = if (deleteFile) "文件已删除" else "记录已移除") }
        }
    }

    fun openDownload(record: MediaDownloadRecord) {
        val context = getApplication<Application>()
        val file = File(record.filePath)
        if (!file.exists()) {
            update { copy(notice = "本地文件不存在") }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, record.mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            update { copy(notice = "没有可播放该格式的应用") }
        }
    }

    fun shareDownload(record: MediaDownloadRecord) {
        val context = getApplication<Application>()
        val file = File(record.filePath)
        if (!file.exists()) {
            update { copy(notice = "本地文件不存在") }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = record.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, record.title)
            clipData = ClipData.newUri(context.contentResolver, record.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享视频").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = send.clipData
        }
        context.startActivity(chooser)
    }

    fun consumeNotice() {
        update { copy(notice = null) }
    }

    private inline fun update(block: MediaDownloadUiState.() -> MediaDownloadUiState) {
        state.value = state.value.block()
    }

    override fun onCleared() {
        resolveJob?.cancel()
        downloader.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_AUTOMATIC_REFRESHES = 1
        const val BILIBILI_RESOLVE_ATTEMPTS = 3
        const val BILIBILI_RETRY_DELAY_MS = 650L
    }
}
