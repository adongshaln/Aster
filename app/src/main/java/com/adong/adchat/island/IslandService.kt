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
import com.adong.adchat.data.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
        private const val IDLE_TEXT = "点按处理内容，长按框选屏幕提问"

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
    private var screenSession: ScreenQuestionSession? = null
    private var screenTaskJob: Job? = null
    private var screenTaskGeneration = 0L
    private var screenStopGeneration: Long? = null
    private var captureJob: Job? = null
    private var captureGeneration = 0L
    private var capturing = false
    private var screenSelection: ScreenSelectionView? = null
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
                cancelScreenSelection()
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
        if ((locked || !interactive) && capturing) cancelScreenSelection(restore = false)
        // Accessibility overlays sit above the lock screen too. Keep clipboard/results private.
        islandView?.visibility = if (capturing || (IslandTouchService.connectedHost.value != null && (locked || !interactive)))
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
            StarIslandAction.AskScreen -> Unit // Screen input is accepted only after explicit crop confirmation.
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
        // A screen conversation can be folded away and resumed from the ring without losing it.
        if (screenSession != null) return
        invalidateTask()
        invalidateLinkResolution()
        notifyStatus(IDLE_TEXT)
    }

    override fun onRingTapped(): Boolean {
        val session = screenSession ?: return false
        obtainView().showScreenQuestion(session)
        return true
    }

    override fun onScreenCaptureRequested() {
        if (capturing || shuttingDown) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "屏幕框选需要 Android 11 或更新版本", Toast.LENGTH_LONG).show()
            return
        }
        val host = IslandTouchService.connectedHost.value
        if (host == null) {
            Toast.makeText(this, "请先开启星岛无障碍服务，以便在长按时截取屏幕", Toast.LENGTH_LONG).show()
            openActivity(IslandPermissions.touchCompatibilitySettingsIntent())
            return
        }
        if ((getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return
        val generation = ++captureGeneration
        capturing = true
        islandView?.hideKeyboard()
        islandView?.visibility = View.INVISIBLE
        captureJob = scope.launch {
            try {
                // Let the window compositor remove our ring/card before taking the one-shot image.
                delay(180)
                val screenshot = withTimeout(5_000) { host.captureScreen() }
                if (generation != captureGeneration || shuttingDown) return@launch
                val selector = ScreenSelectionView(host, screenshot,
                    islandView?.islandPalette ?: IslandPalette.forNightMode(IslandPalette.isNight(resources.configuration)),
                    onConfirm = { crop ->
                        captureJob = scope.launch {
                            try {
                                val session = withContext(Dispatchers.Default) { prepareScreenQuestion(screenshot, crop) }
                                if (generation != captureGeneration || shuttingDown) return@launch
                                screenSelection?.destroy()
                                screenSelection = null
                                captureJob = null
                                capturing = false
                                clearScreenQuestion()
                                invalidateTask()
                                invalidateLinkResolution()
                                screenSession = session
                                updateOverlayVisibility()
                                obtainView().showScreenQuestion(session)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                cancelScreenSelection()
                                Toast.makeText(this@IslandService, "无法准备选区，请重新长按框选", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onCancel = { cancelScreenSelection() },
                )
                screenSelection = selector
                check(selector.attach()) { "无法显示选区，请检查星岛无障碍服务" }
                captureJob = null
            } catch (cancelled: kotlinx.coroutines.TimeoutCancellationException) {
                cancelScreenSelection()
                Toast.makeText(this@IslandService, "截屏超时，请重新长按星岛", Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                cancelScreenSelection()
                val message = if (error is SecurityException) "请在系统设置中重新开启星岛无障碍服务，允许主动截屏"
                    else error.message ?: "暂时无法截取屏幕"
                Toast.makeText(this@IslandService, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cancelScreenSelection(restore: Boolean = true) {
        captureGeneration++
        captureJob?.cancel()
        captureJob = null
        screenSelection?.destroy()
        screenSelection = null
        capturing = false
        if (restore) updateOverlayVisibility()
    }

    override fun onScreenDraftChanged(draft: String) {
        screenSession = screenSession?.copy(draft = draft)
    }

    override fun onScreenQuestionRequested(question: String) {
        val session = screenSession ?: return
        if (session.task?.phase == StarIslandPhase.Working || question.isBlank() || question.length > 2_000) return
        val previous = if (session.question.isNotBlank() && !session.task?.output.isNullOrBlank()) {
            (session.history + listOf(ChatMessage(role = "user", content = session.question),
                ChatMessage(role = "assistant", content = session.task!!.output))).takeLast(8)
        } else session.history
        val generation = ++screenTaskGeneration
        screenStopGeneration = null
        val request = ScreenQuestionRequest(session.attachment, previous)
        screenSession = session.copy(history = previous, question = question, draft = "",
            task = StarIslandTaskState(StarIslandAction.AskScreen, StarIslandPhase.Working, detail = "正在查看选中区域…"))
        islandView?.hideKeyboard()
        islandView?.updateScreenQuestion(checkNotNull(screenSession))
        notifyStatus("正在回答屏幕问题")
        screenTaskJob = scope.launch {
            try {
                runner.runScreen(question, request) { update ->
                    if (generation == screenTaskGeneration) {
                        val task = if (screenStopGeneration == generation) update.stopped() else update
                        screenSession = screenSession?.let { current ->
                            current.copy(task = task, draft = if (task.phase == StarIslandPhase.Error && current.draft.isBlank())
                                question else current.draft)
                        }
                        screenSession?.let { islandView?.updateScreenQuestion(it) }
                        if (task.phase != StarIslandPhase.Working) notifyStatus("屏幕问答 · 点按星岛查看")
                    }
                }
            } finally {
                if (generation == screenTaskGeneration) screenTaskJob = null
            }
        }
    }

    override fun onScreenQuestionStopped() {
        val session = screenSession ?: return
        val task = session.task?.takeIf { it.phase == StarIslandPhase.Working } ?: return
        screenStopGeneration = screenTaskGeneration
        screenTaskJob?.cancel()
        screenSession = session.copy(task = task.stopped())
        islandView?.updateScreenQuestion(checkNotNull(screenSession))
        notifyStatus("屏幕问答已停止 · 点按星岛查看")
    }

    override fun onScreenAnswerCopyRequested() {
        screenSession?.task?.output?.takeIf(String::isNotBlank)?.let { copyText(it, "回答已复制") }
    }

    override fun onScreenQuestionClosed() {
        clearScreenQuestion()
        islandView?.collapse()
        notifyStatus(IDLE_TEXT)
    }

    private fun clearScreenQuestion() {
        screenTaskGeneration++
        screenTaskJob?.cancel()
        screenTaskJob = null
        screenStopGeneration = null
        screenSession = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (capturing) cancelScreenSelection()
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
        val showingScreen = old.screenQuestionVisible
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
                if (showingScreen) screenSession?.let(replacement::showScreenQuestion)
            }
        } finally {
            restoringView = false
        }
    }

    override fun onDestroy() {
        shuttingDown = true
        unregisterReceiver(screenReceiver)
        cancelScreenSelection(restore = false)
        clearScreenQuestion()
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
        val notification = buildNotification((screenSession?.task ?: currentTask)?.takeIf { it.phase == StarIslandPhase.Working }
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
