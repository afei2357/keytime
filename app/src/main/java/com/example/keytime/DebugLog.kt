package com.example.keytime

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志：写入应用内部存储 files/debug.log。
 *
 * 为什么不用 Logcat：HyperOS 把本应用的日志从 logcat 屏蔽了（实测 8000 行全缓冲 0 命中），
 * 且 adb 注入触摸/部分按键被"USB 调试（安全设置）"限制，无法用自动化复现用户操作。
 * 因此把关键路径写入内部文件，配合 debuggable 构建用 `run-as` 读取，
 * 并把最近状态同步到通知文本（dumpsys notification 可读），形成两条外部观测通道。
 */
object DebugLog {

    private const val FILE = "debug.log"
    private const val MAX_LINES = 500
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun w(context: Context, tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg\n"
        // 同时输出 logcat，若哪天 ROM 放开了能直接看到
        android.util.Log.i(tag, msg)
        try {
            val f = File(context.filesDir, FILE)
            f.appendText(line)
            trim(f)
        } catch (_: Exception) {
            // 磁盘不可用时静默失败，不影响主功能
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE).writeText("")
        } catch (_: Exception) {
        }
    }

    fun read(context: Context): String = try {
        File(context.filesDir, FILE).readText()
    } catch (e: Exception) {
        "(read failed: ${e.message})"
    }

    private fun trim(f: File) {
        val lines = f.readLines()
        if (lines.size > MAX_LINES) {
            f.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
        }
    }
}
