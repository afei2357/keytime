package com.example.keytime

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用配置。全部本地 SharedPreferences 存储，不联网、不上传。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /** 音量下键作为报时键 */
    var triggerDown: Boolean
        get() = sp.getBoolean(KEY_TRIGGER_DOWN, true)
        set(value) = sp.edit().putBoolean(KEY_TRIGGER_DOWN, value).apply()

    /** 音量上键作为报时键 */
    var triggerUp: Boolean
        get() = sp.getBoolean(KEY_TRIGGER_UP, false)
        set(value) = sp.edit().putBoolean(KEY_TRIGGER_UP, value).apply()

    /** 暂停报时（不关闭服务，仅不播报；通知栏可一键切换） */
    var paused: Boolean
        get() = sp.getBoolean(KEY_PAUSED, false)
        set(value) = sp.edit().putBoolean(KEY_PAUSED, value).apply()

    /** 语音走闹钟声道（夜间不易被静音/勿扰过滤）；false 走媒体声道 */
    var useAlarmStream: Boolean
        get() = sp.getBoolean(KEY_ALARM_STREAM, true)
        set(value) = sp.edit().putBoolean(KEY_ALARM_STREAM, value).apply()

    /** v2.0 主机制：监听音量变化触发报时（服务开启时生效） */
    var listenVolume: Boolean
        get() = sp.getBoolean(KEY_LISTEN_VOLUME, true)
        set(value) = sp.edit().putBoolean(KEY_LISTEN_VOLUME, value).apply()

    /** 已废弃：早期调试用的"忽略息屏判断"开关。
     *  v3.2.1 起屏幕门控为硬性逻辑（handleVolumeKey/onVolumeChanged 均直接判 interactive），
     *  此字段不再参与是否报时的决策，仅保留以免破坏旧序列化；可用 DebugReceiver op=reset 清零。 */
    var debugAlways: Boolean
        get() = sp.getBoolean(KEY_DEBUG_ALWAYS, false)
        set(value) = sp.edit().putBoolean(KEY_DEBUG_ALWAYS, value).apply()

    /** 开机后自动启动报时服务（默认开；用户可在 App 内关闭） */
    var bootStartEnabled: Boolean
        get() = sp.getBoolean(KEY_BOOT_START, true)
        set(value) = sp.edit().putBoolean(KEY_BOOT_START, value).apply()

    /**
     * 报时音量百分比（0..100，默认 80）。
     * 通过 TTS 的 KEY_PARAM_VOLUME 生效——是本应用自己的播报增益，
     * 不改动系统任何音量流（闹钟/媒体音量照旧，不影响真实闹钟）。
     */
    var announceVolumePct: Int
        get() = sp.getInt(KEY_ANNOUNCE_VOLUME_PCT, DEFAULT_ANNOUNCE_VOLUME_PCT).coerceIn(0, 100)
        set(value) = sp.edit().putInt(KEY_ANNOUNCE_VOLUME_PCT, value.coerceIn(0, 100)).apply()

    /** 使用系统最大音量播报（忽略百分比）。应对需要较大声音的场景，默认关。 */
    var announceVolumeMax: Boolean
        get() = sp.getBoolean(KEY_ANNOUNCE_VOLUME_MAX, false)
        set(value) = sp.edit().putBoolean(KEY_ANNOUNCE_VOLUME_MAX, value).apply()

    /** 当前生效的 TTS 音量（0f..1f）：最大选项优先，否则按百分比换算 */
    val announceVolumeFraction: Float
        get() = if (announceVolumeMax) 1f else announceVolumePct / 100f

    // ---------- 定时自动控制（v3.7.0） ----------

    /** 定时自动控制总开关 */
    var autoEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_ENABLED, value).apply()

    /** 自动开启报时的时刻（当天分钟数 0..1439，默认 22:00） */
    var autoOnMinute: Int
        get() = sp.getInt(KEY_AUTO_ON_MINUTE, DEFAULT_AUTO_ON_MINUTE).coerceIn(0, MINUTES_PER_DAY - 1)
        set(value) = sp.edit().putInt(KEY_AUTO_ON_MINUTE, value.coerceIn(0, MINUTES_PER_DAY - 1)).apply()

    /** 自动暂停报时的时刻（当天分钟数 0..1439，默认 07:00） */
    var autoOffMinute: Int
        get() = sp.getInt(KEY_AUTO_OFF_MINUTE, DEFAULT_AUTO_OFF_MINUTE).coerceIn(0, MINUTES_PER_DAY - 1)
        set(value) = sp.edit().putInt(KEY_AUTO_OFF_MINUTE, value.coerceIn(0, MINUTES_PER_DAY - 1)).apply()

    /**
     * 重复日 bitmask：bit 0=周日, bit 1=周一 … bit 6=周六（对应 Calendar.DAY_OF_WEEK - 1）。
     * 默认每天 = 0b1111111 = 127。
     */
    var autoRepeatMask: Int
        get() = sp.getInt(KEY_AUTO_REPEAT_MASK, DEFAULT_AUTO_REPEAT_MASK) and ALL_DAYS_MASK
        set(value) = sp.edit().putInt(KEY_AUTO_REPEAT_MASK, value and ALL_DAYS_MASK).apply()

    /**
     * 手动操作标记时间（ms）：用户手动点"暂停/恢复"时写入。
     * 自动规则只在该时间在【上一次自动触发之前】才让位（见 AutoSchedule 说明）。
     * 0 表示当前没有手动覆盖。
     */
    var manualOverrideAtMs: Long
        get() = sp.getLong(KEY_MANUAL_OVERRIDE_MS, 0L)
        set(value) = sp.edit().putLong(KEY_MANUAL_OVERRIDE_MS, value).apply()

    /** 最近一次自动触发时间（ms），供界面展示与调试 */
    var lastAutoFireMs: Long
        get() = sp.getLong(KEY_LAST_AUTO_FIRE_MS, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_AUTO_FIRE_MS, value).apply()

    /** 最近一次自动触发的动作（"on"/"off"），供界面展示 */
    var lastAutoAction: String
        get() = sp.getString(KEY_LAST_AUTO_ACTION, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_AUTO_ACTION, value).apply()

    companion object {
        const val SP_NAME = "keytime_prefs"
        const val KEY_TRIGGER_DOWN = "trigger_down"
        const val KEY_TRIGGER_UP = "trigger_up"
        const val KEY_PAUSED = "paused"
        const val KEY_ALARM_STREAM = "use_alarm_stream"
        const val KEY_LISTEN_VOLUME = "listen_volume"
        const val KEY_DEBUG_ALWAYS = "debug_always"
        const val KEY_BOOT_START = "boot_start"
        const val KEY_ANNOUNCE_VOLUME_PCT = "announce_volume_pct"
        const val KEY_ANNOUNCE_VOLUME_MAX = "announce_volume_max"

        /** 报时音量默认值：80%——睡前够清楚，又不会像满音量那样突兀 */
        const val DEFAULT_ANNOUNCE_VOLUME_PCT = 80

        const val KEY_AUTO_ENABLED = "auto_enabled"
        const val KEY_AUTO_ON_MINUTE = "auto_on_minute"
        const val KEY_AUTO_OFF_MINUTE = "auto_off_minute"
        const val KEY_AUTO_REPEAT_MASK = "auto_repeat_mask"
        const val KEY_MANUAL_OVERRIDE_MS = "manual_override_ms"
        const val KEY_LAST_AUTO_FIRE_MS = "last_auto_fire_ms"
        const val KEY_LAST_AUTO_ACTION = "last_auto_action"

        const val MINUTES_PER_DAY = 24 * 60
        const val ALL_DAYS_MASK = 0b1111111
        const val DEFAULT_AUTO_ON_MINUTE = 22 * 60      // 22:00 自动开启（睡前）
        const val DEFAULT_AUTO_OFF_MINUTE = 7 * 60      // 07:00 自动暂停（起床）
        const val DEFAULT_AUTO_REPEAT_MASK = ALL_DAYS_MASK

        /** 把分钟数格式化为 HH:mm（Locale.US 保证 ASCII 数字） */
        fun formatMinute(minute: Int): String {
            val m = minute.coerceIn(0, MINUTES_PER_DAY - 1)
            return "%02d:%02d".format(java.util.Locale.US, m / 60, m % 60)
        }
    }
}