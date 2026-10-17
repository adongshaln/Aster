package com.adong.adchat.ui.clawd

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * 活动带高度：身体 36dp + 叠放 14dp + 帽尖/闪光余量。真实占位（不遮列表滚动：
 * 空白处没有 pointerInput，触摸直接落到后面的消息列表上）。
 */
val ClawdPileBandHeight = 56.dp

/**
 * 三只小 Clawd 的渲染层。对照原版 clawd-pile.js：
 * - 形态位图 = sheet()/cells()（原版 48–72 行），Kotlin 由 ClawdPixels.pileCells 出像素画进 22x18 ImageBitmap
 * - 触摸 = 原版容器 pointer-events:none、爪子本体 auto（451/453 行）：每只一个真实小 Box（画布 44x36dp
 *   全区域可点），空白处穿透到消息列表
 * - 睡姿果冻呼吸 = CSS transform-origin:8*PX 100%（455 行）：绕"脚底、身体中心"缩放；
 *   三只独立时长+负相位（473–476 行：3.1s / shades 3.5s -1.2s / wizard 2.8s -0.6s）
 * - 被拎着蹬腿 = dangleA/dangleB 两帧 720ms 交替（原版 145 行 steps(2,jump-none)，360ms 换帧）
 * - 影子 = 13*PX x 3 椭圆、贴地线上 2dp（原版 471 行 top:-2px），叠在别人头上时隐藏
 * - 拖拽 = 原版 bindPointer（309–330 行）：阈值 4 CSS px、松手 drop(左缘, 手指高度)；
 *   叠头判定要求对方头顶空着（390 行 !carriers(o).length）
 *
 * 几何：overlay 是一条真实高度的活动带，**底边 = 地面**（调用方把它放在表单区上沿）。
 * 坐标约定：状态机的 x/lift 全是 CSS px（Android 上 1 CSS px = 1 dp）；带宽以 dp 报给
 * 状态机，绘制/定位前 dp x density 转 px。1 美术格 = data.px dp。x = 44dp 画布左缘（原版 el.left）。
 * 拖拽中手指可超出带的范围：事件在按下时已捕获给爪子的 Box，绘制不裁剪，身体可画到带外。
 */
