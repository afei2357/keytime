package com.example.keytime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * 看门狗（v3.4.3）：每分钟检查一次报时服务是否"真的活着"，死了立即拉起。
 *
 * 为什么需要它（三次真机迭代的结论）：
 * - HyperOS 在用户上划清理（SwipeUpClean）后会把 App 列入清理名单，界面一不可见
 *   （息屏/Activity stop）就杀死服务实例，且把 START_STICKY 的重启延迟改为无效时间
 *   （实测 restartDelay=-2d19h...，nextRestartTime 为过去时刻，等于永不重启）；
 * - 服务实例被系统杀死时 onDestroy 未必执行（进程被直接 kill 时就不走生命周期），
 *   静态 isRunning 停留在 true → 界面显示"运行中"但息屏按键无响应；
 * - 即便走了 onDestroy，其中的自重启也可能被"后台启动前台服务"限制拒绝。
 *
 * AlarmManager 是系统级调度，不依赖应用进程存活：到点会唤醒进程执行本接收器，
 * 发现服务实例已死（或 MediaSession 会话已失）就重新拉起，并续注册下一次检查，
 * 形成循环。PendingIntent 用 FLAG_IMMUTABLE（Android 12+ 要求）。
 *
 * 注意：本看门狗尊重用户意图——用户主动停止（markUserStopped）后会取消循环。
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Watchdog.ACTION) return

        val alive = TimeAnnouncerService.isRunning && TimeAnnouncerService.isSessionAlive()
        if (!alive && !TimeAnnouncerService.isUserStopped()) {
            DebugLog.w(context, "KeyTimeWatchdog", "service dead (running=${TimeAnnouncerService.isRunning}) -> restart")
            val serviceIntent = Intent(context, TimeAnnouncerService::class.java)
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                DebugLog.w(context, "KeyTimeWatchdog", "fg restart failed: $e")
                // 后台启动前台服务被拒时退化为普通启动（onStartCommand 仍会执行，
                // 服务内部会自行 startForeground）
                try {
                    context.startService(serviceIntent)
                } catch (e2: Exception) {
                    DebugLog.w(context, "KeyTimeWatchdog", "fallback start failed: $e2")
                }
            }
        }
        // 续注册，保持循环（用户已主动停止时不再续）
        if (!TimeAnnouncerService.isUserStopped()) {
            Watchdog.schedule(context)
        } else {
            Watchdog.cancel(context)
        }
    }
}

/** 看门狗的注册/取消工具（单例 object，服务与接收器共用） */
object Watchdog {
    const val ACTION = "com.example.keytime.WATCHDOG"

    /** 检查间隔：1 分钟。alarm 回调仅做两个布尔判断 + 重排，毫秒级完成，耗电可忽略 */
    private const val INTERVAL_MS = 60_000L

    /** 窗口宽度：给系统留出批处理空间（省电），窗口内任意时刻唤醒均可 */
    private const val WINDOW_MS = 30_000L

    fun schedule(context: Context) {
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        val pi = pendingIntent(context)
        try {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            // v3.5.3：普通 setWindow 在 Doze（深度休眠）下会被推迟到系统维护窗口，
            // 夜间息屏数小时后等于失效——这正是"睡一晚服务就没了"的技术原因。
            // 改用 setExactAndAllowWhileIdle：可穿透 Doze（受每 9 分钟一次的频率节流）。
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
                )
                DebugLog.w(context, "KeyTimeWatchdog", "scheduled exact+idle in ${INTERVAL_MS}ms")
            } else {
                am.setWindow(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, WINDOW_MS, pi
                )
                DebugLog.w(
                    context, "KeyTimeWatchdog",
                    "scheduled window (no exact-alarm permission) in ${INTERVAL_MS}ms"
                )
            }
        } catch (e: Exception) {
            // SecurityException 等：退化为可被 Doze 推迟的普通窗口闹钟
            DebugLog.w(context, "KeyTimeWatchdog", "exact schedule failed, fallback: $e")
            try {
                context.getSystemService(AlarmManager::class.java)?.setWindow(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, WINDOW_MS, pi
                )
            } catch (_: Exception) {
            }
        }
    }

    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.cancel(pendingIntent(context))
            DebugLog.w(context, "KeyTimeWatchdog", "cancelled")
        } catch (_: Exception) {
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WatchdogReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
