package com.adong.adchat.ui.clawd

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 大 Clawd 渲染层 = 原版 clawd-rig.js 的 CSS 动画引擎在 Compose 里的等价物。
 *
 * 原版：每层一个 <i class="clr-p-X">，帧 = box-shadow 点阵，位移 = left/top，
 * 缩放/旋转/透明度 = 独立属性动画，整只平移 = .clr-root transform，
 * 弹性层 = .clr-flex transform（transform-origin 脚底中点），
 * 剪辑切换 = data-clawd-clip 换 animation；待机常驻眨眼 + 双钳呼吸。
 *
 * 这里每帧把「当前剪辑在 t 时刻」的 9 层状态采出来直接画：
 * - 时间轴挂在 runtime.visualAt 的 startMs（单调时钟）；intro 播一次 → loop 段循环；
 *   非循环播完停在末帧（hold）或回调 runtime.notifyClipEnded。
 * - 镜像：除 sym 外每层以框中线翻转（rigFramePixels 已处理像素翻转），
 *   sym 只镜像位置不翻像素（「？」翻过来就不是问号），缩放/旋转/透明度方向随镜像取反。
 * - 物理层（A2 x/fy/rot/squash）与剪辑的 root/flex 分层 transform，顺序与原版一致：
 *   按钮 translate/rotate/scale（A2）→ flex 层（弹性）→ root（整只平移）→ 各层（相对位移）。
 * - clipGround（hide/peek/peekOut）：画布里地面线以下裁掉 = 缩进输入框的视觉效果。
 * - 待机（无剪辑）：rest 姿势 + 4.4s 眨眼 + 双钳 3.2s 呼吸起伏（clr-blink / clr-breathe-claw）。
 * - 夜间墨色：'e'/'k'（墨迹、道具描边）换浅色，对应原版 --clr-ink 夜间变量。
 *
 * 触摸层：42×34dp 小盒跟随 runtime.x/fy；屏幕位移用 window 坐标（节点自身位移
 * 会污染局部坐标，原版 clientX/Y 语义 = window 坐标之差）。
 */
