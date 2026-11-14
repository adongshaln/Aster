package com.adong.adchat.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.adong.adchat.data.*
import com.adong.adchat.ui.theme.*
import java.io.File

/** The same compact paper cards represent draft and sent context. */
@Composable
fun ConversationContextCards(
    documents: List<ChatDocumentAttachment>,
    quotes: List<ChatQuote>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onRemoveDocument: ((String) -> Unit)? = null,
    onRemoveQuote: ((String) -> Unit)? = null
) {
    val motion = LocalAsterMotionEnabled.current
    AnimatedContent(
        targetState = ChatComposerContext(documents.toList(), quotes.toList()),
        modifier = modifier,
        transitionSpec = {
            (if (motion) fadeIn(AsterSpring.editorial()) togetherWith fadeOut(AsterSpring.editorial())
            else EnterTransition.None togetherWith ExitTransition.None).using(
                SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> if (motion) AsterSpring.spatial() else snap() })
            )
        }, label = "composer-context"
    ) { context ->
        if (!context.isEmpty) {
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                context.quotes.forEach { quote ->
                    ContextCard(Icons.Rounded.FormatQuote,
                        "引用${if (quote.role == "user") "提问" else "回复"}${if (quote.partial) " · 片段" else ""}",
                        quote.text, "quote-${quote.id}", enabled,
                        onRemoveQuote?.let { remove -> { remove(quote.id) } })
                }
                context.documents.forEach { file ->
                    val androidContext = LocalContext.current
                    var openError by remember(file.id) { mutableStateOf(false) }
                    ContextCard(Icons.Rounded.Description, file.name,
                        if (openError) "无法打开文件，请检查文件查看器" else
                            "${file.name.substringAfterLast('.', "文件").uppercase()} · ${fileSizeLabel(file.size)}",
                        "document-${file.id}", enabled,
                        onRemoveDocument?.let { remove -> { remove(file.id) } },
                        onOpen = {
                            openError = runCatching {
                                val uri = FileProvider.getUriForFile(androidContext,
                                    "${androidContext.packageName}.files", File(Uri.parse(file.uri).path!!))
                                androidContext.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mimeType)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }.isFailure
                        })
                }
            }
        }
    }
}

private fun fileSizeLabel(size: Long): String = when {
    size >= 1024 * 1024 -> "${String.format(java.util.Locale.ROOT, "%.1f", size / 1048576.0)} MB"
    size >= 1024 -> "${(size + 1023) / 1024} KB"
    else -> "$size B"
}

@Composable
private fun ContextCard(
    icon: ImageVector, title: String, detail: String, tag: String, enabled: Boolean,
    onRemove: (() -> Unit)?, onOpen: (() -> Unit)? = null
) {
    val body: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = 12.dp).width(2.dp).height(30.dp).background(Accent.copy(alpha = 0.4f)))
            Icon(icon, null, Modifier.padding(start = 9.dp).size(17.dp), tint = MutedInk)
            Column(Modifier.weight(1f).padding(horizontal = 9.dp, vertical = 10.dp)) {
                Text(title, color = Ink, style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail.replace('\n', ' '), color = MutedInk, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onRemove != null) ContextRemoveButton("移除$title", enabled, onRemove)
        }
    }
    if (onOpen == null) Surface(color = SurfaceInset, shape = AsterShape.control, content = body)
    else Surface(onClick = rememberAsterClick(onOpen, enabled), enabled = enabled,
        color = SurfaceInset, shape = AsterShape.control, content = body)
}

/** Keep a 48dp target while the visible paper button remains small. */
@Composable
internal fun ContextRemoveButton(description: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    IconButton(onClick = rememberAsterClick(onClick, enabled), enabled = enabled,
        interactionSource = interaction, modifier = modifier.size(48.dp)) {
        Surface(color = Surface, contentColor = MutedInk, shape = CircleShape,
            border = BorderStroke(0.6.dp, Hairline),
            modifier = Modifier.size(24.dp).appleSpringPressable(pressedScale = 0.90f,
                enabled = enabled, enableHaptics = false, interactionSource = interaction)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, description, Modifier.size(14.dp))
            }
        }
    }
}
