package com.adong.adchat.ui.media

import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.adong.adchat.media.ResolvedMedia
import com.adong.adchat.ui.theme.Accent
import com.adong.adchat.ui.theme.Ink
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@OptIn(markerClass = [UnstableApi::class])
@Composable
fun MediaPreviewPlayer(
    media: ResolvedMedia,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var retryToken by remember(media.mediaUrl) { mutableIntStateOf(0) }
    var buffering by remember(media.mediaUrl, retryToken) { mutableStateOf(true) }
    var playing by remember(media.mediaUrl, retryToken) { mutableStateOf(false) }
    var positionMs by remember(media.mediaUrl, retryToken) { mutableLongStateOf(0L) }
    var durationMs by remember(media.mediaUrl, retryToken) { mutableLongStateOf(0L) }
    var errorMessage by remember(media.mediaUrl, retryToken) { mutableStateOf<String?>(null) }
    var controlsVisible by remember(media.mediaUrl, retryToken) { mutableStateOf(true) }

    val player = remember(media.mediaUrl, retryToken) {
        val headers = buildMap {
            if (media.userAgent.isNotBlank()) put("User-Agent", media.userAgent)
            if (media.referer.isNotBlank()) put("Referer", media.referer)
            if (media.cookieHeader.isNotBlank()) put("Cookie", media.cookieHeader)
        }
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        val httpFactory = OkHttpDataSource.Factory(httpClient).setDefaultRequestProperties(headers)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(MediaItem.fromUri(media.mediaUrl))
                playWhenReady = true
            }
    }

    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    buffering = false
                    val value = player.duration
                    durationMs = if (value == C.TIME_UNSET) 0L else value.coerceAtLeast(0L)
                }
                if (playbackState == Player.STATE_ENDED) {
                    playing = false
                    controlsVisible = true
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) controlsVisible = false
            }

            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                playing = false
                controlsVisible = true
                errorMessage = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "视频地址已失效，请重新解析"
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "当前设备无法解码这个视频"
                    else -> "预览失败，请重试"
                }
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_DESTROY -> player.release()
                else -> Unit
            }
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        buffering = true
        errorMessage = null
        player.prepare()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            val value = player.duration
            durationMs = if (value == C.TIME_UNSET) durationMs else value.coerceAtLeast(0L)
            delay(250)
        }
    }

    Box(
        modifier = modifier.background(Color.Black).clickable {
            if (errorMessage == null && !buffering) controlsVisible = !controlsVisible
        }
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    keepScreenOn = true
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = .42f))
                .padding(start = 13.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("在线播放", color = Color.White.copy(alpha = .92f), modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Close, "关闭预览", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        when {
            errorMessage != null -> {
                Surface(
                    color = Color(0xFFF9F5EF),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 30.dp)
                ) {
                    Column(
                        Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(errorMessage.orEmpty(), color = Ink)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.clip(CircleShape).clickable {
                                errorMessage = null
                                buffering = true
                                retryToken += 1
                            }.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Refresh, null, tint = Accent, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("重试", color = Accent)
                        }
                    }
                }
            }
            buffering -> CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.5.dp,
                modifier = Modifier.align(Alignment.Center).size(38.dp)
            )
            else -> AnimatedVisibility(
                visible = controlsVisible || !playing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(color = Color.Black.copy(alpha = .66f), shape = CircleShape) {
                    IconButton(
                        onClick = {
                            if (playing) player.pause()
                            else {
                                if (durationMs > 0L && positionMs >= durationMs - 500L) player.seekTo(0L)
                                player.play()
                            }
                        },
                        modifier = Modifier.size(58.dp)
                    ) {
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (playing) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(31.dp)
                        )
                    }
                }
            }
        }

        if (durationMs > 0L) {
            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = .55f))
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    color = Accent,
                    trackColor = Color.White.copy(alpha = .28f)
                )
                Spacer(Modifier.height(6.dp))
                Text("${formatPreviewTime(positionMs)} / ${formatPreviewTime(durationMs)}", color = Color.White.copy(alpha = .92f))
            }
        }
    }
}

private fun formatPreviewTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

