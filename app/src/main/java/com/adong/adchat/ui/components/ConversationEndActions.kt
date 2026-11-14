package com.adong.adchat.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.adong.adchat.ui.chat.ConversationEndPull
import com.adong.adchat.ui.theme.*

/** Observe touch release without taking selection, links, or ordinary list scrolling away. */
internal fun Modifier.conversationEndPull(
    state: ConversationEndPull,
    enabled: Boolean,
    atEnd: () -> Boolean,
    onNewConversation: () -> Unit
): Modifier = composed {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestAtEnd by rememberUpdatedState(atEnd)
    val latestNew by rememberUpdatedState(onNewConversation)
    LaunchedEffect(enabled) { if (!enabled) state.cancel() }
    val connection = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val consumed = if (available.y < 0f)
                    state.pull(available.y, latestAtEnd(), latestEnabled) else state.reverse(available.y)
                return Offset(0f, consumed)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput)
                    Offset(0f, state.pull(available.y, latestAtEnd(), latestEnabled)) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (state.consumeFling()) Velocity(0f, available.y) else Velocity.Zero
        }
    }
    nestedScroll(connection).pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            state.begin(latestEnabled)
            try {
                var released = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.changes.count { it.pressed } > 1) state.cancel()
                    // Compose synthesizes consumed up events for ACTION_CANCEL.
                    released = event.changes.any { !it.pressed && it.previousPressed && !it.isConsumed }
                } while (event.changes.any { it.pressed })
                if (released && state.release(latestEnabled && latestAtEnd())) latestNew()
            } finally {
                state.cancel()
            }
        }
    }
}

@Composable
internal fun ConversationNewPullIndicator(state: ConversationEndPull, modifier: Modifier = Modifier) {
    val progress = state.progress
    val motion = LocalAsterMotionEnabled.current
    val haptics = LocalHapticFeedback.current
    val armed = progress >= 1f
    LaunchedEffect(armed) { if (armed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val fill by animateFloatAsState(progress, if (motion) AsterSpring.snappy() else snap(), label = "new-chat-fill")
    AnimatedVisibility(progress > .025f, modifier = modifier,
        enter = if (motion) fadeIn(AsterSpring.editorial()) + scaleIn(AsterSpring.spatial(), initialScale = .88f) else EnterTransition.None,
        exit = if (motion) fadeOut(AsterSpring.editorial()) + scaleOut(AsterSpring.spatial(), targetScale = .9f) else ExitTransition.None) {
        Surface(color = Surface, contentColor = Ink, shape = AsterShape.card,
            border = BorderStroke(.6.dp, Hairline), shadowElevation = 2.dp,
            modifier = Modifier.testTag("new-conversation-pull")) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val track = Hairline
                val ink = AccentInk
                Box(Modifier.size(40.dp).progressSemantics(progress).semantics {
                    contentDescription = "新建会话进度"
                    stateDescription = if (armed) "松开，新建会话" else "${(progress * 100).toInt()}%"
                }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize().padding(2.dp)) {
                        val stroke = 2.5.dp.toPx()
                        drawCircle(track, style = Stroke(stroke))
                        drawArc(ink, -90f, 360f * fill.coerceIn(0f, 1f), false,
                            style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    Icon(Icons.Rounded.Add, null, Modifier.size(19.dp), tint = ink)
                }
                Spacer(Modifier.height(6.dp))
                Text(if (armed) "松开，新建会话" else "继续滑动，新建会话",
                    style = MaterialTheme.typography.labelMedium, color = MutedInk)
            }
        }
    }
}

@Composable
internal fun ConversationAnswerNavigation(
    visible: Boolean, previousEnabled: Boolean, nextEnabled: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier
) {
    val motion = LocalAsterMotionEnabled.current
    AnimatedVisibility(visible, modifier = modifier,
        enter = if (motion) fadeIn(AsterSpring.editorial()) + scaleIn(AsterSpring.spatial(), initialScale = .92f) else EnterTransition.None,
        exit = if (motion) fadeOut(AsterSpring.editorial()) + scaleOut(AsterSpring.spatial(), targetScale = .92f) else ExitTransition.None) {
        Surface(color = Surface, contentColor = Ink, shape = CircleShape,
            border = BorderStroke(.6.dp, Hairline), shadowElevation = 2.dp,
            modifier = Modifier.testTag("answer-navigation")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NavigationArrow(true, previousEnabled, onPrevious)
                VerticalDivider(Modifier.height(18.dp), color = Hairline)
                NavigationArrow(false, nextEnabled, onNext)
            }
        }
    }
}

@Composable
private fun NavigationArrow(previous: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(onClick = rememberAsterClick(onClick, enabled), enabled = enabled,
        interactionSource = interaction, modifier = Modifier.size(48.dp)) {
        Icon(if (previous) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
            if (previous) "上一个回答" else "下一个回答",
            Modifier.size(21.dp).appleSpringPressable(pressedScale = .9f, enabled = enabled,
                enableHaptics = false, interactionSource = interaction),
            tint = if (enabled) Ink else MutedInk.copy(alpha = .35f))
    }
}
