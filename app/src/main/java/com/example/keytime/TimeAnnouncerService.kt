package com.example.keytime

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

/**
 * 按键报时前台服务（v3.0 主机制已落地；v2.0 监听逻辑保留为无副作用兜底）。
 *
 * 背景：HyperOS 对个人应用（非应用商店/云白名单来源）的无障碍服务有系统级过滤——
 * 向 Settings.Secure.enabled_accessibility_services 写入会被同步剥离，服务也不会出现在
 * 设置的"已下载的应用"列表，用户手动无法开启（已实测：换安装途径、installer 字符串、
 * 去掉按键过滤 flag、release 签名均无效）。因此改用无需授权的方案：
 *
 * 主机制（v3.4.4）：用 MediaSession 声明"远程音量"接管音量键（VolumeProvider.onAdjustVolume
 * 拿到的是按键事件本身，与音量数值变化无关）。会话从激活起就**全程 REMOTE、
 * 绝不做 Local/Remote 切换**——真机实证：这台 HyperOS 上 setPlaybackToLocal 之后
 * 再 setPlaybackToRemote 要不回音量键路由（v3.4~v3.4.3 息屏按键失效根因），
 * 只有 v3.0 的"全程 REMOTE"配置能稳定报时。两种屏幕状态的行为：
 *   - 息屏：按键被会话接管 → 触发报时（系统真实音量不变，符合睡觉场景）；
 *   - 亮屏：按键同样到达 App，但由 App **代为调用 adjustVolume 调节系统音量**
 *     （用户感知与正常按键一致），且不报时。
 *
 * 兜底（v2.0 遗留，无副作用）：VOLUME_CHANGED_ACTION 广播 + ContentObserver + 轮询，
 * 仅在息屏时生效，作为保底通道；实测本机物理键不改变任何音量流数值，故通常不触发。
 *
 * 已知边界：
 * - 需前台服务存活：HyperOS 需设省电"无限制" + 最近任务加锁 + 允许自启动（开机自启）
 */
