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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    // 动画时钟：hop/squash/Z 飘/星星按 elapsed 采样（原版 Web Animations）
    var animNow by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { ns -> animNow = ns / 1_000_000 }
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

            val peekDrop = if (isHeld || flight != null) 0 else pile.peekOffsetOf(actor.name)
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

                    // 影子贴地面（叠在别人头上时不画；peek 缩下去时隐藏，原版 data-paused）
                    if (actor.on.isEmpty() && peekDrop == 0) {
                        drawOval(
                            color = if (night) Color(0x59000000) else Color(0x24141413),
                            topLeft = Offset((visualX + 1.5f * data.px) * density, groundY - 2f * density),
                            size = Size(13f * data.px * density, 3f * density),
                        )
                    }

                    // 戳跳 HOP（460ms）与落地 squash（320ms）：原版 Web Animations 关键帧
                    val (hopDy, hopSx, hopSy) = hopTransform(actor, animNow)
                    val (sqSx, sqSy) = squashTransform(actor, animNow)

                    // 原版 transform-origin: 8*PX 100% —— 绕"脚底、身体中心"做果冻缩放
                    val pivotX = (visualX + 8f * data.px) * density
                    val pivotY = groundY - lift * density + peekDrop * density
                    translate(pivotX, pivotY + hopDy * data.px * density) {
                        scale(breatheX * hopSx * sqSx, breatheY * hopSy * sqSy, pivot = Offset.Zero) {
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
                    // 走路腿：身体图是「站起来、没腿」的 raised 帧，四根小腿单独画（原版 legs()）
                    if (walking) drawWalkLegs(actor, visualX, groundY - lift * density, density, data, animNow)
                }
            }
         }
        }

        // ===== 特效层：睡着的 Z 飘 + 巫师被戳的星星（原版 z-index:5）=====
        Canvas(Modifier.fillMaxSize()) {
            drawPileFx(pile, actors, animNow, bandHeightDp, density, night)
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
                                        // 离地高度 = 带底 - 画布底边（地面时 = 0，向上为正）
                                        val fromGround = (bandHeightDp - release.y - sheetHDp).toDouble()
                                        pile.throwAt(
                                            actor.name, release.x.toDouble(), release.y.toDouble(),
                                            throwVx.toDouble(), throwVy.toDouble(),
                                            releaseFromGround = fromGround,
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

/* ═══════════ 小爪动画（原版 Web Animations 关键帧的 Kotlin 等价）═══════════ */

/**
 * 戳跳 HOP（原版 460ms）：
 * 0% 1/1 → 18% 1.08/0.9 → 50% 上跳 6px(3*PX) 0.95/1.07 → 78% 1.07/0.92 → 100% 1/1。
 * 返回 (向上位移格, sx, sy)。
 */
private fun hopTransform(actor: ClawdPileState.Actor, nowMs: Long): Triple<Float, Float, Float> {
    if (actor.hopAt == 0L) return Triple(0f, 1f, 1f)
    val t = (nowMs - actor.hopAt) / 460.0
    if (t !in 0.0..1.0) return Triple(0f, 1f, 1f)
    return when {
        t < .18 -> {
            val k = (t / .18).toFloat()
            Triple(0f, lerp(1f, 1.08f, k), lerp(1f, .9f, k))
        }
        t < .5 -> {
            val k = ((t - .18) / .32).toFloat()
            Triple(lerp(0f, -3f, k), lerp(1.08f, .95f, k), lerp(.9f, 1.07f, k))
        }
        t < .78 -> {
            val k = ((t - .5) / .28).toFloat()
            Triple(lerp(-3f, 0f, k), lerp(.95f, 1.07f, k), lerp(1.07f, .92f, k))
        }
        else -> {
            val k = ((t - .78) / .22).toFloat()
            Triple(0f, lerp(1.07f, 1f, k), lerp(.92f, 1f, k))
        }
    }
}

/** 落地 squash（原版 320ms）：1/1 → 1.12/.86 → .97/1.04 → 1/1 */
private fun squashTransform(actor: ClawdPileState.Actor, nowMs: Long): Pair<Float, Float> {
    if (actor.squashAt == 0L) return 1f to 1f
    val t = (nowMs - actor.squashAt) / 320.0
    if (t !in 0.0..1.0) return 1f to 1f
    return when {
        t < .35 -> {
            val k = (t / .35).toFloat()
            lerp(1f, 1.12f, k) to lerp(1f, .86f, k)
        }
        t < .7 -> {
            val k = ((t - .35) / .35).toFloat()
            lerp(1.12f, .97f, k) to lerp(.86f, 1.04f, k)
        }
        else -> {
            val k = ((t - .7) / .3).toFloat()
            lerp(.97f, 1f, k) to lerp(1.04f, 1f, k)
        }
    }
}

private fun lerp(a: Float, b: Float, k: Float): Float = a + (b - a) * k

/**
 * 走路腿（原版 legs()）：四根小腿在 x = LEG_X 位置、TOP+13 高度，
 * 每根以「顶部」为原点做 scaleY 1→.4→1 并前移，两对相位差半个周期。
 * 一个周期 2*STEP_MS（320ms）。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWalkLegs(
    actor: ClawdPileState.Actor,
    visualX: Float,
    groundTop: Float,
    density: Float,
    data: PileData,
    nowMs: Long,
) {
    val dir = if (actor.face == 0) 1 else actor.face
    val cycle = 2.0 * data.stepMs
    val px = data.px
    for ((i, legX) in data.legX.withIndex()) {
        val phase = ((nowMs - actor.walkBase * 0L) % cycle.toLong()).toDouble() / cycle
        val p = ((phase + if (i % 2 == 1) .5 else 0.0) % 1.0)
        // 0 → .28 缩到 .4 并前移 .6 格；.28 → .5 回来；其余保持
        val (scaleY, shiftX) = when {
            p < .28 -> lerp(1f, .4f, (p / .28).toFloat()) to lerp(0f, dir * .6f * px, (p / .28).toFloat())
            p < .5 -> lerp(.4f, 1f, ((p - .28) / .22).toFloat()) to lerp(dir * .6f * px, 0f, ((p - .28) / .22).toFloat())
            else -> 1f to 0f
        }
        val cx = (visualX + legX * px + shiftX) * density
        val topY = groundTop + (data.top + 13) * px * density
        val w = px * density
        val h = 2 * px * density * scaleY
        drawRect(
            color = if (i % 2 == 1) Color(0xFFBF684C) else Color(0xFFD97757),
            topLeft = Offset(cx, topY),
            size = Size(w, h),
        )
    }
}

/**
 * 小爪特效（原版 puffZ / sparkle）：
 * - Z 飘：每 5s 从一只睡着的（没驮人）头顶往右上飘一个「Z」，4.2s 线性淡入淡出。
 *   原版用两个大小轮换的 box-shadow 点阵；这里直接画像素字。
 * - 星星：巫师被戳时 4 颗星依次炸开（500ms，steps(4)）。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPileFx(
    pile: ClawdPileState,
    actors: List<ClawdPileState.Actor>,
    nowMs: Long,
    bandHeightDp: Float,
    density: Float,
    night: Boolean,
) {
    if (!pile.isLaid) return
    val sheetHDp = pile.data.h * pile.data.px
    val px = pile.data.px
    val ink = if (night) Color(0xFFBDB7AE) else Color(0xFF6B6560)
    val starY = Color(0xFFFFF3C4)
    val starW = Color(0xFFFFFFFF)

    // ── Z 飘：5s 一轮，从「睡着的、没人驮的」里随机一只（原版 puffZ）──
    val zCycle = 5000L
    val zIdx = nowMs / zCycle
    val zPhase = (nowMs % zCycle) / 4200.0   // 飘 4.2s
    if (zPhase in 0.0..1.0) {
        val sleepers = actors.filter { a ->
            a.mode == ClawdPileState.Mode.SLEEP &&
                actors.none { o -> o.name != a.name && o.on.contains(a.name) }
        }
        if (sleepers.isNotEmpty()) {
            val a = sleepers[((zIdx % sleepers.size).toInt() + sleepers.size) % sleepers.size]
            val big = (zIdx % 2L) == 0L
            val alpha = when {
                zPhase < .12 -> (zPhase / .12).toFloat()
                zPhase < .8 -> 1f
                else -> ((1 - zPhase) / .2).toFloat().coerceIn(0f, 1f)
            } * .9f
            val x0 = (a.x + 12 * px).toFloat()
            val y0 = bandHeightDp - sheetHDp - pile.liftOf(a.name) + ((pile.data.top + 5) * px).toFloat()
            val dx = 6 * px * zPhase.toFloat()
            val dy = -14 * px * zPhase.toFloat()
            drawZMark(x0 + dx, y0 + dy, big, ink, alpha, density)
        }
    }

    // ── 星星：巫师被戳后 500ms 内（原版 sparkle，4 颗错开 0/120/220/320ms）──
    for (a in actors) {
        if (a.name != "wizard" || a.hopAt == 0L) continue
        val el = nowMs - a.hopAt
        if (el < 0 || el > 820) continue
        val top = bandHeightDp - sheetHDp - pile.liftOf(a.name)
        val at = listOf(Triple(19, -3, 0), Triple(21, -1, 120), Triple(18, -1, 220), Triple(20, -5, 320))
        for ((sx, sy, delay) in at) {
            val t = (el - delay) / 500.0
            if (t !in 0.0..1.0) continue
            val scale = 1f + .6f * t.toFloat()
            val alpha = (1 - t).toFloat()
            val cx: Float = (a.x + (sx - 1) * px).toFloat()
            val cy: Float = top + ((pile.data.top + 7 + sy - 1) * px).toFloat()
            drawSpark(cx, cy, scale, alpha, starY, starW, density.toFloat())
        }
    }
}

/** 画一个像素「Z」（原版 z 点阵：大 4x4、小 3x3，用 k 色） */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawZMark(
    xDp: Float, yDp: Float, big: Boolean, ink: Color, alpha: Float, density: Float,
) {
    val cells = if (big) listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0, 2 to 1, 1 to 2, 0 to 3, 1 to 3, 2 to 3, 3 to 3)
    else listOf(0 to 0, 1 to 0, 2 to 0, 1 to 1, 0 to 2, 1 to 2, 2 to 2)
    val px = 2f * density
    for ((cx, cy) in cells) {
        drawRect(ink.copy(alpha = alpha), Offset((xDp + cx * 2) * density, (yDp + cy * 2) * density), Size(px, px))
    }
}

/** 画一颗像素星（原版 3x3：Y 十字 + 中心白点） */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpark(
    xDp: Float, yDp: Float, scale: Float, alpha: Float, y: Color, w: Color, density: Float,
) {
    val s = 2f * density * scale
    val cells = listOf(
        Triple(1, 0, y), Triple(0, 1, y), Triple(2, 1, y), Triple(1, 2, y), Triple(1, 1, w),
    )
    for ((cx, cy, color) in cells) {
        drawRect(color.copy(alpha = alpha), Offset((xDp + cx * 2 * scale) * density, (yDp + cy * 2 * scale) * density), Size(s, s))
    }
}