@Composable
fun ClawdBigOverlay(
    runtime: ClawdBigRuntime,
    rig: RigData,
    modifier: Modifier = Modifier,
    night: Boolean = false,
) {
    // 夜间墨色：'e'/'k' 在夜间主题换浅色（原版的 --clr-ink 夜间变量）
    val palArgb = remember(night) {
        rig.pal.mapValues { (ch, v) ->
            if (night && (ch == "e" || ch == "k")) NIGHT_INK else ClawdLoader.colorArgb(v)
        }
    }
    // 部件位图缓存：palette 变了整体重建（夜间切换）
    val bitmapCache = remember(palArgb) { HashMap<PartKey, ImageBitmap>() }

    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(runtime) {
        runtime.onChanged = { version++ }
        runtime.start()
        onDispose {
            runtime.onChanged = null
            runtime.stop()
        }
    }

    val density = LocalDensity.current
    val d = density.density
    val cellDp = rig.px                      // 1 美术格 = 3dp

    // 剪辑会话：runtime 换剪辑（seq 变）时重置相位
    var session by remember { mutableStateOf(ClipSession()) }
    var frame by remember { mutableStateOf(BigFrame.IDLE) }

    LaunchedEffect(runtime, rig, night) {
        var frameT = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                val nowMs = frameNanos / 1_000_000
                if (nowMs == frameT) return@withFrameNanos
                frameT = nowMs
                val v = runtime.visualAt(nowMs)
                var s = session
                if (s.seq != v.seq || s.clipId != v.clipId) {
                    s = ClipSession(seq = v.seq, clipId = v.clipId, mirror = v.mirror, startMs = v.startMs)
                    session = s
                }
                frame = computeFrame(rig, palArgb, bitmapCache, s, nowMs) { endedBase ->
                    runtime.notifyClipEnded(endedBase)
                }
            }
        }
    }

    val f = frame
    // 物理层平移/形变（帧循环里只更新了 frame；x/fy/rot/squash 直接读 runtime 快照）
    val phys = runtime.visualAt(android.os.SystemClock.elapsedRealtime())

    Box(modifier) {
        // ── 触摸层：与大爪同位置的 42×34 小盒（原版按钮命中区），空白处穿透 ──
        var nodePosInWindow by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset((phys.dx * d).roundToInt(), (phys.dy * d).roundToInt()) }
                .size(42.dp, 34.dp)
                .onGloballyPositioned { nodePosInWindow = it.positionInWindow() }
                .pointerInput(runtime) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        // 屏幕坐标按下点（dp）：节点 window 位置 + 指下局部偏移
                        val sx = (nodePosInWindow.x + down.position.x) / d
                        val sy = (nodePosInWindow.y + down.position.y) / d
                        runtime.a2Down(sx, sy, down.id.value)
                        drag(down.id) { change ->
                            val cx = (nodePosInWindow.x + change.position.x) / d
                            val cy = (nodePosInWindow.y + change.position.y) / d
                            runtime.a2Move(cx, cy, change.id.value)
                            change.consume()
                        }
                        runtime.a2Up(down.id.value)
                    }
                }
        )

        // ── 绘制层：按钮体（42×34）钉右缘，rig 画布溢出到左 -4 / 上 -14 ──
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset((phys.dx * d).roundToInt(), (phys.dy * d).roundToInt()) }
                .size(42.dp, 34.dp)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // A2 物理形变层：transform-origin 按钮中心底部（21, 34）
                rotate(phys.rot.toFloat(), pivot = Offset(21f * d, 34f * d)) {
                    scale(phys.sqx.toFloat(), phys.sqy.toFloat(), pivot = Offset(21f * d, 34f * d)) {
                        // rig 原点：按钮左上 + (-4dp, -14dp)；transform-origin (24, 48) 相对 rig 原点
                        translate(-4f * d, -14f * d) {
                            val pivot = Offset(24f * d, 48f * d)
                            translate(pivot.x + f.flexTx * cellDp * d, pivot.y + f.flexTy * cellDp * d) {
                                rotate(f.flexR, pivot = Offset.Zero) {
                                    scale(f.flexSx, f.flexSy, pivot = Offset.Zero) {
                                        translate(-pivot.x, -pivot.y) {
                                            if (f.clipGround) {
                                                // 地面线以下裁掉：rig 框底 = 6+10=16 格 = 48dp
                                                clipRect(-200f * d, -200f * d, 200f * d, 48f * d) {
                                                    drawParts(f, d, cellDp)
                                                }
                                            } else {
                                                drawParts(f, d, cellDp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 生成计时徽标（原版 #clawd_gen_timer）
            phys.timerText?.let { text ->
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

private const val NIGHT_INK = 0xFFC3C2B7.toInt()

/** 逐层画部件位图。parts 已按 z 升序；x/y 是框内美术格（含 root 位移）。 */
private fun DrawScope.drawParts(f: BigFrame, d: Float, cellDp: Int) {
    val canvasW = 17 * cellDp * d
    val canvasH = 17 * cellDp * d
    for (p in f.parts) {
        val bmp = p.bitmap
        val ox = (p.x * cellDp * d)
        val oy = (p.y * cellDp * d)
        if (p.sc == 1f && p.r == 0f && p.a == 1f) {
            drawImage(
                bmp,
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                dstSize = IntSize(canvasW.roundToInt(), canvasH.roundToInt()),
                filterQuality = FilterQuality.None,
            )
        } else {
            // 需要形变/透明：绕部件格中心（原版 transform-origin 该格中心）
            val cx = ox + p.cx * cellDp * d
            val cy = oy + p.cy * cellDp * d
            translate(cx, cy) {
                rotate(p.r, pivot = Offset.Zero) {
                    scale(p.sc, p.sc, pivot = Offset.Zero) {
                        drawImage(
                            bmp,
                            dstOffset = IntOffset((-p.cx * cellDp * d).roundToInt(), (-p.cy * cellDp * d).roundToInt()),
                            dstSize = IntSize(canvasW.roundToInt(), canvasH.roundToInt()),
                            alpha = p.a,
                            filterQuality = FilterQuality.None,
                        )
                    }
                }
            }
        }
    }
}

/** 一帧要画的内容（帧循环产出，Canvas 消费）。 */
private class BigFrame(
    val parts: List<Part>,           // z 升序；x/y 框内美术格（含 root 位移）
    val flexSx: Float, val flexSy: Float, val flexR: Float,
    val flexTx: Float, val flexTy: Float,   // 美术格单位（×3 = dp）
    val clipGround: Boolean,
) {
    class Part(
        val bitmap: ImageBitmap,
        val x: Double, val y: Double,
        val sc: Float, val r: Float, val a: Float,
        val cx: Double, val cy: Double,     // 缩放/旋转中心（相对部件左上，美术格）
    )

    companion object {
        val IDLE = BigFrame(emptyList(), 1f, 1f, 0f, 0f, 0f, false)
    }
}

/** 剪辑会话：runtime 换剪辑（seq 变）时重建。 */
private class ClipSession(
    val seq: Int = -1,
    val clipId: String? = null,
    val mirror: Boolean = false,
    val startMs: Long = 0,
)

private data class PartKey(val part: String, val frame: String, val mirror: Boolean)

/**
 * 采一帧：剪辑态或待机 rest 态。
 * - 剪辑：intro 内播 intro；loop 段 (t-intro) 取模循环；非循环到 dur 停住并回调 ended。
 * - 待机：rest 帧 + 眨眼（4.4s 一轮）+ 双钳呼吸（3.2s 平滑抬放）。
 */
private fun computeFrame(
    rig: RigData,
    pal: Map<String, Int>,
    cache: HashMap<PartKey, ImageBitmap>,
    session: ClipSession,
    nowMs: Long,
    onEnded: (String) -> Unit,
): BigFrame {
    val clipId = session.clipId ?: return restFrame(rig, pal, cache, nowMs)
    val clip = rig.clips[clipId] ?: return restFrame(rig, pal, cache, nowMs)

    val elapsed = (nowMs - session.startMs).coerceAtLeast(0)
    val t: Int
    if (clip.loop) {
        val intro = clip.intro
        t = if (elapsed < intro) elapsed.toInt()
        else intro + ((elapsed - intro) % maxOf(1L, clip.dur - intro.toLong())).toInt()
    } else {
        t = elapsed.coerceAtMost(clip.dur.toLong()).toInt()
        if (elapsed >= clip.dur && !clip.hold && clip.next == null) onEnded(clip.id)
    }
    val st = ClawdSampler.sampleState(rig, clip, t)
    val flex = ClawdSampler.flexAt(clip, t)
    return BigFrame(
        parts = buildParts(rig, pal, cache, st, session.mirror),
        flexSx = flex.sx.toFloat(), flexSy = flex.sy.toFloat(), flexR = flex.r.toFloat(),
        flexTx = flex.tx.toFloat(), flexTy = flex.ty.toFloat(),
        clipGround = clip.clipGround,
    )
}

/** 待机帧：rest 姿势 + 眨眼（clr-blink 4400ms）+ 双钳呼吸（clr-breathe-claw 3200ms 平滑）。 */
private fun restFrame(
    rig: RigData,
    pal: Map<String, Int>,
    cache: HashMap<PartKey, ImageBitmap>,
    nowMs: Long,
): BigFrame {
    val blinkT = (nowMs % 4400).toInt()
    val eyesF = when {
        blinkT < 4000 -> "open"
        blinkT < 4100 -> "half"
        blinkT < 4200 -> "shut"
        blinkT < 4300 -> "half"
        else -> "open"
    }
    val bt = (nowMs % 3200).toInt()
    val clawDy = when {
        bt < 1400 -> -easeInOut(bt / 1400f)
        bt < 2600 -> -1f + easeInOut((bt - 1400) / 1200f)
        else -> 0f
    }
    val out = ArrayList<Pair<Int, BigFrame.Part>>()
    for (part in rig.parts) {
        val rest = rig.restF[part] ?: continue
        val f = if (part == "eyes") eyesF else rest
        val dy = if (part == "clawL" || part == "clawR") clawDy.toDouble() else 0.0
        makePart(rig, pal, cache, part, f, 0.0, dy, false, 1f, 0f, 1f, rig.restZ.getValue(part))
            ?.let { out.add(it) }
    }
    return BigFrame(out.sortedBy { it.first }.map { it.second }, 1f, 1f, 0f, 0f, 0f, false)
}

private fun easeInOut(p: Float): Float = p * p * (3 - 2 * p)

/** 采样状态 → 可画部件列表（z 升序）。root.dx/dy 折进每层坐标。 */
private fun buildParts(
    rig: RigData,
    pal: Map<String, Int>,
    cache: HashMap<PartKey, ImageBitmap>,
    st: ClawdSampler.RigState,
    mirror: Boolean,
): List<BigFrame.Part> {
    val out = ArrayList<Pair<Int, BigFrame.Part>>()
    for (part in rig.parts) {
        val ps = st.parts[part] ?: continue
        val f = ps.f ?: continue
        if (ps.a <= 0.0) continue
        // 镜像：sym 只翻位置（dx → 1-dx，像素不翻）；其余层像素已由 rigFramePixels 翻转
        val px = (if (mirror && part == "sym") 1.0 - ps.dx else ps.dx) + st.rootDx
        val py = ps.dy + st.rootDy
        // 缩放/旋转方向随镜像取反（原版 mirrorState 对 flex 取反、各层 scale/rotate 属性不镜像；
        // 这里的 per-part sc/r 是「整张图绕自己中心」的变换，镜像时角度取反才对称）
        makePart(
            rig, pal, cache, part, f, px, py, mirror && part != "sym",
            ps.sc.toFloat(), (if (mirror) -ps.r else ps.r).toFloat(), ps.a.toFloat(), ps.z,
        )?.let { out.add(it) }
    }
    return out.sortedBy { it.first }.map { it.second }
}

/** 单部件 → 17×17 位图 + 框内坐标。x/y 是部件原点（美术格，含 root），位图内已含 BOX 平移。 */
private fun makePart(
    rig: RigData,
    pal: Map<String, Int>,
    cache: HashMap<PartKey, ImageBitmap>,
    part: String,
    frame: String,
    x: Double, y: Double,
    mirror: Boolean,
    sc: Float, r: Float, a: Float,
    z: Int,
): Pair<Int, BigFrame.Part>? {
    val bmp = cache.getOrPut(PartKey(part, frame, mirror)) {
        val pixels = ClawdPixels.rigFramePixels(rig, part, frame, mirror, pal)
        if (pixels.isEmpty()) return null
        val b = android.graphics.Bitmap.createBitmap(17, 17, android.graphics.Bitmap.Config.ARGB_8888)
        for (p in pixels) {
            if (p.x in 0..16 && p.y in 0..16) b.setPixel(p.x, p.y, p.argb)
        }
        b.asImageBitmap()
    }
    // 缩放/旋转中心：部件位图中心（原版 transform-origin 该格中心）
    return z to BigFrame.Part(bmp, x, y, sc, r, a, 8.5, 8.5)
}
