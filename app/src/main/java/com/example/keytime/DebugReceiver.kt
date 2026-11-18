package com.example.keytime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager

/**
 * 调试用接收器（exported，仅在本机调试期间存在，正式发布版会移除）。
 *
 * 用途：adb 侧完全无法靠触摸/按键注入驱动应用，也没有 logcat 可见性，
 * 因此暴露少量只读/受控操作，便于定位问题：
 *   op=start    启动前台服务
 *   op=stop     停止服务
 *   op=status   把当前状态写进日志 + 通知（dumpsys notification 可读）
 *   op=speak    立即播报一次（验证 TTS 链路）
 *   op=clear    清空日志
 *   op=always   切换"忽略息屏判断"（排查是否是息屏判断误伤）
 *   op=notify   把 msg 写进通知
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.getStringExtra("op") ?: "status"
        val msg = intent.getStringExtra("msg")
        DebugLog.w(context, "DebugReceiver", "op=$op msg=$msg")
        when (op) {
            "start" -> {
                runCatching {
                    context.startForegroundService(Intent(context, TimeAnnouncerService::class.java))
                    Watchdog.schedule(context) // v3.4.3：启动即纳入看门狗循环
                }.onFailure { DebugLog.w(context, "DebugReceiver", "start failed: $it") }
            }

            "stop" -> {
                TimeAnnouncerService.markUserStopped() // v3.4.2：先打标志，阻止 onDestroy 自重启
                Watchdog.cancel(context) // v3.4.3：看门狗一并停，尊重用户意图
                context.stopService(Intent(context, TimeAnnouncerService::class.java))
            }

            "speak" -> {
                val speaker = TimeSpeaker(context, Prefs(context).useAlarmStream)
                speaker.init()
                val ok = speaker.speakCurrentTime(force = true)
                DebugLog.w(context, "DebugReceiver", "speak called ok=$ok")
            }

            "clear" -> DebugLog.clear(context)

            // 让应用自己真实改一次音量（走 AudioService 正规路径），
            // 用于验证"音量变化 → 检测 → 播报"整条链路，无需物理按键
            "vol" -> {
                val am = context.getSystemService(AudioManager::class.java)
                val cur = am?.getStreamVolume(AudioManager.STREAM_RING) ?: -1
                val next = if (cur > 0) cur - 1 else cur + 1
                val res = am?.setStreamVolume(AudioManager.STREAM_RING, next, 0)
                DebugLog.w(context, "DebugReceiver", "vol: $cur -> $next (setResult=$res)")
            }

            // 跳过检测，直接触发一次播报（验证 TTS 与音量恢复）
            "fake" -> TimeAnnouncerService.simulatePress(context, -1)

            // v3.4.9：模拟"闹钟开始响"，验证闹钟报时链路（跳过 60s 冷却）
            "alarm" -> TimeAnnouncerService.simulateAlarm(context)

            // v3.4.9：以 USAGE_ALARM 真实播放 3 秒提示音，端到端验证闹钟检测
            "alarmplay" -> TimeAnnouncerService.simulateAlarmSound(context)

            "always" -> {
                val p = Prefs(context)
                p.debugAlways = !p.debugAlways
                DebugLog.w(context, "DebugReceiver", "debugAlways=${p.debugAlways}")
            }

            // 复位调试状态：v3.2.1 起 debugAlways 不再参与屏幕门控，
            // 但早期调试可能把它持久化为 true 导致亮屏误报时，这里一键清掉。
            "reset" -> {
                val p = Prefs(context)
                p.debugAlways = false
                DebugLog.w(context, "DebugReceiver", "debugAlways reset -> false")
            }

            // v3.6.0：报时音量调试——设置百分比后立即播报一次，验证音量生效
            "volset" -> {
                // 兼容 adb --es 传字符串与 -ei 传整数两种形式
                val pctStr = intent.getStringExtra("pct")
                val pct = (pctStr?.toIntOrNull() ?: intent.getIntExtra("pct", -1)).coerceIn(0, 100)
                val p = Prefs(context)
                p.announceVolumeMax = false
                p.announceVolumePct = pct
                DebugLog.w(context, "DebugReceiver", "announce volume pct=$pct")
                TimeAnnouncerService.simulateSpeak(context)
            }

            // v3.6.0：切换"最大音量"选项后立即播报
            "volmax" -> {
                val p = Prefs(context)
                p.announceVolumeMax = !p.announceVolumeMax
                DebugLog.w(context, "DebugReceiver", "announce max=${p.announceVolumeMax}")
                TimeAnnouncerService.simulateSpeak(context)
            }

            // v3.6.0：只看当前音量配置，不播报（用于验证重启后保持）
            "volstatus" -> {
                val p = Prefs(context)
                DebugLog.w(
                    context, "DebugReceiver",
                    "announce volume pct=${p.announceVolumePct} max=${p.announceVolumeMax} " +
                        "fraction=${p.announceVolumeFraction}"
                )
            }

            // 模拟"用户手动切换暂停/恢复"（与 App 内开关 / 通知栏按钮同路径）
            "manualtoggle" -> {
                val p = Prefs(context)
                p.paused = !p.paused
                p.manualOverrideAtMs = System.currentTimeMillis()
                Notifier.refresh(context)
                DebugLog.w(context, "DebugReceiver", "manual toggle -> paused=${p.paused}")
            }

            // v3.7.0：定时自动控制调试
            // autoset --es on "22:00" --es off "07:00" --es mask "127" --es enabled "1"
            "autoset" -> {
                val p = Prefs(context)
                intent.getStringExtra("on")?.split(":")?.let {
                    p.autoOnMinute = it[0].toInt() * 60 + it[1].toInt()
                }
                intent.getStringExtra("off")?.split(":")?.let {
                    p.autoOffMinute = it[0].toInt() * 60 + it[1].toInt()
                }
                intent.getStringExtra("mask")?.toIntOrNull()?.let { p.autoRepeatMask = it }
                intent.getStringExtra("enabled")?.let { p.autoEnabled = it == "1" }
                AutoSchedule.schedule(context)
                DebugLog.w(
                    context, "DebugReceiver",
                    "autoset on=${Prefs.formatMinute(p.autoOnMinute)} off=${Prefs.formatMinute(p.autoOffMinute)} " +
                        "mask=${p.autoRepeatMask} enabled=${p.autoEnabled}"
                )
            }

            "autostatus" -> {
                val p = Prefs(context)
                val ev = AutoSchedule.nextEvent(context)
                val line =
                    "auto enabled=${p.autoEnabled} on=${Prefs.formatMinute(p.autoOnMinute)} " +
                        "off=${Prefs.formatMinute(p.autoOffMinute)} mask=${p.autoRepeatMask} " +
                        "paused=${p.paused} manualOverride=${p.manualOverrideAtMs > 0} " +
                        "next=${ev?.action}@${ev?.timeMillis}"
                DebugLog.w(context, "DebugReceiver", line)
                // 同步写进通知，便于 logcat 被 ROM 屏蔽时用 dumpsys notification 读取
                TimeAnnouncerService.publishStatus(context, line)
            }

            // 模拟一次自动事件（on/off）走完整链路，无需真等到点
            "autofire" -> {
                val action = intent.getStringExtra("event") ?: "on"
                val ev = Intent(context, AutoScheduleReceiver::class.java).apply {
                    this.action = AutoSchedule.ACTION_FIRE
                    putExtra(AutoSchedule.EXTRA_EVENT, action)
                }
                context.sendBroadcast(ev)
                DebugLog.w(context, "DebugReceiver", "autofire -> $action")
            }

            "notify" -> TimeAnnouncerService.publishStatus(context, msg ?: "(no msg)")

            "status" -> TimeAnnouncerService.publishStatus(context, state(context))
        }
    }

    private fun state(context: Context): String {
        val p = Prefs(context)
        val am = context.getSystemService(AudioManager::class.java)
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        return buildString {
            append("run=${TimeAnnouncerService.isRunning} ").append("paused=${p.paused} ")
            append("listen=${p.listenVolume} ").append("down=${p.triggerDown} ")
            append("up=${p.triggerUp} ").append("always=${p.debugAlways} ")
            append("interactive=${pm?.isInteractive} ")
            append("ring=${am?.getStreamVolume(AudioManager.STREAM_RING)} ")
            append("music=${am?.getStreamVolume(AudioManager.STREAM_MUSIC)} ")
            append("alarm=${am?.getStreamVolume(AudioManager.STREAM_ALARM)} ")
            append("last=${TimeAnnouncerService.lastEvent}")
        }
    }
}
