package com.example.keytime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 常驻通知：显示服务运行状态，提供"暂停/恢复"按钮。
 * 通知里不含任何敏感信息，只做状态展示。
 */
object Notifier {

    const val CHANNEL_ID = "service_status"
    const val NOTIF_ID = 1001
    const val ACTION_TOGGLE_PAUSE = "com.example.keytime.action.TOGGLE_PAUSE"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    /** detail 非空时用它覆盖默认文案：调试期把运行状态暴露到通知，
     *  便于 adb 用 dumpsys notification 读取（HyperOS 屏蔽了本应用 logcat）。 */
    fun refresh(context: Context, detail: String? = null) {
        ensureChannel(context)
        val paused = Prefs(context).paused
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIF_ID, buildNotification(context, paused, detail))
        } catch (_: Exception) {
            // 未授予通知权限的少数 ROM 上 notify 可能抛异常；忽略即可，不影响报时
        }
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.cancel(NOTIF_ID)
    }

    /** 前台服务启动/暂停切换时直接取通知用 */
    fun buildNotification(context: Context, paused: Boolean, detail: String? = null): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val togglePending = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_TOGGLE_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_time)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(
                if (detail.isNullOrBlank()) {
                    context.getString(
                        if (paused) R.string.notif_text_paused else R.string.notif_text_running
                    )
                } else {
                    detail
                }
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_LOW)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(if (paused) R.string.action_resume else R.string.action_pause),
                    togglePending
                ).build()
            )
            .build()
    }
}