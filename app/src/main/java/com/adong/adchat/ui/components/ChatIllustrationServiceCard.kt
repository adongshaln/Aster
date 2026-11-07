package com.adong.adchat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adong.adchat.data.ApiProfile
import com.adong.adchat.data.CHAT_ILLUSTRATION_MODEL
import com.adong.adchat.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatIllustrationServiceCard(
    profile: ApiProfile?,
    profiles: List<ApiProfile>,
    onSelect: (String) -> Unit,
    onEdit: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showServices by remember { mutableStateOf(false) }
    Surface(color = Surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(0.8.dp, Hairline),
        modifier = modifier.fillMaxWidth().testTag("illustration-service-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("剧情插图服务", color = Ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("独立选择绘图服务，画面提炼仍使用该轮对话模型。", color = MutedInk, style = MaterialTheme.typography.bodySmall)
            Surface(onClick = { showServices = true }, color = Canvas, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("illustration-service-select")) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Dns, null, Modifier.size(20.dp), tint = Accent)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile?.name ?: "选择服务", color = Ink, style = MaterialTheme.typography.bodyMedium)
                        Text(profile?.baseUrl?.takeIf { it.isNotBlank() } ?: "尚未配置地址与密钥",
                            color = MutedInk, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("切换", color = Accent, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("模型 · $CHAT_ILLUSTRATION_MODEL", color = MutedInk, style = MaterialTheme.typography.labelMedium)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile != null) TextButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("编辑服务")
                }
                TextButton(onClick = onCreate, modifier = Modifier.testTag("illustration-service-create")) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建专用服务")
                }
            }
        }
    }
    if (showServices) AdSelectionSheet(
        title = "选择剧情插图服务",
        subtitle = "仅切换剧情插图使用的服务",
        options = profiles.map {
            AdChoiceOption(it.id, it.name, it.baseUrl, Icons.Rounded.Dns, if (it.id == profile?.id) "当前" else null)
        },
        selectedId = profile?.id.orEmpty(),
        onSelect = { onSelect(it.id); showServices = false },
        onDismiss = { showServices = false },
        searchPlaceholder = "搜索服务名称或地址",
        headerIcon = Icons.Rounded.Dns
    )
}
