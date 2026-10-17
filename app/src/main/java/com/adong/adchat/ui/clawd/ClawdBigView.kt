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
 * 原版：每层一个 <i class="clr-p-X">，帧 = box-shadow 点阵（无裁剪，符号/纸页自由溢出
 * Clawd 框），位移 = left/top，缩放/旋转/透明度 = 独立属性动画，整只平移 = .clr-root
 * transform，弹性层 = .clr-flex transform（transform-origin 脚底中点），剪辑切换 =
 * data-clawd-clip 换 animation；待机常驻眨眼 + 双钳呼吸。
 *
 * 这里每帧把「当前剪辑在 t 时刻」的 9 层状态采出来直接画：
 * - 位图窗口 = 全部精灵帧（含镜像）的最大包络（实测 x −6..21、y −10..16 格），
 *   不再按 0..16 裁——「？」/「！」符号、写字的纸、伸出去的钳子都必须完整。
 *   部件位移最大 ±33 格（done/write 甩纸），绘制层不裁剪（Compose 默认不裁）。
 * - 时间轴挂在 runtime.visualAt 的 startMs（单调时钟）；intro 播一次 → loop 段循环；
 *   非循环播完停在末帧（fill:forwards 等价）；剪辑退出靠轨道切换。
 * - 镜像（mirrorState）：像素以框中线翻转（rigFramePixels 已处理）之外，
 *   每层 dx 取反（sym 是 1−dx）、root.dx 取反、flex 的 r/tx 取反、逐层旋转取反。
 * - 物理层（A2 x/fy/rot/squash）与剪辑的 root/flex 分层 transform，顺序与原版一致。
 * - clipGround（hide/peek/peekOut）：地面线（框内 y=16 格）以下裁掉。
 * - 待机：rest 姿势 + 4.4s 眨眼 + 双钳 3.2s 呼吸起伏。
 * - 夜间墨色：'e'/'k' 换浅色（原版 --clr-ink 夜间变量）。
 *
 * 触摸层：42×34dp 小盒跟随 runtime.x/fy；屏幕位移用 window 坐标（原版 clientX/Y 语义）。
 */
