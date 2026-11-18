package com.example.keytime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 开机自启接收器：系统启动完成（BOOT_COMPLETED / LOCKED_BOOT_COMPLETED）后，
 * 自动拉起报时前台服务，实现"重启手机后无需手动打开 App 即可报时"。
 *
 * 要点：
 * - 需要 RECEIVE_BOOT_COMPLETED 权限；
 * - 小米/HyperOS 对侧载应用还有"自启动"管控：即便声明权限、注册了接收器，
 *   若未在 设置 → 应用管理 → 按键报时 → 自启动 中允许，系统不会把该广播发给本应用；
 * - 从 BOOT_COMPLETED 接收器启动前台服务，Android 12+ 属于后台启动前台服务的豁免场景，
 *   因此用 ContextCompat.startForegroundService 是合规的；
 * - 受 Prefs.bootStartEnabled 开关控制，用户可在 App 内关闭开机自启。
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        // v3.5.3：插电 / 应用更新后同样恢复服务。
        // HyperOS 可能在深度清理时把 AlarmManager 里的看门狗一并清掉（实测 alarm 队列归零），
        // 此时只能靠系统广播重新拉起；夜间多数用户会插电充电，这是最可靠的自愈时机。
        val isRevive = action == Intent.ACTION_POWER_CONNECTED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isBoot && !isRevive) return

        val prefs = Prefs(context)
        // v3.7.0：即便开机自启关闭，定时闹钟也要按用户配置恢复
        // （闹钟是系统侧实体，重启后必然丢失；且它不依赖服务常驻）。
        if (prefs.autoEnabled) AutoSchedule.schedule(context)
        if (!prefs.bootStartEnabled) {
            DebugLog.w(context, TAG, "start skipped (switch off), action=$action")
            return
        }

        val serviceIntent = Intent(context, TimeAnnouncerService::class.java)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            Watchdog.schedule(context)
            DebugLog.w(context, TAG, "start requested, action=$action")
        } catch (e: Exception) {
            DebugLog.w(context, TAG, "start failed, action=$action: $e")
        }
    }

    companion object {
        private const val TAG = "KeyTimeBoot"
    }
}
