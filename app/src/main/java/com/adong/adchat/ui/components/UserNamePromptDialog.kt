package com.adong.adchat.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.adong.adchat.ui.theme.Accent
import com.adong.adchat.ui.theme.AccentSoft
import com.adong.adchat.ui.theme.Canvas
import com.adong.adchat.ui.theme.Hairline
import com.adong.adchat.ui.theme.Ink

/** 称呼弹窗的使用场景：首启询问与设置编辑共用一套外观，但文案与按钮语义不同。 */
enum class UserNamePromptMode { Onboarding, Edit }

/**
 * 称呼编辑弹窗。
 * - [UserNamePromptMode.Onboarding]：首启一次性询问；跳过或关闭都由调用方标记完成，之后不再打扰。
 * - [UserNamePromptMode.Edit]：设置内编辑；取消只关闭、不写任何配置，允许留空保存以清除称呼。
 */
@Composable
fun UserNamePromptDialog(
    mode: UserNamePromptMode,
    currentName: String,
    onConfirm: (String) -> Unit,
    onSkip: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    val trimmed = name.trim()

    val title = if (mode == UserNamePromptMode.Onboarding) "该怎么称呼您？" else "我的称呼"
    val subtitle = if (mode == UserNamePromptMode.Onboarding)
        "助手会用这个称呼您；也可以先跳过，随时能在 设置 → 助手偏好 里补充"
    else
        "助手会在对话中这样称呼您；留空保存即清除"
    val skipLabel = if (mode == UserNamePromptMode.Onboarding) "稍后再说" else "取消"
    val confirmLabel = if (mode == UserNamePromptMode.Onboarding) "就叫我这个" else "保存"
    val confirmEnabled = if (mode == UserNamePromptMode.Onboarding) trimmed.isNotBlank() else true

    val confirm: () -> Unit = { onConfirm(trimmed) }

    AdModalDialog(
        title = title,
        subtitle = subtitle,
        icon = Icons.Rounded.AutoAwesome,
        iconTint = Accent,
        iconContainerColor = AccentSoft,
        onDismiss = onSkip,
        content = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                placeholder = { Text("例如：小明") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (confirmEnabled) confirm() }),
                shape = RoundedCornerShape(15.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ink, unfocusedBorderColor = Hairline, focusedContainerColor = Canvas,
                    unfocusedContainerColor = Canvas, cursorColor = Accent
                )
            )
        },
        actions = {
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
            ) { Text(skipLabel) }
            Button(
                onClick = confirm,
                enabled = confirmEnabled,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text(confirmLabel, fontWeight = FontWeight.SemiBold) }
        }
    )
}
