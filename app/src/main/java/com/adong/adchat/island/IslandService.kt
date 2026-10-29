package com.adong.adchat.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.View
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.adong.adchat.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

/** User-triggered clipboard tools, with requests isolated from ordinary conversations. */
class IslandService : Service(), IslandView.Callbacks {
    companion object {
        const val ACTION_START = "com.adong.adchat.island.START"
        const val ACTION_STOP = "com.adong.adchat.island.STOP"
        const val ACTION_OPEN_SETTINGS = "com.adong.adchat.island.OPEN_SETTINGS"
        const val ACTION_THEME_CHANGED = "com.adong.adchat.island.THEME_CHANGED"
        const val EXTRA_THEME_NIGHT = "com.adong.adchat.island.THEME_NIGHT"
        const val EXTRA_THEME_FOLLOW_SYSTEM = "com.adong.adchat.island.THEME_FOLLOW_SYSTEM"
        // Keep the existing channel and preference IDs so upgrades retain the user's choices.
        private const val CHANNEL_ID = "link_island"
        private const val NOTIFICATION_ID = 9301
        private const val IDLE_TEXT = "点按墨线圈，处理文字或链接"

        fun start(context: Context, night: Boolean? = null, followSystem: Boolean = false) {
            if (!IslandPermissions.isEnabled(context) || !IslandPermissions.isReady(context)) return
            val intent = Intent(context, IslandService::class.java).setAction(ACTION_START)
            if (night != null) {
                intent.putExtra(EXTRA_THEME_NIGHT, night)
                    .putExtra(EXTRA_THEME_FOLLOW_SYSTEM, followSystem)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IslandService::class.java))
        }

        /** Synchronise Aster's in-app theme with the separately hosted overlay. */
        fun syncTheme(context: Context, night: Boolean, followSystem: Boolean) {
            if (!IslandPermissions.isEnabled(context) || !IslandPermissions.isReady(context)) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, IslandService::class.java)
                    .setAction(ACTION_THEME_CHANGED)
                    .putExtra(EXTRA_THEME_NIGHT, night)
                    .putExtra(EXTRA_THEME_FOLLOW_SYSTEM, followSystem)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runner by lazy { StarIslandTaskRunner(applicationContext) }
    private var islandView: IslandView? = null
    private var resolveJob: Job? = null
    private var linkGeneration = 0L
    private var taskJob: Job? = null
    private var taskGeneration = 0L
    private var stopRequestedGeneration: Long? = null
    private var currentTask: StarIslandTaskState? = null
    private var themeNightOverride: Boolean? = null
    private var viewConfiguration: Configuration? = null
    private var restoringView = false
    private var shuttingDown = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateOverlayVisibility()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            IslandTouchService.connectedHost.collectLatest {
                if (islandView != null) recreateView()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            IslandPermissions.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!IslandPermissions.isEnabled(this) || !IslandPermissions.isReady(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // A sticky restart restores only the entry point; it never repeats a model request.
        startAsForeground()
        val view = obtainView()
        view.attach()
        if (intent?.hasExtra(EXTRA_THEME_NIGHT) == true) {
            val night = intent.getBooleanExtra(EXTRA_THEME_NIGHT, false)
            val followSystem = intent.getBooleanExtra(EXTRA_THEME_FOLLOW_SYSTEM, false)
            themeNightOverride = night.takeUnless { followSystem }
            view.setNightMode(night, followSystem)
        }
        return START_STICKY
    }

    private fun obtainView(): IslandView {
        islandView?.let { return it }
        val touchHost = IslandTouchService.connectedHost.value
        return IslandView(touchHost ?: this, this, accessibilityOverlay = touchHost != null).also {
            islandView = it
            viewConfiguration = Configuration(resources.configuration)
            themeNightOverride?.let(it::setNightMode)
            updateOverlayVisibility()
        }
    }

    private fun updateOverlayVisibility() {
        val locked = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val interactive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        // Accessibility overlays sit above the lock screen too. Keep clipboard/results private.
        islandView?.visibility = if (IslandTouchService.connectedHost.value != null && (locked || !interactive))
            View.GONE else View.VISIBLE
    }

    private fun expandShortLink(content: StarIslandContent) {
        val link = content.mediaLink ?: return
        if (!IslandLinkParser.isShortLink(link.originalUrl)) return
        val generation = linkGeneration
        resolveJob = scope.launch {
            val expanded = IslandLinkResolver.expand(link.originalUrl) ?: return@launch
            if (generation != linkGeneration || islandView?.displayedContent?.key != content.key) return@launch
            IslandLinkParser.resolveExpanded(link, expanded)?.let { islandView?.updateCard(it) }
        }
    }

    private fun invalidateLinkResolution() {
        linkGeneration++
        resolveJob?.cancel()
        resolveJob = null
    }

    private fun invalidateTask() {
        taskGeneration++
        stopRequestedGeneration = null
        taskJob?.cancel()
        taskJob = null
        currentTask = null
    }

    override fun onContentShown(content: StarIslandContent) {
        if (restoringView) return
        invalidateTask()
        invalidateLinkResolution()
        notifyStatus(IDLE_TEXT)
        expandShortLink(content)
    }

    override fun onLinkShown(link: IslandLink) {
        onContentShown(StarIslandContent(link.originalUrl, link.originalUrl, link))
    }

    override fun onActionRequested(content: StarIslandContent, action: StarIslandAction) {
        if (islandView?.displayedContent?.key != content.key) return
        when (action) {
            StarIslandAction.Download -> content.mediaLink?.let(::onParseRequested)
            StarIslandAction.OpenLink -> content.url?.let { url ->
                val uri = Uri.parse(url)
                if (uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                    openActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                }
            }
            StarIslandAction.CopyLink -> (content.mediaLink?.let { it.cleanedUrl.ifBlank { it.originalUrl } }
                ?: content.url)?.let { copyText(it, "链接已复制") }
            StarIslandAction.Translate, StarIslandAction.Polish, StarIslandAction.Explain ->
                if (content.kind == StarIslandKind.Text) startTask(content, action)
            StarIslandAction.ReadWeb -> if (content.kind == StarIslandKind.Web) startTask(content, action)
        }
    }

    private fun startTask(content: StarIslandContent, action: StarIslandAction) {
        if (currentTask?.phase == StarIslandPhase.Working) return
        invalidateTask()
        invalidateLinkResolution()
        val generation = taskGeneration
        publishTask(StarIslandTaskState(action, StarIslandPhase.Working, detail = "正在准备${action.label}"))
        taskJob = scope.launch {
            try {
                runner.run(content, action) { state ->
                    if (generation == taskGeneration) {
                        publishTask(if (stopRequestedGeneration == generation) state.stopped() else state)
                    }
                }
            } catch (error: CancellationException) {
                if (generation == taskGeneration && stopRequestedGeneration == generation) {
                    currentTask?.let { publishTask(it.stopped()) }
                }
                throw error
            } finally {
                if (generation == taskGeneration) taskJob = null
            }
        }
    }

    private fun StarIslandTaskState.stopped() = copy(
        phase = StarIslandPhase.Stopped,
        detail = if (output.isBlank()) "已停止" else "已停止，已保留收到的内容",
    )

    private fun publishTask(state: StarIslandTaskState) {
        val previous = currentTask
        currentTask = state
        islandView?.updateTask(state)
        if (previous?.phase != state.phase || previous?.action != state.action) {
            notifyStatus(if (state.phase == StarIslandPhase.Working) "正在${state.action.label}" else "${state.action.label} · 点按星岛查看")
        }
    }

    override fun onStopRequested() {
        val task = currentTask ?: return
        if (task.phase != StarIslandPhase.Working) return
        // Keep this generation alive until the runner flushes its final throttled fragment.
        stopRequestedGeneration = taskGeneration
        taskJob?.cancel()
        publishTask(task.stopped())
    }

    override fun onBackRequested() {
        invalidateTask()
        islandView?.clearTask()
        notifyStatus(IDLE_TEXT)
    }

    override fun onCopyRequested() {
        currentTask?.output?.takeIf { it.isNotBlank() }?.let { copyText(it, "结果已复制") }
    }

    private fun copyText(text: String, confirmation: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("星岛", text))
        Toast.makeText(this, confirmation, Toast.LENGTH_SHORT).show()
    }

