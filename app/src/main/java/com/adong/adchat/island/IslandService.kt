package com.adong.adchat.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：持有隐形灵动岛悬浮窗。
 * - 由设置开关启用/停用；启用后胶囊常驻挖孔位置；
 * - 用户复制链接后点一下胶囊 → 读剪贴板 → 展开询问卡片；
 * - [解析] 通过显式 Intent 唤起 MainActivity，路由到下载页并自动填入链接。
 */
class IslandService : Service(), IslandView.Callbacks {

    companion object {
        const val ACTION_START = "com.adong.adchat.island.START"
        const val ACTION_STOP = "com.adong.adchat.island.STOP"
        private const val CHANNEL_ID = "link_island"
        private const val NOTIFICATION_ID = 9301

        /** 启用隐形岛（设置开关 → 已授权）。 */
        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, IslandService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, IslandService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var islandView: IslandView? = null
    private var pendingLink: IslandLink? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                islandView?.destroy()
                islandView = null
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startAsForeground()
                obtainView().attach()
            }
        }
        return START_STICKY
    }

    private fun expandShortLink(link: IslandLink) {
        if (!IslandLinkParser.isShortLink(link.originalUrl)) return
        scope.launch {
            val expanded = IslandLinkResolver.expand(link.originalUrl)
            if (expanded != null) {
                IslandLinkParser.resolveExpanded(link, expanded)?.let { resolved ->
                    pendingLink = resolved
                    islandView?.show(resolved)
                }
            }
        }
    }

    private fun obtainView(): IslandView {
        islandView?.let { return it }
        return IslandView(this, this).also { islandView = it }
    }

    // ---------- IslandView.Callbacks ----------

    override fun onParseRequested(link: IslandLink) {
        // 显式唤起 MainActivity，走已有的分享路由：下载页自动填入链接
        val intent = Intent(this, com.adong.adchat.MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link.cleanedUrl.ifBlank { link.originalUrl })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
        islandView?.collapse()
    }

    override fun onLinkShown(link: IslandLink) {
        expandShortLink(link)
    }

    override fun onDismissed() {
        updateIdleNotification()
    }

    override fun onDestroy() {
        islandView?.destroy()
        islandView = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun startAsForeground() {
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("链接岛待命中"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("链接岛待命中"))
        }
    }

    private fun updateIdleNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification("链接岛已收起"))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Aster 链接岛")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "链接岛", NotificationManager.IMPORTANCE_MIN).apply {
                description = "维持链接岛悬浮窗的常驻服务"
            }
        )
    }
}