@Composable
fun ClawdPileOverlay(
    pile: ClawdPileState,
    modifier: Modifier = Modifier,
    night: Boolean = false,
) {
    val data = pile.data
    val pal = if (night) data.palNight else data.pal
    val palArgb = remember(pal) { pal.mapValues { (_, v) -> ClawdLoader.colorArgb(v) } }

    // 形态位图缓存：key = name|eyes|pose|face
    val bitmapCache = remember(palArgb) { HashMap<String, ImageBitmap>() }
    fun bitmapFor(name: String, eyes: PileEyes, pose: PilePose, face: Int): ImageBitmap =
        bitmapCache.getOrPut("$name|$eyes|$pose|$face") {
            val pixels = ClawdPixels.pileCells(data, name, eyes, pose, face)
            val bmp = android.graphics.Bitmap.createBitmap(data.w, data.h, android.graphics.Bitmap.Config.ARGB_8888)
            for (p in pixels) bmp.setPixel(p.x, p.y, p.argb)
            bmp.asImageBitmap()
        }

    // 状态版本：状态机 onChanged 时 +1，触发 snapshot 重读
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(pile) {
        pile.onChanged = { version++ }
        onDispose { pile.onChanged = null }
    }
    val actors = remember(version) { pile.snapshot() }

    /** 拎起会话：origin = 拎起瞬间画布左上角（触摸 Box 冻结在此不动） */
    var dragInfo by remember { mutableStateOf<DragInfo?>(null) }
    /** 拎着时画布左上角（dp，跟手）；手指位置 = heldPos + grab */
    var heldPos by remember { mutableStateOf<Offset?>(null) }
    var bandHeightDp by remember { mutableFloatStateOf(0f) }
    /** 松手弹道速度（dp/16.7ms 帧，拖拽最后几帧的指数平均） */
    var throwVx by remember { mutableFloatStateOf(0f) }
    var throwVy by remember { mutableFloatStateOf(0f) }

    // 被拎着的两帧蹬腿
    var dangleFrame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(360)
            dangleFrame = 1 - dangleFrame
        }
    }

    // 弹道帧：有在飞的小爪时驱动 stepFlights（原版 rAF 逐帧积分）
    LaunchedEffect(pile) {
        var last = 0L
        while (true) {
            withFrameNanos { ns: Long ->
                val nowMs = ns / 1_000_000
                val dt = if (last > 0) ((nowMs - last) / 16.6667f).coerceIn(0.5f, 3f) else 1f
                last = nowMs
                pile.stepFlights(dt, bandHeightDp)
            }
        }
    }

    val density = LocalDensity.current.density
    val sheetWDp = data.w * data.px
    val sheetHDp = data.h * data.px

    Box(
        modifier
            .onSizeChanged { size ->
                bandHeightDp = size.height / density
                val w = (size.width / density).roundToInt()
                if (pile.width == 0) pile.ensureLaid(w) else pile.relayout(w)
            }
    ) {
        val heldName = dragInfo?.name

        // 原版 data-unlaid：量到宽度、摆好之前先藏着
        if (pile.isLaid) {

        // ===== 绘制层：后画的在上（lift 高的盖住低的；拎着的/在飞的最后画） =====
        val breath = rememberInfiniteTransition(label = "clawdBreath")
        for (actor in actors.sortedBy {
            when {
                it.name == heldName -> Int.MAX_VALUE
                pile.isFlying(it.name) -> Int.MAX_VALUE - 1
                else -> pile.liftOf(it.name)
            }
        }) {
         key(actor.name) {
            val isHeld = actor.name == heldName && heldPos != null
            val flight = pile.flightOf(actor.name)
            val walking = actor.mode == ClawdPileState.Mode.WALK
            val poseNow = when {
                isHeld || flight != null -> if (dangleFrame == 0) PilePose.DANGLE_A else PilePose.DANGLE_B
                walking -> PilePose.RAISED
                else -> actor.pose
            }
            val bmp = bitmapFor(actor.name, actor.eyes, poseNow, actor.face)
            val visualX = visualXOf(actor)
            val sleeping = !isHeld && flight == null && actor.mode == ClawdPileState.Mode.SLEEP && actor.pose == PilePose.CROUCH

            // 果冻呼吸（只对睡姿有形变）：三只各自独立时长+负相位错开
            // （原版 473–476 行：3.1s；shades 3.5s/-1.2s；wizard 2.8s/-0.6s）
            val (breathDurMs, breathDelayMs) = when (actor.name) {
                "shades" -> 3500 to 1200
                "wizard" -> 2800 to 600
                else -> 3100 to 0
            }
            val breatheX by breath.animateFloat(
                initialValue = 1f,
                targetValue = if (sleeping) 1.07f else 1f,
                animationSpec = infiniteRepeatable(
                    tween(breathDurMs, delayMillis = breathDelayMs, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "bx-${actor.name}",
            )
            val breatheY by breath.animateFloat(
                initialValue = 1f,
                targetValue = if (sleeping) 0.91f else 1f,
                animationSpec = infiniteRepeatable(
                    tween(breathDurMs, delayMillis = breathDelayMs, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "by-${actor.name}",
            )

            Canvas(Modifier.fillMaxSize()) {
                val groundY = size.height
                if (isHeld) {
                    val pos = heldPos ?: return@Canvas
                    drawClawdAt(bmp, pos.x, pos.y, data)
                } else if (flight != null) {
                    // 弹道中：画布直接画在飞行位置（不画影子、不做果冻呼吸）
                    drawClawdAt(bmp, flight.x.toFloat(), flight.y.toFloat(), data)
                } else {
                    val lift = pile.liftOf(actor.name).toFloat()

                    // 影子贴地面（叠在别人头上时不画）
                    if (actor.on.isEmpty()) {
                        drawOval(
                            color = if (night) Color(0x59000000) else Color(0x24141413),
                            topLeft = Offset((visualX + 1.5f * data.px) * density, groundY - 2f * density),
                            size = Size(13f * data.px * density, 3f * density),
                        )
                    }

                    // 原版 transform-origin: 8*PX 100% —— 绕"脚底、身体中心"做果冻缩放
                    val pivotX = (visualX + 8f * data.px) * density
                    val pivotY = groundY - lift * density
                    translate(pivotX, pivotY) {
                        scale(breatheX, breatheY, pivot = Offset.Zero) {
                            drawImage(
                                bmp,
                                dstOffset = IntOffset(
                                    (-8f * data.px * density).roundToInt(),
                                    (-sheetHDp * density).roundToInt(),
                                ),
                                dstSize = IntSize(
                                    (sheetWDp * density).roundToInt(),
                                    (sheetHDp * density).roundToInt(),
                                ),
                                filterQuality = FilterQuality.None,
                            )
                        }
                    }
                }
            }
         }
        }

        // ===== 触摸层：每只一个真实小 Box（画布 44x36dp 全区域），空白处穿透 =====
        for (actor in actors.sortedBy { pile.liftOf(it.name) }) {
            key(actor.name) {
                val frozen = dragInfo?.takeIf { it.name == actor.name }?.origin
                val originDp = frozen ?: Offset(
                    visualXOf(actor),
                    bandHeightDp - sheetHDp - pile.liftOf(actor.name),
                )
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                (originDp.x * density).roundToInt(),
                                (originDp.y * density).roundToInt(),
                            )
                        }
                        .size(sheetWDp.dp, sheetHDp.dp)
                        .pointerInput(actor.name) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                // 按下即消费：爪子矩形就是触摸区（原版 .clawd-pile-c pointer-events:auto
                                // + stopPropagation），穿透会让戳 Clawd 同时聚焦下面的输入框
                                down.consume()
                                var dragging = false
                                var grab = Offset.Zero
                                var prevUptime = down.uptimeMillis
                                drag(down.id) { change ->
                                    if (!dragging && (change.position - down.position).getDistance() > 4f * density) {
                                        dragging = true
                                        // 拎起瞬间取最新位置（闭包里的 actor 可能过期）
                                        val fresh = pile.snapshot().first { it.name == actor.name }
                                        val origin0 = Offset(
                                            visualXOf(fresh),
                                            bandHeightDp - sheetHDp - pile.liftOf(actor.name),
                                        )
                                        grab = Offset(
                                            down.position.x / density - origin0.x,
                                            down.position.y / density - origin0.y,
                                        )
                                        pile.pickUp(actor.name)
                                        heldPos = origin0
                                        dragInfo = DragInfo(actor.name, origin0)
                                        throwVx = 0f; throwVy = 0f
                                    }
                                    if (dragging) {
                                        val nx = change.position.x / density - grab.x
                                        val ny = change.position.y / density - grab.y
                                        // 松手弹道速度：最后几帧的指数平均（dp/16.7ms 帧）
                                        val prev = heldPos
                                        if (prev != null) {
                                            val dtMs = (change.uptimeMillis - prevUptime).coerceAtLeast(1)
                                            val fvx = (nx - prev.x) / dtMs * 16.6667f
                                            val fvy = (ny - prev.y) / dtMs * 16.6667f
                                            throwVx = throwVx * .45f + fvx * .55f
                                            throwVy = throwVy * .45f + fvy * .55f
                                        }
                                        prevUptime = change.uptimeMillis
                                        heldPos = Offset(nx, ny)
                                        change.consume()
                                    }
                                }
                                if (!dragging) {
                                    pile.poke(actor.name)
                                } else {
                                    val release = heldPos
                                    if (release != null) {
                                        // 松手 → 弹道飞行（落座/落地由 state 机在落地回调里判）
                                        val fingerY = release.y + grab.y
                                        pile.throwAt(
                                            actor.name, release.x.toDouble(), release.y.toDouble(),
                                            throwVx.toDouble(), throwVy.toDouble(),
                                        ) { n, landX, target ->
                                            pile.drop(n, landX, target)
                                        }
                                    }
                                    heldPos = null
                                    dragInfo = null
                                }
                            }
                        }
                )
            }
        }
        } // if (pile.isLaid)
    }
}

private data class DragInfo(val name: String, val origin: Offset)

/** 走路中的视觉 x = base + off，否则 x（均为 dp，画布左缘） */
private fun visualXOf(actor: ClawdPileState.Actor): Float =
    if (actor.mode == ClawdPileState.Mode.WALK) (actor.walkBase + actor.walkOff).toFloat() else actor.x.toFloat()

/** dp 坐标画形态位图（画布左上角对齐 x,y） */
private fun DrawScope.drawClawdAt(bmp: ImageBitmap, xDp: Float, yDp: Float, data: PileData) {
    translate(xDp * density, yDp * density) {
        drawImage(
            bmp,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(
                (data.w * data.px * density).roundToInt(),
                (data.h * data.px * density).roundToInt(),
            ),
            filterQuality = FilterQuality.None,
        )
    }
}
