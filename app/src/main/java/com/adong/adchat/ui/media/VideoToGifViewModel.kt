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
    val fullVideo: Boolean = false,
    val progress: Float = 0f,
    val result: MediaDownloadRecord? = null,
    val error: String? = null
) {
    enum class Phase { Idle, Converting, Done, Failed }
}

class VideoToGifViewModel(app: Application) : AndroidViewModel(app) {
    private val saver = MediaGallerySaver(app)
    private val _state = MutableStateFlow(VideoToGifState())
    val state: StateFlow<VideoToGifState> = _state
    private var job: Job? = null

    fun start(source: MediaDownloadRecord) {
        _state.value = VideoToGifState(source = source)
    }

    fun update(preset: GifPreset? = null, fullVideo: Boolean? = null) {
        val s = _state.value
        _state.value = s.copy(
            preset = preset ?: s.preset,
            fullVideo = fullVideo ?: s.fullVideo
        )
    }

    fun convert() {
        val s = _state.value
        val source = s.source ?: return
        if (s.phase == VideoToGifState.Phase.Converting) return
        job = viewModelScope.launch {
            _state.value = s.copy(phase = VideoToGifState.Phase.Converting, progress = 0f, error = null)
            try {
                val sourceFile = File(source.filePath)
                val dir = sourceFile.parentFile ?: getApplication<Application>().cacheDir
                val baseName = sourceFile.nameWithoutExtension + "_gif"
                val result = VideoToGifConverter.convert(
                    request = VideoToGifRequest(
                        videoPath = source.filePath,
                        outputDirectory = dir,
                        baseName = baseName,
                        preset = s.preset,
                        fullVideo = s.fullVideo
                    ),
                    onProgress = { p -> _state.value = _state.value.copy(progress = p) }
                )
                val gifRecord = source.asGifRecord(result.file, result.file.length())
                _state.value = _state.value.copy(phase = VideoToGifState.Phase.Done, result = gifRecord)
            } catch (c: CancellationException) {
                _state.value = _state.value.copy(phase = VideoToGifState.Phase.Idle)
                throw c
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    phase = VideoToGifState.Phase.Failed,
                    error = e.message ?: "转换失败"
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /** 保存到相册；成功后返回图库 Uri，供提示展示。 */
    suspend fun saveToGallery(): String? {
        val record = _state.value.result ?: return null
        return when (val r = saver.save(record)) {
            is MediaGallerySaveResult.Saved -> {
                _state.value = _state.value.copy(result = record.copy(galleryUri = r.uri))
                r.uri
            }
            is MediaGallerySaveResult.PermissionRequired -> {
                _state.value = _state.value.copy(error = "需要存储权限才能保存到相册")
                null
            }
            is MediaGallerySaveResult.Failed -> {
                _state.value = _state.value.copy(error = r.reason)
                null
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        _state.value = VideoToGifState()
    }
}
