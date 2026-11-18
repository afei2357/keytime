package com.example.keytime

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Calendar
import java.util.Locale

/**
 * 文字转语音封装：把当前时间播报为 "13点51分"。
 *
 * - 息屏/锁屏状态下播报（音频不依赖亮屏）
 * - 播报期间持有短超时的 PARTIAL_WAKE_LOCK，防止 CPU 休眠截断语音
 * - 短去重窗口：按键主路径与各兜底路径可能同时触发，只播一次
 *
 * 注意：TextToSpeech 构造后引擎绑定是异步的（onInit 回调才就绪）。
 * 早期版本用 `tts != null` 判断就绪，导致首次点击"测试报时"时 speak()
 * 在引擎就绪前被静默丢弃。现在用 [initialized] 标志，未就绪时挂起，
 * onInit 成功后自动补播。
 *
 * ## v3.5.6：引擎"僵尸绑定"自检（真机实证）
 *
 * 现象：服务明明在运行、按键也检测到、日志显示 `SPEAK -> true`，
 * 但用户听不到任何声音。取证发现：那两次播报在系统音频层
 * （`dumpsys audio` 的 player 事件）**根本没有创建播放器**；而用全新
 * TextToSpeech 实例播报则会立刻创建 `USAGE_ALARM` 播放器并 started。
 *
 * 根因：默认引擎是小米 `com.xiaomi.mibrain.speech`，长期持有的绑定在
 * 息屏/Doze 一段时间后会被静默断开——`speak()` 调用照常返回，音频却不出。
 * 服务此前只在 onCreate 里 init 一次（可持有数小时），必然踩中。
 *
 * 对策：
 * 1. 绑定闲置超过 60s 后的第一次播报，先重建实例再播（覆盖"睡一觉第一次按"）；
 * 2. 用 UtteranceProgressListener 监听 onStart，1.5s 内没开始播放即判定僵尸，
 *    重建引擎并补播一次（不无限重试）；
 * 3. onStart/onDone/onError、speak() 返回值全部留日志，以后可直接区分
 *    "没调用 / 引擎没播 / 播了被静音"；
 * 4. 闹钟声道加 FLAG_AUDIBILITY_ENFORCED，降低被后台静音策略吃掉的概率。
 */
