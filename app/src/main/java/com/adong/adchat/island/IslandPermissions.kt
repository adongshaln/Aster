package com.adong.adchat.island

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 灵动岛所需权限的一次性引导：悬浮窗（必要）+ 通知（Android 13+）。
 * 权限齐备前 IslandService 不会被唤起，避免无效启动。
 */
object IslandPermissions {

    private const val PREFS = "link_island"
    private const val KEY_ENABLED = "enabled"

    fun isReady(context: Context): Boolean =
        Settings.canDrawOverlays(context) && hasNotifications(context)

    fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ---------- 设置开关 ----------

    /** 开关状态。开启且权限就绪时调用方应启动服务。 */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** 返回本次开关操作后服务应有的状态：true = 应启动，false = 应停止。 */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        return enabled && isReady(context)
    }

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
