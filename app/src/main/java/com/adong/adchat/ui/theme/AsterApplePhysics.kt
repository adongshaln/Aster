package com.adong.adchat.ui.theme

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Aster v1.0 — Apple Fluid Spring Physics & Tactile Interaction Engine
 * ====================================================================
 * 位于 `com.adong.adchat.ui.theme` 包下，任何导入 `com.adong.adchat.ui.theme.*` 的组件均可直接调用。
 */
object AsterSpring {
    /** 空间与容器形变弹簧：用于灵动胶囊展开、页面切换、卡片展开（带微妙的 iOS 灵动回弹） */
    fun <T> spatial(): SpringSpec<T> = spring(
        dampingRatio = 0.78f,
        stiffness = 380f
    )

    /** 敏捷微交互弹簧：用于按钮按压、SegmentedControl 滑块、模型切换胶囊 */
    fun <T> snappy(): SpringSpec<T> = spring(
        dampingRatio = 0.82f,
        stiffness = 640f
    )

    /** 文学级舒展弹簧：用于思考面板折叠、列表高度自适应与颜色过渡（平滑无过冲） */
    fun <T> editorial(): SpringSpec<T> = spring(
        dampingRatio = 0.92f,
        stiffness = 300f
    )

    /** 灵动图标入场弹簧：用于欢迎页 Logo 与空状态徽章入场 */
    fun <T> gentleBounce(): SpringSpec<T> = spring(
        dampingRatio = 0.68f,
        stiffness = 260f
    )
}

/**
 * Apple iOS 风格弹性按压修饰符：
 * - 手指按下时以 [AsterSpring.snappy] 物理曲线平滑缩放到 [pressedScale] 并触发微弱的 CLOCK_TICK 触觉反馈；
 * - 手指抬起或滑出时带着手势初速度自然回弹。
 */
fun Modifier.appleSpringPressable(
    pressedScale: Float = 0.96f,
    enabled: Boolean = true,
    enableHaptics: Boolean = true
): Modifier = composed {
    if (!enabled) return@composed this
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = AsterSpring.snappy(),
        label = "ApplePressScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
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
 * Apple 瀑布流错峰弹簧入场修饰符（Staggered Spring Entrance）：
 * 让欢迎页卡片、列表项按索引依次以弹簧物理曲线上浮并淡入。
 */
fun Modifier.appleStaggeredEntrance(
    index: Int,
    stepDelayMillis: Long = 55L,
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
    val scale by animateFloatAsState(
        targetValue = if (triggered) 1f else 0.94f,
        animationSpec = AsterSpring.spatial(),
        label = "StaggerScale"
    )

    this.graphicsLayer {
        this.alpha = alpha
        this.translationY = translationY
        this.scaleX = scale
        this.scaleY = scale
    }
}

/**
 * 呼吸悬浮微动效修饰符：用于 Claude 星标徽章与空状态图标
 */
fun Modifier.claudeBreathingFloat(
    amplitudeDp: Dp = 3.dp,
    durationMillis: Int = 2600
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
 * iOS Stage Manager 景深缩放容器：
 * 当侧边栏或模态弹窗打开时，主画布自动后退缩放到 0.94f 并呈现 26.dp 圆角景深。
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