    override fun onConfigureRequested() {
        openActivity(Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_SETTINGS))
    }

    override fun onParseRequested(link: IslandLink) {
        openActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link.cleanedUrl.ifBlank { link.originalUrl })
        })
    }

    private fun openActivity(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            islandView?.collapse()
        } catch (_: Exception) {
            Toast.makeText(this, "暂时无法打开，请稍后再试", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDismissed() {
        if (restoringView || shuttingDown) return
        invalidateTask()
        invalidateLinkResolution()
        notifyStatus(IDLE_TEXT)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val previous = viewConfiguration
        viewConfiguration = Configuration(newConfig)
        if (previous == null || (previous.densityDpi == newConfig.densityDpi &&
                previous.fontScale == newConfig.fontScale && previous.locales == newConfig.locales &&
                previous.orientation == newConfig.orientation &&
                IslandPalette.isNight(previous) == IslandPalette.isNight(newConfig))) return
        recreateView()
    }

    /** Swap window hosts without restarting a model task or dropping its visible output. */
    private fun recreateView() {
        if (shuttingDown) return
        val old = islandView ?: return
        val content = old.displayedContent
        val task = currentTask
        restoringView = true
        try {
            old.destroy()
            islandView = null
            obtainView().also { replacement ->
                replacement.attach()
                if (content != null) {
                    replacement.showContent(content)
                    task?.let(replacement::updateTask)
                }
            }
        } finally {
            restoringView = false
        }
    }

    override fun onDestroy() {
        shuttingDown = true
        unregisterReceiver(screenReceiver)
        invalidateTask()
        invalidateLinkResolution()
        islandView?.destroy()
        islandView = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startAsForeground() {
        createChannel()
        val notification = buildNotification(currentTask?.takeIf { it.phase == StarIslandPhase.Working }
            ?.let { "正在${it.action.label}" } ?: IDLE_TEXT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyStatus(text: String) {
        if (shuttingDown) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, IslandService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Aster · 星岛")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭星岛", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "星岛", NotificationManager.IMPORTANCE_MIN).apply {
                description = "维持星岛入口与用户主动发起的内容处理"
            }
        )
    }
}
