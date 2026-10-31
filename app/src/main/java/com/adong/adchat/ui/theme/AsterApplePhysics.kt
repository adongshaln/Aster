package com.adong.adchat.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Small controls may overshoot; large surfaces and colors settle more firmly. */
object AsterSpring {
    fun <T> jelly(): SpringSpec<T> = spring(dampingRatio = 0.58f, stiffness = 380f)
    fun <T> spatial(): SpringSpec<T> = spring(dampingRatio = 0.78f, stiffness = 300f)
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.78f, stiffness = 620f)
    fun <T> editorial(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 420f)
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 0.94f, stiffness = 1100f)
    fun <T> releaseHorizontal(): SpringSpec<T> = spring(dampingRatio = 0.54f, stiffness = 440f)
    fun <T> releaseVertical(): SpringSpec<T> = spring(dampingRatio = 0.62f, stiffness = 320f)
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 0.72f, stiffness = 300f)
    fun <T> gentleBounce(): SpringSpec<T> = jelly()
}

/** Provided once at the app root; Compose still applies the system duration scale. */
val LocalAsterMotionEnabled = staticCompositionLocalOf { true }

@Composable
fun rememberAsterMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val readEnabled = remember(resolver) {
        { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    }
    var enabled by remember(resolver) { mutableStateOf(readEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = readEnabled()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer
        )
        enabled = readEnabled()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/**
 * Compress promptly, then release with different horizontal and vertical response times.
 * Pass a clickable's interaction source to animate its visual child without moving its hit area.
 * The fallback observes gestures without consuming them, so scroll cancellation still wins.
 */
fun Modifier.appleSpringPressable(
    pressedScale: Float = 0.94f,
    enabled: Boolean = true,
    enableHaptics: Boolean = true,
    jellySquish: Boolean = true,
    interactionSource: InteractionSource? = null
): Modifier = composed {
    val view = LocalView.current
    val motionEnabled = LocalAsterMotionEnabled.current
    var pointerPressed by remember { mutableStateOf(false) }
    val sourcePressed = interactionSource?.collectIsPressedAsState()
    val isPressed = enabled && (sourcePressed?.value ?: pointerPressed)
    val delta = (1f - pressedScale).coerceIn(0f, 0.10f)
    val targetScaleX = if (!isPressed || !motionEnabled) 1f else
        if (jellySquish) 1f + delta * 0.16f else 1f - delta
    val targetScaleY = if (!isPressed || !motionEnabled) 1f else 1f - delta
    val scaleX by animateFloatAsState(
        targetValue = targetScaleX,
        animationSpec = if (isPressed) AsterSpring.press() else AsterSpring.releaseHorizontal(),
        label = "press-width"
    )
    val scaleY by animateFloatAsState(
        targetValue = targetScaleY,
        animationSpec = if (isPressed) AsterSpring.press() else AsterSpring.releaseVertical(),
        label = "press-height"
    )
    LaunchedEffect(isPressed, enableHaptics) {
        if (isPressed && enableHaptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    val observePress = if (enabled && interactionSource == null) {
        Modifier.pointerInput(Unit) {
            try {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pointerPressed = true
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pointerPressed = false
                    }
                }
            } finally {
                pointerPressed = false
            }
        }
    } else Modifier
    this
        .then(observePress)
        .graphicsLayer {
            this.scaleX = if (motionEnabled && enabled) scaleX else 1f
            this.scaleY = if (motionEnabled && enabled) scaleY else 1f
            transformOrigin = TransformOrigin.Center
        }
}

/** Short, bounded entrance for a small collection; content changes do not restart it. */
fun Modifier.appleStaggeredEntrance(
    index: Int,
    stepDelayMillis: Long = 50L,
    initialOffsetY: Dp = 18.dp
): Modifier = composed {
    val motionEnabled = LocalAsterMotionEnabled.current
    var triggered by remember { mutableStateOf(!motionEnabled) }
    LaunchedEffect(motionEnabled) {
        if (!triggered && motionEnabled) {
            delay((index.coerceAtLeast(0).toLong() * stepDelayMillis.coerceIn(0L, 60L)).coerceAtMost(200L))
        }
        triggered = true
    }
    val initialPx = with(LocalDensity.current) { initialOffsetY.coerceIn(0.dp, 20.dp).toPx() }
    val alpha by animateFloatAsState(
        targetValue = if (triggered) 1f else 0f,
        animationSpec = AsterSpring.editorial(), label = "entrance-alpha"
    )
    val translationY by animateFloatAsState(
        targetValue = if (triggered) 0f else initialPx,
        animationSpec = AsterSpring.spatial(), label = "entrance-y"
    )
    val scaleX by animateFloatAsState(
        targetValue = if (triggered) 1f else 0.985f,
        animationSpec = AsterSpring.releaseHorizontal(), label = "entrance-width"
    )
    val scaleY by animateFloatAsState(
        targetValue = if (triggered) 1f else 0.96f,
        animationSpec = AsterSpring.releaseVertical(), label = "entrance-height"
    )
    this.graphicsLayer {
        this.alpha = if (motionEnabled) alpha else 1f
        this.translationY = if (motionEnabled) translationY else 0f
        this.scaleX = if (motionEnabled) scaleX else 1f
        this.scaleY = if (motionEnabled) scaleY else 1f
        transformOrigin = TransformOrigin(0.5f, 0.85f)
    }
}

/** Compatibility name: decorative artwork now settles once instead of floating forever. */
fun Modifier.claudeBreathingFloat(
    amplitudeDp: Dp = 2.5.dp,
    durationMillis: Int = 2800
): Modifier = composed {
    val motionEnabled = LocalAsterMotionEnabled.current
    var settled by remember { mutableStateOf(!motionEnabled) }
    LaunchedEffect(Unit) { settled = true }
    val amplitudePx = with(LocalDensity.current) { amplitudeDp.coerceIn(0.dp, 6.dp).toPx() }
    val offsetY by animateFloatAsState(
        targetValue = if (settled) 0f else amplitudePx,
        animationSpec = spring(
            dampingRatio = 0.62f,
            stiffness = 900_000f / durationMillis.coerceIn(1800, 3600)
        ),
        label = "artwork-settle"
    )
    this.graphicsLayer { translationY = if (motionEnabled) offsetY else 0f }
}

/** Reserved for work that is actively progressing; static and fully legible with motion off. */
fun Modifier.claudeStreamingPulse(): Modifier = composed {
    if (!LocalAsterMotionEnabled.current) return@composed this
    val infinite = rememberInfiniteTransition(label = "streaming")
    val alpha by infinite.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "streaming-alpha"
    )
    this.graphicsLayer { this.alpha = alpha }
}

@Composable
fun AsterStageDepthContainer(
    isOverlayOpen: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val motionEnabled = LocalAsterMotionEnabled.current
    val targetScale = if (isOverlayOpen && motionEnabled) 0.975f else 1f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = AsterSpring.spatial(), label = "stage-depth"
    )
    val targetCorner = if (isOverlayOpen && motionEnabled) 24.dp else 0.dp
    val corner by animateDpAsState(
        targetValue = targetCorner,
        animationSpec = AsterSpring.spatial(), label = "stage-corner"
    )
    val clampedCorner = if (motionEnabled) corner.coerceAtLeast(0.dp) else 0.dp
    Box(
        modifier = modifier.fillMaxSize().graphicsLayer {
            scaleX = if (motionEnabled) scale else 1f
            scaleY = if (motionEnabled) scale else 1f
            shape = RoundedCornerShape(clampedCorner)
            clip = motionEnabled && clampedCorner > 0.dp
        }
    ) { content() }
}
