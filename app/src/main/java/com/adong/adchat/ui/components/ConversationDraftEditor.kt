package com.adong.adchat.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.ui.theme.*

/** A live view of the existing draft, never a second copy requiring a save step. */
@Composable
fun ConversationDraftEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    attachments: List<ChatImageAttachment>,
    attachmentLoading: Boolean,
    loading: Boolean,
    configureRequired: Boolean,
    onRemoveImage: (String) -> Unit,
    onDismiss: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    testTag: String
) {
    val motionEnabled = LocalAsterMotionEnabled.current
    var entered by remember { mutableStateOf(!motionEnabled) }
    LaunchedEffect(Unit) { entered = true }
    val scaleX by animateFloatAsState(
        targetValue = if (entered) 1f else 0.92f,
        animationSpec = if (motionEnabled) AsterSpring.releaseHorizontal() else snap(),
        label = "draft-editor-scale-x"
    )
    val scaleY by animateFloatAsState(
        targetValue = if (entered) 1f else 0.84f,
        animationSpec = if (motionEnabled) AsterSpring.releaseVertical() else snap(),
        label = "draft-editor-scale-y"
    )
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = if (motionEnabled) AsterSpring.editorial() else snap(),
        label = "draft-editor-alpha"
    )

    Dialog(onDismissRequest = { onDismiss(value.copy(composition = null)) }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false
    )) {
        val focus = LocalFocusManager.current
        val requester = remember { FocusRequester() }
        LaunchedEffect(Unit) { requester.requestFocus() }
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = if (motionEnabled) alpha.coerceIn(0f, 1f) else 1f
                    this.scaleX = if (motionEnabled) scaleX else 1f
                    this.scaleY = if (motionEnabled) scaleY else 1f
                    transformOrigin = TransformOrigin(0.5f, 0.92f)
                }
                .background(Canvas)
                .safeDrawingPadding()
                .imePadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AsterIconButton(Icons.Rounded.CloseFullscreen, "收起草稿", {
                    // Dismiss with the selection before CoreTextField collapses it on blur.
                    onDismiss(value.copy(composition = null))
                })
                Text("编辑草稿", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleMedium)
                AsterPillButton(
                    text = if (loading) "停止生成" else if (configureRequired) "选择模型" else "发送",
                    primary = true,
                    compact = true,
                    enabled = loading || (!attachmentLoading &&
                        (value.text.isNotBlank() || attachments.isNotEmpty() || configureRequired)),
                    onClick = {
                        if (loading) onStop() else { focus.clearFocus(); onSend() }
                    }
                )
            }
            HorizontalDivider(color = Hairline)
            Surface(
                color = Surface,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Hairline),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 18.dp)
                ) {
                    BasicTextField(value = value, onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)
                            .focusRequester(requester).testTag("$testTag-expanded-input"),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                        cursorBrush = SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default),
                        decorationBox = { field ->
                            Box {
                                if (value.text.isEmpty()) Text("慢慢写，把想法完整展开…", color = MutedInk,
                                    style = MaterialTheme.typography.bodyLarge)
                                field()
                            }
                        })
                }
            }
            if (attachments.isNotEmpty()) {
                ConversationAttachmentPreview(attachments, attachmentLoading, onRemoveImage)
            }
            HorizontalDivider(color = Hairline)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${value.text.codePointCount(0, value.text.length)} 字", color = MutedInk,
                    style = MaterialTheme.typography.labelMedium)
                Text(if (attachmentLoading) "正在导入附件…" else "收起后保留草稿", color = MutedInk,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