@Composable
fun ClawdBigOverlay(
    runtime: ClawdBigRuntime,
    rig: RigData,
    modifier: Modifier = Modifier,
    night: Boolean = false,
) {
    val palArgb = remember(night) {
        rig.pal.mapValues { (ch, v) ->
            if (night && (ch == "e" || ch == "k")) NIGHT_INK else ClawdLoader.colorArgb(v)
        }
    }
    val bitmapCache = remember(palArgb) { HashMap<PartKey, ImageBitmap>() }
    // 位图窗口：全部部件、全部帧、含镜像的最大包络（数据驱动，换数据自动跟随）
    val win = remember(rig) { rigWindow(rig) }
    // sym 的变换中心（原版 .clr-p-sym transform-origin：box + origin + (0.5, 2.5) 格）
    val symPivot = remember(rig, win) {
        val o = rig.origin["sym"] ?: (7 to -9)
        Pair(rig.boxX + o.first + 0.5 - win.x0, rig.boxY + o.second + 2.5 - win.y0)
    }

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
    val cellDp = rig.px

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
                frame = computeFrame(rig, palArgb, bitmapCache, win, symPivot, s, nowMs)
            }
        }
    }

    // 物理层：订阅 version（runtime.onChanged 驱动），拖拽/弹道逐帧重组
    val phys = remember(version) { runtime.visualAt(android.os.SystemClock.elapsedRealtime()) }
    val f = frame

    Box(modifier) {
        // ── 触摸层：与大爪同位置的 42×34 小盒（原版按钮命中区），空白处穿透 ──
        // 底边贴带底（= 小爪脚线 = 地面）：原版按钮 inset:auto auto calc(100% - 5px)。
        var nodePosInWindow by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset((phys.dx * d).roundToInt(), (phys.dy * d).roundToInt()) }
                .size(42.dp, 34.dp)
                .onGloballyPositioned { nodePosInWindow = it.positionInWindow() }
                .pointerInput(runtime) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
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

        // ── 绘制层：按钮体（42×34）底边贴带底，rig 画布溢出到左 -4 / 上 -14 ──
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset((phys.dx * d).roundToInt(), (phys.dy * d).roundToInt()) }
                .size(42.dp, 34.dp)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // A2 物理形变层：transform-origin 按钮中心底部（21, 34）
                rotate(phys.rot.toFloat(), pivot = Offset(21f * d, 34f * d)) {
                    scale(phys.sqx.toFloat(), phys.sqy.toFloat(), pivot = Offset(21f * d, 34f * d)) {
                        // rig 原点：按钮左上 + (-4dp, -14dp)；flex transform-origin (24, 48) 相对 rig 原点
                        translate(-4f * d, -14f * d) {
                            val pivot = Offset(24f * d, 48f * d)
                            translate(pivot.x + f.flexTx * cellDp * d, pivot.y + f.flexTy * cellDp * d) {
                                rotate(f.flexR, pivot = Offset.Zero) {
                                    scale(f.flexSx, f.flexSy, pivot = Offset.Zero) {
                                        translate(-pivot.x, -pivot.y) {
                                            if (f.clipGround) {
                                                // 地面线以下裁掉：框内 y=16 格 = 48dp（原版 groundClipRule）
                                                clipRect(-400f * d, -400f * d, 400f * d, 48f * d) {
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

/** 精灵包络窗：全部部件、全部帧、含镜像的最大范围（格）。位图按此开窗，不再 0..16 硬裁。 */
private class RigWindow(val x0: Int, val y0: Int, val w: Int, val h: Int)

private fun rigWindow(rig: RigData): RigWindow {
    var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE
    var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
    for (part in rig.parts) {
        val sheet = rig.sprites[rig.sheetOf(part)] ?: continue
        val o = rig.origin[part] ?: continue
        for (fr in sheet.values) {
            for ((y, row) in fr.rows.withIndex()) {
                for ((x, ch) in row.withIndex()) {
                    if (ch == '.' || ch == ' ') continue
                    val cx = o.first + fr.ox + x
                    val cy = o.second + fr.oy + y
                    for (fx in intArrayOf(cx, 15 - cx)) {   // 镜像也罩进来
                        val ax = fx + rig.boxX
                        val ay = cy + rig.boxY
                        if (ax < x0) x0 = ax
                        if (ax > x1) x1 = ax
                        if (ay < y0) y0 = ay
                        if (ay > y1) y1 = ay
                    }
                }
            }
        }
    }
    return RigWindow(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
}

/** 逐层画部件位图。parts 已按 z 升序；x/y 是位图原点的框内格坐标（含 root 位移）。 */
private fun DrawScope.drawParts(f: BigFrame, d: Float, cellDp: Int) {
    for (p in f.parts) {
        val drawX = (p.x * cellDp * d)
        val drawY = (p.y * cellDp * d)
        val bmpW = (p.bw * cellDp * d).roundToInt()
        val bmpH = (p.bh * cellDp * d).roundToInt()
        if (p.sc == 1f && p.r == 0f) {
            drawImage(
                p.bitmap,
                dstOffset = IntOffset(drawX.roundToInt(), drawY.roundToInt()),
                dstSize = IntSize(bmpW, bmpH),
                alpha = p.a,
                filterQuality = FilterQuality.None,
            )
        } else {
            // 绕部件自己的变换中心（sym 用原版 transform-origin 格）
            val px = drawX + (p.cx * cellDp * d).toFloat()
            val py = drawY + (p.cy * cellDp * d).toFloat()
            translate(px.toFloat(), py.toFloat()) {
                rotate(p.r, pivot = Offset.Zero) {
                    scale(p.sc, p.sc, pivot = Offset.Zero) {
                        drawImage(
                            p.bitmap,
                            dstOffset = IntOffset((-p.cx * cellDp * d).roundToInt(), (-p.cy * cellDp * d).roundToInt()),
                            dstSize = IntSize(bmpW, bmpH),
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
    val parts: List<Part>,           // z 升序
    val flexSx: Float, val flexSy: Float, val flexR: Float,
    val flexTx: Float, val flexTy: Float,   // 美术格单位（×px=3 = dp）
    val clipGround: Boolean,
) {
    class Part(
        val bitmap: ImageBitmap,
        val bw: Int, val bh: Int,           // 位图尺寸（格）
        val x: Double, val y: Double,       // 位图原点的框内格坐标（含位移）
        val sc: Float, val r: Float, val a: Float,
        val cx: Double, val cy: Double,     // 变换中心（位图内，格）
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
 * - 剪辑：intro 内播 intro；loop 段 (t-intro) 取模循环；非循环到 dur 停末帧（fill:forwards）。
 * - 待机：rest 帧 + 眨眼（4.4s 一轮）+ 双钳呼吸（3.2s 平滑抬放）。
 */
private fun computeFrame(
    rig: RigData,
    pal: Map<String, Int>,
    cache: HashMap<PartKey, ImageBitmap>,
    win: RigWindow,
    symPivot: Pair<Double, Double>,
    session: ClipSession,
    nowMs: Long,
): BigFrame {
    val clipId = session.clipId ?: return restFrame(rig, pal, cache, win, symPivot, nowMs)
    val clip = rig.clips[clipId] ?: return restFrame(rig, pal, cache, win, symPivot, nowMs)

    val elapsed = (nowMs - session.startMs).coerceAtLeast(0)
    val t: Int
    if (clip.loop) {
        val intro = clip.intro
        t = if (elapsed < intro) elapsed.toInt()
        else intro + ((elapsed - intro) % maxOf(1L, clip.dur - intro.toLong())).toInt()
    } else {
        t = elapsed.coerceAtMost(clip.dur.toLong()).toInt()
        // hold/非循环都停在末帧（原版 fill:forwards）；剪辑退出靠轨道切换
    }
    val st = ClawdSampler.sampleState(rig, clip, t)
    var flex = ClawdSampler.flexAt(clip, t)
    if (session.mirror) {
        // 原版 flexOf：镜像时 r 与 tx 取反
        flex = FlexValue(flex.sx, flex.sy, -flex.r, -flex.tx, flex.ty)
    }
    return BigFrame(
        parts = buildParts(rig, pal, cache, win, symPivot, st, session.mirror),
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
    win: RigWindow,
    symPivot: Pair<Double, Double>,
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
        makePart(rig, pal, cache, win, symPivot, part, f, win.x0.toDouble(), win.y0 + dy,
            mirror = false, sc = 1f, r = 0f, a = 1f, z = rig.restZ.getValue(part))
            ?.let { out.add(it) }
    }
    return BigFrame(out.sortedBy { it.first }.map { it.second }, 1f, 1f, 0f, 0f, 0f, false)
}

private fun easeInOut(p: Float): Float = p * p * (3 - 2 * p)

/** 采样状态 → 可画部件列表（z 升序）。原版 mirrorState：dx 取反（sym 1−dx）、root.dx 取反。 */
private fun buildParts(
    rig: RigData,
    pal: Map<String, Int>,
    cache: HashMap<PartKey, ImageBitmap>,
    win: RigWindow,
    symPivot: Pair<Double, Double>,
    st: ClawdSampler.RigState,
    mirror: Boolean,
): List<BigFrame.Part> {
    val out = ArrayList<Pair<Int, BigFrame.Part>>()
    val rootDx = if (mirror) -st.rootDx else st.rootDx
    for (part in rig.parts) {
        val ps = st.parts[part] ?: continue
        val f = ps.f ?: continue
        if (ps.a <= 0.0) continue
        val dx = when {
            !mirror -> ps.dx
            part == "sym" -> 1.0 - ps.dx
            else -> -ps.dx
        }
        // 位图原点 = 窗口原点 + 合成位移（像素翻转已在 rigFramePixels 内完成）
        makePart(
            rig, pal, cache, win, symPivot, part, f,
            win.x0 + dx + rootDx, win.y0 + ps.dy + st.rootDy,
            mirror = mirror && part != "sym",
            sc = ps.sc.toFloat(),
            r = (if (mirror) -ps.r else ps.r).toFloat(),
            a = ps.a.toFloat(),
            z = ps.z,
        )?.let { out.add(it) }
    }
    return out.sortedBy { it.first }.map { it.second }
}

/** 单部件 → 包络窗大小的位图。x/y 是位图原点应画到的框内格坐标。 */
private fun makePart(
    rig: RigData,
    pal: Map<String, Int>,
    cache: HashMap<PartKey, ImageBitmap>,
    win: RigWindow,
    symPivot: Pair<Double, Double>,
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
        val b = android.graphics.Bitmap.createBitmap(win.w, win.h, android.graphics.Bitmap.Config.ARGB_8888)
        for (p in pixels) {
            val bx = p.x - win.x0
            val by = p.y - win.y0
            if (bx in 0 until win.w && by in 0 until win.h) b.setPixel(bx, by, p.argb)
        }
        b.asImageBitmap()
    }
    // 变换中心：sym 用原版 transform-origin，其余（理论只有 sym 有 sc/r）用位图中心
    val cx = if (part == "sym") symPivot.first else win.w / 2.0
    val cy = if (part == "sym") symPivot.second else win.h / 2.0
    return z to BigFrame.Part(bmp, win.w, win.h, x, y, sc, r, a, cx, cy)
}
