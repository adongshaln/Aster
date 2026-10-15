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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 三只小 Clawd 的渲染层。对照原版 clawd-pile.js：
 * - 形态位图 = sheet()/cells()（原版 48–72 行），Kotlin 由 ClawdPixels.pileCells 出像素画进 22x18 ImageBitmap
 * - 睡姿果冻呼吸 = CSS clawd-pile-breathe（3.1s，三只相位错开 -1.2s / -0.6s）
 * - 被拎着蹬腿 = dangleA/dangleB 两帧 720ms 交替（原版 Web Animations steps(2)）
 * - 影子 = 26x3 椭圆（原版 13px * 2px/格），叠在别人头上时隐藏
 *
 * 挂载：放进输入框容器 Box，align(BottomCenter)。overlay 底边 = "地面"。
 * 原版 1 CSS px = 这边 1 dp；渲染时 1 美术格 = data.px dp。
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

    // 拖拽中的手指位置（name → 身体左上角在 overlay 里的 dp 坐标）
    var heldPos by remember { mutableStateOf<Pair<String, Offset>?>(null) }

    // 被拎着的两帧蹬腿
    var dangleFrame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(360)
            dangleFrame = 1 - dangleFrame
        }
    }

    val density = LocalDensity.current

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                val wDp = with(density) { size.width.toDp().roundToPx() }
                if (pile.width == 0) pile.ensureLaid(wDp) else pile.relayout(wDp)
            }
            .pointerInput(pile) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val groundY = size.height.toFloat()
                    val ordered = pile.snapshot().sortedByDescending { pile.liftOf(it.name) }
                    val hit = ordered.firstOrNull { actor ->
                        val lift = pile.liftOf(actor.name)
                        val left = actor.x.toFloat()
                        left <= down.position.x && down.position.x < left + pile.actorWidthPx &&
                            groundY - pile.actorHeightPx - lift <= down.position.y &&
                            down.position.y < groundY - lift
                    } ?: return@awaitEachGesture

                    var dragging = false
                    var grabOffset = Offset.Zero
                    drag(down.position) { change ->
                        if (!dragging && (change.position - down.position).getDistance() > 8f) {
                            dragging = true
                            val lift = pile.liftOf(hit)
                            // 抓取偏移：手指相对身体左上角（dangle 状态身体挂在手指下）
                            grabOffset = Offset(
                                down.position.x - pile.snapshot().first { it.name == hit }.x,
                                down.position.y - (groundY - pile.actorHeightPx - lift),
                            )
                            pile.pickUp(hit)
                        }
                        if (dragging) {
                            heldPos = hit to (change.position - grabOffset)
                            change.consume()
                        }
                    }
                    if (!dragging) {
                        pile.poke(hit)
                    } else {
                        val bodyLeft = (heldPos?.second?.x ?: down.position.x)
                        val cx = bodyLeft + pile.actorWidthPx / 2f
                        val releaseY = (heldPos?.second?.y ?: 0f)
                        val target = pile.snapshot()
                            .filter { it.name != hit && it.mode != ClawdPileState.Mode.HELD }
                            .filter { a ->
                                abs(a.x + pile.actorWidthPx / 2 - cx) < pile.actorWidthPx * 0.6f &&
                                    releaseY < groundY - pile.actorHeightPx / 2f - pile.liftOf(a.name)
                            }
                            .minByOrNull { a -> abs(a.x + pile.actorWidthPx / 2 - cx) }
                            ?.name
                        heldPos = null
                        pile.drop(hit, (cx - pile.actorWidthPx / 2f).roundToInt(), target)
                    }
                }
            }
    ) {
        val groundY = size.height.toFloat()
        val breath = rememberInfiniteTransition(label = "clawdBreath")
        for (actor in actors) {
            val held = heldPos?.first == actor.name && heldPos != null
            if (held) {
                // 被拎着：跟手指，dangle 两帧交替，无影子
                val pos = heldPos!!.second
                val pose = if (dangleFrame == 0) PilePose.DANGLE_A else PilePose.DANGLE_B
                val bmp = bitmapFor(actor.name, PileEyes.OPEN, pose, 0)
                Canvas(Modifier.fillMaxSize()) {
                    drawClawd(bmp, pos.x, pos.y, data, filter = null)
                }
                continue
            }

            val lift = pile.liftOf(actor.name)
            val visualX = if (actor.mode == ClawdPileState.Mode.WALK) actor.walkBase + actor.walkOff else actor.x
            val sleeping = actor.mode == ClawdPileState.Mode.SLEEP && actor.pose == PilePose.CROUCH
            val walking = actor.mode == ClawdPileState.Mode.WALK

            // 果冻呼吸（只对睡姿有形变）：三只相位错开
            val breathPhase = when (actor.name) {
                "shades" -> -1200
                "wizard" -> -600
                else -> 0
            }
            val breatheX by breath.animateFloat(
                initialValue = if (sleeping) 1f else 1f,
                targetValue = if (sleeping) 1.07f else 1f,
                animationSpec = infiniteRepeatable(
                    tween(1550, delayMillis = 0, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "bx-${actor.name}",
            )
            val breatheY by breath.animateFloat(
                initialValue = 1f,
                targetValue = if (sleeping) 0.91f else 1f,
                animationSpec = infiniteRepeatable(
                    tween(1550, delayMillis = breathPhase.coerceAtLeast(0), easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "by-${actor.name}",
            )

            val poseNow = if (walking) PilePose.RAISED else actor.pose
            val bmp = bitmapFor(actor.name, actor.eyes, poseNow, actor.face)
            val top = groundY - pile.actorHeightPx - lift

            // 影子
            if (actor.on.isEmpty()) {
                Canvas(Modifier.fillMaxSize()) {
                    drawOval(
                        color = if (night) Color(0x59000000) else Color(0x24141313),
                        topLeft = Offset(visualX + 1.5f * data.px, groundY - 3f),
                        size = Size(13f * data.px, 3f),
                    )
                }
            }

            // 身体
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = visualX.toFloat()
                        translationY = 0f
                        scaleX = breatheX
                        scaleY = breatheY
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
            ) {
                // 果冻形变以"脚底中心"为原点：先平移到脚底中心，缩放后再画
                val cxp = visualX + pile.actorWidthPx / 2f
                val byp = groundY - lift
                drawClawd(bmp, cxp - pile.actorWidthPx / 2f, byp - pile.actorHeightPx, data, filter = null)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawClawd(
    bmp: ImageBitmap,
    xDp: Float,
    yDp: Float,
    data: PileData,
    filter: Unit?,
) {
    translate(xDp, yDp) {
        drawImage(
            bmp,
            dstSize = IntSize(data.w * data.px, data.h * data.px),
            dstOffset = IntOffset.Zero,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
        )
    }
}
