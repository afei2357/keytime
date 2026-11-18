package com.example.keytime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * 定时自动控制（v3.7.0）：到点自动开启/暂停报时。
 *
 * 状态模型：本应用只有 [Prefs.paused] 一个报时开关。自动规则到点就是写这个字段：
 *   - "开启"事件 → paused = false
 *   - "暂停"事件 → paused = true
 * 服务不必重启，通知栏刷新即可（Notifier.refresh）。
 *
 * ## 下一次触发的算法（纯函数 [nextEvent]）
 * 从"现在"向后扫描最多 8 天，为每一天生成两个候选事件（当天 onMinute 开启、
 * 当天 offMinute 暂停），过滤掉：不在重复日里的、时刻已过的；取最早者。
 * 8 天 > 7 天周期，保证任何重复规则都能覆盖到下一个事件。
 *
 * **跨天**：on=22:00、off=07:00 这类"暂停早于开启"的配置，07:00 天然落在开启的
 * 次日——因为扫描的是日历天，nextEvent 会先找到今天的 22:00（开），再找到明天的
 * 07:00（关），无需任何特殊分支。这正是"睡前开、起床关"的常规场景。
 *
 * **时间冲突**：on==off 时同一分钟既开又关，规则无意义 → 判定无效、不调度（UI
 * 层同时拦截）。其余情况（含 on<off 的"白天开启"）均合法。
 *
 * **手动优先级**：用户手动点"暂停/恢复"会写 [Prefs.manualOverrideAtMs]。
 * 约定：手动状态保持到【下一次自动事件】为止——自动事件到点必然接管（这是"自动"
 * 的意义），在那之前手动说了算。界面据此显示"手动覆盖中，等待下次自动事件"。
 *
 * **跨进程可靠性**：用 AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP)，与看门狗
 * 同款、可穿透 Doze。触发后重新计算并续排，形成链式调度。开机/插电/更新后由
 * BootCompletedReceiver 重新 schedule，进程被杀也不影响（闹钟在系统侧）。
 */
object AutoSchedule {

    const val ACTION_FIRE = "com.example.keytime.AUTO_SCHEDULE_FIRE"
    const val EXTRA_EVENT = "auto_event"          // "on" / "off"
    const val TAG = "KeyTimeAuto"

    /** 扫描天数：大于 7 天重复周期，确保任何规则都能找到下一个事件 */
    private const val SCAN_DAYS = 8

    /** 防重复投递窗口（ms）：同类型事件在此时长内只执行一次 */
    private const val FIRE_DEDUP_MS = 60_000L

    data class Event(val action: String, val timeMillis: Long)

    /**
     * 计算下一次触发事件；规则无效时返回 null。
     * @param nowMillis 当前时刻（毫秒，wall clock）
     */
    fun nextEvent(context: Context, nowMillis: Long = System.currentTimeMillis()): Event? {
        val prefs = Prefs(context)
        if (!prefs.autoEnabled) return null

        val onMin = prefs.autoOnMinute
        val offMin = prefs.autoOffMinute
        val mask = prefs.autoRepeatMask
        // 无效规则：重复日为空，或开启/暂停同一时刻（冲突）→ 不调度
        if (mask == 0 || onMin == offMin) return null

        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var best: Event? = null
        repeat(SCAN_DAYS) {
            val dow = cal.get(Calendar.DAY_OF_WEEK) - 1   // 0=周日 … 6=周六
            val dayStart = cal.timeInMillis
            if (mask and (1 shl dow) != 0) {
                val onTime = dayStart + onMin.toLong() * 60_000L
                val offTime = dayStart + offMin.toLong() * 60_000L
                if (onTime > nowMillis && (best == null || onTime < best!!.timeMillis)) {
                    best = Event("on", onTime)
                }
                if (offTime > nowMillis && (best == null || offTime < best!!.timeMillis)) {
                    best = Event("off", offTime)
                }
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return best
    }

    /** 规则是否可调度（供界面提示无效输入） */
    fun isValid(context: Context): Boolean {
        val p = Prefs(context)
        return p.autoRepeatMask != 0 && p.autoOnMinute != p.autoOffMinute
    }

    /** 服务是否运行（以系统查询为准，与 MainActivity 同款） */
    fun isServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return false
        return try {
            @Suppress("DEPRECATION")
            am.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == TimeAnnouncerService::class.java.name
            }
        } catch (_: Exception) {
            TimeAnnouncerService.isRunning
        }
    }