class TimeAnnouncerService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var speaker: TimeSpeaker
    private var volumeReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private val observers = mutableListOf<ContentObserver>()
    private val lastVolumes = mutableMapOf<Int, Int>()
    /** v3.5.0：自己恢复音量后写入的目标值，用于精确识别"自己造成的回声"而非用户按键 */
    private val restoreTargets = mutableMapOf<Int, Int>()
    private var restoreAtMs = 0L

    /** v3.5.2：按键批次归并——一次物理按键改动的多条流视为同一次按键 */
    private var keySessionMs = 0L
    private val pendingRestore = mutableMapOf<Int, Int>()
    private var pollCount = 0
    private var lastPressMs = 0L
    private lateinit var interceptor: VolumeKeyInterceptor
    private val handler = Handler(Looper.getMainLooper())
    private var silentTrack: AudioTrack? = null

    /** v3.4.9：闹钟监听（USAGE_ALARM 播放回调） */
    private var alarmCallback: AudioManager.AudioPlaybackCallback? = null
    private var alarmWasPlaying = false
    private var lastAlarmAnnounceMs = 0L

    /**
     * v3.5.0：本应用自己 TTS 播报的时间戳。
     * TTS 走 USAGE_ALARM（默认闹钟声道），会被自己的闹钟检测逻辑误认为"闹钟响了"
     * （实测日志：按键报时后紧接 `alarm playing=true`），必须在此窗口内忽略。
     */
    private var lastSelfSpeakMs = 0L

    private val audioManager: AudioManager?
        get() = getSystemService(AudioManager::class.java)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        speaker = TimeSpeaker(this, prefs.useAlarmStream).also { it.init() }
        Notifier.ensureChannel(this)
        instance = this
        // v3.0 主机制：MediaSession 接管音量键（不依赖音量数值变化）
        interceptor = VolumeKeyInterceptor(this) { handleVolumeKey(it) }
        DebugLog.w(this, TAG, "onCreate alarmStream=${prefs.useAlarmStream}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        registerListeners()
        // v3.4.4：会话从激活起就全程 REMOTE（v3.0 实证可用配置）。
        // 绝不在这之后做 setPlaybackToLocal 切换——实测该 ROM 上切走再切回
        // 要不回音量键路由（v3.4~v3.4.3 息屏按键失效根因）。亮屏"不吞音量键"
        // 改由 handleVolumeKey 在 App 层代为调节音量实现。
        // v3.5.3：会话已活着就不重建——插电/更新广播会重复触发 onStartCommand，
        // 频繁 release→重建会让系统重新评估音量键路由（存在丢路由风险）。
        if (!interceptor.isAlive()) {
            interceptor.start()
        } else {
            DebugLog.w(this, TAG, "interceptor already alive, skip rebuild")
        }
        // v3.4.8：静音媒体保活——让音量键作为媒体音量键直接派发给会话，
        // 而不是被系统当作"唤醒屏幕"的键消费掉（见 startSilentKeepAlive 注释）
        startSilentKeepAlive()
        // v3.4.9：闹钟响铃监听（独立于音量监听开关，始终可用）
        registerAlarmWatcher()
        isRunning = true
        // v3.4.3：服务真正起来了才清除"用户主动停止"标志（看门狗据此恢复守护）。
        // 此前在 onDestroy 里复位，会导致"用户停止后看门狗立刻又把服务拉起来"。
        userStopped = false
        // v3.4.3：注册看门狗（AlarmManager 系统级循环，兜底 START_STICKY 被 HyperOS 卡死）
        Watchdog.schedule(this)
        // v3.7.0：确保定时自动控制闹钟已排（服务每次启动都重排，覆盖系统清理丢失的场景）
        if (prefs.autoEnabled) AutoSchedule.schedule(this)
        DebugLog.w(this, TAG, "onStartCommand (startId=$startId)")
        // 被系统杀掉后自动重启，保证常驻
        return START_STICKY
    }

    /**
     * 屏幕开关广播：仅用于日志与调整轮询节奏（v3.4.4 起不再切换会话模式）。
     *
     * 历史教训：v3.4~v3.4.3 曾在此根据屏幕状态切换 setPlaybackToLocal/Remote，
     * 实测在这台 HyperOS 上"亮屏交还、息屏接管"的切换会导致息屏按键失效——
     * setLocal 之后再 setRemote 要不回音量键路由。故会话全程 REMOTE，
     * 亮屏的"代为调音量、不报时"逻辑放在 handleVolumeKey。
     */
    override fun onDestroy() {
        isRunning = false
        instance = null
        if (::interceptor.isInitialized) interceptor.release()
        stopSilentKeepAlive()
        unregisterAlarmWatcher()
        unregisterListeners()
        Notifier.cancel(this)
        speaker.release()
        DebugLog.w(this, TAG, "onDestroy userStopped=$userStopped")
        // v3.4.2：HyperOS 在用户上划清理（SwipeUpClean）后，会把 App 列入清理名单——
        // 界面一不可见（息屏/Activity stop）就杀死服务实例，且把 START_STICKY 的
        // 重启延迟到无效时间（实测 restartDelay=-2d19h...，等于永不重启）。
        // 表现为"界面显示运行中、息屏按键无响应"。
        // v3.4.3：自重启之外，再确保看门狗循环继续（alarm 不依赖 onDestroy 被调用，
        // 是比自重启更可靠的兜底）；用户主动停止则取消看门狗，尊重用户意图。
        if (userStopped) {
            Watchdog.cancel(this)
        } else {
            if (System.currentTimeMillis() - lastSelfRestartMs > SELF_RESTART_INTERVAL_MS) {
                lastSelfRestartMs = System.currentTimeMillis()
                try {
                    ContextCompat.startForegroundService(
                        this, Intent(this, TimeAnnouncerService::class.java)
                    )
                    DebugLog.w(this, TAG, "self-restart requested (killed by system)")
                } catch (e: Exception) {
                    DebugLog.w(this, TAG, "self-restart failed: $e")
                }
            }
            Watchdog.schedule(this)
        }
        // v3.4.3：userStopped 标志**不在**此处复位——它要一直保持到服务下次成功
        // 启动（onStartCommand 里复位），否则"用户主动停止"后看门狗会立刻把服务拉回来。
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 监听注册 ----------

    private fun registerListeners() {
        if (!prefs.listenVolume) {
            DebugLog.w(this, TAG, "listenVolume=false, skip registering")
            return
        }

        // 主路径：音量变化广播（extras 带 stream/新值/旧值）
        if (volumeReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != VOLUME_CHANGED_ACTION) return
                    val stream = intent.getIntExtra(EXTRA_STREAM_TYPE, -1)
                    val newVal = intent.getIntExtra(EXTRA_STREAM_VALUE, -1)
                    val prevVal = intent.getIntExtra(EXTRA_PREV_STREAM_VALUE, -1)
                    DebugLog.w(
                        this@TimeAnnouncerService, TAG,
                        "BCAST action=${intent.action} stream=$stream $prevVal->$newVal"
                    )
                    if (stream < 0 || newVal < 0 || prevVal < 0) return
                    onVolumeChanged(stream, prevVal, newVal, "bcast")
                }
            }
            volumeReceiver = receiver
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        receiver, IntentFilter(VOLUME_CHANGED_ACTION), Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(receiver, IntentFilter(VOLUME_CHANGED_ACTION))
                }
                DebugLog.w(this, TAG, "broadcast receiver registered (dynamic)")
            } catch (e: Exception) {
                volumeReceiver = null
                DebugLog.w(this, TAG, "broadcast register failed: $e")
            }
        }

        // 兜底路径：Settings.System 音量值观察者
        if (observers.isEmpty()) {
            val am = audioManager
            for (stream in WATCHED_STREAMS) {
                lastVolumes[stream] = am?.getStreamVolume(stream) ?: 0
                val obs = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        val mgr = audioManager ?: return
                        val cur = mgr.getStreamVolume(stream)
                        val prev = lastVolumes[stream] ?: cur
                        DebugLog.w(
                            this@TimeAnnouncerService, TAG,
                            "OBSERVE stream=$stream prev=$prev cur=$cur"
                        )
                        if (cur != prev) onVolumeChanged(stream, prev, cur, "observe")
                    }
                }
                observers.add(obs)
                try {
                    contentResolver.registerContentObserver(
                        Settings.System.getUriFor(volumeSettingName(stream)), false, obs
                    )
                    DebugLog.w(
                        this, TAG,
                        "observer registered: ${volumeSettingName(stream)} base=${lastVolumes[stream]}"
                    )
                } catch (e: Exception) {
                    DebugLog.w(this, TAG, "observer register failed for stream $stream: $e")
                }
            }
        }
        // 兜底 B：轮询音量（不依赖任何系统广播/设置项）
        // 原理：Handler 定时器不会阻止 CPU 挂起，息屏时零耗电；
        // 用户按音量键会唤醒 CPU，已过期的定时器会在恢复瞬间立即执行 → 毫秒级检测到变化。
        // 这是为绕开 MIUI 可能拦截 VOLUME_CHANGED_ACTION 而设计的保底通道。
        startPolling()

        // 屏幕开关：仅用于日志与调整轮询节奏（v3.4.4 起不再切换会话模式——
        // 会话全程 REMOTE，亮屏的代为调音量逻辑在 handleVolumeKey）
        if (screenReceiver == null) {
            screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val on = intent?.action == Intent.ACTION_SCREEN_ON
                    DebugLog.w(this@TimeAnnouncerService, TAG, "screen ${if (on) "ON" else "OFF"}")
                    reschedulePoll()
                    // v3.4.8：取消 v3.4.7 的"屏幕亮起即报时"兜底——它无法区分唤醒源，
                    // 来通知/闹钟/按电源键点亮锁屏都会误报（用户明确反馈）。
                    // 现在改为依赖静音媒体保活，让音量键直接派发到会话（见 startSilentKeepAlive）。
                }
            }
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(screenReceiver, f)
                }
            } catch (e: Exception) {
                DebugLog.w(this, TAG, "screen receiver register failed: $e")
            }
        }

        publishStatus(this, "已注册：广播+${observers.size}观察者+轮询")
    }

    // ---------- 轮询兜底 ----------

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                pollVolumes()
            } catch (e: Exception) {
                DebugLog.w(this@TimeAnnouncerService, TAG, "poll error: $e")
            } finally {
                handler.postDelayed(this, currentPollInterval())
            }
        }
    }

    private fun currentPollInterval(): Long {
        val pm = getSystemService(PowerManager::class.java)
        return if (pm?.isInteractive == false) POLL_MS_OFF else POLL_MS_ON
    }

    private fun startPolling() {
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        DebugLog.w(this, TAG, "polling started")
    }

    private fun reschedulePoll() {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, currentPollInterval())
    }

    private fun pollVolumes() {
        val am = audioManager ?: return
        pollCount++
        for (stream in WATCHED_STREAMS) {
            val cur = am.getStreamVolume(stream)
            val prev = lastVolumes[stream]
            if (prev == null) {
                lastVolumes[stream] = cur
                continue
            }
            if (cur != prev) {
                DebugLog.w(this, TAG, "POLL stream=$stream $prev->$cur")
                lastVolumes[stream] = cur
                onVolumeChanged(stream, prev, cur, "poll")
            }
        }
        if (pollCount % 40 == 0) {
            DebugLog.w(
                this, TAG,
                "heartbeat n=$pollCount " + WATCHED_STREAMS.joinToString(" ") { "$it=${lastVolumes[it]}" }
            )
        }
    }

    private fun unregisterListeners() {
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(flushRestoreRunnable)
        listOfNotNull(volumeReceiver, screenReceiver).forEach {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        volumeReceiver = null
        screenReceiver = null
        observers.forEach {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {
            }
        }
        observers.clear()
    }

    // ---------- 触发逻辑 ----------

    private fun onVolumeChanged(stream: Int, prev: Int, newVal: Int, from: String) {
        // v3.5.0：精确去重——只忽略"我们刚把音量恢复到目标值"产生的那条回声事件。
        // 旧实现用 restoring 布尔量全局抑制 600ms，会把用户紧随其后的真实按键一起吞掉
        // （实测：按一下 130->120 被 skip: restoring，既不报时也不恢复音量）。
        val target = restoreTargets[stream]
        if (target != null && newVal == target &&
            System.currentTimeMillis() - restoreAtMs < RESTORE_SUPPRESS_MS
        ) {
            lastVolumes[stream] = newVal
            DebugLog.w(this, TAG, "skip: self-restore echo stream=$stream -> $newVal")
            return
        }
        if (prefs.paused) {
            DebugLog.w(this, TAG, "skip: paused")
            return
        }

        // v3.4.7：与主路径同一判据——亮屏且已解锁才算"用户在正常用手机"，
        // 熄屏/锁屏（含按键唤醒后的锁屏界面）都应报时
        val pm = getSystemService(PowerManager::class.java)
        val interactive = pm?.isInteractive ?: true
        if (interactive && !isKeyguardLocked()) {
            DebugLog.w(this, TAG, "skip: unlocked & interactive (from=$from)")
            return
        }

        val realPrev = if (prev >= 0) prev else (lastVolumes[stream] ?: newVal)
        if (newVal == realPrev) {
            DebugLog.w(this, TAG, "skip: no real change ($realPrev)")
            return
        }
        lastVolumes[stream] = newVal

        val isUp = newVal > realPrev
        val wanted = if (isUp) prefs.triggerUp else prefs.triggerDown
        if (!wanted) {
            DebugLog.w(this, TAG, "skip: ${if (isUp) "UP" else "DOWN"} not enabled")
            return
        }

        // v3.5.2：一次物理按键会在极短时间内同时改变多条流的音量
        // （实测一次按键触发 stream=10/9/3 三条 TRIGGER）。若每条都尝试播报，
        // 第 2、3 条会被 TTS 去重窗口挡成 speak=false，用户听到的是"没报时"。
        // 因此按时间窗归并为"同一次按键"：只播报一次，并在窗口结束后
        // 统一恢复**所有**被改动的流（此前只恢复成功播报的那一条，音量会累积下降）。
        val now = System.currentTimeMillis()
        val isNewKey = now - keySessionMs > KEY_GROUP_MS
        pendingRestore[stream] = realPrev
        scheduleRestore()

        if (!isNewKey) {
            DebugLog.w(
                this, TAG,
                "same key session: stream=$stream $realPrev->$newVal (合并, 不重复播报)"
            )
            return
        }

        keySessionMs = now
        DebugLog.w(
            this, TAG,
            "TRIGGER stream=$stream $realPrev->$newVal (${if (isUp) "UP" else "DOWN"}, from=$from)"
        )
        val announced = speaker.speakCurrentTime()
        if (announced) lastSelfSpeakMs = now
        DebugLog.w(this, TAG, "speak -> $announced, text=${TimeSpeaker.currentTimeText()}")
        publishStatus(this, "触发报时 ${TimeSpeaker.currentTimeText()}")
    }

    /** 安排"按键批次结束"后的统一音量恢复 */
    private fun scheduleRestore() {
        handler.removeCallbacks(flushRestoreRunnable)
        handler.postDelayed(flushRestoreRunnable, KEY_GROUP_MS)
    }

    private val flushRestoreRunnable = Runnable { flushPendingRestore() }

    /**
     * 把本次按键改动的所有流恢复为按键前的值（v3.5.2）。
     * 与是否播报成功无关——即使 TTS 被去重挡掉，也要把音量还原，
     * 否则用户会看到"按一次音量掉 10 格且再也回不来"。
     */
    private fun flushPendingRestore() {
        if (pendingRestore.isEmpty()) return
        restoreAtMs = System.currentTimeMillis()
        for ((s, v) in pendingRestore) {
            restoreTargets[s] = v
            lastVolumes[s] = v
            try {
                audioManager?.setStreamVolume(s, v, 0)
                DebugLog.w(this, TAG, "restore volume stream=$s -> $v")
            } catch (e: Exception) {
                DebugLog.w(this, TAG, "restore volume failed stream=$s: $e")
            }
        }
        pendingRestore.clear()
    }

    /** MediaSession 远程音量回调：收到音量键事件 */
    private fun handleVolumeKey(direction: Int) {
        val pm = getSystemService(PowerManager::class.java)
        val interactive = pm?.isInteractive ?: true
        val locked = isKeyguardLocked()
        // 进门即记录：无论后续是否播报，都让日志可观测"按键是否到达了 App"
        publishStatus(
            this,
            "收到按键 dir=$direction screen=${if (interactive) "ON" else "OFF"} locked=$locked"
        )

        // v3.4.7 关键判定：用**锁屏状态**而非屏幕亮灭区分场景。
        // v3.4.8：静音媒体保活后，音量键走系统"媒体音量"通道（实测 BCAST 可见
        // stream=3 真实变化），系统会自行调节，无需再代调，避免一次按键调两倍。
        if (interactive && !locked) {
            DebugLog.w(this, TAG, "unlocked+ON: pass through (system adjusts media volume)")
            return
        }

        // 息屏 或 亮屏但锁屏（睡觉场景）→ 按方向判断是否报时
        // v3.4.8：必须忽略 dir=0（ADJUST_SAME）——系统在每次音量键后都会补发一个
        // dir=0 的"面板刷新"事件，若把它当下键会导致按音量上键也报时（实测踩坑）。
        if (direction == 0) {
            DebugLog.w(this, TAG, "announce skipped: dir=0 (panel/refresh event)")
            return
        }
        val enabled = if (direction > 0) prefs.triggerUp else prefs.triggerDown
        if (!enabled) {
            DebugLog.w(this, TAG, "announce skipped: dir=$direction not enabled")
            return
        }
        announceFromKey("key dir=$direction screen=${if (interactive) "ON" else "OFF"} locked=$locked")
    }

    private fun isKeyguardLocked(): Boolean = try {
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * 静音媒体保活（v3.4.8 核心）。
     *
     * 问题：这台 HyperOS 在熄屏时把音量键第一下用于唤醒屏幕并消费掉，
     * 会话收不到 onAdjustVolume（v3.2~v3.4.6 息屏永不报时的根因）。
     * v3.4.7 曾用"屏幕亮起即报时"兜底，但无法区分唤醒源（通知/闹钟/电源键都会误报）。
     *
     * 对策：常驻一条**静音、音量 0、不申请音频焦点**的 AudioTrack 循环播放，
     * 让 AudioService 认为本应用是"正在播放的媒体"——音量键因此作为媒体音量键
     * 直接派发给远程音量会话，而不再被当作唤醒键消费。
     * 这样按键事件可以精确区分上/下键，也不会点亮屏幕。
     *
     * 资源开销：MODE_STATIC 静音循环由硬件混音，CPU 占用可忽略；音量 0 不出声；
     * 不申请音频焦点，不会打断其他应用播放。
     */
    private fun startSilentKeepAlive() {
        if (silentTrack != null) return
        try {
            val sampleRate = 8000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            val zeros = ShortArray(minBuf / 2)
            track.write(zeros, 0, zeros.size)
            track.setLoopPoints(0, zeros.size, -1)
            track.setVolume(0f)
            track.play()
            silentTrack = track
            DebugLog.w(this, TAG, "silent keep-alive started (static loop, volume 0)")
        } catch (e: Exception) {
            DebugLog.w(this, TAG, "silent keep-alive failed: $e")
        }
    }

    private fun stopSilentKeepAlive() {
        silentTrack?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        silentTrack = null
        DebugLog.w(this, TAG, "silent keep-alive stopped")
    }

    // ---------- 闹钟响铃报时（v3.4.9） ----------

    /**
     * 监听"闹钟开始响铃"并跟着报时。
     *
     * 采用系统标准 API `AudioManager.registerAudioPlaybackCallback`（API 26+，无需权限）：
     * 系统闹钟（小米时钟等）响铃时的播放配置为 `USAGE_ALARM`，据此可以精确识别
     * "闹钟开始响"这一事件，而不受通知、来电等其他音源干扰。
     *
     * 用 `alarmWasPlaying` 做上升沿检测：闹钟持续响铃期间播放配置会多次回调，
     * 只在 false→true 的那一刻报一次；60 秒冷却避免"稍后提醒"短间隔重复播报。
     */
    private fun registerAlarmWatcher() {
        val am = audioManager ?: return
        if (alarmCallback != null) return
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(
                configs: MutableList<AudioPlaybackConfiguration>
            ) {
                // 回调参数本身就是"当前活跃的播放配置列表"，无需再判 isActive
                // （compileSdk 35 的 stub 中未暴露 isActive()，但语义等价）
                val alarmPlaying = configs.any { cfg ->
                    try {
                        cfg.audioAttributes.usage == AudioAttributes.USAGE_ALARM
                    } catch (_: Exception) {
                        false
                    }
                }
                if (alarmPlaying != alarmWasPlaying) {
                    DebugLog.w(this@TimeAnnouncerService, TAG, "alarm playing=$alarmPlaying")
                    alarmWasPlaying = alarmPlaying
                    if (alarmPlaying) {
                        // 忽略本应用 TTS 自身（同样走 USAGE_ALARM）造成的"假闹钟"
                        if (System.currentTimeMillis() - lastSelfSpeakMs < SELF_SPEAK_IGNORE_MS) {
                            DebugLog.w(
                                this@TimeAnnouncerService, TAG,
                                "alarm event ignored: self TTS playback"
                            )
                            return
                        }
                        onAlarmStarted()
                    }
                }
            }
        }
        try {
            am.registerAudioPlaybackCallback(cb, handler)
            alarmCallback = cb
            DebugLog.w(this, TAG, "alarm watcher registered")
        } catch (e: Exception) {
            DebugLog.w(this, TAG, "alarm watcher register failed: $e")
        }
    }

    private fun unregisterAlarmWatcher() {
        val am = audioManager
        alarmCallback?.let {
            try {
                am?.unregisterAudioPlaybackCallback(it)
            } catch (_: Exception) {
            }
        }
        alarmCallback = null
    }

    /** 闹钟刚开始响：报一次当前时间（独立于按键节流，用 60s 冷却防重复） */
    private fun onAlarmStarted(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAlarmAnnounceMs < ALARM_ANNOUNCE_COOLDOWN_MS) {
            DebugLog.w(this, TAG, "alarm announce skipped: cooldown")
            return
        }
        lastAlarmAnnounceMs = now
        if (prefs.paused) {
            DebugLog.w(this, TAG, "alarm announce skipped: paused")
            return
        }
        // 稍等 1 秒再报，避开闹铃起始的响度爬升，听感更清楚
        handler.postDelayed({
            val text = TimeSpeaker.currentTimeText()
            val ok = speaker.speakCurrentTime()
            if (ok) lastSelfSpeakMs = System.currentTimeMillis()
            DebugLog.w(this, TAG, "ALARM announce -> $ok text=$text")
            publishStatus(this, "闹钟报时 $text")
        }, if (force) 0L else ALARM_ANNOUNCE_DELAY_MS)
    }

    /** 统一的报时入口：节流 + 暂停检查 + 播报 + 状态记录 */
    private fun announceFromKey(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastPressMs < PRESS_THROTTLE_MS) {
            DebugLog.w(this, TAG, "announce ignored: throttle (${now - lastPressMs}ms, $reason)")
            return
        }
        lastPressMs = now

        if (prefs.paused) {
            DebugLog.w(this, TAG, "announce ignored: paused ($reason)")
            return
        }

        val text = TimeSpeaker.currentTimeText()
        val ok = speaker.speakCurrentTime()
        if (ok) lastSelfSpeakMs = System.currentTimeMillis()
        DebugLog.w(this, TAG, "SPEAK ($reason) -> $ok text=$text")
        publishStatus(this, "按键报时 $text")
    }

    /** 报时后把音量恢复到按键前，避免每次报时都改一次音量 */
    private fun restoreVolume(stream: Int, value: Int) {
        restoreTargets[stream] = value
        restoreAtMs = System.currentTimeMillis()
        lastVolumes[stream] = value
        try {
            audioManager?.setStreamVolume(stream, value, 0)
            DebugLog.w(this, TAG, "restore volume stream=$stream -> $value")
        } catch (e: Exception) {
            DebugLog.w(this, TAG, "restore volume failed: $e")
        }
    }

    // ---------- 前台通知 ----------

    private fun startForegroundCompat() {
        val notification = Notifier.buildNotification(this, prefs.paused, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifier.NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Notifier.NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "KeyTimeService"

        /** AudioManager.VOLUME_CHANGED_ACTION：系统隐藏广播，音量变化时发出 */
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

        private val WATCHED_STREAMS = intArrayOf(
            AudioManager.STREAM_RING,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_DTMF,
            AudioManager.STREAM_ACCESSIBILITY
        )

        /** 息屏时的轮询间隔（ms）；亮屏时放慢，只为刷新基线 */
        private const val POLL_MS_OFF = 300L
        private const val POLL_MS_ON = 3000L

        /** 恢复音量后识别"自身回声"的时间窗（ms） */
        private const val RESTORE_SUPPRESS_MS = 800L

        /** 按键批次归并窗口（ms）：此窗口内的多流音量变化视为同一次按键 */
        private const val KEY_GROUP_MS = 600L

        /** 连续按键的最小间隔，避免一次误触播报多次 */
        private const val PRESS_THROTTLE_MS = 1200L

        /** 闹钟响铃后延迟多久播报（避开铃声起始的响度爬升） */
        private const val ALARM_ANNOUNCE_DELAY_MS = 1000L

        /** 闹钟报时冷却时间：防止铃声波动/"稍后提醒"造成短间隔重复播报 */
        private const val ALARM_ANNOUNCE_COOLDOWN_MS = 60_000L

        /** 播报后多久内忽略"USAGE_ALARM 播放"事件（本应用 TTS 自身造成的假闹钟） */
        private const val SELF_SPEAK_IGNORE_MS = 5_000L

        /** MainActivity 轮询的服务运行标志（同进程，无需 IPC） */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 最近一次关键事件，供调试读取 */
        @Volatile
        var lastEvent: String = "-"
            private set

        /** MainActivity 主动 stopService 后同步标志，避免 UI 状态滞后 */
        fun markStopped() {
            isRunning = false
        }

        /**
         * 标记"用户主动停止"（点 App 内停止按钮/调试 op=stop）。
         * 必须在 stopService **之前**调用，否则 onDestroy 的自重启会违背用户意图。
         */
        fun markUserStopped() {
            userStopped = true
        }

        /** 看门狗据此区分"用户主动停止"与"系统杀死"（v3.4.3） */
        fun isUserStopped(): Boolean = userStopped

        /**
         * 服务实例与 MediaSession 会话是否**真实存活**（v3.4.3）。
         *
         * 为什么不能只看 isRunning：服务实例被系统杀死时 onDestroy 未必执行，
         * 静态标志会停留在 true，造成"界面显示运行中、息屏按键无响应"的假象。
         * 会话是系统侧实体，它不在了就说明接管能力真的没了。
         */
        fun isSessionAlive(): Boolean {
            val inst = instance ?: return false
            // interceptor 为 lateinit：实例存在但未初始化时 runCatching 兜底返回 false
            return runCatching { inst.interceptor.isAlive() }.getOrDefault(false)
        }

        /** 最近一次自重启时间（节流，防止"被杀→重启→再被杀"无限循环） */
        @Volatile
        private var lastSelfRestartMs = 0L

        /** 用户主动停止标志：区分"用户点停止"与"系统杀死"（v3.4.2 自重启依据） */
        @Volatile
        private var userStopped = false

        /** 自重启最小间隔（ms）：防止系统连环杀时的重启风暴 */
        private const val SELF_RESTART_INTERVAL_MS = 5000L

        /** 把一行状态同时写进日志和通知文本（dumpsys notification 可读） */
        fun publishStatus(context: Context, text: String) {
            lastEvent = text
            DebugLog.w(context, TAG, "STATUS $text")
            try {
                Notifier.refresh(context, text)
            } catch (e: Exception) {
                DebugLog.w(context, TAG, "publishStatus notify failed: $e")
            }
        }

        /** 调试：模拟"闹钟开始响"（跳过 60s 冷却），验证闹钟报时链路 */
        fun simulateAlarm(context: Context) {
            val svc = instance
            if (svc == null) {
                DebugLog.w(context, TAG, "simulateAlarm: service not running")
                return
            }
            DebugLog.w(context, TAG, "simulateAlarm (debug)")
            svc.onAlarmStarted(force = true)
        }

        /**
         * 调试：以 `USAGE_ALARM` 真实播放 3 秒 440Hz 提示音，
         * 用于端到端验证"AudioPlaybackCallback 检测到闹钟 → 自动报时"整条链路。
         */
        fun simulateAlarmSound(context: Context) {
            Thread {
                var track: AudioTrack? = null
                try {
                    val sr = 8000
                    val minBuf = AudioTrack.getMinBufferSize(
                        sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(4096)
                    track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sr)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    val n = sr * 3
                    val samples = ShortArray(n) { i ->
                        (Math.sin(2 * Math.PI * 440 * i / sr) * 6000).toInt().toShort()
                    }
                    track.write(samples, 0, n)
                    track.setLoopPoints(0, n, -1)
                    track.play()
                    DebugLog.w(context, "DebugReceiver", "alarm sound playing (USAGE_ALARM, 3s)")
                    Thread.sleep(3000)
                } catch (e: Exception) {
                    DebugLog.w(context, "DebugReceiver", "alarm sound failed: $e")
                } finally {
                    try {
                        track?.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        track?.release()
                    } catch (_: Exception) {
                    }
                }
            }.start()
        }

        /** 调试：跳过检测直接触发一次播报，验证 TTS 与音量恢复 */
        fun simulatePress(context: Context, delta: Int) {            val svc = instance
            if (svc == null) {
                DebugLog.w(context, TAG, "simulatePress: service not running")
                return
            }
            val am = svc.audioManager ?: return
            val stream = AudioManager.STREAM_RING
            val prev = am.getStreamVolume(stream)
            svc.lastVolumes[stream] = prev
            DebugLog.w(context, TAG, "simulatePress prev=$prev delta=$delta")
            svc.onVolumeChanged(stream, prev, prev + delta, "fake")
        }

        /**
         * v3.6.0：按当前音量设置播报一次（调试用，验证音量即时生效）。
         * 复用运行中的服务 speaker；服务未运行时新建一个临时 speaker。
         */
        fun simulateSpeak(context: Context) {
            val svc = instance
            val speakerToUse = if (svc != null) {
                svc.speaker
            } else {
                DebugLog.w(context, TAG, "simulateSpeak: no service, temp speaker")
                TimeSpeaker(context.applicationContext, Prefs(context).useAlarmStream).also { it.init() }
            }
            val ok = speakerToUse.speakCurrentTime(force = true)
            if (ok && svc != null) svc.lastSelfSpeakMs = System.currentTimeMillis()
            DebugLog.w(context, TAG, "simulateSpeak -> $ok fraction=${Prefs(context).announceVolumeFraction}")
        }

        /** 当前服务实例（仅调试用途） */
        @Volatile
        private var instance: TimeAnnouncerService? = null

        private fun volumeSettingName(stream: Int): String = when (stream) {
            AudioManager.STREAM_RING -> "volume_ring"
            AudioManager.STREAM_MUSIC -> "volume_music"
            AudioManager.STREAM_ALARM -> "volume_alarm"
            AudioManager.STREAM_NOTIFICATION -> "volume_notification"
            AudioManager.STREAM_SYSTEM -> "volume_system"
            AudioManager.STREAM_VOICE_CALL -> "volume_voice"
            AudioManager.STREAM_DTMF -> "volume_dtmf"
            AudioManager.STREAM_ACCESSIBILITY -> "volume_accessibility"
            else -> "volume_ring"
        }
    }
}
