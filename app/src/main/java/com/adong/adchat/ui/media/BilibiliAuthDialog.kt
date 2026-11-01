package com.adong.adchat.ui.media

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adong.adchat.media.BilibiliTvAuthClient
import com.adong.adchat.media.BilibiliTvPollResult
import com.adong.adchat.media.BilibiliTvQrSession
import com.adong.adchat.media.BilibiliTvSession
import com.adong.adchat.ui.components.AdModalDialog
import com.adong.adchat.ui.components.AsterLoadingIndicator
import com.adong.adchat.ui.components.AsterPillButton
import com.adong.adchat.ui.theme.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun BilibiliAuthDialog(
    currentSession: BilibiliTvSession?,
    onAuthorized: (BilibiliTvSession) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    var switchingAccount by remember { mutableStateOf(false) }
    val activeSession = currentSession?.takeIf { !switchingAccount }

    if (activeSession != null) {
        AdModalDialog(
            title = "B 站高画质无水印",
            subtitle = "已通过官方 TV 接口解锁纯净原画",
            icon = Icons.Rounded.Hd,
            onDismiss = onDismiss,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        color = Surface,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, Hairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Sage, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    activeSession.uname.ifBlank { "已授权账号" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    activeSession.vipLabel.ifBlank { "已解锁 1080P 无水印" },
                                    color = Accent,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    Text(
                        "普通账号可下载 1080P 无水印，大会员账号自动解锁 1080P60 / 1080P+ / 4K 无水印。授权凭证采用 Android Keystore 硬件加密，仅保存在本机。",
                        color = MutedInk,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            actions = {
                AsterPillButton(
                    text = "退出绑定",
                    destructive = true,
                    onClick = {
                        onLogout()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                )
                AsterPillButton(
                    text = "切换账号",
                    onClick = { switchingAccount = true },
                    modifier = Modifier.weight(1f).height(48.dp)
                )
            }
        )
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val authClient = remember { BilibiliTvAuthClient() }
    var refreshKey by remember { mutableIntStateOf(0) }
    var resumeTrigger by remember { mutableIntStateOf(0) }
    var qrSession by remember { mutableStateOf<BilibiliTvQrSession?>(null) }
    var loadingQr by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("正在申请官方授权码…") }
    var expired by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTrigger += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshKey) {
        loadingQr = true
        expired = false
        errorText = null
        qrSession = null
        statusText = "正在申请官方授权码…"
        try {
            val created = authClient.requestQrCode()
            qrSession = created
            loadingQr = false
            statusText = "可一键跳转本机哔哩哔哩确认，或扫码授权"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadingQr = false
            errorText = error.message ?: "无法连接 B 站授权服务"
        }
    }

    // Immediate poll when returning from the Bilibili app + steady background polling
    LaunchedEffect(qrSession?.authCode, resumeTrigger) {
        val code = qrSession?.authCode ?: return@LaunchedEffect
        if (expired) return@LaunchedEffect
        while (!expired) {
            try {
                when (val result = authClient.pollQrCode(code)) {
                    BilibiliTvPollResult.WaitingScan -> Unit
                    BilibiliTvPollResult.ScannedWaitingConfirm -> {
                        statusText = "已识别，请在哔哩哔哩点击「确认登录」"
                    }
                    BilibiliTvPollResult.Expired -> {
                        expired = true
                        statusText = "授权码已过期，请点击刷新"
                    }
                    is BilibiliTvPollResult.Authorized -> {
                        onAuthorized(result.session)
                        onDismiss()
                        return@LaunchedEffect
                    }
                    is BilibiliTvPollResult.Failed -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep polling across transient network hiccups while switching apps
            }
            delay(1800L)
        }
    }

    AdModalDialog(
        title = "解锁 1080P / 4K 无水印",
        subtitle = "普通账号解锁 1080P · 大会员解锁 4K / 1080P60",
        icon = Icons.Rounded.Hd,
        onDismiss = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, Hairline),
                    modifier = Modifier.size(196.dp)
                ) {
                    Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
                        when {
                            loadingQr -> AsterLoadingIndicator(size = 28.dp, tint = Accent)
                            errorText != null -> Text(
                                errorText.orEmpty(),
                                color = Danger,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                            qrSession != null -> PaperQrCode(
                                content = qrSession!!.authUrl,
                                dimmed = expired,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                Text(
                    statusText,
                    color = if (expired || errorText != null) Danger else MutedInk,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    "凭证采用 Android Keystore 硬件加密仅存于本机，随时可退出。",
                    color = MutedInk.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
            }
        },
        actions = {
            if (expired || errorText != null) {
                AsterPillButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(48.dp)
                )
                AsterPillButton(
                    text = "刷新授权码",
                    icon = Icons.Rounded.Refresh,
                    primary = true,
                    onClick = { refreshKey += 1 },
                    modifier = Modifier.weight(1f).height(48.dp)
                )
            } else {
                AsterPillButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.36f).height(48.dp)
                )
                AsterPillButton(
                    text = "打开哔哩哔哩确认",
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    primary = true,
                    enabled = qrSession != null && !loadingQr,
                    onClick = {
                        val url = qrSession?.authUrl ?: return@AsterPillButton
                        openBilibiliConfirmation(context, url)
                    },
                    modifier = Modifier.weight(0.64f).height(48.dp)
                )
            }
        }
    )
}

private fun openBilibiliConfirmation(context: android.content.Context, authUrl: String) {
    val encoded = Uri.encode(authUrl)
    val candidates = listOf(
        Uri.parse("bilibili://browser/?url=$encoded"),
        Uri.parse("bilibili://browser?url=$encoded"),
        Uri.parse(authUrl)
    )
    for (uri in candidates) {
        val launched = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        if (launched) return
    }
}

@Composable
private fun PaperQrCode(
    content: String,
    dimmed: Boolean,
    modifier: Modifier = Modifier
) {
    val matrix = remember(content) {
        runCatching {
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(
                    EncodeHintType.MARGIN to 0,
                    EncodeHintType.CHARACTER_SET to "UTF-8"
                )
            )
        }.getOrNull()
    }
    if (matrix == null) return
    val inkColor = Color(0xFF141413).copy(alpha = if (dimmed) 0.25f else 1f)
    Canvas(modifier) {
        val cols = matrix.width.coerceAtLeast(1)
        val rows = matrix.height.coerceAtLeast(1)
        val cell = minOf(size.width / cols, size.height / rows)
        val offsetX = (size.width - cell * cols) / 2f
        val offsetY = (size.height - cell * rows) / 2f
        val dotSize = cell * 0.92f
        val inset = (cell - dotSize) / 2f
        val radius = CornerRadius(cell * 0.24f, cell * 0.24f)
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if (matrix.get(x, y)) {
                    drawRoundRect(
                        color = inkColor,
                        topLeft = Offset(offsetX + x * cell + inset, offsetY + y * cell + inset),
                        size = Size(dotSize, dotSize),
                        cornerRadius = radius
                    )
                }
            }
        }
    }
}
