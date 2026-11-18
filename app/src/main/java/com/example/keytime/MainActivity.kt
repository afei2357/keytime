package com.example.keytime

import android.Manifest
import android.app.ActivityManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.Calendar
import com.example.keytime.databinding.ActivityMainBinding

/**
 * 设置页：服务状态、报时按键选择、声道选择、测试报时、使用说明。
 * v2.0 起不再依赖无障碍权限（HyperOS 对个人应用的无障碍服务有系统级过滤），
 * 改由本页直接启动/停止前台服务。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var speaker: TimeSpeaker? = null

    /** 本 Activity 实例是否已自动启动过服务：保证一次打开只自动拉起一次，
     *  用户手动"停止"后不会被立刻重新拉起；下次重新打开 App（新实例）才会再自动启动。 */
    private var autoStarted = false

    /** v3.5.4：界面可见时定时刷新真实状态，服务被杀后能立刻反映出来 */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }
    private var lastRebuildMs = 0L

    /** 最近一次状态快照，仅在变化时打日志，避免 2 秒刷屏 */
    private var lastStatusSnap = ""

    /** 防止 refreshStatus 同步控件时反触发 OnCheckedChangeListener */
    private var syncingAutoViews = false

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也能用，只是没状态通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnEnableService.setOnClickListener {
            toggleService()
        }

        binding.btnTestSpeak.setOnClickListener {
            Log.i("KeyTimeUI", "test speak clicked, ttsInitialized=${speaker?.isInitialized}")
            ensureSpeaker().speakCurrentTime(force = true)
        }

        binding.switchPaused.setOnCheckedChangeListener { _, checked ->
            if (syncingAutoViews) return@setOnCheckedChangeListener
            prefs.paused = checked
            // v3.7.0：手动操作标记覆盖，保持到下一次自动事件（优先级语义见 AutoSchedule）
            prefs.manualOverrideAtMs = System.currentTimeMillis()
            if (isServiceRunning()) Notifier.refresh(this)
            refreshAutoViews()
        }

        binding.switchBootStart.setOnCheckedChangeListener { _, checked ->
            prefs.bootStartEnabled = checked
        }

        binding.radioTriggerGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radio_trigger_up -> {
                    prefs.triggerDown = false
                    prefs.triggerUp = true
                }
                R.id.radio_trigger_both -> {
                    prefs.triggerDown = true
                    prefs.triggerUp = true
                }
                else -> {
                    prefs.triggerDown = true
                    prefs.triggerUp = false
                }
            }
        }

        binding.radioStreamGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.useAlarmStream = checkedId == R.id.radio_stream_alarm
            ensureSpeaker().setUseAlarmStream(prefs.useAlarmStream)
        }

        // 报时音量：滑块改动立即写入 Prefs（服务每次播报都会实时读取，无需重启）
        binding.seekAnnounceVolume.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean
                ) {
                    if (fromUser) {
                        prefs.announceVolumePct = progress
                        updateVolumeViews()
                        Log.i("KeyTimeUI", "announce volume -> $progress%")
                    }
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    // 用户松开滑块即试听一次，直观听到当前音量
                    ensureSpeaker().speakCurrentTime(force = true)
                }
            }
        )

        binding.switchMaxVolume.setOnCheckedChangeListener { _, checked ->
            prefs.announceVolumeMax = checked
            updateVolumeViews()
            Log.i("KeyTimeUI", "announce max volume -> $checked")
        }

        // ---------- 定时自动控制（v3.7.0） ----------
        binding.switchAutoEnabled.setOnCheckedChangeListener { _, checked ->
            if (syncingAutoViews) return@setOnCheckedChangeListener
            prefs.autoEnabled = checked
            if (checked && !AutoSchedule.isValid(this)) {
                // 用户打开了开关但规则无效：给出即时反馈，仍允许打开但不会触发
                toastAutoInvalid()
            }
            AutoSchedule.schedule(this)   // 打开即排程，关闭即取消
            refreshAutoViews()
            Log.i("KeyTimeUI", "auto schedule enabled -> $checked")
        }

        binding.toggleAutoDays.addOnButtonCheckedListener { _, _, _ ->
            if (syncingAutoViews) return@addOnButtonCheckedListener
            // 至少保留一天：若用户取消最后一个，恢复之（selectionRequired 也会拦，这里兜底）
            val checked = binding.toggleAutoDays.checkedButtonIds
            if (checked.isEmpty()) {
                syncingAutoViews = true
                binding.toggleAutoDays.check(R.id.day_mon)
                syncingAutoViews = false
            }
            saveAutoRepeatMask()
        }

        binding.btnRepeatEveryday.setOnClickListener {
            setRepeatMask(Prefs.ALL_DAYS_MASK)
        }
        binding.btnRepeatWeekdays.setOnClickListener {
            // 周一(1)到周五(5)，bit = Calendar.DAY_OF_WEEK-1
            setRepeatMask(0b0111110)
        }

        binding.btnAutoOnTime.setOnClickListener {
            pickTime(prefs.autoOnMinute, isOnTime = true)
        }
        binding.btnAutoOffTime.setOnClickListener {
            pickTime(prefs.autoOffMinute, isOnTime = false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            binding.btnEnableService.post {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // v3.7.0：打开 App 时确保定时闹钟已排（进程被彻底清理后系统闹钟可能丢失）
        if (prefs.autoEnabled) AutoSchedule.schedule(this)
        // 打开 App 即自动启动报时服务，无需手动点击"启动报时服务"。
        // 仅在尚未自动启动过、且当前确实没在运行时拉起一次。
        if (!autoStarted && !isServiceRunning()) {
            ContextCompat.startForegroundService(
                this, Intent(this, TimeAnnouncerService::class.java)
            )
            autoStarted = true
            Watchdog.schedule(this)
            Log.i("KeyTimeUI", "auto-start service on resume")
        }
        // 启动有延迟：500ms 后再刷新一次，避免"刚启动"被显示成未运行
        handler.postDelayed({ refreshStatus() }, 600)
        // v3.5.4：等服务有时间建好会话，再判断是否需要重建陈旧实例
        handler.postDelayed({ correctStaleService() }, 1_500)
        // 定时刷新，服务被系统杀掉后界面会如实变为"未运行"
        handler.removeCallbacks(statusTicker)
        handler.postDelayed(statusTicker, STATUS_REFRESH_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(statusTicker)
        super.onPause()
    }

    /**
     * 校正"陈旧服务实例"（v3.5.4 改为以系统查询为准）。
     *
     * 服务在系统里活着但按键接管会话已失（被系统掐死后 START_STICKY 复活、
     * 或进程被保留而实例被结束），表现为"运行中但不响应"。
     * 此时 stopService + 重新启动，恢复接管能力；60 秒节流避免反复重建。
     */
    private fun correctStaleService() {
        if (!isServiceRunning()) return
        if (TimeAnnouncerService.isSessionAlive()) return
        val now = System.currentTimeMillis()
        if (now - lastRebuildMs < REBUILD_THROTTLE_MS) return
        lastRebuildMs = now

        Log.i("KeyTimeUI", "stale service detected (running but session dead), rebuilding")
        val intent = Intent(this, TimeAnnouncerService::class.java)
        try {
            stopService(intent)
        } catch (_: Exception) {
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.w("KeyTimeUI", "rebuild via fg failed: $e")
            try {
                startService(intent)
            } catch (_: Exception) {
            }
        }
        Watchdog.schedule(this)
        handler.postDelayed({ refreshStatus() }, 800)
    }

    private fun toggleService() {
        val intent = Intent(this, TimeAnnouncerService::class.java)
        if (isServiceRunning()) {
            TimeAnnouncerService.markUserStopped() // v3.4.2：先打标志，阻止 onDestroy 自重启
            stopService(intent)
            Watchdog.cancel(this) // v3.4.3：用户主动停止，看门狗一并停
            TimeAnnouncerService.markStopped()
            Log.i("KeyTimeUI", "service stop requested")
        } else {
            ContextCompat.startForegroundService(this, intent)
            Watchdog.schedule(this) // v3.4.3：手动启动也纳入看门狗循环
            Log.i("KeyTimeUI", "service start requested")
        }
        refreshStatus()
    }

    // ---------- 定时自动控制辅助方法 ----------

    /** 弹出系统时间选择器；确认后写入配置、校验冲突、重排闹钟 */
    private fun pickTime(currentMinute: Int, isOnTime: Boolean) {
        val hour = currentMinute / 60
        val minute = currentMinute % 60
        TimePickerDialog(
            this,
            { _, h, m ->
                val next = h * 60 + m
                val onMin = if (isOnTime) next else prefs.autoOnMinute
                val offMin = if (isOnTime) prefs.autoOffMinute else next
                if (onMin == offMin) {
                    Toast.makeText(this, R.string.auto_invalid_conflict, Toast.LENGTH_SHORT).show()
                    return@TimePickerDialog
                }
                if (isOnTime) prefs.autoOnMinute = next else prefs.autoOffMinute = next
                AutoSchedule.schedule(this)
                refreshAutoViews()
                Log.i("KeyTimeUI", "auto ${if (isOnTime) "on" else "off"} time -> ${Prefs.formatMinute(next)}")
            },
            hour, minute, true   // 24 小时制
        ).show()
    }

    /** 从 ToggleButton 组读回重复日 bitmask 并保存；至少一天 */
    private fun saveAutoRepeatMask() {
        var mask = 0
        binding.toggleAutoDays.checkedButtonIds.forEach { id ->
            val bit = when (id) {
                R.id.day_sun -> 0
                R.id.day_mon -> 1
                R.id.day_tue -> 2
                R.id.day_wed -> 3
                R.id.day_thu -> 4
                R.id.day_fri -> 5
                R.id.day_sat -> 6
                else -> -1
            }
            if (bit >= 0) mask = mask or (1 shl bit)
        }
        if (mask == 0) {
            Toast.makeText(this, R.string.auto_invalid_no_day, Toast.LENGTH_SHORT).show()
            syncingAutoViews = true
            binding.toggleAutoDays.check(R.id.day_mon)
            syncingAutoViews = false
            mask = 1 shl 1
        }
        prefs.autoRepeatMask = mask
        AutoSchedule.schedule(this)
        refreshAutoViews()
    }

    /** 快捷设置重复日（每天 / 工作日），同步控件并保存 */
    private fun setRepeatMask(mask: Int) {
        prefs.autoRepeatMask = mask
        syncAutoDayButtons(mask)
        AutoSchedule.schedule(this)
        refreshAutoViews()
        Toast.makeText(this, R.string.auto_saved, Toast.LENGTH_SHORT).show()
    }

    private fun syncAutoDayButtons(mask: Int) {
        syncingAutoViews = true
        binding.toggleAutoDays.clearChecked()
        val ids = arrayOf(
            R.id.day_sun, R.id.day_mon, R.id.day_tue, R.id.day_wed,
            R.id.day_thu, R.id.day_fri, R.id.day_sat
        )
        ids.forEachIndexed { bit, id ->
            if (mask and (1 shl bit) != 0) binding.toggleAutoDays.check(id)
        }
        syncingAutoViews = false
    }

    private fun toastAutoInvalid() {
        val msg = if (prefs.autoOnMinute == prefs.autoOffMinute) {
            R.string.auto_invalid_conflict
        } else {
            R.string.auto_invalid_no_day
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 刷新定时卡片：规则摘要 / 下次触发 / 运行状态。
     * 每次状态 tick（2s）都调用，故"下次触发"倒计时始终准确。
     */
    private fun refreshAutoViews() {
        syncingAutoViews = true
        binding.switchAutoEnabled.isChecked = prefs.autoEnabled
        binding.btnAutoOnTime.text = Prefs.formatMinute(prefs.autoOnMinute)
        binding.btnAutoOffTime.text = Prefs.formatMinute(prefs.autoOffMinute)
        syncAutoDayButtons(prefs.autoRepeatMask)
        syncingAutoViews = false

        // 规则摘要
        val days = repeatLabel(prefs.autoRepeatMask)
        binding.tvAutoSummary.text = getString(
            R.string.label_auto_summary,
            "$days ${Prefs.formatMinute(prefs.autoOnMinute)} 开 / ${Prefs.formatMinute(prefs.autoOffMinute)} 关"
        )

        // 下一次触发（跨天由 AutoSchedule.nextEvent 统一处理）
        val ev = AutoSchedule.nextEvent(this)
        binding.tvAutoNext.text = if (ev == null) {
            getString(R.string.label_auto_next, "—")
        } else {
            getString(R.string.label_auto_next, formatNextTime(ev.timeMillis, ev.action))
        }

        // 运行状态：手动覆盖 > 自动 > 未启用
        binding.tvAutoRunning.text = when {
            !prefs.autoEnabled -> getString(R.string.label_auto_running_off)
            prefs.manualOverrideAtMs > 0L -> getString(R.string.label_auto_running_manual)
            else -> getString(R.string.label_auto_running_auto)
        }
    }

    /** 把 bitmask 转成简短中文标签 */
    private fun repeatLabel(mask: Int): String = when (mask) {
        Prefs.ALL_DAYS_MASK -> getString(R.string.label_auto_repeat_everyday)
        0b0111110 -> getString(R.string.label_auto_repeat_weekdays)
        else -> {
            val names = arrayOf(
                R.string.day_sun, R.string.day_mon, R.string.day_tue, R.string.day_wed,
                R.string.day_thu, R.string.day_fri, R.string.day_sat
            )
            (0..6).filter { mask and (1 shl it) != 0 }
                .joinToString("") { getString(names[it]) }
                .ifEmpty { "—" }
        }
    }

    /** 格式化"下次触发"为 今天/明天/周三 22:00 开启报时 */
    private fun formatNextTime(timeMillis: Long, action: String): String {
        val target = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val now = Calendar.getInstance()
        val dayLabel = when {
            isSameDay(now, target) -> getString(R.string.auto_next_today)
            isSameDay(now, Calendar.getInstance().apply {
                timeInMillis = now.timeInMillis
                add(Calendar.DAY_OF_MONTH, 1)
            }) -> getString(R.string.auto_next_tomorrow)
            else -> getString(
                R.string.auto_next_weekday,
                resources.getStringArray(R.array.weekday_names)[target.get(Calendar.DAY_OF_WEEK) - 1]
            )
        }
        val time = Prefs.formatMinute(target.get(Calendar.HOUR_OF_DAY) * 60 + target.get(Calendar.MINUTE))
        val actionLabel = getString(if (action == "on") R.string.auto_action_on else R.string.auto_action_off)
        return getString(R.string.auto_next_format, "$dayLabel $time", actionLabel)
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /** 同步音量相关控件：最大音量开启时禁用滑块并显示提示 */
    private fun updateVolumeViews() {
        val max = prefs.announceVolumeMax
        binding.switchMaxVolume.isChecked = max
        binding.seekAnnounceVolume.isEnabled = !max
        val pct = prefs.announceVolumePct
        if (binding.seekAnnounceVolume.progress != pct) {
            binding.seekAnnounceVolume.progress = pct
        }
        binding.tvAnnounceVolumeValue.text = getString(
            R.string.label_announce_volume_value,
            if (max) 100 else pct
        )
    }

    private fun refreshStatus() {
        updateVolumeViews()
        refreshAutoViews()
        val running = isServiceRunning()
        // v3.5.4：状态必须反映系统真实情况。三种状态分开显示：
        // 未运行 / 运行但接管异常 / 运行且接管正常，避免"显示运行中却按不响"。
        val sessionOk = TimeAnnouncerService.isSessionAlive()
        // 状态变化时留痕（便于排障：可确认界面是否如实跟随系统状态）
        val snap = "running=$running sessionOk=$sessionOk"
        if (snap != lastStatusSnap) {
            lastStatusSnap = snap
            Log.i("KeyTimeUI", "status changed: $snap")
            DebugLog.w(this, "KeyTimeUI", "status $snap")
        }
        binding.tvServiceStatus.setText(
            when {
                !running -> R.string.service_status_off
                !sessionOk -> R.string.service_status_degraded
                else -> R.string.service_status_on
            }
        )
        binding.btnEnableService.setText(
            if (running) R.string.btn_service_stop else R.string.btn_service_enable
        )
        binding.switchPaused.isChecked = prefs.paused
        binding.switchBootStart.isChecked = prefs.bootStartEnabled
        binding.radioTriggerGroup.check(
            when {
                prefs.triggerDown && prefs.triggerUp -> R.id.radio_trigger_both
                prefs.triggerUp -> R.id.radio_trigger_up
                else -> R.id.radio_trigger_down
            }
        )
        binding.radioStreamGroup.check(
            if (prefs.useAlarmStream) R.id.radio_stream_alarm else R.id.radio_stream_media
        )
    }

    /**
     * 服务是否真的在运行——**以系统查询为唯一权威**（v3.5.4）。
     *
     * 旧实现先看静态 `isRunning`，为 true 就直接返回，永远不查系统。
     * 但服务实例被系统杀死时 onDestroy 未必执行，静态标志会停在 true，
     * 于是界面一直显示"运行中"，实际按键毫无响应——正是用户反馈的问题。
     *
     * `ActivityManager.getRunningServices` 在 Android 8+ 只返回本应用自己的服务，
     * 不受隐私限制；服务实例一旦被系统结束，就从该列表中消失，能如实反映状态。
     */
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return TimeAnnouncerService.isRunning
        return try {
            @Suppress("DEPRECATION")
            am.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == TimeAnnouncerService::class.java.name
            }
        } catch (e: Exception) {
            Log.w("KeyTimeUI", "getRunningServices failed: $e")
            TimeAnnouncerService.isRunning
        }
    }

    private fun ensureSpeaker(): TimeSpeaker {
        if (speaker == null) {
            speaker = TimeSpeaker(this, prefs.useAlarmStream).also {
                it.init { ok ->
                    if (!ok) {
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "语音引擎初始化失败：请到 设置 → 无障碍 → 文字转语音输出 检查语音引擎",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
        return speaker!!
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speaker?.release()
        speaker = null
        super.onDestroy()
    }

    companion object {
        /** 界面可见时的状态刷新间隔（ms） */
        private const val STATUS_REFRESH_MS = 2_000L

        /** 陈旧实例重建的最小间隔，避免反复 stop/start */
        private const val REBUILD_THROTTLE_MS = 60_000L
    }
}