class TimeSpeaker(
    private val context: Context,
    private var useAlarmStream: Boolean
) {

    /** 配置在构造时持有，但音量在【每次播报时实时读取】——保证设置变更立即生效 */
    private val prefs = Prefs(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null

    /** onInit 回调返回 SUCCESS 后才为 true；仅 [tts] 非空不代表就绪 */
    private var initialized = false

    /** onInit 失败过：允许下次 init() 时重建实例重试 */
    private var initFailed = false

    private var pendingText: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastSpokeElapsed = 0L
    private var lastInitElapsed = 0L

    /** 本轮播报是否仍在等待 onStart（健康监控用） */
    private var awaitingStart = false

    /** 单次用户触发只允许重建补播一次，防止死循环 */
    private var retryUsed = false

    val isInitialized: Boolean
        get() = initialized

    /**
     * 初始化语音引擎（幂等）。重复调用不会创建多个实例。
     * @param onReady 就绪/失败时回调，参数为是否成功（主线程不保证，调用方自行切线程）
     */
    fun init(onReady: (Boolean) -> Unit = {}) {
        if (initialized) {
            onReady(true)
            return
        }
        if (tts != null && !initFailed) {
            // 正在初始化中，等 onInit 回调即可
            onReady(false)
            return
        }
        // 首次或上次失败后重试：先释放旧实例
        runCatching { tts?.shutdown() }
        initFailed = false
        lastInitElapsed = SystemClock.elapsedRealtime()
        Log.i(TAG, "creating TextToSpeech instance")
        tts = TextToSpeech(context.applicationContext) { status ->
            initialized = status == TextToSpeech.SUCCESS
            if (initialized) {
                tts?.let { engine ->
                    val langResult = engine.setLanguage(Locale.CHINA)
                    Log.i(TAG, "TTS ready, setLanguage(zh_CN) = $langResult")
                    if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                        langResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        val fallback = engine.setLanguage(Locale.getDefault())
                        Log.w(TAG, "zh_CN unavailable, fallback default locale = $fallback")
                    }
                    applyAudioAttributes()
                }
                pendingText?.let {
                    Log.i(TAG, "flush pending utterance: $it")
                    pendingText = null
                    speakTracked(it)
                }
            } else {
                initFailed = true
                Log.e(TAG, "TTS init failed, status = $status")
            }
            onReady(initialized)
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                awaitingStart = false
                handler.removeCallbacks(startWatchdog)
                Log.i(TAG, "utterance onStart id=$utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                awaitingStart = false
                handler.removeCallbacks(startWatchdog)
                Log.i(TAG, "utterance onDone id=$utteranceId")
                releaseWakeLock()
            }

            @Deprecated("Deprecated in Java", ReplaceWith("handleSpeakError()"))
            override fun onError(utteranceId: String?) {
                handleSpeakError("onError(deprecated) id=$utteranceId")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleSpeakError("onError id=$utteranceId code=$errorCode")
            }
        })
    }

    /**
     * 播报当前时间。
     * @param force true 时跳过去重窗口（用于"测试报时"按钮）
     * @return true 表示本次确实发起了播报（含挂起/重建后补播）
     */
    fun speakCurrentTime(force: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastSpokeElapsed != 0L && now - lastSpokeElapsed < DEDUP_WINDOW_MS) {
            return false
        }
        lastSpokeElapsed = now
        val text = currentTimeText()
        retryUsed = false

        if (!initialized) {
            // 引擎未就绪（如刚打开 App 就点击）：挂起，onInit 成功后自动补播
            Log.i(TAG, "TTS not ready, queue utterance: $text")
            pendingText = text
            init()
            return true
        }

        // v3.5.6：绑定长时间闲置 → 主动重建，避免僵尸绑定静默吞掉播报
        val idle = now - lastInitElapsed
        if (idle > TTS_IDLE_REBUILD_MS) {
            Log.i(TAG, "TTS binding idle ${idle}ms -> rebuild before speak")
            rebuildAndSpeak(text)
            return true
        }

        speakTracked(text)
        return true
    }

    /** 真正调用引擎，并布下"是否开始播放"的监控 */
    private fun speakTracked(text: String) {
        acquireWakeLock()
        awaitingStart = true
        handler.removeCallbacks(startWatchdog)
        handler.postDelayed(startWatchdog, SPEAK_START_TIMEOUT_MS)

        // 每次播报实时读取音量设置（0f..1f）：滑块改动后下一次报时立即生效。
        // KEY_PARAM_VOLUME 是【本应用 TTS 引擎的播报增益】，不改动系统任何音量流——
        // 真实闹钟音量、媒体音量都不受影响，只是这句报时说大声或小声。
        // 1f = 引擎正常音量（即以往行为，对应"最大音量"选项）。
        val volume = prefs.announceVolumeFraction
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
        Log.i(TAG, "speak: $text result=$result volume=$volume")
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak() returned $result (expected ${TextToSpeech.SUCCESS}) -> rebuild")
            awaitingStart = false
            handler.removeCallbacks(startWatchdog)
            if (!retryUsed) {
                retryUsed = true
                rebuildAndSpeak(text)
            }
        }
    }

    /** 1.5 秒内没有任何开始/完成回调 = 引擎静默失败，重建并补播一次 */
    private val startWatchdog = Runnable {
        if (!awaitingStart) return@Runnable
        awaitingStart = false
        Log.w(TAG, "utterance did NOT start within ${SPEAK_START_TIMEOUT_MS}ms -> zombie engine, rebuild")
        if (!retryUsed) {
            retryUsed = true
            rebuildAndSpeak(currentTimeText())
        }
    }

    private fun handleSpeakError(reason: String) {
        Log.w(TAG, "utterance $reason")
        awaitingStart = false
        handler.removeCallbacks(startWatchdog)
        releaseWakeLock()
        if (!retryUsed) {
            retryUsed = true
            rebuildAndSpeak(currentTimeText())
        }
    }

    /** 丢弃旧绑定、重新 init，并在 onInit 后自动补播指定文本（复用 pendingText 机制，不会双播） */
    private fun rebuildAndSpeak(text: String) {
        Log.i(TAG, "rebuilding TTS binding, will speak: $text")
        handler.removeCallbacks(startWatchdog)
        awaitingStart = false
        runCatching { tts?.shutdown() }
        tts = null
        initialized = false
        initFailed = false
        pendingText = text
        init()
    }

    fun release() {
        handler.removeCallbacks(startWatchdog)
        awaitingStart = false
        releaseWakeLock()
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        initialized = false
        pendingText = null
    }

    private fun applyAudioAttributes() {
        val usage = if (useAlarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
        val builder = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        if (useAlarmStream) {
            // 让系统保证"可听"（闹钟语义），降低勿扰/后台静音策略把报时吃掉的概率
            builder.setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
        }
        tts?.setAudioAttributes(builder.build())
    }

    fun setUseAlarmStream(value: Boolean) {
        useAlarmStream = value
        applyAudioAttributes()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).also {
            it.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "TimeSpeaker"
        const val UTTERANCE_ID = "keytime_time"
        private const val WAKE_LOCK_TAG = "keytime:tts"
        private const val WAKE_LOCK_TIMEOUT_MS = 20_000L

        /**
         * 去重窗口（ms）。
         * v3.5.2 由 1000 降为 300——按键批次归并（TimeAnnouncerService.KEY_GROUP_MS=600）
         * 已在服务层完成去重，这里只需挡住同一瞬间的重复调用；窗口过长会把
         * 正常播报静默丢弃（日志表现为 speak -> false，用户听到的是"没报时"）。
         */
        private const val DEDUP_WINDOW_MS = 300L

        /** 绑定闲置超过该时长，下一次播报前主动重建（真机实证：僵尸绑定会静默吞声） */
        private const val TTS_IDLE_REBUILD_MS = 60_000L

        /** 播报后等待 onStart 的最长时间，超时即判定引擎静默失败并重建补播 */
        private const val SPEAK_START_TIMEOUT_MS = 1_500L

        /** 生成 "13点51分" / "0点07分"；Locale.US 保证 %02d 永远输出 ASCII 数字 */
        fun currentTimeText(): String {
            val cal = Calendar.getInstance()
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)
            return "%d点%02d分".format(Locale.US, hour, minute)
        }
    }
}
