package com.adong.adchat.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.adong.adchat.data.ApiProfile
import com.adong.adchat.data.story.StoryCharacterCard
import com.adong.adchat.data.story.StorySetupPhase
import com.adong.adchat.ui.theme.Danger
import com.adong.adchat.ui.theme.Ink
import com.adong.adchat.ui.theme.MutedInk
import com.adong.adchat.ui.theme.Surface

@Composable
fun StorySetupBar(
    phase: StorySetupPhase,
    charCardJson: String,
    userPersona: String,
    identity: String,
    busy: Boolean,
    error: String?,
    profile: ApiProfile?,
    onIdentity: (String) -> Unit,
    onEndDiscussion: (ApiProfile) -> Unit,
    onConfirmChar: (StoryCharacterCard) -> Unit,
    onRegenerateChar: (ApiProfile) -> Unit,
    onSubmitIdentity: (ApiProfile) -> Unit,
    onConfirmPersona: (String) -> Unit,
    onRegeneratePersona: (ApiProfile) -> Unit
) {
    if (phase == StorySetupPhase.Prose || profile == null) return
    val storedCard = StoryCharacterCard.fromStored(charCardJson)
    var name by rememberSaveable(charCardJson) { mutableStateOf(storedCard?.name.orEmpty()) }
    var description by rememberSaveable(charCardJson) { mutableStateOf(storedCard?.description.orEmpty()) }
    var personality by rememberSaveable(charCardJson) { mutableStateOf(storedCard?.personality.orEmpty()) }
    var scenario by rememberSaveable(charCardJson) { mutableStateOf(storedCard?.scenario.orEmpty()) }
    var persona by rememberSaveable(userPersona) { mutableStateOf(userPersona) }
    Surface(color = Surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (phase) {
                    StorySetupPhase.Discussion -> "先把故事设定谈清楚"
                    StorySetupPhase.CharReview -> "确认对方的角色卡"
                    StorySetupPhase.Identity -> "你要在故事里担任谁"
                    StorySetupPhase.PersonaReview -> "确认你的身份卡"
                    StorySetupPhase.Prose -> ""
                },
                style = MaterialTheme.typography.titleSmall,
                color = Ink
            )
            error?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodySmall) }
            when (phase) {
                StorySetupPhase.Discussion -> {
                    Text("结束后会生成对方的角色卡：人是人，这部同人写在「这部故事」里。", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onEndDiscussion(profile) }, enabled = !busy) { Text(if (busy) "正在整理…" else "结束讨论") }
                }
                StorySetupPhase.CharReview -> {
                    Text("滑到底下可以改。「这部故事」才是同人前提，不要只留人物介绍。", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                    SetupFields {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("名字") }, singleLine = true)
                        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("这个人") }, minLines = 3)
                        OutlinedTextField(personality, { personality = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("性格") }, minLines = 2)
                        OutlinedTextField(scenario, { scenario = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("这部故事") }, minLines = 3)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onConfirmChar(StoryCharacterCard(name.trim(), description.trim(), personality.trim(), scenario.trim())) },
                            enabled = !busy && name.isNotBlank() && description.isNotBlank() && scenario.isNotBlank()
                        ) { Text("确认角色卡") }
                        OutlinedButton(onClick = { onRegenerateChar(profile) }, enabled = !busy) { Text("重新生成") }
                    }
                }
                StorySetupPhase.Identity -> {
                    SetupFields {
                        OutlinedTextField(identity, onIdentity, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("你的身份") }, minLines = 2)
                    }
                    Button(onClick = { onSubmitIdentity(profile) }, enabled = !busy && identity.isNotBlank()) {
                        Text(if (busy) "正在整理…" else "生成身份卡")
                    }
                }
                StorySetupPhase.PersonaReview -> {
                    SetupFields {
                        OutlinedTextField(persona, { persona = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("身份卡") }, minLines = 4)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onConfirmPersona(persona) }, enabled = !busy && persona.isNotBlank()) { Text("进入正文") }
                        OutlinedButton(onClick = { onRegeneratePersona(profile) }, enabled = !busy) { Text("重新生成") }
                    }
                }
                StorySetupPhase.Prose -> Unit
            }
        }
    }
}

/**
 * Vertical drag gesture for the collapsible setup dock: swipe up expands,
 * swipe down collapses, once the accumulated drag passes [thresholdPx].
 * Attach to the whole dock (setup bar + composer) so the gesture works
 * anywhere inside it; children that consume drags (scrollable forms,
 * text fields) naturally take precedence.
 */
fun Modifier.setupDockVerticalSwipe(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    thresholdPx: Float
): Modifier = pointerInput(expanded) {
    var accumulated = 0f
    detectVerticalDragGestures(
        onDragStart = { accumulated = 0f },
        onDragEnd = { accumulated = 0f },
        onDragCancel = { accumulated = 0f },
        onVerticalDrag = { change, amount ->
            change.consume()
            accumulated += amount
            if (!expanded && accumulated <= -thresholdPx) {
                accumulated = 0f
                onExpandedChange(true)
            } else if (expanded && accumulated >= thresholdPx) {
                accumulated = 0f
                onExpandedChange(false)
            }
        }
    )
}

@Composable
fun CollapsibleSetupDock(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    busy: Boolean,
    error: String?,
    hint: String,
    gestureEnabled: Boolean,
    swipeModifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (!gestureEnabled) {
        Box { content() }
        return
    }
    Box(Modifier.fillMaxWidth().then(swipeModifier)) {
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            content()
        }
        AnimatedVisibility(
            visible = !expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                color = Surface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                    .clickable { onExpandedChange(true) }
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (error != null) {
                        Text(error, color = Danger, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    } else {
                        Text(
                            if (busy) "正在整理…" else hint,
                            color = MutedInk,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Icon(Icons.Rounded.KeyboardArrowUp, null, Modifier.size(20.dp), tint = MutedInk)
                }
            }
        }
    }
}

@Composable
private fun SetupFields(content: @Composable ColumnScope.() -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.34f).dp.coerceIn(180.dp, 340.dp)
    Column(
        Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}
