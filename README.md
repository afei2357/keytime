# 按键报时（KeyTime）

[English](README_EN.md) | **简体中文**

> 睡觉时不用睁眼：按一下音量键，手机用中文播报“13点51分”。

KeyTime 是一个完全离线运行的 Android 小工具。它通过前台服务与 `MediaSession`
接管锁屏状态下的音量键，在不申请无障碍权限、不读取屏幕内容、不联网的前提下，
用系统 TTS 播报当前时间。项目针对小米 HyperOS 做了大量真机适配，也适用于其他
Android 8.0+ 设备。

- 当前版本：**3.7.0**（versionCode 24）
- 技术栈：Kotlin / Android SDK 35 / Gradle 8.9
- 包名：`com.example.keytime`
- 许可证：MIT

## 主要功能

- **锁屏/息屏报时**：锁屏或熄屏时按音量下键，播报“13点51分”；音量上键、双键可选。
- **不打扰正常使用**：解锁亮屏后按音量键只调节媒体音量，不报时；来通知、按电源键点亮锁屏也不会误报。
- **闹钟自动报时**：系统闹钟开始响铃时，延迟约 1 秒自动播报一次当前时间。
- **报时音量可调**：0–100% 滑块，默认 80%；可开启“最大音量”，只影响报时语音，不改系统音量。
- **定时自动控制**：按星期重复设置自动开启/暂停时间，支持跨天规则（如 22:00 开、07:00 关），并显示下次触发时间。
- **开机自启与保活**：前台服务 + `START_STICKY` + AlarmManager 看门狗，支持开机、插电、应用更新后自动恢复。
- **状态真实可见**：界面以系统 `ActivityManager` 查询结果为准，区分未运行、运行中、运行但按键接管异常。
- **完全离线**：不申请 `INTERNET` 权限，不上传任何数据，不包含统计或广告 SDK。

## 工作原理

```text
锁屏/息屏按下音量键
        │
        ▼
常驻 MediaSession 以 REMOTE + ABSOLUTE 模式接管按键
        │
        ▼
前台服务收到 onAdjustVolume(direction)
        │
        ├─ 已解锁 ──→ 交还给系统调音量，不播报
        └─ 仍锁屏 ──→ TextToSpeech 播报当前时间
```

关键点：

1. **MediaSession 远程音量**：系统把音量键直接派发给活跃会话的
   `VolumeProvider.onAdjustVolume()`，应用拿到的是“按键事件”，不依赖音量数值变化。
2. **静音媒体保活**：常驻一条音量为 0 的静音 `AudioTrack`，让系统把按键视为媒体音量键，
   避免锁屏时第一下按键被用于唤醒屏幕而消费掉。
3. **锁屏判据**：用 `KeyguardManager.isKeyguardLocked` 判断，而不是只看屏幕亮灭。
4. **TTS 自愈**：TTS 绑定闲置 60 秒后自动重建；播报后 1.5 秒内没有 `onStart` 回调则判定
   引擎僵尸绑定，重建并补播一次。
5. **看门狗**：`AlarmManager.setExactAndAllowWhileIdle` 每分钟检查服务与会话是否真实存活，
   被系统杀死后自动拉起；用户主动停止时不会拉回。

> 更完整的版本演进、真机排障过程与踩坑记录见 [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md)
> 和 [HANDOVER.md](HANDOVER.md)（中文）。

## 环境要求

- Android 8.0（API 26）及以上；targetSdk 35。
- 设备已安装中文 TTS 语音（HyperOS / MIUI 默认提供）。
- 构建需要 JDK 17、Android SDK Platform 35、Build-Tools 35.0.0。

## 安装与使用

### 从源码构建

