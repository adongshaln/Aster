package com.adong.adchat.ui.theme

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Aster v4.0 — Apple Jelly Elasticity (果冻感) & Viscous Damping (液压阻尼感) 物理引擎
 * ====================================================================================
 * 核心设计原理：
 * 1. 真正的 Apple 果冻感 (Jelly) 并非生硬的等比缩放，而是「非等向体积守恒形变 (Anisotropic Squash & Stretch)」：
 *    按下时 Y 轴下压更深、X 轴微幅横向延展；松手时 X/Y 轴以微小相位差和欠阻尼弹簧 (`dampingRatio = 0.52f`) 回弹，
 *    产生如同 iOS 18 控制中心与灵动岛般的液态果冻Q弹手感。
 * 2. 真正的阻尼感 (Viscous Damping) 体现在面板展开与布局流转：采用高粘滞阻尼比 (`0.76f`) 与低刚度 (`240f`)，
 *    初速度极快响应，收尾如液压铰链般丝滑刹停，绝不生硬跳变。
 */
object AsterSpring {
    /** 液态果冻弹簧 (Jelly Bounce)：明显而克制的弹性过冲，用于按钮浮现、箭头翻转、胶囊滑块 */
    fun <T> jelly(): SpringSpec<T> = spring(
        dampingRatio = 0.52f,
        stiffness = 420f
    )

    /** 液压粘滞阻尼弹簧 (Viscous Damping)：起步灵敏、收尾绵密厚重，用于思考面板展开、输入框聚焦抬升 */
    fun <T> spatial(): SpringSpec<T> = spring(
        dampingRatio = 0.74f,
        stiffness = 250f
    )

    /** 敏捷阻尼微交互弹簧：用于高频触控反馈与分段切换 */
    fun <T> snappy(): SpringSpec<T> = spring(
        dampingRatio = 0.66f,
        stiffness = 520f
    )

    /** 丝滑无过冲阻尼：用于色彩过渡、边框高亮与透明度呼吸 */
    fun <T> editorial(): SpringSpec<T> = spring(
        dampingRatio = 0.90f,
        stiffness = 240f
    )

    /** 兼容别名 */
    fun <T> gentleBounce(): SpringSpec<T> = jelly()
}

/**
 * Apple iOS 18 果冻感弹性按压修饰符 (Anisotropic Jelly Squash & Stretch)：
 * - 按下瞬间：Y 轴压缩至 [pressedScale]，X 轴呈现微幅果冻形变（体积守恒感），伴随轻微触觉反馈；
 * - 松开瞬间：X 轴与 Y 轴以不同刚度的欠阻尼果冻弹簧 (`dampingRatio = 0.52f`) 错峰回弹，形成细腻的果冻震颤收敛。
 */
fun Modifier.appleSpringPressable(
    pressedScale: Float = 0.94f,
    enabled: Boolean = true,
    enableHaptics: Boolean = true,
    jellySquish: Boolean = true
): Modifier = composed {
    if (!enabled) return@composed this
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }

    // 非等向果冻形变：Y 轴下压更深，X 轴轻微横向张开或较小幅度收缩，松手时不同刚度产生果冻Q弹相位差
    val delta = 1f - pressedScale
    val targetScaleX = if (!isPressed) 1f else if (jellySquish) (1f - delta * 0.55f) else pressedScale
    val targetScaleY = if (!isPressed) 1f else if (jellySquish) (1f - delta * 1.18f) else pressedScale

    val scaleX by animateFloatAsState(
        targetValue = targetScaleX,
        animationSpec = spring(
            dampingRatio = if (isPressed) 0.78f else 0.50f,
            stiffness = if (isPressed) 700f else 460f
        ),
        label = "JellyScaleX"
    )
    val scaleY by animateFloatAsState(
        targetValue = targetScaleY,
        animationSpec = spring(
            dampingRatio = if (isPressed) 0.78f else 0.54f,
            stiffness = if (isPressed) 700f else 380f
        ),
        label = "JellyScaleY"
    )

    this
        .graphicsLayer {
            this.scaleX = scaleX
            this.scaleY = scaleY
            this.transformOrigin = TransformOrigin.Center
        }
        .pointerInput(enabled) {
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    if (enableHaptics) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    waitForUpOrCancellation()
                    isPressed = false
                }
            }
        }
}

/**
 * Apple 错峰果冻阻尼入场修饰符：
 * 列表卡片以底部为锚点，带着微小的果冻纵向拉伸与粘滞阻尼依次浮现。
 */
fun Modifier.appleStaggeredEntrance(
    index: Int,
    stepDelayMillis: Long = 50L,
    initialOffsetY: Dp = 18.dp
): Modifier = composed {
    var triggered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * stepDelayMillis)
        triggered = true
    }

    val density = LocalDensity.current
    val initialPx = with(density) { initialOffsetY.toPx() }

    val alpha by animateFloatAsState(
        targetValue = if (triggered) 1f else 0f,
        animationSpec = AsterSpring.editorial(),
        label = "StaggerAlpha"
    )
    val translationY by animateFloatAsState(
        targetValue = if (triggered) 0f else initialPx,
        animationSpec = AsterSpring.spatial(),
        label = "StaggerOffsetY"
    )
    val scaleX by animateFloatAsState(
        targetValue = if (triggered) 1f else 0.94f,
        animationSpec = AsterSpring.jelly(),
        label = "StaggerScaleX"
    )
    val scaleY by animateFloatAsState(
        targetValue = if (triggered) 1f else 0.88f,
        animationSpec = AsterSpring.spatial(),
        label = "StaggerScaleY"
    )

    this.graphicsLayer {
        this.alpha = alpha
        this.translationY = translationY
        this.scaleX = scaleX
        this.scaleY = scaleY
        this.transformOrigin = TransformOrigin(0.5f, 0.85f)
    }
}

/**
 * 呼吸悬浮微动效修饰符
 */
fun Modifier.claudeBreathingFloat(
    amplitudeDp: Dp = 2.5.dp,
    durationMillis: Int = 2800
): Modifier = composed {
    val density = LocalDensity.current
    val amplitudePx = with(density) { amplitudeDp.toPx() }
    val infinite = rememberInfiniteTransition(label = "ClaudeBreathing")
    val offsetY by infinite.animateFloat(
        initialValue = -amplitudePx,
        targetValue = amplitudePx,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BreathingOffsetY"
    )
    this.graphicsLayer {
        translationY = offsetY
    }
}

/**
 * Claude 极简流式呼吸透明度修饰符（用于「正在思考...」文字，替代笨重的转圈进度条）
 */
fun Modifier.claudeStreamingPulse(): Modifier = composed {
    val infinite = rememberInfiniteTransition(label = "ClaudePulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.42f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(950, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )
    this.graphicsLayer {
        this.alpha = alpha
    }
}

/**
 * iOS Stage Manager 景深后退缩放容器
 */
@Composable
fun AsterStageDepthContainer(
    isOverlayOpen: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isOverlayOpen) 0.94f else 1f,
        animationSpec = AsterSpring.spatial(),
        label = "StageDepthScale"
    )
    val corner by animateDpAsState(
        targetValue = if (isOverlayOpen) 26.dp else 0.dp,
        animationSpec = AsterSpring.spatial(),
        label = "StageDepthCorner"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shape = RoundedCornerShape(corner)
                clip = corner > 0.dp
            }
    ) {
        content()
    }
}
