package com.adong.adchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
    onDismiss: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    testTag: String
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false
    )) {
        val focus = LocalFocusManager.current
        val requester = remember { FocusRequester() }
        LaunchedEffect(Unit) { requester.requestFocus() }
        Column(Modifier.fillMaxSize().background(Canvas).safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AsterIconButton(Icons.Rounded.CloseFullscreen, "收起草稿", {
                    focus.clearFocus(); onDismiss()
                })
                Text("编辑草稿", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    if (loading) onStop() else { focus.clearFocus(); onSend() }
                }, enabled = loading || (!attachmentLoading &&
                    (value.text.isNotBlank() || attachments.isNotEmpty() || configureRequired))) {
                    Text(if (loading) "停止生成" else if (configureRequired) "选择模型" else "发送")
                }
            }
            HorizontalDivider(color = Hairline)
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()).padding(vertical = 20.dp)) {
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
