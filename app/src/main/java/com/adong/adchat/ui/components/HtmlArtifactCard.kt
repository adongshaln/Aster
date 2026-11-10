package com.adong.adchat.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adong.adchat.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Used by fenced HTML in both workspaces and by create_file attachments. */
@Composable
internal fun HtmlArtifactCard(
    code: String,
    filename: String = "document.html",
    ready: Boolean = true,
    allowScripts: Boolean = true,
    actions: Boolean = true,
    panelActions: ((String) -> Unit)? = null,
    adaptiveHeight: Boolean = false,
    helper: HelperHost? = null,
    messageId: Int? = null,
    /** Bump to rebuild the document (e.g. the floor's variables changed). */
    revision: Int = 0,
    /** Stable key for remembering the measured height across recycling (e.g. "messageId:block"). */
    heightKey: String? = null,
    /** Card surfaces may load remote https images/media; other previews stay offline. */
    remoteMedia: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val source = pendingExport ?: code
        pendingExport = null
        if (uri != null) scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use {
                    it.write(source.toByteArray(Charsets.UTF_8))
                }
            } }
            Toast.makeText(context, if (result.isSuccess) "已保存 $filename" else "保存失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }
    val canPreview = ready && code.length <= HtmlPreviewDocument.MAX_PREVIEW_CHARS
    var contentHeightDp by remember(heightKey ?: code) {
        mutableStateOf(heightKey?.let(HtmlHeightCache::get) ?: if (adaptiveHeight) HtmlPreviewDocument.initialHeightDp(code) else 340)
    }
    Column(Modifier.fillMaxWidth()) {
        if (canPreview && !fullscreen) {
            HtmlPreview(
                code,
                Modifier.fillMaxWidth().height((if (adaptiveHeight) contentHeightDp else 340).dp),
                allowScripts,
                panelActions,
                onHeight = if (adaptiveHeight) { { h ->
                    contentHeightDp = h.coerceIn(48, 20000)
                    heightKey?.let { HtmlHeightCache.put(it, contentHeightDp) }
                } } else null,
                helper = helper,
                messageId = messageId,
                revision = revision,
                remoteMedia = remoteMedia
            )
        } else if (fullscreen) {
            // Retain the card's footprint while its engine is owned by the dialog. Keeping a second
            // scripted WebView alive here would run the same helper actions twice.
            Spacer(Modifier.fillMaxWidth().height((if (adaptiveHeight) contentHeightDp else 340).dp))
        } else {
            // 只在真的看不到预览时才占位：能预览时那一行本来就只是重复信息。
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (ready) "文件较大，保存后可用其他应用打开" else "正在生成预览…",
                    color = MutedInk, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(20.dp)
                )
            }
        }
        if (actions) Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(filename, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MutedInk)
            IconButton(onClick = { fullscreen = true }, enabled = canPreview) {
                Icon(Icons.Rounded.OpenInFull, "全屏预览 HTML", tint = MutedInk)
            }
            IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(filename, code))
            }) { Icon(Icons.Rounded.ContentCopy, "复制 HTML 代码", tint = MutedInk, modifier = Modifier.size(18.dp)) }
            AsterPillButton(
                text = "下载 HTML",
                icon = Icons.Rounded.Download,
                compact = true,
                enabled = ready,
                onClick = { pendingExport = code; export.launch(filename) }
            )
        }
    }
    if (fullscreen && canPreview) {
        val motionEnabled = LocalAsterMotionEnabled.current
        var closing by remember { mutableStateOf(false) }
        val progress = remember { Animatable(if (motionEnabled) 0f else 1f) }
        val travelPx = with(LocalDensity.current) { 18.dp.toPx() }
        LaunchedEffect(closing, motionEnabled) {
            val target = if (closing) 0f else 1f
            if (motionEnabled) {
                if (!closing) withFrameNanos { }
                progress.animateTo(target, tween(if (closing) 140 else 240, easing = AsterEase))
            } else progress.snapTo(target)
            if (closing) fullscreen = false
        }
        Dialog(onDismissRequest = { closing = true },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(
                color = Canvas,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = progress.value
                        translationY = (1f - progress.value) * travelPx
                    }
            ) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsterIconButton(Icons.Rounded.Close, "关闭 HTML 预览", { closing = true })
                        Text(
                            filename,
                            Modifier.weight(1f).padding(start = 6.dp),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        AsterPillButton(
                            text = "收起",
                            compact = true,
                            onClick = { closing = true }
                        )
                    }
                    HorizontalDivider(color = Hairline)
                    HtmlPreview(code, Modifier.fillMaxWidth().weight(1f), allowScripts, panelActions, helper = helper, messageId = messageId, revision = revision, remoteMedia = remoteMedia)
                }
            }
        }
    }
}

