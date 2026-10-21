package com.adong.adchat.ui.media

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adong.adchat.media.MediaDownloadRecord
import com.adong.adchat.media.MediaGallerySaveResult
import com.adong.adchat.media.MediaGallerySaver
import com.adong.adchat.media.asGifRecord
import com.adong.adchat.media.gif.GifPreset
import com.adong.adchat.media.gif.VideoToGifConverter
import com.adong.adchat.media.gif.VideoToGifRequest
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 视频转 GIF 的会话状态：选择预设 → 转换中 → 完成（可保存/分享）。 */
data class VideoToGifState(
    val source: MediaDownloadRecord? = null,
    val phase: Phase = Phase.Idle,
    val preset: GifPreset = GifPreset.Standard,
    val progress: Float = 0f,
    val result: MediaDownloadRecord? = null,
    /** 转换元信息（尺寸/帧数/时长），供完成页展示。 */
    val convertInfo: com.adong.adchat.media.gif.VideoToGifResult? = null,
    val error: String? = null,
    val savingToGallery: Boolean = false
) {
    enum class Phase { Idle, Converting, Done, Failed }
}

class VideoToGifViewModel(app: Application) : AndroidViewModel(app) {
    private val saver = MediaGallerySaver(app)
    private val _state = MutableStateFlow(VideoToGifState())
    val state: StateFlow<VideoToGifState> = _state
    private var job: Job? = null
    private var operationSequence = 0L

    fun start(source: MediaDownloadRecord) {
        operationSequence++
        job?.cancel()
        job = null
        _state.value = VideoToGifState(source = source)
    }

    fun update(preset: GifPreset) {
        val s = _state.value
        if (s.phase != VideoToGifState.Phase.Idle) return
        _state.value = s.copy(preset = preset)
    }

    fun convert() {
        val s = _state.value
        val source = s.source ?: return
        if (s.phase == VideoToGifState.Phase.Converting) return
        val operation = ++operationSequence
        job = viewModelScope.launch {
            _state.value = s.copy(phase = VideoToGifState.Phase.Converting, progress = 0f, error = null)
            try {
                val sourceFile = File(source.filePath)
                require(sourceFile.isFile && sourceFile.length() > 0L) { "原视频文件不存在或为空" }
                val dir = sourceFile.parentFile ?: getApplication<Application>().cacheDir
                val baseName = sourceFile.nameWithoutExtension + "_gif"
                val result = VideoToGifConverter.convert(
                    request = VideoToGifRequest(
                        videoPath = source.filePath,
                        outputDirectory = dir,
                        baseName = baseName,
                        preset = s.preset
                    ),
                    onProgress = { p ->
                        if (operation == operationSequence) _state.value = _state.value.copy(progress = p)
                    }
                )
                if (operation != operationSequence) return@launch
                val gifRecord = source.asGifRecord(result.file, result.file.length())
                _state.value = _state.value.copy(
                    phase = VideoToGifState.Phase.Done,
                    result = gifRecord,
                    convertInfo = result
                )
            } catch (c: CancellationException) {
                if (operation == operationSequence) {
                    _state.value = _state.value.copy(phase = VideoToGifState.Phase.Idle, progress = 0f)
                }
                throw c
            } catch (e: Exception) {
                if (operation == operationSequence) {
                    _state.value = _state.value.copy(
                        phase = VideoToGifState.Phase.Failed,
                        error = e.message ?: "转换失败"
                    )
                }
            }
        }
    }

    fun cancel() {
        operationSequence++
        job?.cancel()
        job = null
        val s = _state.value
        if (s.phase == VideoToGifState.Phase.Converting) {
            _state.value = s.copy(phase = VideoToGifState.Phase.Idle, progress = 0f, error = null)
        }
    }

    /** 保存到相册；权限不足由调用界面发起系统权限请求后再试。 */
    suspend fun saveToGallery(record: MediaDownloadRecord? = _state.value.result): MediaGallerySaveResult {
        record ?: return MediaGallerySaveResult.Failed("没有可保存的 GIF")
        val current = _state.value
        if (current.savingToGallery) return MediaGallerySaveResult.Failed("正在保存")
        val ownsCurrentSession = current.result?.id == record.id
        if (ownsCurrentSession) _state.value = current.copy(savingToGallery = true, error = null)
        return try {
            when (val r = saver.save(record)) {
                is MediaGallerySaveResult.Saved -> {
                    if (_state.value.result?.id == record.id) {
                        _state.value = _state.value.copy(result = record.copy(galleryUri = r.uri))
                    }
                    r
                }
                is MediaGallerySaveResult.PermissionRequired -> {
                    r
                }
                is MediaGallerySaveResult.Failed -> {
                    if (_state.value.result?.id == record.id) _state.value = _state.value.copy(error = r.reason)
                    r
                }
            }
        } finally {
            if (_state.value.result?.id == record.id) _state.value = _state.value.copy(savingToGallery = false)
        }
    }

    fun dismiss() {
        operationSequence++
        job?.cancel()
        job = null
        _state.value = VideoToGifState()
    }
}
