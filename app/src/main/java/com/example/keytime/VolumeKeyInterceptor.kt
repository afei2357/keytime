package com.example.keytime

import android.content.Context
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * 音量键拦截（v3.0 主机制，v3.4.4 回归实证配置）。
 *
 * 为什么需要它：实测在这台 HyperOS 设备上，物理音量键**不会改变任何音量流的数值**
 * （v2.0 以 800ms 轮询 8 条音量流，按键瞬间全程无变化），因此依赖
 * "音量值变化"的方案收不到任何事件。
 *
 * 原理：AudioService/PhoneWindowManager 处理音量键时，若存在持有"远程音量"
 * （setPlaybackToRemote）的**活跃** MediaSession，会把按键派发给该会话的
 * VolumeProvider.onAdjustVolume()，而不再调整系统音量。这条路径拿到的是
 * **按键事件本身**，与音量数值无关。
 *
 * v3.4.4 关键结论（四次真机迭代的实证）：
 * - v3.0/v3.1：会话**全程 REMOTE、从不切换** → 息屏报时实测成功；
 * - v3.2：亮屏 release()、息屏 new 新会话 → 系统不把路由给"休眠后才激活"的会话；
 * - v3.3：复用会话、息屏才首次 setActive → 同样拿不到路由（首次激活时机须是亮屏）；
 * - v3.4~v3.4.3：亮屏 setPlaybackToLocal 交还、息屏 setPlaybackToRemote 接管 →
 *   **息屏按键从未成功**。实证说明：这台 ROM 上 setLocal 之后再 setRemote
 *   **要不回音量键路由**（onAdjustVolume 不再被调用），服务看着活着却是"僵尸"。
 *
 * 因此 v3.4.4 回归 v3.0 实证配置：会话从激活起就**全程 REMOTE**，绝不做
 * Local/Remote 切换。亮屏"不吞音量键"的需求改由 App 层解决——
 * TimeAnnouncerService.handleVolumeKey 在亮屏时收到按键后**代为调用
 * AudioManager.adjustVolume** 调节系统音量（用户感知与正常按键一致），
 * 息屏时才报时。这样路由全程不丢，两种屏幕状态的行为都正确。
 */
class VolumeKeyInterceptor(
    private val context: Context,
    private val onPress: (direction: Int) -> Unit
) {

    private var session: MediaSession? = null
    private var provider: VolumeProvider? = null

    /** 远程音量当前值（v3.4.8：用于在 onSetVolumeTo 中推断按键方向） */
    private var currentVolume: Int = CUR_VOLUME

    /**
     * 启动拦截器：**先释放再重建**，激活瞬间即 REMOTE（复刻 v3.0 实证可用配置）。
     *
     * 先 release 的原因（v3.4.1 实证）：进程被上划清理（SwipeUpClean）杀死后，
     * 系统内部对旧会话的路由引用可能悬空，先 release 迫使系统重置引用再新建。
     */
    fun start() {
        release()
        val s = ensureSession()
        s.setActive(true)
        s.setPlaybackToRemote(ensureProvider()) // 全程 REMOTE，此后不再切换
        DebugLog.w(context, TAG, "session rebuilt+setActive(true)+REMOTE -> 路由常驻")
    }

    /**
     * 彻底释放会话（仅服务销毁时调用，避免泄漏）。
     * 注意：绝不在亮灭屏之间调用——那会重建路由，实测息屏按键失效（v3.2 教训）。
     */
    fun release() {
        session?.let { s ->
            try {
                if (s.isActive) s.setActive(false)
            } catch (_: Exception) {
            }
            try {
                s.release()
            } catch (_: Exception) {
            }
        }
        session = null
        provider = null
        DebugLog.w(context, TAG, "session released")
    }

    /**
     * 会话是否真实存活（v3.4.3 看门狗依据）。
     * 系统杀死服务实例时 MediaSession 会被回收，此时进程可能还活着、
     * 静态 isRunning 还停在 true——只有查会话本身才能发现"假运行中"。
     */
    fun isAlive(): Boolean {
        val s = session ?: return false
        return try {
            s.isActive
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureSession(): MediaSession {
        session?.let { return it }
        val s = MediaSession(context, SESSION_TAG).apply {
            @Suppress("DEPRECATION")
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(
                        PlaybackState.STATE_PLAYING,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1.0f
                    )
                    .build()
            )
            setCallback(object : MediaSession.Callback() {
                // 不做实际播放控制，仅占位；音量键由 VolumeProvider 接管
            })
        }
        session = s
        DebugLog.w(context, TAG, "session created")
        return s
    }

    private fun ensureProvider(): VolumeProvider {
        provider?.let { return it }
        // v3.4.6：恢复 v3.0 实证可用的 ABSOLUTE 类型（max=100/cur=50）。
        // 此前误改为 RELATIVE——亮屏注入按键仍可到达 onAdjustVolume，
        // 但息屏物理按键不被派发（这台 ROM 对物理键的派发更严格）。
        val p = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, MAX_VOLUME, currentVolume) {
            override fun onAdjustVolume(direction: Int) {
                DebugLog.w(context, TAG, "onAdjustVolume dir=$direction")
                if (direction < 0) currentVolume = (currentVolume - 1).coerceAtLeast(0)
                if (direction > 0) currentVolume = (currentVolume + 1).coerceAtMost(MAX_VOLUME)
                onPress(direction)
            }

            override fun onSetVolumeTo(volume: Int) {
                // v3.4.8：ABSOLUTE 型会话下，系统可能把音量键转成"设置绝对值"派发。
                // 依据相对当前值的方向变化推断是上键还是下键，避免报时方向判断失效。
                val dir = when {
                    volume < currentVolume -> -1
                    volume > currentVolume -> 1
                    else -> 0
                }
                val prev = currentVolume
                currentVolume = volume.coerceIn(0, MAX_VOLUME)
                DebugLog.w(context, TAG, "onSetVolumeTo $prev->$volume dir=$dir")
                if (dir != 0) onPress(dir)
            }
        }
        provider = p
        return p
    }

    companion object {
        private const val TAG = "VolKey"
        private const val SESSION_TAG = "TimeTalkVolume"
        private const val VOLUME_CONTROL_ABSOLUTE = VolumeProvider.VOLUME_CONTROL_ABSOLUTE
        private const val MAX_VOLUME = 100
        private const val CUR_VOLUME = 50
    }
}
