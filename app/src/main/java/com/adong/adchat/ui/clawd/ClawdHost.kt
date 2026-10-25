package com.adong.adchat.ui.clawd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adong.adchat.ui.ForegroundEffect
import com.adong.adchat.ui.theme.LocalThemeDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Clawd 宿主挂载点：三只小爪 + 大 Clawd 同一张画布。
 *
 * - pile 活动带（56dp 真实占位，调用方放在表单区上沿，底边 = 地面）
 * - 大 Clawd 叠加在带内右侧（原版锚点：钉在 #send_form 右内缘）
 * - 坐标约定：pile/rig 都用 dp（1 CSS px = 1 dp），渲染层负责 density
 *
 * 状态接线（取代 WebView evaluateJavascript）：
 * - inputText / inputFocused → runtime.onInputText/onInputFocus
 * - generating / lastText → runtime.setGeneration
 * - night → 调色板切换（pile palNight / rig 'e' 换色）
 * - formAnchor / chatAnchor → runtime.formRect/chatRect（dp 矩形，供 a2Walls 边界算）
 */
@Composable
fun ClawdHost(
    inputText: String,
    inputFocused: Boolean,
    generating: Boolean,
    lastText: String,
    formAnchor: Rect?,
    chatAnchor: Rect?,
    scrollTick: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val night = LocalThemeDark.current
    val density = LocalDensity.current.density

    val bundle by produceState(ClawdBundleCache.cached, context.applicationContext) {
        value = ClawdBundleCache.load(context.applicationContext)
    }
    val data = bundle ?: run {
        // Keep the composer in place while the bundled sprites are parsed off the main thread.
        Box(modifier.height(ClawdPileBandHeight).offset(y = 7.dp))
        return
    }
    val scope = rememberCoroutineScope()
    val pile = remember(data, scope) {
        ClawdPileState(
            data = data.pile,
            store = ClawdPilePositionStore(context),
            scope = scope,
        )
    }
    val runtime = remember(data, pile, scope) {
        ClawdBigRuntime(
            rig = data.rig,
            pile = pile,
            scope = scope,
        )
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, pile, runtime) {
        fun updateVisibility() {
            val active = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            pile.setForeground(active)
            runtime.setForeground(active)
        }
        updateVisibility()
        val observer = LifecycleEventObserver { _, _ -> updateVisibility() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            pile.setForeground(false)
            runtime.setForeground(false)
        }
    }

    // 视口尺寸（dp）：band 所在窗口的宽高，最后防线用
    LaunchedEffect(Unit) {
        val metrics = context.resources.displayMetrics
        runtime.viewportW = metrics.widthPixels / density.toDouble()
        runtime.viewportH = metrics.heightPixels / density.toDouble()
    }

    // 锚矩形：Window 像素 → 转 dp → 写 runtime（同步 formRect/chatRect）
    LaunchedEffect(formAnchor, chatAnchor, density) {
        // The activity band has the capsule's current width; update it on each geometry change
        // instead of polling every 50 ms behind the shell's spring animation.
        formAnchor?.let { runtime.bandLeftOffset = (it.width / density - 42f).toDouble() }
        runtime.formRect = formAnchor?.let {
            ClawdBigRuntime.RectF(it.left / density, it.top / density, it.right / density, it.bottom / density)
        }
        runtime.chatRect = chatAnchor?.let {
            ClawdBigRuntime.RectF(it.left / density, it.top / density, it.right / density, it.bottom / density)
        }
        // 小爪左边距输入框左缘的偏移 = 带的位置；大爪左边 = runtime.x，相对输入框左缘
        // pile 的 left() 等价物：大爪左边在 pile 坐标系里 = runtime.x
        pile.setBigGrounded(
            grounded = runtime.isBigGrounded(),
            leftDp = runtime.bigLeftInBand(),
            walking = runtime.isWalking(),
        )
    }

    // 大爪位置变化同步给 pile（它要知道障碍在哪；大爪在输入框上才算障碍）
    ForegroundEffect(runtime) {
        while (true) {
            kotlinx.coroutines.delay(100)
            pile.setBigGrounded(runtime.isBigGrounded(), runtime.bigLeftInBand(), walking = runtime.isWalking())
        }
    }

    // 输入联动
    LaunchedEffect(inputText) { runtime.onInputText(inputText) }
    LaunchedEffect(inputFocused) { runtime.onInputFocus(inputFocused) }
    LaunchedEffect(generating, lastText) {
        runtime.setGeneration(generating, lastText.isNotBlank(), if (!generating) "done" else null)
    }
    LaunchedEffect(night) { runtime.night = night }
    // 聊天区滚动只驱动大爪扒边，小爪不响应（原版 __clawdScroll → clawdScrollTilt）
    LaunchedEffect(scrollTick) { if (scrollTick > 0) runtime.onScroll() }

    DisposableEffect(pile, runtime) {
        onDispose {
            pile.onChanged = null
            runtime.stop()
        }
    }

    // 小爪整体下移：原版 .clawd-pile{bottom:100%; margin-bottom:-1px}，WebView 时期校准到净 -5px；
    // 本版要求再降 ~1mm（6dp），带从 1dp 提到 7dp——爪脚压进输入框上缘，视觉上「站在框上」。
    // Sprite physics use absolute screen x coordinates; do not mirror hit targets in RTL.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier.height(ClawdPileBandHeight).offset(y = 7.dp)) {
            // 小爪带：底边 = 地面（正好压在输入框上沿，原版 .clawd-pile{bottom:100%}）
            ClawdPileOverlay(
                pile = pile,
                night = night,
                modifier = Modifier.fillMaxSize(),
            )
            // 大爪：叠加在同画布，自身位置由 runtime.x/fy 驱动；默认钉在右侧内边
            ClawdBigOverlay(
                runtime = runtime,
                rig = data.rig,
                night = night,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private data class ClawdBundle(val rig: RigData, val pile: PileData)

private object ClawdBundleCache {
    @Volatile var cached: ClawdBundle? = null
        private set
    private val mutex = Mutex()

    suspend fun load(context: android.content.Context): ClawdBundle = withContext(Dispatchers.IO) {
        mutex.withLock {
            cached ?: run {
                val json = context.assets.open("clawd/clawd_data.json").bufferedReader().use { it.readText() }
                ClawdBundle(ClawdLoader.loadRigData(json), ClawdLoader.loadPileData(json)).also { cached = it }
            }
        }
    }
}
