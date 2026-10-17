package com.adong.adchat.ui.clawd

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 大 Clawd 的渲染层。行为逐段对照原版：
 * - 视觉尺寸 = 16格 × px=3 = 48dp 宽，42dp 高（14格 × px=3，超出部分被裁，原版 rig 框就是 16x14）
 * - 每帧 runtime.visualAt(nowMs) → clipId → ClawdSampler.sampleState → rigFramePixels → Bitmap
 * - squash（被扔时）：A2.sqx/sqy 直接作用于整只缩放（原版 scale(sqx,sqy)）
 * - rot（拖拽转身）：rotate 按 A2.rot
 * - flex（弹性层）：ClawdSampler.flexAt(clip, t) 输出 sx/sy/r/tx/ty 合成 transform
 * - 生成徽标：44dp 方形贴纸叠在右上角（原版 #clawd_gen_timer）
 * - 戳/拖/摸：pointerInput 命中 48x48dp Box 直接驱动 runtime.a2Down/Move/Up
 * - 夜里墨色（night）：rig.pal 里 'e' 项替换为深色（原版四套主题）
 *
 * 坐标约定：runtime.x/fy 是「大爪左边距输入框左缘的 dp」；渲染层按把画布放在 runtime.x 处。
 * bigLeftDp（pile 状态机用）= 大爪左边在 pile 坐标系里的 x = runtime.x - pile 原点偏移。
 */
@Composable
fun ClawdBigOverlay(
    runtime: ClawdBigRuntime,
    rig: RigData,
    modifier: Modifier = Modifier,
    night: Boolean = false,
) {
    // 夜间墨色：rig.pal 的 'e' 项在夜间主题变成偏暖的暗墨色（原版四套主题的 night 变量）
    val palArgb = remember(night) {
        rig.pal.mapValues { (ch, v) ->
            if (night && ch == "e") 0xFF3A3832.toInt() else ClawdLoader.colorArgb(v)
        }
    }
    // 形态位图缓存：key = clipId|t|mirror|night
    val bitmapCache = remember(palArgb) { HashMap<String, ImageBitmap>() }

    // 每帧重采样（TimeAnimator 驱动）
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(runtime) {
        runtime.onChanged = { version++ }
        runtime.start()
        onDispose {
            runtime.onChanged = null
            runtime.stop()
        }
    }

    // 采样时钟：60fps 驱动动作时间轴
    var sampledVisual by remember { mutableStateOf<ClawdBigRuntime.Visual?>(null) }
    LaunchedEffect(runtime) {
        var last = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                val nowMs = frameNanos / 1_000_000
                if (nowMs != last) {
                    last = nowMs
                    sampledVisual = runtime.visualAt(nowMs)
                }
            }
        }
    }

    val visual = sampledVisual ?: return
    val clip = rig.clips[visual.clip] ?: rig.clips["idle"] ?: return
    val clipT = runtime.clipElapsedMs(visual.clip, android.os.SystemClock.elapsedRealtime())
    val state = ClawdSampler.sampleState(rig, clip, clipT)

    val density = LocalDensity.current.density
    val w = 16 * rig.px   // 48dp
    val h = 14 * rig.px   // 42dp

    // 按 z 排序部件 → 合成位图（一次成帧，金标准同款）
    val frameBitmap = remember(visual.clip, clipT, visual.mirror, night, version) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = NativeCanvas(bmp)
        val paint = Paint()
        val parts = state.parts.entries.sortedBy { it.value.z }
        for ((partName, ps) in parts) {
            val frame = ps.f ?: continue
            val pixels = ClawdPixels.rigFramePixels(rig, partName, frame, visual.mirror)
            for (px in pixels) {
                val cx = px.x + ps.dx.toInt()
                val cy = px.y + ps.dy.toInt()
                if (cx in 0 until w && cy in 0 until h) {
                    paint.color = px.argb
                    canvas.drawPoint(cx.toFloat(), cy.toFloat(), paint)
                }
            }
        }
        bmp.asImageBitmap()
    }

    // 整只的物理形变：x/fy 平移 + rot + squash + flex
    val (dx, dy) = visual.dx to visual.dy

    // 大爪原位 = 输入框右内缘（x=0 时左边贴右边）；x/fy 是相对这个位置的偏移（dy<0 往上升）。
    // 渲染 = 对齐到带右缘再平移：原版按钮钉在 #send_form 内右侧，x 是 translateX 偏移。
    // 触摸层：48x48dp Box，命中才消费
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset((dx * density).roundToInt(), (dy * density).roundToInt()) }
                .size(w.dp, h.dp)
                .pointerInput(runtime) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        runtime.a2Down(down.position.x, down.position.y, down.id.value)
                        drag(down.id) { change ->
                            runtime.a2Move(change.position.x, change.position.y, change.id.value)
                            change.consume()
                        }
                        runtime.a2Up(down.id.value)
                    }
                }
        ) {
            // flex 层：sx/sy/r/tx/ty（原版 CSS transform 合成，按「先缩后转再移」作用在整只上）
            val flex = visual.flex
            Canvas(Modifier.fillMaxSize()) {
                // transform-origin: 8*PX 100%（脚底中心），与原版 CSS 一致
                val pivotX = (8f * rig.px) * density
                val pivotY = h * density
                translate(pivotX + flex.tx.toFloat() * density, pivotY + flex.ty.toFloat() * density) {
                    scale(
                        scaleX = (visual.sx * flex.sx).toFloat(),
                        scaleY = (visual.sy * flex.sy).toFloat(),
                        pivot = Offset.Zero,
                    ) {
                        translate(-pivotX, -pivotY) {
                            drawImage(
                                frameBitmap,
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize((w * density).roundToInt(), (h * density).roundToInt()),
                                filterQuality = FilterQuality.None,
                            )
                        }
                    }
                }
            }

            // 生成计时徽标（原版 #clawd_gen_timer：44dp 方贴纸右上）
            visual.timerText?.let { text ->
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .offset(x = 6.dp, y = (-4).dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = text,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