```bash
git clone https://github.com/afei2357/keytime.git
cd keytime

# 调试版
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 正式版（未配置签名时使用默认调试签名）
./gradlew assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

如需正式签名，自行生成 keystore 后设置以下环境变量，构建脚本不会读取任何仓库内的密钥文件：

```bash
export KEYTIME_KEYSTORE=/path/to/release.jks
export KEYTIME_STORE_PASSWORD=your_store_password
export KEYTIME_KEY_ALIAS=keytime
export KEYTIME_KEY_PASSWORD=your_key_password
```

安装到手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以把 APK 传到手机后点击安装，并按系统提示允许“安装未知来源应用”。

> 包名已改为中性的 `com.example.keytime`。如果手机上还装着旧包名的版本，需要先卸载旧版；
> 两者会被系统视为两个不同应用，无法直接覆盖升级。

### 首次使用

1. 打开 App，报时服务会自动启动；首次启动请允许通知权限（用于显示服务状态和暂停/恢复按钮）。
2. 点“测试报时”确认能听到声音。
3. 在 HyperOS 上完成以下保活设置（缺一项，夜间服务都可能被系统清理）：
   - 设置 → 应用设置 → 应用管理 → 按键报时 → **省电策略 → 无限制**；
   - 同一页面 → **自启动 → 允许**；
   - 打开最近任务，给“按键报时”卡片**加锁**。
4. 锁屏后按音量下键，即可听到“13点51分”。

### 定时自动控制

在 App 的“定时自动控制”卡片中：

- 打开总开关；
- 设置“自动开启时间”和“自动暂停时间”；
- 选择重复日期（日～六），或使用“每天”“工作日”快捷键；
- 界面会显示当前规则、下次触发时间和运行状态。

开启时间与暂停时间不能相同；跨天规则（如 22:00 开、07:00 关）会自动按次日计算。
手动暂停/恢复立即生效，并保持到下一次自动事件接管。

## 权限说明

| 权限 | 用途 |
|---|---|
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 前台服务常驻，监听音量键 |
| `POST_NOTIFICATIONS` | 显示服务状态通知（Android 13+；拒绝后仍可报时） |
| `WAKE_LOCK` | 播报期间短时保持 CPU 唤醒，避免语音被截断 |
| `RECEIVE_BOOT_COMPLETED` | 开机后自动恢复报时服务 |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | 看门狗与定时规则使用精确闹钟，穿透 Doze |
| `INTERNET` | **未申请**，应用完全离线 |
| 无障碍（Accessibility） | **未申请**，改用 MediaSession 方案 |

## 项目结构

```text
app/src/main/java/com/example/keytime/
├── MainActivity.kt               # 设置页：服务控制、按键/声道、音量、定时规则
├── TimeAnnouncerService.kt       # 前台服务：按键处理、闹钟监听、报时、保活
├── VolumeKeyInterceptor.kt       # MediaSession 远程音量接管
├── TimeSpeaker.kt                # TTS 封装：初始化、重建、音量、WakeLock
├── AutoSchedule.kt               # 定时规则计算与 AlarmManager 排程
├── Notifier.kt                   # 常驻通知与通知渠道
├── NotificationActionReceiver.kt # 通知栏暂停/恢复按钮
├── BootCompletedReceiver.kt      # 开机、插电、应用更新后恢复
├── WatchdogReceiver.kt           # 每分钟检查服务真实存活
├── Prefs.kt                      # SharedPreferences 配置
├── DebugLog.kt                   # 内部日志（files/debug.log）
└── DebugReceiver.kt              # 受控调试广播
```

## 常见问题

**按了音量键但没有声音？**
先检查闹钟/媒体音量、勿扰模式，以及“设置 → 无障碍 → 文字转语音输出”是否选择了中文语音。
然后在 App 内点“测试报时”。若测试有声、锁屏无声，请确认省电策略、自启动和后台加锁三项设置。

**界面显示运行中，但锁屏按键没反应？**
App 会以系统服务列表为准显示状态。若显示“运行但按键接管异常”，点“停止报时服务”再“启动”
一次即可重建会话；若服务被系统杀死，看门狗会在一分钟内尝试拉起。

**按音量键时音量条变小，但声音大小没变？**
这是 MediaSession 接管按键的预期行为：系统显示的是会话音量面板，应用并不会修改真实音量。
解锁后按键则完全交还系统，音量正常变化。

**为什么不用无障碍服务？**
HyperOS 会对个人侧载应用的无障碍服务做系统级过滤，写入后会被立即剥离，设置列表中也不可见。
因此项目从 v3.0 起改用无需敏感权限的 MediaSession 方案。

## 贡献

欢迎提交 Issue 和 Pull Request：

1. Fork 本仓库并从 `main` 创建分支；
2. 保持代码风格与现有文件一致，新增逻辑尽量附带注释说明“为什么”；
3. 涉及按键、TTS、后台保活的改动，请在真机上验证并说明机型、系统版本与复现步骤；
4. 提交 PR 前运行 `./gradlew assembleDebug`，确保可以编译；
5. 请勿在代码、日志或截图中提交个人信息、设备序列号、签名文件或任何密钥。

如果是 HyperOS / MIUI 的兼容性问题，请尽量附上 `adb shell dumpsys media_session`、
`adb shell dumpsys alarm` 或 `files/debug.log` 的关键片段（注意先脱敏）。

## 许可证

本项目采用 [MIT License](LICENSE) 开源。

```text
Copyright (c) 2026 KeyTime contributors
```

你可以自由使用、修改、分发本项目，包括商业用途，但需保留版权声明与许可证文本。
本项目按“现状”提供，不附带任何明示或暗示的担保。
