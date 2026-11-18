package com.example.keytime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知栏"暂停/恢复"按钮：只切配置并刷新通知，不做重活 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Notifier.ACTION_TOGGLE_PAUSE) {
            val prefs = Prefs(context)
            prefs.paused = !prefs.paused
            // v3.7.0：通知栏手动切换同样标记覆盖（与 App 内开关语义一致）
            prefs.manualOverrideAtMs = System.currentTimeMillis()
            Notifier.refresh(context)
        }
    }
}