private data class HtmlPreviewInput(val source: String, val scripts: Boolean, val panelBridge: Boolean, val reportHeight: Boolean, val helper: Boolean, val messageId: Int?, val revision: Int, val remoteMedia: Boolean, val dark: Boolean)

/**
 * Provided by scrolling conversations: while true, previews that are not yet mounted wait instead
 * of creating a WebView. Other surfaces only wait for a free frame in the mount queue.
 */
internal val LocalHtmlPreviewDeferral = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Boolean>?> { null }

/** Last measured card heights (dp), so recycled list items start at their real size. */
internal object HtmlHeightCache {
    private val values = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 512
    }
    @Synchronized fun get(key: String): Int? = values[key]
    @Synchronized fun put(key: String, value: Int) { values[key] = value }
}
private data class PreparedHtmlPreview(val input: HtmlPreviewInput, val html: String)

// Never initialize several Chromium views on the same frame after a fling. Waiting requests are
// cancelled with their list item, and a new drag postpones any work that has not mounted yet.
private val htmlMountMutex = Mutex()

@Composable
private fun HtmlPreview(
    source: String,
    modifier: Modifier,
    allowScripts: Boolean,
    panelActions: ((String) -> Unit)? = null,
    onHeight: ((Int) -> Unit)? = null,
    helper: HelperHost? = null,
    messageId: Int? = null,
    /** Bump to rebuild the document (e.g. the floor's variables changed). */
    revision: Int = 0,
    remoteMedia: Boolean = false
) {
    val context = LocalContext.current
    val viewportPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx().toInt()
    }
    val panelBridge = panelActions != null
    val reportHeight = onHeight != null
    val dark = LocalThemeDark.current
    val input = remember(source, allowScripts, panelBridge, reportHeight, helper != null, messageId, revision, remoteMedia, dark) {
        HtmlPreviewInput(source, allowScripts, panelBridge, reportHeight, allowScripts && helper != null, messageId, revision, remoteMedia, dark)
    }
    val document by produceState<PreparedHtmlPreview?>(null, input) {
        val html = withContext(Dispatchers.Default) {
            HtmlPreviewDocument.wrap(
                input.source, input.scripts,
                // With the helper runtime scripts perform their own actions; text scraping would
                // double-fire (phantom user messages) on every scripted button.
                input.panelBridge && !input.helper,
                input.reportHeight,
                helperBundle = if (input.helper) TavernHelperAssets.bundle(context) else null,
                messageId = input.messageId,
                viewportHeightPx = viewportPx,
                remoteMedia = input.remoteMedia,
                revision = input.revision,
                dark = input.dark
            )
        }
        value = PreparedHtmlPreview(input, html)
    }
    val prepared = document
    // Inside a scrolling list, creating a WebView per card mid-fling is what stutters; show the sized
    // placeholder until the list settles, then mount once (and keep it while the item stays composed).
    val scrolling = LocalHtmlPreviewDeferral.current
    var mounted by remember { mutableStateOf(false) }
    if (!mounted && prepared != null) LaunchedEffect(scrolling) {
        while (!mounted) {
            snapshotFlow { scrolling?.value ?: false }.first { !it }
            htmlMountMutex.withLock {
                withFrameNanos { }
                if (scrolling?.value != true) mounted = true
            }
        }
    }
    if (prepared == null || !mounted) HtmlPreviewPlaceholder(modifier, showProgress = scrolling?.value != true)
    else OfflineArtifactPreview(prepared.html, modifier, allowScripts, baseUrl = "https://aster.local/", panelActions = panelActions?.let { action ->
        { text ->
            // produceState retains the old document while preparing the new source.
            // Its still-visible buttons must not invoke the new document's callback.
            if (prepared.input == input) action(text)
        }
    }, onHeight = onHeight?.let { height ->
        { h -> if (prepared.input == input) height(h) }
    }, helper = helper, remoteMedia = remoteMedia)
}
