package com.adong.adchat.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adong.adchat.ui.theme.Accent
import com.adong.adchat.ui.theme.AccentSoft
import com.adong.adchat.ui.theme.Canvas
import com.adong.adchat.ui.theme.Hairline
import com.adong.adchat.ui.theme.Ink

/**
 * 首启询问称呼。仅在 userNamePromptDone 为 false 时由入口挂载一次；
 * 填写或跳过都会把标记写进配置，不再打扰。
 */
@Composable
fun UserNamePromptDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onSkip: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    val trimmed = name.trim()
    AdModalDialog(
        title = "该怎么称呼您？",
        subtitle = "助手会用这个称呼您；可以随时在设置的助手偏好中修改",
        icon = Icons.Rounded.Badge,
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
            ) { Text("跳过") }
            Button(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotBlank(),
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("就叫我这个", fontWeight = FontWeight.SemiBold) }
        }
    )
}