    /**
     * 读取当前已排定的下一次事件的时间（从闹钟系统反查，或重算）。
     * 界面直接调 [nextEvent] 即可，此法仅用于调试留痕。
     */
    fun schedule(context: Context) {
        val ev = nextEvent(context)
        if (ev == null) {
            cancel(context)
            DebugLog.w(context, TAG, "no next event (disabled or invalid rule) -> cancelled")
            return
        }
        val pi = pendingIntent(context, ev.action)
        try {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            // RTC_WAKEUP：按钟表时间触发（定时器语义）；exact+idle 穿透 Doze。
            // 权限 USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM 已在 Manifest 声明。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ev.timeMillis, pi)
            } else {
                // 极端情况：用户手动关了精确闹钟权限 → 退化为窗口闹钟（可能被 Doze 推迟）
                am.setWindow(AlarmManager.RTC_WAKEUP, ev.timeMillis, 60_000L, pi)
            }
            DebugLog.w(
                context, TAG,
                "scheduled ${ev.action} @ ${Prefs.formatMinute(((ev.timeMillis - dayStartOf(ev.timeMillis)) / 60_000L).toInt())} " +
                    "(in ${(ev.timeMillis - System.currentTimeMillis()) / 1000}s)"
            )
        } catch (e: Exception) {
            DebugLog.w(context, TAG, "schedule failed: $e")
        }
    }

    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.cancel(pendingIntent(context, "on"))
            am.cancel(pendingIntent(context, "off"))
        } catch (_: Exception) {
        }
    }

    private fun dayStartOf(timeMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timeMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** requestCode 用不同值区分 on/off，避免两个 PendingIntent 相互覆盖 */
    private fun pendingIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            if (action == "on") 100 else 101,
            Intent(context, AutoScheduleReceiver::class.java).apply {
                this.action = ACTION_FIRE
                putExtra(EXTRA_EVENT, action)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** 闹钟到点：执行状态切换 + 刷新通知 + 续排下一次 */
class AutoScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutoSchedule.ACTION_FIRE) return
        val event = intent.getStringExtra(AutoSchedule.EXTRA_EVENT) ?: return
        if (event != "on" && event != "off") return

        val prefs = Prefs(context)
        if (!prefs.autoEnabled) {
            DebugLog.w(context, AutoSchedule.TAG, "fire ignored: auto disabled")
            AutoSchedule.cancel(context)
            return
        }
        // 防重复投递：同类型事件 60s 内只执行一次
        val now = System.currentTimeMillis()
        if (prefs.lastAutoAction == event && now - prefs.lastAutoFireMs < 60_000L) {
            DebugLog.w(context, AutoSchedule.TAG, "fire ignored: dedup ($event)")
            AutoSchedule.schedule(context)
            return
        }

        // 自动事件到点即接管：清除手动覆盖标记，切换报时状态
        prefs.manualOverrideAtMs = 0L
        prefs.paused = (event == "off")
        prefs.lastAutoFireMs = now
        prefs.lastAutoAction = event

        // "开启"事件若服务未运行，顺带拉起（到点必须真的能报时）
        if (event == "on" && !AutoSchedule.isServiceRunning(context)) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, TimeAnnouncerService::class.java)
                )
                Watchdog.schedule(context)
                DebugLog.w(context, AutoSchedule.TAG, "auto ON: service was down, start requested")
            } catch (e: Exception) {
                DebugLog.w(context, AutoSchedule.TAG, "auto ON start service failed: $e")
            }
        }

        TimeAnnouncerService.publishStatus(
            context,
            "定时自动${if (event == "on") "开启" else "暂停"}报时"
        )
        Notifier.refresh(context)
        DebugLog.w(context, AutoSchedule.TAG, "fired $event -> paused=${prefs.paused}, rescheduling")
        AutoSchedule.schedule(context)
    }
}
