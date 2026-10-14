package com.adong.adchat.ui.theme

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Aster — Apple Fluid Spring Physics & Tactile Interaction Engine
 */
object AsterSpring {
    /** 空间与容器形变弹簧：用于灵动胶囊展开、思考面板舒展、页面切换（带微妙 iOS 弹性） */
    fun <T> spatial(): SpringSpec<T> = spring(
        dampingRatio = 0.76f,
        stiffness = 380f
    )

    /** 敏捷微交互弹簧：用于按钮按压缩放、SegmentedControl 胶囊切换 */
    fun <T> snappy(): SpringSpec<T> = spring(
        dampingRatio = 0.80f,
        stiffness = 620f
    )

    /** 文学级临界阻尼弹簧：用于色彩平滑过渡、焦点边框渐变（无过冲） */
    fun <T> editorial(): SpringSpec<T> = spring(
        dampingRatio = 0.92f,
        stiffness = 300f
    )

    /** 灵动弹跳弹簧：用于发送按钮浮现、回到底部胶囊弹出、欢迎页徽标弹入 */
    fun <T> gentleBounce(): SpringSpec<T> = spring(
        dampingRatio = 0.64f,
        stiffness = 420f
    )
}

/**
 * Apple iOS 风格弹性按压修饰符（支持手势中途打断与微触觉反馈）
 */
fun Modifier.appleSpringPressable(
    pressedScale: Float = 0.95f,
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
 * Apple 瀑布流错峰弹簧入场修饰符
 */
fun Modifier.appleStaggeredEntrance(
    index: Int,
    stepDelayMillis: Long = 55L,
    initialOffsetY: Dp = 20.dp
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
        targetValue = if (triggered) 1f else 0.92f,
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
 * 呼吸悬浮微动效修饰符
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
